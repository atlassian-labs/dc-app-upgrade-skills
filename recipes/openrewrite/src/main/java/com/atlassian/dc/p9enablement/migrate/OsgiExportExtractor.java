package com.atlassian.dc.p9enablement.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.module.ModuleFinder;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

/** Extracts OSGi package evidence from an unpacked product or a product WAR/JAR. */
public final class OsgiExportExtractor {

    private OsgiExportExtractor() {
    }

    public static void main(String[] args) throws Exception {
        if (List.of(args).contains("--help")) {
            System.out.println("exports --workspace DIR --product jira|confluence|refapp --product-version VERSION "
                    + "--target-java-version MAJOR --distribution WAR_OR_WEBAPP [--container-libs DIR] "
                    + "(--library-repository DIR | --verification-report FILE) --output FILE");
            return;
        }
        Arguments arguments = Arguments.parse(args);
        SnapshotBuilder builder = new SnapshotBuilder(arguments);
        builder.extract();
        builder.write();
    }

    private static final class SnapshotBuilder {
        private final Arguments arguments;
        private final Map<String, Bundle> bundles = new TreeMap<>();
        private final Map<String, Set<String>> classProviders = new TreeMap<>();
        private final Map<String, PackageExport> manifestExports = new TreeMap<>();
        private final Map<String, List<String>> descriptors = new LinkedHashMap<>();

        private final Set<String> observedProductVersions = new TreeSet<>();

        private final Map<String, String> packageVersions = new TreeMap<>();

        private final Map<String, String> systemPackages = new TreeMap<>();

        private Bundle framework;

        private final Set<String> coveredPackages = new TreeSet<>();

        private final Map<String, String> scopeProvenance = new TreeMap<>();

        private SnapshotBuilder(Arguments arguments) {
            this.arguments = arguments;
        }

        private void extract() throws IOException {
            if (Runtime.version().feature() != arguments.targetJavaMajor()) {
                throw new IOException("Extractor JVM " + Runtime.version().feature() + " at "
                        + System.getProperty("java.home") + " does not match --target-java-version "
                        + arguments.targetJavaMajor() + "; set JAVA_HOME to a JDK " + arguments.targetJavaMajor()
                        + " installation, which the launcher uses before the java on PATH");
            }
            readPackageScope();
            Path distribution = arguments.distribution();
            if (!Files.exists(distribution)) {
                throw new IOException("Distribution does not exist: " + distribution);
            }

            if (Files.isDirectory(distribution)) {
                try (Stream<Path> files = Files.walk(distribution)) {
                    files.filter(Files::isRegularFile)
                            .filter(SnapshotBuilder::isJar)
                            .sorted()
                            .forEach(path -> readJar(path, distribution.relativize(path).toString()));
                }
                readDescriptorsFromDirectory(distribution);
            } else if (isJar(distribution)) {
                readJar(distribution, distribution.getFileName().toString());
                readDirectDescriptors(distribution);
            } else {
                readArchive(distribution);
            }

            if (arguments.containerLibs() != null) {
                if (!Files.isDirectory(arguments.containerLibs())) {
                    throw new IOException("Container library directory does not exist: " + arguments.containerLibs());
                }

                try (Stream<Path> files = Files.walk(arguments.containerLibs())) {
                    files.filter(Files::isRegularFile).filter(SnapshotBuilder::isJar).sorted()
                            .forEach(path -> readJar(path, "container/lib/"
                                    + arguments.containerLibs().relativize(path)));
                }
            }

            if (bundles.isEmpty()) {
                throw new IOException("No JAR manifests found under distribution: " + distribution);
            }
            if (!observedProductVersions.equals(Set.of(arguments.productVersion()))) {
                throw new IOException("Product metadata versions " + observedProductVersions
                        + " do not match requested " + arguments.productVersion());
            }
            if (arguments.product().equals("refapp")) {
                readRefappConfiguration();
            }
            if (descriptors.isEmpty()) {
                throw new IOException("No product export descriptor found under distribution: " + distribution);
            }
            if (framework == null || systemPackages.isEmpty()) {
                throw new IOException("Distribution lacks framework/system export evidence");
            }
            addJdkProviders();
        }

