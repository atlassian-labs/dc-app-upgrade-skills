package com.atlassian.dc.p9enablement.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verifies the actual plugin class path, including declared nested jars and OSGi requirements. */
public final class BundlingArtifactVerifier {
    private BundlingArtifactVerifier() {}

    public static void main(String[] arguments) throws Exception {
        if (List.of(arguments).contains("--help")) {
            System.out.println("verify --workspace PATH --plan FILE --artifact JAR --output FILE "
                    + "[--baseline JAR] [--provided-packages FILE] [--export-snapshot FILE] "
                    + "[--target-java-version INTEGER]");
            return;
        }

        Map<String, String> options = new LinkedHashMap<>();
        Set<String> accepted = Set.of("--workspace", "--plan", "--artifact", "--output", "--baseline",
                "--provided-packages", "--export-snapshot", "--target-java-version");

        for (int index = 0; index < arguments.length; index += 2) {
            if (!accepted.contains(arguments[index]) || index + 1 >= arguments.length) {
                throw new IllegalArgumentException("Unknown or incomplete option: " + arguments[index]);
            }

            if (options.put(arguments[index], arguments[index + 1]) != null) {
                throw new IllegalArgumentException("Repeated option: " + arguments[index]);
            }
        }

        Path workspace = Path.of(required(options, "--workspace")).toRealPath();
        Path planPath = workspace.resolve(required(options, "--plan")).normalize();
        Path artifact = workspace.resolve(required(options, "--artifact")).toRealPath();
        Path output = workspace.resolve(required(options, "--output")).normalize();
        Path baseline = options.containsKey("--baseline")
                ? workspace.resolve(options.get("--baseline")).toRealPath() : null;

        Set<String> provided = options.containsKey("--provided-packages")
                ? Set.copyOf(Files.readAllLines(workspace.resolve(options.get("--provided-packages"))).stream()
                        .map(String::trim).filter(line -> !line.isBlank() && !line.startsWith("#")).toList())
                : Set.of();

        ObjectMapper mapper = new ObjectMapper();
        BundlePlan plan = mapper.readValue(planPath.toFile(), BundlePlan.class);
        int targetJavaVersion = options.containsKey("--target-java-version")
                ? Integer.parseInt(options.get("--target-java-version"))
                : plan.targetJavaVersion() > 0 ? plan.targetJavaVersion() : Runtime.version().feature();
        OsgiExportCatalog.Selection exportSelection;
        if (options.containsKey("--export-snapshot")) {
            exportSelection = OsgiExportCatalog.selectOverride(
                    workspace.resolve(options.get("--export-snapshot")).toRealPath(),
                    plan.targetProduct(), plan.targetVersion(), targetJavaVersion);
        } else if (plan.exportEvidence() != null && Path.of(plan.exportEvidence().source()).isAbsolute()) {
            exportSelection = OsgiExportCatalog.selectOverride(Path.of(plan.exportEvidence().source()),
                    plan.targetProduct(), plan.targetVersion(), targetJavaVersion);
        } else {
            exportSelection = OsgiExportCatalog.selectBundled(
                    BundlingArtifactVerifier.class.getClassLoader(), plan.targetProduct(), plan.targetVersion(),
                    targetJavaVersion);
        }
        List<ArtifactRequirement> requirements = plan.verificationArtifacts().stream()
                .map(expected -> new ArtifactRequirement(expected.coordinate(), expected.classPrefixes())).toList();

        Set<String> forbidden = plan.verificationArtifacts().stream()
                .flatMap(expected -> expected.forbiddenPrefixes().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Set<String> targetProvidedPackages = plan.dependencyClassifications().stream()
                .filter(candidate -> BundlePlan.DependencyClassificationStatus.PROVIDED_BY_TARGET.equals(candidate.status()))
                .flatMap(candidate -> candidate.packages().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Verification verification = verifyBundling(artifact, requirements, forbidden, baseline, provided, exportSelection,
                targetJavaVersion, targetProvidedPackages);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("verifiedAt", Instant.now().toString());
        report.put("artifact", artifact.toString());
        report.put("artifactSha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
        report.put("targetProduct", plan.targetProduct());
        report.put("targetVersion", plan.targetVersion());
        report.put("targetJavaVersion", targetJavaVersion);
        report.put("exportEvidence", exportEvidence(exportSelection, plan.targetProduct(), plan.targetVersion(),
                targetJavaVersion));
        report.put("verificationScope", "selected-bundled-library-requirements");
        report.put("targetProvidedPackages", targetProvidedPackages.stream().sorted().toList());
        report.put("outOfScopeImports", mandatoryImports(readBundle(artifact).manifest()).stream()
                .filter(name -> !verification.mandatoryImports().contains(name)).sorted().toList());
        report.put("verification", verification);
        report.put("status", verification.blockers().isEmpty() && verification.requirementsToReview().isEmpty()
                && verification.evidenceGaps().isEmpty()
                ? "passed" : "needs-attention");

        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println(output);
        if (!verification.blockers().isEmpty() || !verification.requirementsToReview().isEmpty()
                || !verification.evidenceGaps().isEmpty()) {
            System.exit(2);
        }
    }

    private static Map<String, Object> exportEvidence(OsgiExportCatalog.Selection selection, String product,
                                                       String targetVersion, int targetJavaVersion) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("product", product);
        evidence.put("targetVersion", targetVersion);
        evidence.put("targetJavaVersion", targetJavaVersion);
        evidence.put("matchQuality", selection.matchQuality().name().toLowerCase());
        evidence.put("provenance", selection.provenance());
        evidence.put("evidenceGaps", selection.evidenceGaps());
        if (selection.snapshot() != null) {
            evidence.put("sourceVersion", selection.snapshot().sourceVersion());
            evidence.put("normalizedVersion", selection.snapshot().normalizedVersion());
            evidence.put("javaMajor", selection.snapshot().javaMajor());
            evidence.put("evidenceKind", selection.snapshot().evidenceKind());
            evidence.put("snapshotProvenance", selection.snapshot().provenance());
        }
        return evidence;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }

        return value;
    }

    public record ArtifactRequirement(String coordinate, List<String> classPrefixes) {}

    public record Verification(
            List<String> containedArtifacts,
            List<String> blockers,
            List<String> mandatoryImports,
            List<String> requirementsToReview,
            boolean baselineAvailable,
            List<String> missingImports,
            List<String> incompatibleImports,
            List<String> restrictedImports,
            List<String> evidenceGaps,
            List<String> uncertainties,
            ExportVerification exportVerification) {
        public Verification {
            containedArtifacts = List.copyOf(containedArtifacts == null ? List.of() : containedArtifacts);
            blockers = List.copyOf(blockers == null ? List.of() : blockers);
            mandatoryImports = List.copyOf(mandatoryImports == null ? List.of() : mandatoryImports);
            requirementsToReview = List.copyOf(requirementsToReview == null ? List.of() : requirementsToReview);
            missingImports = List.copyOf(missingImports == null ? List.of() : missingImports);
            incompatibleImports = List.copyOf(incompatibleImports == null ? List.of() : incompatibleImports);
            restrictedImports = List.copyOf(restrictedImports == null ? List.of() : restrictedImports);
            evidenceGaps = List.copyOf(evidenceGaps == null ? List.of() : evidenceGaps);
            uncertainties = List.copyOf(uncertainties == null ? List.of() : uncertainties);
        }

        /** Preserves the original result-construction API for callers without export evidence. */
        public Verification(List<String> containedArtifacts, List<String> blockers, List<String> mandatoryImports,
                            List<String> requirementsToReview, boolean baselineAvailable) {
            this(containedArtifacts, blockers, mandatoryImports, requirementsToReview, baselineAvailable,
                    List.of(), List.of(), List.of(), List.of(), List.of(), null);
        }

        /** Source and quality of the export snapshot used for this result, if any. */
        public record ExportVerification(String sourceVersion, String normalizedVersion, String matchQuality,
                                         String provenance) {
        }
    }

    public static Verification verify(
            Path artifact,
            List<ArtifactRequirement> requirements,
            Set<String> forbiddenPrefixes,
            Path baseline,
            Set<String> providedPackages) throws IOException {
        return verify(artifact, requirements, forbiddenPrefixes, baseline, providedPackages,
                (OsgiExportCatalog.Selection) null,
                Runtime.version().feature());
    }

    /** Verifies packaging and, when supplied, resolves mandatory imports against product exports. */
    public static Verification verify(
            Path artifact,
            List<ArtifactRequirement> requirements,
            Set<String> forbiddenPrefixes,
            Path baseline,
            Set<String> providedPackages,
            OsgiExportCatalog.Selection exportSelection,
            int targetJavaVersion) throws IOException {
        return verifyImports(artifact, requirements, forbiddenPrefixes, baseline, providedPackages,
                exportSelection, targetJavaVersion, null, Set.of());
    }

    /** Checks only manifest imports referenced by the selected bundled library classes. */
    public static Verification verifyBundling(Path artifact, List<ArtifactRequirement> requirements,
                                              Set<String> forbiddenPrefixes, Path baseline,
                                              Set<String> providedPackages, OsgiExportCatalog.Selection exportSelection,
                                              int targetJavaVersion) throws IOException {
        return verifyBundling(artifact, requirements, forbiddenPrefixes, baseline, providedPackages,
                exportSelection, targetJavaVersion, Set.of());
    }

    /** Also checks app-used packages that preflight deliberately left to the product. */
    public static Verification verifyBundling(Path artifact, List<ArtifactRequirement> requirements,
                                              Set<String> forbiddenPrefixes, Path baseline,
                                              Set<String> providedPackages, OsgiExportCatalog.Selection exportSelection,
                                              int targetJavaVersion, Set<String> targetProvidedPackages) throws IOException {
        List<String> prefixes = requirements.stream().flatMap(requirement -> requirement.classPrefixes().stream())
                .distinct().toList();
        Set<String> references = new TreeSet<>(LibraryPackageRequirements.bundledReferences(artifact, prefixes, targetJavaVersion));
        requirements.forEach(requirement -> references.addAll(
                BundleDependencyModel.reflectivePackageReferences(requirement.coordinate())));
        references.addAll(targetProvidedPackages);
        return verifyImports(artifact, requirements, forbiddenPrefixes, baseline, providedPackages,
                exportSelection, targetJavaVersion, references, targetProvidedPackages);
    }

    private static Verification verifyImports(Path artifact, List<ArtifactRequirement> requirements,
                                               Set<String> forbiddenPrefixes, Path baseline,
                                               Set<String> providedPackages, OsgiExportCatalog.Selection exportSelection,
                                               int targetJavaVersion, Set<String> references,
                                               Set<String> targetProvidedPackages) throws IOException {
        BundleContents contents = readBundle(artifact);
        List<String> contained = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        for (ArtifactRequirement requirement : requirements) {
            List<String> missing = requirement.classPrefixes().stream()
                    .filter(prefix -> contents.classes().stream().noneMatch(name -> name.startsWith(prefix)))
                    .toList();

            if (missing.isEmpty()) {
                contained.add(requirement.coordinate());
            } else {
                blockers.add("Missing classes for " + requirement.coordinate() + ": " + missing);
            }
        }

        for (String prefix : forbiddenPrefixes) {
            if (contents.classes().stream().anyMatch(name -> name.startsWith(prefix))) {
                blockers.add("Plugin contains excluded platform/JDK classes under " + prefix);
            }
        }

        for (String header : List.of("Import-Package", "Export-Package")) {
            List<String> packages = splitHeader(contents.manifest().getMainAttributes().getValue(header), ',').stream()
                    .flatMap(clause -> splitHeader(clause, ';').stream().takeWhile(segment -> !segment.contains("=")))
                    .toList();

            for (ArtifactRequirement requirement : requirements) {
                for (String prefix : requirement.classPrefixes()) {
                    String packagePrefix = prefix.replace('/', '.').replaceAll("\\.$", "");
                    packages.stream().filter(name -> name.equals(packagePrefix) || name.startsWith(packagePrefix + "."))
                            .forEach(name -> blockers.add(header + " still exposes bundled package " + name));
                }
            }
        }

        List<ImportRequirement> importRequirements = importRequirements(contents.manifest(), targetProvidedPackages).stream()
                .filter(requirement -> references == null || references.contains(requirement.packageName())).toList();
        for (String packageName : targetProvidedPackages) {
            String classDirectory = packageName.replace('.', '/') + "/";
            boolean containedLocally = contents.classes().stream().anyMatch(name -> name.startsWith(classDirectory)
                    && name.indexOf('/', classDirectory.length()) < 0);
            boolean imported = importRequirements.stream().anyMatch(requirement -> requirement.packageName().equals(packageName));
            if (!containedLocally && !imported) {
                blockers.add("App-used target-provided package is neither contained nor imported: " + packageName);
            }
        }
        Set<String> mandatory = importRequirements.stream().map(ImportRequirement::packageName).collect(
                java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> previous = baseline == null ? Set.of() : mandatoryImports(readBundle(baseline).manifest());
        List<String> requirementsToReview = mandatory.stream()
                .filter(name -> !previous.contains(name) && !providedPackages.contains(name))
                .sorted()
                .toList();

        List<String> missingImports = new ArrayList<>();
        List<String> incompatibleImports = new ArrayList<>();
        List<String> restrictedImports = new ArrayList<>();
        List<String> evidenceGaps = new ArrayList<>();
        List<String> uncertainties = new ArrayList<>();
        Verification.ExportVerification exportVerification = null;
        if (exportSelection != null) {
            evidenceGaps.addAll(exportSelection.evidenceGaps());
            OsgiExportCatalog.Snapshot snapshot = exportSelection.snapshot();
            if (snapshot != null && snapshot.javaMajor() != targetJavaVersion) {
                evidenceGaps.add("export snapshot Java " + snapshot.javaMajor()
                        + " does not match target Java " + targetJavaVersion);
            }
            if (snapshot == null) {
                evidenceGaps.add("no product OSGi export snapshot is available");
                requirementsToReview = List.of();
            } else {
                exportVerification = new Verification.ExportVerification(snapshot.sourceVersion(),
                        snapshot.normalizedVersion(),
                        exportSelection.matchQuality().name().toLowerCase(), exportSelection.provenance());
                requirementsToReview = new ArrayList<>();
                for (ImportRequirement requirement : importRequirements) {
                    checkImport(requirement, snapshot, missingImports, incompatibleImports, restrictedImports,
                            blockers, evidenceGaps, uncertainties);
                }
            }
            if (contents.manifest().getMainAttributes().getValue("DynamicImport-Package") != null) {
                uncertainties.add("DynamicImport-Package is not attributed to selected libraries by static verification");
            }
        }

        return new Verification(
            List.copyOf(contained), List.copyOf(blockers), mandatory.stream().sorted().toList(),
                List.copyOf(requirementsToReview), baseline != null, List.copyOf(missingImports),
                List.copyOf(incompatibleImports), List.copyOf(restrictedImports), distinct(evidenceGaps),
                distinct(uncertainties),
                exportVerification);
    }

    public static Verification verify(
            Path artifact,
            List<ArtifactRequirement> requirements,
            Set<String> forbiddenPrefixes,
            Path baseline,
            Set<String> providedPackages,
            OsgiExportCatalog.Selection exportSelection) throws IOException {
        return verify(artifact, requirements, forbiddenPrefixes, baseline, providedPackages, exportSelection,
                Runtime.version().feature());
    }

    private static void checkImport(ImportRequirement requirement, OsgiExportCatalog.Snapshot snapshot,
                                    List<String> missingImports, List<String> incompatibleImports,
                                    List<String> restrictedImports, List<String> blockers,
                                    List<String> evidenceGaps, List<String> uncertainties) {
        if (requirement.packageName().equals("*") || requirement.packageName().contains("*")) {
            evidenceGaps.add("wildcard Import-Package cannot be resolved statically: " + requirement.packageName());
            return;
        }

        if (!snapshot.covers(requirement.packageName())) {
            evidenceGaps.add("Package is outside this snapshot's coverage; refresh target export evidence: "
                    + requirement.packageName());
            return;
        }

        List<OsgiExportCatalog.Export> exports = snapshot.exports().stream()
                .filter(export -> export.packageName().equals(requirement.packageName()))
                .toList();
        if (exports.isEmpty()) {
            missingImports.add(requirement.packageName());
            blockers.add("Required package has no externally usable export in the selected product evidence: "
                    + requirement.packageName());
            return;
        }

        boolean missingProviderEvidence = exports.stream().anyMatch(export ->
                (!requirement.bundleSymbolicName().isBlank() && export.bundleSymbolicName().isBlank())
                        || (requirement.bundleVersionRange() != null && !requirement.bundleVersionRange().isBlank()
                        && export.bundleVersion().isBlank()));
        if (missingProviderEvidence) {
            evidenceGaps.add("Import requires bundle provider identity/version omitted from this snapshot: "
                    + requirement.packageName() + "; obtain provider evidence from the exact target runtime");
            return;
        }

        List<OsgiExportCatalog.Export> compatible = exports.stream()
                .filter(export -> versionInRange(export.version(), requirement.versionRange(), evidenceGaps,
                        requirement.packageName()))
                .filter(export -> versionInRange(export.bundleVersion(), requirement.bundleVersionRange(), evidenceGaps,
                        requirement.packageName()))
                .filter(export -> requirement.bundleSymbolicName().isBlank()
                        || requirement.bundleSymbolicName().equals(export.bundleSymbolicName()))
                .filter(export -> attributesMatch(requirement.attributes(), export))
                .toList();
        if (!compatible.isEmpty()) {
            List<OsgiExportCatalog.Export> unrestricted = compatible.stream()
                    .filter(export -> !export.restricted()).toList();
            if (unrestricted.isEmpty()) {
                restrictedImports.add(requirement.packageName());
                blockers.add("Mandatory Import-Package is restricted by the target product export: "
                        + requirement.packageName());
            }
            compatible.stream().map(export -> export.directives().get("uses"))
                    .filter(java.util.Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .forEach(value -> uncertainties.add("uses constraint is not resolved statically for "
                            + requirement.packageName() + ": " + value));
            compatible.stream()
                    .filter(export -> export.directives().containsKey("dmz")
                            || export.directives().containsKey("x-dmz")
                            || export.attributes().containsKey("dmz"))
                    .forEach(export -> uncertainties.add("DMZ visibility is not resolved statically for "
                            + requirement.packageName()));
            return;
        }

        incompatibleImports.add(requirement.packageName());
        blockers.add("Mandatory Import-Package constraints do not match target exports: "
                + requirement.packageName());
    }

    private static boolean attributesMatch(Map<String, String> imported, OsgiExportCatalog.Export exported) {
        for (Map.Entry<String, String> entry : imported.entrySet()) {
            String key = entry.getKey();
            if (key.equals("version") || key.equals("bundle-version") || key.equals("bundle-symbolic-name")) {
                continue;
            }
            String actual = key.equals("atlassian-access")
                    ? exported.attributes().getOrDefault(key, "public") : exported.attributes().get(key);
            if (actual == null || !actual.equals(entry.getValue())) {
                return false;
            }
        }

        String mandatory = exported.directives().get("mandatory");
        if (mandatory != null) {
            for (String key : mandatory.split(",")) {
                String name = key.trim();
                if (!name.isBlank() && !imported.containsKey(name)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean versionInRange(String actual, String range, List<String> evidenceGaps,
                                          String packageName) {
        if (range == null || range.isBlank()) {
            return true;
        }
        if (actual == null || actual.isBlank()) {
            evidenceGaps.add("export version is absent for constrained import " + packageName);
            return false;
        }

        try {
            Version value = Version.parse(actual);
            String expression = unquote(range.trim());
            if (!(expression.startsWith("[") || expression.startsWith("("))) {
                return value.compareTo(Version.parse(expression)) >= 0;
            }
            boolean lowerInclusive = expression.startsWith("[");
            boolean upperInclusive = expression.endsWith("]");
            if (expression.length() < 2 || (!expression.endsWith(")") && !expression.endsWith("]"))) {
                throw new IllegalArgumentException("invalid range");
            }
            String[] bounds = expression.substring(1, expression.length() - 1).split(",", -1);
            if (bounds.length != 2) {
                throw new IllegalArgumentException("invalid range");
            }
            boolean lower = bounds[0].isBlank() || value.compareTo(Version.parse(bounds[0].trim())) > 0
                    || (lowerInclusive && value.compareTo(Version.parse(bounds[0].trim())) == 0);
            boolean upper = bounds[1].isBlank() || value.compareTo(Version.parse(bounds[1].trim())) < 0
                    || (upperInclusive && value.compareTo(Version.parse(bounds[1].trim())) == 0);
            return lower && upper;
        } catch (RuntimeException exception) {
            evidenceGaps.add("unrecognised OSGi version range for " + packageName + ": " + range);
            return false;
        }
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static List<String> distinct(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private record ImportRequirement(String packageName, String versionRange, String bundleSymbolicName,
                                     String bundleVersionRange, Map<String, String> attributes,
                                     Map<String, String> directives) {
    }

    static List<ImportRequirement> importRequirements(Manifest manifest) {
        return importRequirements(manifest, Set.of());
    }

    private static List<ImportRequirement> importRequirements(Manifest manifest, Set<String> requiredOptionalPackages) {
        String header = manifest.getMainAttributes().getValue("Import-Package");
        List<ImportRequirement> requirements = new ArrayList<>();
        for (String clause : splitHeader(header, ',')) {
            List<String> segments = splitHeader(clause, ';');
            if (segments.isEmpty() || segments.get(0).isBlank()) {
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            Map<String, String> directives = new LinkedHashMap<>();
            for (String segment : segments.subList(1, segments.size())) {
                int directive = segment.indexOf(":=");
                int attribute = segment.indexOf('=');
                if (directive > 0) {
                    directives.put(segment.substring(0, directive).trim(),
                            unquote(segment.substring(directive + 2).trim()));
                } else if (attribute > 0) {
                    attributes.put(segment.substring(0, attribute).trim(),
                            unquote(segment.substring(attribute + 1).trim()));
                }
            }
            if ("optional".equalsIgnoreCase(directives.get("resolution"))
                    && !requiredOptionalPackages.contains(segments.get(0).trim())) {
                continue;
            }
            String packageName = segments.get(0).trim();
            requirements.add(new ImportRequirement(packageName, attributes.get("version"),
                    attributes.getOrDefault("bundle-symbolic-name", ""), attributes.get("bundle-version"),
                    Map.copyOf(attributes), Map.copyOf(directives)));
        }
        return List.copyOf(requirements);
    }

    static Set<String> mandatoryImports(Manifest manifest) {
        String header = manifest.getMainAttributes().getValue("Import-Package");
        Set<String> packages = new TreeSet<>();

        for (String clause : splitHeader(header, ',')) {
            List<String> segments = splitHeader(clause, ';');
            boolean optional = segments.stream()
                    .map(segment -> segment.replace(" ", ""))
                    .anyMatch(segment -> segment.equals("resolution:=optional")
                            || segment.equals("resolution:=\"optional\""));

            if (!optional) {
                segments.stream().takeWhile(segment -> !segment.contains("="))
                        .filter(segment -> !segment.isBlank()).forEach(packages::add);
            }
        }

        return Set.copyOf(packages);
    }

    // Commas inside quoted version ranges do not delimit OSGi clauses.
    static List<String> splitHeader(String header, char delimiter) {
        if (header == null || header.isBlank()) {
            return List.of();
        }

        List<String> clauses = new ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int start = 0;

        for (int index = 0; index < header.length(); index++) {
            char character = header.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (character == delimiter && !quoted) {
                clauses.add(header.substring(start, index).trim());
                start = index + 1;
            }
        }

        if (quoted) {
            throw new IllegalArgumentException("Unterminated quoted OSGi header: " + header);
        }

        clauses.add(header.substring(start).trim());
        return List.copyOf(clauses);
    }

    private static BundleContents readBundle(Path artifact) throws IOException {
        try (ZipFile archive = new ZipFile(artifact.toFile())) {
            ZipEntry manifestEntry = archive.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry == null) {
                throw new IOException("Plugin has no manifest: " + artifact);
            }

            Manifest manifest;
            try (InputStream stream = archive.getInputStream(manifestEntry)) {
                manifest = new Manifest(stream);
            }

            Attributes attributes = manifest.getMainAttributes();
            String classPathHeader = attributes.getValue("Bundle-ClassPath");
            List<String> classPath = classPathHeader == null ? List.of(".") : splitHeader(classPathHeader, ',');
            Set<String> classes = new LinkedHashSet<>();

            for (String rawLocation : classPath) {
                String location = rawLocation.replace("\"", "");
                if (location.endsWith(".jar")) {
                    ZipEntry nested = archive.getEntry(location);
                    if (nested == null) {
                        throw new IOException("Bundle-ClassPath entry is absent: " + location);
                    }

                    try (InputStream stream = archive.getInputStream(nested);
                         JarInputStream jar = new JarInputStream(stream)) {
                        ZipEntry entry;
                        while ((entry = jar.getNextEntry()) != null) {
                            if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                                classes.add(entry.getName());
                            }
                        }
                    }
                } else {
                    String prefix = location.equals(".") ? "" : location.replaceAll("/$", "") + "/";
                    archive.stream().filter(entry -> !entry.isDirectory())
                            .map(ZipEntry::getName)
                            .filter(name -> name.startsWith(prefix) && name.endsWith(".class"))
                            .map(name -> name.substring(prefix.length()))
                            .forEach(classes::add);
                }
            }

            return new BundleContents(manifest, Set.copyOf(classes));
        }
    }

    private record Version(int major, int minor, int micro, String qualifier) implements Comparable<Version> {
        private static Version parse(String value) {
            String[] parts = value.trim().split("\\.", 4);
            return new Version(number(parts, 0), number(parts, 1), number(parts, 2),
                    parts.length > 3 ? parts[3] : "");
        }

        private static int number(String[] parts, int index) {
            if (index >= parts.length) {
                return 0;
            }
            return Integer.parseInt(parts[index]);
        }

        @Override
        public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            result = result == 0 ? Integer.compare(minor, other.minor) : result;
            result = result == 0 ? Integer.compare(micro, other.micro) : result;
            return result == 0 ? qualifier.compareTo(other.qualifier) : result;
        }
    }

    private record BundleContents(Manifest manifest, Set<String> classes) {}
}