        private void readRefappConfiguration() throws IOException {
            RefappExportConfiguration configuration = RefappExportConfiguration.read(
                    arguments.distribution(), arguments.containerLibs(), arguments.workspace());
            if (!configuration.hostVersion().equals(arguments.productVersion())) {
                throw new IOException("RefApp scanner host version does not match requested " + arguments.productVersion());
            }
            Map<String, Set<String>> policies = Map.of(
                    "packageExports", configuration.includes(),
                    "packageExcludes", configuration.excludes(),
                    "publicPackages", configuration.publicPackages(),
                    "publicExcludes", configuration.publicExcludes(),
                    "deprecatedPackages", configuration.deprecatedPackages());
            policies.forEach((key, values) -> {
                descriptorValues.put(key, values);
                descriptors.put(key, List.of(RefappExportConfiguration.FACTORY_RESOURCE));
            });
            packageVersions.putAll(configuration.versions());
            descriptors.put("packageVersions", List.of(RefappExportConfiguration.FACTORY_RESOURCE));
        }

        private void readPackageScope() throws IOException {
            if (arguments.verificationReport() != null) {
                com.fasterxml.jackson.databind.JsonNode report = new ObjectMapper()
                        .readTree(arguments.verificationReport().toFile());
                if (!report.path("verificationScope").asText().equals("selected-bundled-library-requirements")
                        || !report.path("verification").path("mandatoryImports").isArray()) {
                    throw new IOException("Expected a scoped bundling verification report");
                }

                report.path("verification").path("mandatoryImports").forEach(value -> coveredPackages.add(value.asText()));
                scopeProvenance.put("scope", "selected-bundled-library-requirements");
                scopeProvenance.put("artifactSha256", report.path("artifactSha256").asText());
                return;
            }

            List<BundleDependencyModel.DependencySpec> libraries = BundleDependencyModel.families().values().stream()
                    .flatMap(family -> Stream.concat(family.dependencies().stream(), family.conditionalDependencies().stream()))
                    .distinct().toList();
            for (BundleDependencyModel.DependencySpec library : libraries) {
                String relative = library.groupId().replace('.', '/') + "/" + library.artifactId() + "/"
                        + library.version() + "/" + library.artifactId() + "-" + library.version() + ".jar";
                Path jar = arguments.libraryRepository().resolve(relative);
                if (!Files.isRegularFile(jar)) {
                    throw new IOException("Missing catalog library " + library.coordinate() + ":" + library.version()
                            + "; resolve it into --library-repository before rebuilding exports");
                }

                coveredPackages.addAll(LibraryPackageRequirements.containedPackages(jar, arguments.targetJavaMajor()));
                coveredPackages.addAll(LibraryPackageRequirements.referencedPackages(jar, arguments.targetJavaMajor()));
                coveredPackages.addAll(BundleDependencyModel.reflectivePackageReferences(library.coordinate()));
                scopeProvenance.put("library." + library.coordinate() + ":" + library.version(), sha256(jar));
            }
            scopeProvenance.put("scope", "catalog-library-requirements-including-conditional-companions");
        }

        private void write() throws IOException {
            Path outputParent = arguments.output().toAbsolutePath().getParent();
            if (outputParent != null) {
                Files.createDirectories(outputParent);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", OsgiExportCatalog.SCHEMA_VERSION);
            root.put("product", arguments.product());
            root.put("normalizedVersion", OsgiExportCatalog.normalizeVersion(arguments.productVersion()));
            root.put("sourceVersion", arguments.productVersion());
            root.put("javaMajor", arguments.targetJavaMajor());
            root.put("generatedAt", Instant.now().toString());
            root.put("evidenceKind", "product-distribution");
            root.put("provenance", provenance());

            root.put("coveredPackages", coveredPackages);
            root.put("exports", exports());

            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(arguments.output().toFile(), root);
        }

        private Map<String, String> provenance() throws IOException {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("distribution", arguments.distribution().getFileName().toString());
            result.put("distributionSha256", sha256(arguments.distribution()));
            if (arguments.containerLibs() != null) {
                result.put("containerLibraries", arguments.containerLibs().getParent().getFileName()
                        + "/" + arguments.containerLibs().getFileName());
                result.put("containerLibrariesSha256", sha256(arguments.containerLibs()));
            }
            result.put("extractionMode", arguments.product().equals("refapp")
                    ? "archive-and-bundle-manifests-plus-refapp-scanner-configuration"
                    : "archive-and-bundle-manifests-plus-product-export-descriptors");
            result.put("runtimeObservation", "unverified-static-distribution-evidence");
            result.put("targetJavaMajor", Integer.toString(arguments.targetJavaMajor()));
            result.put("productMetadataVersion", observedProductVersions.iterator().next());
            result.put("versionPolicy", "Omitted versions are unknown; constrained imports require additional evidence");
            result.putAll(scopeProvenance);
            for (Map.Entry<String, List<String>> descriptor : descriptors.entrySet()) {
                result.put("descriptor." + descriptor.getKey(), descriptor.getValue().get(0));
            }
            return result;
        }

        private List<Map<String, Object>> exports() {
            Map<String, Map<String, Object>> output = new TreeMap<>();
            for (PackageExport export : manifestExports.values()) {
                if (!export.provider().kind().equals("system-library")) {
                    addExport(output, export.packageName(), export.provider(), export.packageVersion(),
                            export.attributes(), export.directives());
                }
            }

            for (String packageName : new TreeSet<>(classProviders.keySet())) {
                Set<String> providers = classProviders.get(packageName);
                boolean host = providers.stream().anyMatch(path -> path.equals("WEB-INF/classes")
                        || path.startsWith("jrt:/") || (bundles.containsKey(path)
                        && bundles.get(path).kind().equals("system-library")));
                boolean exported = (descriptorMatches(packageName, "packageExports")
                        && !descriptorMatches(packageName, "packageExcludes"))
                        || systemPackages.containsKey(packageName);
                if (host && exported) {
                    String version = systemPackages.getOrDefault(packageName, hostVersion(packageName));
                    addExport(output, packageName, framework, version,
                            Map.of(), Map.of());
                }
            }

            return output.values().stream().sorted(Comparator.comparing(value -> (String) value.get("packageName")))
                    .toList();
        }

        private void addExport(Map<String, Map<String, Object>> output, String packageName, Bundle provider,
                                String version, Map<String, String> sourceAttributes,
                                Map<String, String> sourceDirectives) {
            if (!coveredPackages.contains(packageName)) {
                return;
            }
            String access = sourceAttributes.getOrDefault("atlassian-access",
                    sourceDirectives.getOrDefault("atlassian-access", "inherited"));
            String effectiveAccess = access.equals("inherited") ? visibility(packageName) : access;
            OsgiExportCatalog.Export candidate = new OsgiExportCatalog.Export(packageName, version,
                    provider.symbolicName(), provider.version(), sourceAttributes, sourceDirectives);
            if (effectiveAccess.equalsIgnoreCase("internal") || candidate.restricted()) {
                return;
            }

            Map<String, Object> value = new LinkedHashMap<>();
            value.put("packageName", packageName);
            if (version != null && !version.isBlank()) {
                value.put("version", version);
            }
            Map<String, String> attributes = new TreeMap<>(sourceAttributes);
            Set.of("version", "atlassian-access", "visibility", "classProviders", "providerPath", "evidence", "x-internal")
                    .forEach(attributes::remove);
            Map<String, String> directives = new TreeMap<>(sourceDirectives);
            Set.of("uses", "atlassian-access", "x-internal").forEach(directives::remove);
            if (!attributes.isEmpty()) {
                value.put("attributes", attributes);
            }
            if (!directives.isEmpty()) {
                value.put("directives", directives);
            }
            String key = packageName + "\u0000" + provider.symbolicName() + "\u0000" + provider.version() + "\u0000" + version;
            output.putIfAbsent(key, value);
        }

        private String hostVersion(String packageName) {
            return packageVersions.entrySet().stream()
                    .filter(entry -> patternMatches(packageName).test(entry.getKey()))
                    .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                    .map(Map.Entry::getValue).orElse("");
        }

        private String visibility(String packageName) {
            if (!descriptorMatches(packageName, "publicPackages")
                    || descriptorMatches(packageName, "publicExcludes")) {
                return "internal";
            }
            return descriptorMatches(packageName, "deprecatedPackages") ? "deprecated" : "public";
        }

        private boolean descriptorMatches(String packageName, String descriptor) {
            return descriptorValues.getOrDefault(descriptor, Set.of()).stream().anyMatch(patternMatches(packageName));
        }

        private static java.util.function.Predicate<String> patternMatches(String packageName) {
            return pattern -> {
                if (pattern.equals(packageName)) {
                    return true;
                }
                if (!pattern.endsWith("*")) {
                    return false;
                }
                String prefix = pattern.substring(0, pattern.length() - 1);
                return packageName.startsWith(prefix) || (prefix.endsWith(".")
                        && packageName.equals(prefix.substring(0, prefix.length() - 1)));
            };
        }

        private void readArchive(Path archive) throws IOException {
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                List<? extends ZipEntry> entries = zip.stream()
                        .filter(entry -> !entry.isDirectory())
                        .sorted(Comparator.comparing(ZipEntry::getName))
                        .toList();
                for (ZipEntry entry : entries) {
                    String name = entry.getName();
                    if (isJarName(name)) {
                        readJar(zip.getInputStream(entry), name);
                    } else if (name.startsWith("WEB-INF/classes/") && name.endsWith(".class")) {
                        String pkg = packageFromClass(name.substring("WEB-INF/classes/".length()));
                        if (pkg != null) {
                            classProviders.computeIfAbsent(pkg, ignored -> new TreeSet<>()).add("WEB-INF/classes");
                        }
                    } else if (isDescriptor(name)) {
                        readDescriptor(name, zip.getInputStream(entry));
                    }
                }
            }
        }

        private void readJar(Path path, String logicalPath) {
            try {
                readJar(Files.newInputStream(path), logicalPath);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read JAR " + path, exception);
            }
        }

        private void readJar(InputStream input, String logicalPath)
                throws IOException {
            try (InputStream source = input; JarInputStream jar = new JarInputStream(source)) {
                Manifest manifest = jar.getManifest();
                if (manifest == null) {
                    return;
                }
                Map<String, String> main = attributes(manifest.getMainAttributes());
                String symbolicName = valueBeforeSemicolon(main.getOrDefault("Bundle-SymbolicName", ""));
                String bundleVersion = main.getOrDefault("Bundle-Version", "");
                Bundle bundle = new Bundle(logicalPath, symbolicName, bundleVersion,
                        kind(logicalPath));
                bundles.put(logicalPath, bundle);
                if (symbolicName.equals("org.apache.felix.framework") && !logicalPath.startsWith("container/")) {
                    framework = bundle;
                }
                for (Map.Entry<String, String> header : exports(main.get("Export-Package")).entrySet()) {
                    Map<String, String> clauses = clauseAttributes(header.getValue());
                    String packageVersion = clauses.getOrDefault("version", "0.0.0");
                    manifestExports.putIfAbsent(header.getKey() + "\u0000" + logicalPath,
                            new PackageExport(header.getKey(), packageVersion, bundle,
                                    clauses, clauseDirectives(header.getValue())));
                }

                JarEntryView view;
                while ((view = nextEntry(jar)) != null) {
                    if (view.name().endsWith(".class")) {
                        String packageName = packageFromClass(view.name());
                        if (packageName != null) {
                            classProviders.computeIfAbsent(packageName, ignored -> new TreeSet<>()).add(logicalPath);
                        }
                    } else if (isDescriptor(view.name())) {
                        readDescriptor(logicalPath + "!/" + view.name(),
                                new ByteArrayInputStream(jar.readAllBytes()));
                    }
                }
            }
        }

        private static JarEntryView nextEntry(JarInputStream jar) throws IOException {
            ZipEntry entry = jar.getNextJarEntry();
            return entry == null ? null : new JarEntryView(entry.getName());
        }

        private void readDescriptorsFromDirectory(Path root) throws IOException {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String logical = root.relativize(file).toString().replace('\\', '/');
                    if (logical.startsWith("WEB-INF/classes/") && logical.endsWith(".class")) {
                        String pkg = packageFromClass(logical.substring("WEB-INF/classes/".length()));
                        if (pkg != null) {
                            classProviders.computeIfAbsent(pkg, ignored -> new TreeSet<>()).add("WEB-INF/classes");
                        }
                    }
                    if (isDescriptor(file.toString())) {
                        readDescriptor(root.relativize(file).toString(), Files.newInputStream(file));
                    }
                }
            }
        }

        private void readDirectDescriptors(Path archive) throws IOException {
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                for (ZipEntry entry : zip.stream().filter(candidate -> !candidate.isDirectory()).toList()) {
                    if (isDescriptor(entry.getName())) {
                        readDescriptor(entry.getName(), zip.getInputStream(entry));
                    }
                }
            }
        }

        private void readDescriptor(String path, InputStream input) throws IOException {
            try (InputStream source = input) {
                String text = new String(source.readAllBytes(), StandardCharsets.UTF_8);
                String descriptor = descriptorName(path);
                if ("versionMetadata".equals(descriptor)) {
                    Properties values = new Properties();
                    values.load(new java.io.StringReader(text));
                    String version = arguments.product().equals("confluence") ? values.getProperty("version.number")
                            : values.getProperty("version");
                    if (version != null) {
                        observedProductVersions.add(version);
                    }
                    return;
                }
                if ("packageVersions".equals(descriptor) || "systemVersions".equals(descriptor)) {
                    Properties values = new Properties();
                    values.load(new java.io.StringReader(text));
                    Map<String, String> target = descriptor.equals("packageVersions") ? packageVersions : systemPackages;
                    values.forEach((key, value) -> target.put(key.toString(), value.toString()));
                    return;
                }
                if ("jdkPackages".equals(descriptor)) {
                    text.lines().map(line -> line.replaceFirst("#.*$", "").trim())
                            .filter(line -> !line.isEmpty())
                            .forEach(line -> systemPackages.put(line, "0.0.0"));
                    return;
                }
                if (descriptor != null) {
                    String section = "includes";
                    Map<String, Set<String>> parsed = new LinkedHashMap<>();
                    for (String line : text.split("\\R")) {
                        String value = line.replaceFirst("#.*$", "").trim();
                        if (value.endsWith(":")) {
                            section = value.substring(0, value.length() - 1).trim();
                            continue;
                        }
                        if (value.startsWith("-")) {
                            value = value.substring(1).trim();
                        }
                        if (!value.isBlank() && !value.endsWith(":")) {
                            String key = yamlDescriptor(descriptor, section);
                            parsed.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(value);
                        }
                    }
                    if (parsed.isEmpty()) {
                        parsed.put(descriptor, new LinkedHashSet<>(text.lines()
                                .map(line -> line.replaceFirst("#.*$", "").trim())
                                .filter(line -> !line.isBlank())
                                .toList()));
                    }
                    for (Map.Entry<String, Set<String>> entry : parsed.entrySet()) {
                        if (entry.getKey().isBlank()) {
                            continue;
                        }
                        descriptors.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(path);
                        descriptorValues.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>())
                                .addAll(entry.getValue());
                    }
                }
            }
        }

        private static String yamlDescriptor(String descriptor, String section) {
            if (descriptor.equals("publicPackages")) {
                return switch (section) {
                    case "includes" -> "publicPackages";
                    case "excludes" -> "publicExcludes";
                    case "deprecated" -> "deprecatedPackages";
                    default -> "";
                };
            }
            if (descriptor.equals("packageExports")) {
                return section.equals("includes") ? "packageExports"
                        : section.equals("excludes") ? "packageExcludes" : "";
            }
            return descriptor;
        }

        private final Map<String, Set<String>> descriptorValues = new HashMap<>();

        private void addJdkProviders() {
            for (java.lang.module.ModuleReference reference : ModuleFinder.ofSystem().findAll()) {
                java.lang.module.ModuleDescriptor module = reference.descriptor();
                for (String packageName : module.packages()) {
                    // Felix derives its default JPMS exports from unqualified exports of resolved modules.
                    boolean javaExport = ModuleLayer.boot().findModule(module.name()).isPresent()
                            && module.exports().stream().anyMatch(export ->
                            export.source().equals(packageName) && !export.isQualified());
                    if (javaExport || systemPackages.containsKey(packageName)) {
                        systemPackages.putIfAbsent(packageName, "0.0.0");
                        classProviders.computeIfAbsent(packageName, ignored -> new TreeSet<>())
                                .add("jrt:/" + module.name());
                    }
                }
            }
        }

        private boolean isDescriptor(String path) {
            return descriptorName(path) != null;
        }

        private String descriptorName(String path) {
            if ((arguments.product().equals("jira") && path.endsWith("META-INF/maven/com.atlassian.jira/atlassian-jira-webapp/pom.properties"))
                    || (arguments.product().equals("confluence")
                    && path.endsWith("com/atlassian/confluence/default.properties"))
                    || (arguments.product().equals("refapp")
                    && path.endsWith("META-INF/maven/com.atlassian.refapp/atlassian-refapp/pom.properties"))) {
                return "versionMetadata";
            }
            if (path.endsWith("package-versions.properties")) {
                return "packageVersions";
            }
            if (path.endsWith("jdk11-packages.txt")) {
                return "jdkPackages";
            }
            if (path.endsWith("osgi-packages.txt")) {
                return "systemVersions";
            }
            if (path.endsWith("public-packages.txt") || path.endsWith("public-api.yaml")) {
                return "publicPackages";
            }
            if (path.endsWith("public-packages-excludes.txt") || path.endsWith("excludes.yaml")) {
                return "publicExcludes";
            }
            if (path.endsWith("deprecated-packages.txt")) {
                return "deprecatedPackages";
            }
            if (path.endsWith("package-includes.txt") || path.endsWith("package-exports.yaml")) {
                return "packageExports";
            }
            if (path.endsWith("package-excludes.txt")) {
                return "packageExcludes";
            }
            return null;
        }

        private Map<String, String> attributes(java.util.jar.Attributes attributes) {
            Map<String, String> result = new TreeMap<>();
            attributes.forEach((key, value) -> result.put(key.toString(), value.toString()));
            return result;
        }

        private static Map<String, String> exports(String header) {
            Map<String, String> result = new TreeMap<>();
            if (header == null) {
                return result;
            }
            for (String clause : splitClauses(header)) {
                String[] parts = clause.split(";", 2);
                result.put(parts[0].trim(), clause);
            }
            return result;
        }

        private static Map<String, String> clauseAttributes(String clause) {
            Map<String, String> result = new TreeMap<>();
            for (String part : clause.split(";")) {
                int equals = part.indexOf('=');
                if (equals > 0 && !part.substring(0, equals).trim().endsWith(":")) {
                    result.put(part.substring(0, equals).trim(), unquote(part.substring(equals + 1).trim()));
                }
            }
            return result;
        }

        private static Map<String, String> clauseDirectives(String clause) {
            Map<String, String> result = new TreeMap<>();
            for (String part : clause.split(";")) {
                int equals = part.indexOf(":=");
                if (equals > 0) {
                    result.put(part.substring(0, equals).trim(), unquote(part.substring(equals + 2).trim()));
                }
            }
            return result;
        }

        private static List<String> splitClauses(String value) {
            List<String> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            for (char character : value.toCharArray()) {
                if (character == '"') {
                    quoted = !quoted;
                }
                if (character == ',' && !quoted) {
                    result.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(character);
                }
            }
            result.add(current.toString());
            return result;
        }

        private static String unquote(String value) {
            return value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")
                    ? value.substring(1, value.length() - 1) : value;
        }

        private static String valueBeforeSemicolon(String value) {
            int separator = value.indexOf(';');
            return (separator < 0 ? value : value.substring(0, separator)).trim();
        }

        private static String packageFromClass(String entry) {
            if (entry.endsWith("module-info.class") || entry.endsWith("package-info.class")) {
                return null;
            }
            int slash = entry.lastIndexOf('/');
            return slash < 0 ? "" : entry.substring(0, slash).replace('/', '.');
        }

        private static String kind(String path) {
            if (path.contains("framework-bundles/")) {
                return "osgi-framework";
            }
            if (path.contains("atlassian-bundled-plugins")) {
                return "bundled-plugin";
            }
            return "system-library";
        }

        private static boolean isJar(Path path) {
            return isJarName(path.toString());
        }

        private static boolean isJarName(String path) {
            return path.endsWith(".jar");
        }

        private static String sha256(Path path) throws IOException {
            if (Files.isDirectory(path)) {
                MessageDigest digest = digest();
                try (Stream<Path> paths = Files.walk(path)) {
                    for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                        digest.update(path.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                        digest.update(sha256(file).getBytes(StandardCharsets.US_ASCII));
                    }
                }
                return hex(digest.digest());
            }
            try (InputStream input = Files.newInputStream(path)) {
                MessageDigest digest = digest();
                input.transferTo(new java.security.DigestOutputStream(OutputStreamSink.INSTANCE, digest));
                return hex(digest.digest());
            }
        }

        private static MessageDigest digest() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static String hex(byte[] bytes) {
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        }

        private record JarEntryView(String name) {
        }

        private record PackageExport(String packageName, String packageVersion, Bundle provider,
                                     Map<String, String> attributes, Map<String, String> directives) {
        }

        private record Bundle(String path, String symbolicName, String version, String kind) {
        }

        private static final class OutputStreamSink extends java.io.OutputStream {
            private static final OutputStreamSink INSTANCE = new OutputStreamSink();

            @Override
            public void write(int value) {
            }
        }
    }

    private record Arguments(Path workspace, String product, String productVersion, int targetJavaMajor,
                             Path distribution, Path containerLibs, Path libraryRepository, Path verificationReport, Path output) {
        private static Arguments parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            Set<String> allowed = Set.of("workspace", "product", "product-version",
                    "target-java-version", "distribution", "container-libs", "library-repository", "verification-report", "output");
            for (int index = 0; index < args.length; index += 2) {
                if (index + 1 >= args.length || !args[index].startsWith("--")) {
                    throw new IllegalArgumentException("Expected --option value pairs");
                }
                String key = args[index].substring(2);
                if (!allowed.contains(key) || values.putIfAbsent(key, args[index + 1]) != null) {
                    throw new IllegalArgumentException("Unknown or repeated option " + args[index]);
                }
            }
            String product = required(values, "product");
            if (!Set.of("jira", "confluence", "refapp").contains(product)) {
                throw new IllegalArgumentException("Unsupported product '" + product
                        + "'; export extraction supports only jira, confluence and refapp distributions");
            }
            if (values.containsKey("library-repository") == values.containsKey("verification-report")) {
                throw new IllegalArgumentException("Pass exactly one of --library-repository or --verification-report");
            }
            return new Arguments(path(values, "workspace"), product,
                    required(values, "product-version"), Integer.parseInt(required(values, "target-java-version")),
                    path(values, "distribution"), values.containsKey("container-libs") ? path(values, "container-libs") : null,
                    values.containsKey("library-repository") ? path(values, "library-repository") : null,
                    values.containsKey("verification-report") ? path(values, "verification-report") : null,
                    path(values, "output"));
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return value;
        }

        private static Path path(Map<String, String> values, String key) {
            return Path.of(required(values, key));
        }
    }
}
