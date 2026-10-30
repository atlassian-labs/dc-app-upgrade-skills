package com.atlassian.dc.p9enablement.migrate;

import com.atlassian.dc.p9enablement.migrate.source.JvmSourceScanner;

import com.atlassian.dc.p9enablement.migrate.BundleDependencyModel.DependencySpec;
import com.atlassian.dc.p9enablement.migrate.BundleDependencyModel.Exclusion;
import com.atlassian.dc.p9enablement.migrate.BundlePlan.Action;
import com.atlassian.dc.p9enablement.migrate.BundlePlan.DependencyEvidence;
import com.atlassian.dc.p9enablement.migrate.BundlePlan.DependencyOperation;
import com.atlassian.dc.p9enablement.migrate.BundlePlan.SourceEvidence;
import com.atlassian.dc.p9enablement.migrate.BundlePlan.VerificationArtifact;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Discovers bundling changes from JVM source references and Maven's effective model without compiling the app. */
public final class BundleDependencyAnalyzer {
    private static final List<String> SOURCE_ROOTS = List.of(
            "src/main/java", "src/main/kotlin", "src/main/scala", "src/main/groovy");

    private static final List<String> SOURCE_EXTENSIONS = List.of(".java", ".kt", ".scala", ".groovy");

    private BundleDependencyAnalyzer() {}

    public static BundlePlan analyze(
            Path workspace, List<Path> requestedModules, List<Path> embeddedModules,
            String targetProduct, String targetVersion, List<String> profiles, List<String> requestedFamilies,
            String maven, Path evidenceDirectory) throws IOException {
        return analyze(workspace, requestedModules, embeddedModules, targetProduct, targetVersion, profiles,
                requestedFamilies, maven, evidenceDirectory, null, Runtime.version().feature());
    }

    public static BundlePlan analyze(
            Path workspace, List<Path> requestedModules, List<Path> embeddedModules,
            String targetProduct, String targetVersion, List<String> profiles, List<String> requestedFamilies,
            String maven, Path evidenceDirectory, Path exportSnapshot, int targetJavaVersion) throws IOException {
        workspace = workspace.toRealPath();
        validateTarget(targetProduct, targetVersion);
        if (targetJavaVersion <= 0) {
            throw new IllegalArgumentException("An exact target Java version is required");
        }
        OsgiExportCatalog.Selection exportSelection = exportSnapshot == null
                ? OsgiExportCatalog.selectBundled(BundleDependencyAnalyzer.class.getClassLoader(), targetProduct,
                targetVersion, targetJavaVersion)
                : OsgiExportCatalog.selectOverride(exportSnapshot.toRealPath(), targetProduct, targetVersion,
                targetJavaVersion);
        BundlePlan.ExportEvidence exportEvidence = BundleDependencyPreflight.evidence(exportSelection, targetProduct,
                targetVersion, targetJavaVersion, exportSnapshot == null ? exportSelection.provenance()
                        : exportSnapshot.toAbsolutePath().normalize().toString());
        List<Path> modules = canonicalModules(workspace, requestedModules, embeddedModules);
        List<String> families = requestedFamilies.isEmpty()
                ? BundleDependencyModel.families().keySet().stream().sorted().toList()
                : requestedFamilies.stream().distinct().sorted().toList();

        families.forEach(BundleDependencyModel::family);
        List<String> scanFamilies = BundleDependencyModel.families().keySet().stream().sorted().toList();
        Files.createDirectories(evidenceDirectory);
        List<String> unresolved = new ArrayList<>();
        List<SourceEvidence> sources = discoverSources(sourceFiles(modules), scanFamilies, unresolved);
        List<DependencyEvidence> dependencies = new ArrayList<>();
        List<DependencyOperation> operations = new ArrayList<>();
        List<VerificationArtifact> verification = new ArrayList<>();
        List<BundlePlan.DependencyClassification> classifications = new ArrayList<>();
        Map<Path, PomModel> models = new LinkedHashMap<>();
        Map<String, DependencySpec> packaged = new LinkedHashMap<>();

        // A clean source scan needs neither Maven resolution nor application edits.
        if (!sources.isEmpty()) {
            for (int index = 0; index < modules.size(); index++) {
                Path module = modules.get(index);
                Path evidence = evidenceDirectory.resolve("module-" + index);
                Files.createDirectories(evidence);
                Files.deleteIfExists(evidence.resolve("dependency-tree.tgf"));
                Path effectivePom = evidence.resolve("effective-pom.xml");

                runMaven(maven, module, profiles, List.of("help:effective-pom", "-Doutput=" + effectivePom),
                        evidence.resolve("effective-pom.log"));
                models.put(module, new PomModel(parse(module.resolve("pom.xml")), parse(effectivePom), evidence));
            }
        }

        for (Path module : modules) {
            PomModel model = models.get(module);
            if (model == null) {
                continue;
            }

            for (String family : families) {
                List<String> references = sources.stream()
                        .filter(source -> source.family().equals(family) && belongsToModule(source, module))
                        .flatMap(source -> source.references().stream()).distinct().toList();

                if (references.isEmpty()) {
                    continue;
                }

                if (family.equals("rome")) {
                    List<SourceEvidence> jdomUsage = sources.stream().filter(source -> source.family().equals("jdom2")
                            && belongsToModule(source, module)).toList();
                    references = Stream.concat(references.stream(), jdomUsage.stream()
                                    .flatMap(source -> source.references().stream()))
                            .distinct().toList();
                }

                BundleDependencyModel.FamilyAssessment assessment = BundleDependencyModel.assess(family, references);
                unresolved.addAll(assessment.unresolved().stream().map(reason -> module + ": " + reason).toList());
                for (BundleDependencyModel.UnsupportedUsage usage : assessment.unsupported()) {
                    dependencies.add(new DependencyEvidence(module.resolve("pom.xml").toString(),
                            usage.groupId(), usage.artifactId(), null, null, usage.reason()));
                }

                if (!assessment.unresolved().isEmpty() || assessment.supportedReferences().isEmpty()) {
                    continue;
                }

                List<String> supportedReferences = assessment.supportedReferences();
                List<String> observedReferences = supportedReferences;
                List<DependencySpec> specifications = assessment.dependencies();
                PomModel packagingModel = models.get(modules.get(0));
                String importPackage = effectiveImportPackage(packagingModel);
                Map<String, PreflightResult> preflight = new LinkedHashMap<>();
                for (DependencySpec specification : specifications) {
                    BundleDependencyPreflight.Classification classification = BundleDependencyPreflight.classify(
                            family, specification, supportedReferences, exportSelection, importPackage);
                    String status = classification.status();
                    Element effectiveDependency = dependency(model.effective().getDocumentElement(), specification.coordinate());
                    if (effectiveDependency != null
                            && "compile".equals(value(effectiveDependency, "scope", "compile"))) {
                        status = BundlePlan.DependencyClassificationStatus.ALREADY_BUNDLED;
                    }
                    preflight.put(specification.coordinate(), new PreflightResult(specification, status,
                            classification.packages(), classification.reason()));
                }

                Set<String> directlyUsed = specifications.stream()
                        .filter(specification -> BundleDependencyPreflight.directlyReferenced(specification,
                                observedReferences))
                        .map(DependencySpec::coordinate)
                        .collect(java.util.stream.Collectors.toSet());
                boolean closureNeedsBundling = directlyUsed.stream()
                        .map(preflight::get)
                        .anyMatch(result -> !BundlePlan.DependencyClassificationStatus.PROVIDED_BY_TARGET
                                .equals(result.status()));
                for (DependencySpec specification : specifications) {
                    PreflightResult result = preflight.get(specification.coordinate());
                    boolean direct = directlyUsed.contains(specification.coordinate());
                    boolean selected = direct || closureNeedsBundling;
                    if (!selected) {
                        continue;
                    }

                    String status = result.status();
                    if (closureNeedsBundling && !direct
                            && BundlePlan.DependencyClassificationStatus.PROVIDED_BY_TARGET.equals(status)) {
                        status = BundlePlan.DependencyClassificationStatus.NEEDS_BUNDLING;
                        result = new PreflightResult(result.specification(), status, result.packages(),
                                "required companion retained in the closure of a bundled dependency");
                    }
                    classifications.add(new BundlePlan.DependencyClassification(family, specification.coordinate(),
                            status, result.packages(), result.reason()));
                    if (BundlePlan.DependencyClassificationStatus.UNCERTAIN.equals(status)) {
                        unresolved.add(module + ": " + specification.coordinate() + " has uncertain target export evidence: "
                                + result.reason());
                    }
                    if (!BundlePlan.DependencyClassificationStatus.PROVIDED_BY_TARGET.equals(status)) {
                        planDependency(module, model, family, result.specification(), profiles, maven, operations, dependencies,
                                unresolved);
                        packaged.put(result.specification().coordinate(), result.specification());
                        verification.add(new VerificationArtifact(family, result.specification().groupId(),
                                result.specification().artifactId(), result.specification().version(),
                                BundleDependencyModel.classPrefixes(result.specification()),
                                BundleDependencyModel.forbiddenPrefixes(result.specification())));
                    } else {
                        planDependency(module, model, family, result.specification(), profiles, maven, operations, dependencies,
                                unresolved, true);
                    }
                }
            }
        }

        // Packaging belongs to the selected plugin, not necessarily the module declaring the dependency.
        if (!models.isEmpty() && !packaged.isEmpty()) {
            Path plugin = modules.get(0);
            Set<String> bundlingFamilies = classifications.stream()
                    .filter(classification -> !BundlePlan.DependencyClassificationStatus.PROVIDED_BY_TARGET
                            .equals(classification.status()))
                    .map(BundlePlan.DependencyClassification::family)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            planPackaging(plugin, models.get(plugin), bundlingFamilies, sources, packaged, operations, unresolved);
        }

        Map<String, DependencyOperation> distinctOperations = new LinkedHashMap<>();
        for (DependencyOperation operation : operations) {
            String key = operation.pom() + ":" + operation.action() + ":" + operation.coordinate();
            distinctOperations.putIfAbsent(key, operation);
        }

        return new BundlePlan(2, workspace.toString(), modules.stream().map(Path::toString).toList(),
                embeddedModules.stream().map(path -> path.toAbsolutePath().normalize().toString()).toList(),
                targetProduct, targetVersion, targetJavaVersion, profiles, families, sources, dependencies,
                List.copyOf(distinctOperations.values()), List.copyOf(unresolved),
                verification.stream().distinct().toList(), fingerprint(workspace, modules), exportEvidence,
                classifications.stream().distinct().toList());
    }

    private static void validateTarget(String product, String version) {
        Map<String, Integer> minimum = Map.of("jira", 12, "confluence", 11, "refapp", 9,
                "bitbucket", 11, "bamboo", 13, "crowd", 8);

        if (!minimum.containsKey(product) || version == null || !version.matches("[0-9]+\\..+")) {
            throw new IllegalArgumentException("An exact supported target product and version are required");
        }

        int major = Integer.parseInt(version.substring(0, version.indexOf('.')));
        if (major < minimum.get(product)) {
            throw new IllegalArgumentException("The selected target predates this catalog's platform dependency withdrawal");
        }
    }

    private static void planDependency(
            Path module, PomModel model, String family, DependencySpec specification, List<String> profiles,
            String maven, List<DependencyOperation> operations, List<DependencyEvidence> evidence,
            List<String> unresolved) throws IOException {
        planDependency(module, model, family, specification, profiles, maven, operations, evidence, unresolved, false);
    }

    private static void planDependency(
            Path module, PomModel model, String family, DependencySpec specification, List<String> profiles,
            String maven, List<DependencyOperation> operations, List<DependencyEvidence> evidence,
            List<String> unresolved, boolean providedByTarget) throws IOException {
        Path pom = module.resolve("pom.xml");
        Element effective = dependency(model.effective().getDocumentElement(), specification.coordinate());
        Element local = dependency(model.local().getDocumentElement(), specification.coordinate());
        String scope = effective == null ? "missing" : value(effective, "scope", "compile");
        String resolvedVersion = effective == null ? null : value(effective, "version", null);
        evidence.add(new DependencyEvidence(pom.toString(), specification.groupId(), specification.artifactId(),
                resolvedVersion, scope, effective == null ? "missing" : "effective"));

        if (effective == null) {
            Map<String, String> transitive = resolvedDependencies(maven, module, profiles, model);
            if ("compile".equals(transitive.get(specification.coordinate()))) {
                evidence.add(new DependencyEvidence(pom.toString(), specification.groupId(), specification.artifactId(),
                        null, "compile", "already available transitively; packaging still checked"));
                return;
            }
        }

        if (providedByTarget && (effective == null || !"compile".equals(scope))) {
            return;
        }

        // Editing an effective profile dependency as a root dependency can leave the active declaration overriding it.
        boolean profileDeclaration = children(child(model.local().getDocumentElement(), "profiles"), "profile").stream()
                .anyMatch(profile -> dependency(profile, specification.coordinate()) != null);

        if (profileDeclaration && (effective == null || !scope.equals("compile"))) {
            unresolved.add(pom + ": " + specification.coordinate()
                    + " is declared in a profile; select an unambiguous profile-owned edit before applying");
            return;
        }

        if (scope.equals("system")) {
            unresolved.add(pom + ": system-scoped " + specification.coordinate() + " requires an explicit packaging decision");
            return;
        }

        // Preserve the app's chosen compile version while repairing missing packaging exclusions.
        if (scope.equals("compile")) {
            boolean missingExclusions = specification.exclusions().stream().anyMatch(exclusion ->
                    children(child(effective, "exclusions"), "exclusion").stream().noneMatch(element ->
                            value(element, "groupId", "").equals(exclusion.groupId())
                                    && value(element, "artifactId", "").equals(exclusion.artifactId())));

            if (missingExclusions && local != null) {
                operations.add(new DependencyOperation(pom.toString(), Action.ADD_EXCLUSIONS, family,
                        specification.groupId(), specification.artifactId(), resolvedVersion, specification.exclusions(),
                        "Keep excluded platform or unused companion libraries out of the plugin"));
            } else if (missingExclusions) {
                unresolved.add(pom + ": inherited compile dependency " + specification.coordinate()
                        + " needs an exclusion override preserving its effective dependency configuration");
            }

            return;
        }

        String version = specification.version();
        if (resolvedVersion != null && !resolvedVersion.contains("${") && !resolvedVersion.isBlank()) {
            // Existing versions are retained except families whose migration explicitly requires the catalog version.
            boolean pinned = Set.of("commons-io", "commons-codec", "commons-text", "gson").contains(family);
            if (!pinned || versionAtLeast(resolvedVersion, specification.version())) {
                version = local == null ? resolvedVersion : value(local, "version", resolvedVersion);
            }
        }

        if (version == null || version.contains("${") && local == null) {
            unresolved.add(pom + ": cannot resolve a standalone version for " + specification.coordinate());
            return;
        }

        Action action = local == null ? Action.ADD_DEPENDENCY : Action.SET_COMPILE_SCOPE;
        operations.add(new DependencyOperation(pom.toString(), action, family, specification.groupId(),
                specification.artifactId(), version, specification.exclusions(),
                "Main application usage requires an application-owned compile dependency"));
    }

    private static boolean versionAtLeast(String existing, String minimum) {
        return new org.apache.maven.artifact.versioning.ComparableVersion(existing)
                .compareTo(new org.apache.maven.artifact.versioning.ComparableVersion(minimum)) >= 0;
    }

    private static void planPackaging(
            Path plugin, PomModel model, Set<String> families, List<SourceEvidence> sources,
            Map<String, DependencySpec> dependencies, List<DependencyOperation> operations, List<String> unresolved) {
        List<Element> amps = children(child(child(model.effective().getDocumentElement(), "build"), "plugins"), "plugin").stream()
                .filter(element -> value(element, "groupId", "").equals("com.atlassian.maven.plugins"))
                .filter(element -> value(element, "artifactId", "").matches("(jira|confluence|bitbucket|bamboo|crowd|amps)-maven-plugin"))
                .toList();

        if (amps.isEmpty()) {
            return; // Ordinary Maven modules have no AMPS instructions to merge.
        }

        if (amps.size() != 1) {
            unresolved.add(plugin + ": multiple effective AMPS plugins require selecting the packaging owner");
            return;
        }

        Element effectivePlugin = amps.get(0);
        String pluginArtifact = value(effectivePlugin, "artifactId", "");
        Element localPlugin = children(child(child(model.local().getDocumentElement(), "build"), "plugins"), "plugin").stream()
                .filter(element -> value(element, "artifactId", "").equals(pluginArtifact)).findFirst().orElse(null);
        if (localPlugin == null) {
            unresolved.add(plugin + ": inherited AMPS plugin needs an explicit local packaging override");
            return;
        }
        Element instructions = child(child(effectivePlugin, "configuration"), "instructions");
        String embedded = value(instructions, "Embedded-Dependencies", null);
        String imports = value(instructions, "Import-Package", null);

        Element localInstructions = child(child(localPlugin, "configuration"), "instructions");
        boolean inheritedInstructions = (embedded != null && child(localInstructions, "Embedded-Dependencies") == null)
                || (imports != null && child(localInstructions, "Import-Package") == null);

        if (inheritedInstructions) {
            unresolved.add(plugin + ": inherited AMPS instructions need a local override preserving the complete effective configuration");
            return;
        }

        if (embedded != null) {
            List<String> entries = BundlingArtifactVerifier.splitHeader(embedded, ',');
            if (entries.stream().anyMatch(entry -> entry.contains(";") || entry.contains("!") || entry.contains("${"))) {
                unresolved.add(plugin + ": complex Embedded-Dependencies selector needs explicit interpretation");
                return;
            }

            for (DependencySpec dependency : dependencies.values()) {
                if (!entries.contains("*") && !entries.contains(dependency.artifactId())) {
                    operations.add(new DependencyOperation(plugin.resolve("pom.xml").toString(), Action.APPEND_EMBEDDED_DEPENDENCY,
                            "grouped-bundling", dependency.groupId(), dependency.artifactId(), dependency.version(), List.of(),
                            "Pinned plugin embedding configuration must include the selected library"));
                }
            }
        }

        for (String family : families) {
            List<String> references = sources.stream().filter(source -> source.family().equals(family)
                            || (family.equals("rome") && source.family().equals("jdom2")))
                    .flatMap(source -> source.references().stream()).distinct().toList();
            for (String optional : BundleDependencyModel.optionalPackages(family, references)) {
                DependencyOperation operation = new DependencyOperation(plugin.resolve("pom.xml").toString(),
                        Action.APPEND_OPTIONAL_IMPORT, family, "", optional, "", List.of(),
                        "Only unused optional library code paths may remain unresolved");
                String currentImports = imports == null ? "*" : imports;
                if (!BundlePlatformDependenciesRecipe.migrateImports(currentImports, List.of(operation)).equals(currentImports)) {
                    operations.add(operation);
                }
            }
            if (imports != null) {
                for (String bundled : BundleDependencyModel.bundledPackages(family, references)) {
                    DependencyOperation operation = new DependencyOperation(plugin.resolve("pom.xml").toString(),
                            Action.REMOVE_IMPORT_EXCLUSION, family, "", bundled, "", List.of(),
                            "Remove only an existing !package exclusion for a now-contained library");
                    if (!BundlePlatformDependenciesRecipe.migrateImports(imports, List.of(operation)).equals(imports)) {
                        operations.add(operation);
                    }
                }
            }
        }

        // Add narrowly scoped allow-bundling entries for selected withdrawn dependencies only.
        for (DependencySpec dependency : dependencies.values()) {
            boolean newlyBundled = operations.stream().anyMatch(operation -> operation.coordinate().equals(dependency.coordinate())
                    && Set.of(Action.ADD_DEPENDENCY, Action.SET_COMPILE_SCOPE, Action.APPEND_EMBEDDED_DEPENDENCY).contains(operation.action()));

            if (newlyBundled) {
                operations.add(new DependencyOperation(plugin.resolve("pom.xml").toString(), Action.ALLOW_BUNDLING,
                        "grouped-bundling", dependency.groupId(), dependency.artifactId(), dependency.version(), List.of(),
                        "Allow the selected app-owned dependency through AMPS platform banning"));
            }
        }
    }

    private static String effectiveImportPackage(PomModel model) {
        if (model == null) {
            return null;
        }
        List<Element> plugins = children(child(child(model.effective().getDocumentElement(), "build"), "plugins"), "plugin");
        for (Element plugin : plugins) {
            if (!value(plugin, "groupId", "").equals("com.atlassian.maven.plugins")
                    || !value(plugin, "artifactId", "").matches("(jira|confluence|bitbucket|bamboo|crowd|amps)-maven-plugin")) {
                continue;
            }
            Element instructions = child(child(plugin, "configuration"), "instructions");
            return value(instructions, "Import-Package", null);
        }
        return null;
    }

    private static Map<String, String> resolvedDependencies(String maven, Path module, List<String> profiles, PomModel model)
            throws IOException {
        Path tree = model.evidence().resolve("dependency-tree.tgf");
        if (!Files.exists(tree)) {
            runMaven(maven, module, profiles, List.of("dependency:tree", "-DoutputType=tgf", "-DoutputFile=" + tree),
                    model.evidence().resolve("dependency-tree.log"));
        }

        Map<String, String> dependencies = new LinkedHashMap<>();
        for (String line : Files.readAllLines(tree)) {
            if (line.equals("#")) {
                break;
            }

            String[] node = line.trim().split("\\s+", 2);
            if (node.length == 2) {
                String[] coordinate = node[1].split(":");
                if (coordinate.length >= 5) {
                    dependencies.put(coordinate[0] + ":" + coordinate[1], coordinate[coordinate.length - 1]);
                }
            }
        }

        return dependencies;
    }

    private static void runMaven(String executable, Path module, List<String> profiles, List<String> goals, Path log)
            throws IOException {
        List<String> command = new ArrayList<>(List.of(executable, "--batch-mode", "--no-transfer-progress", "-f", module.resolve("pom.xml").toString()));
        if (!profiles.isEmpty()) {
            command.add("-P" + String.join(",", profiles));
        }

        command.addAll(goals);
        Process process = new ProcessBuilder(command).directory(module.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();

        try {
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Maven discovery timed out; see " + log);
            }

            if (process.exitValue() != 0) {
                throw new IOException("Maven discovery failed; see " + log);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Maven discovery interrupted", exception);
        }
    }

    private static List<SourceEvidence> discoverSources(List<Path> files, List<String> families, List<String> unresolved)
            throws IOException {
        Map<Path, List<String>> referencesByFile = new LinkedHashMap<>(discoverJavaReferences(
                files.stream().filter(file -> file.toString().endsWith(".java")).toList(), unresolved));
        JvmSourceScanner.Scan jvm = JvmSourceScanner.scan(
                files.stream().filter(file -> !file.toString().endsWith(".java")).toList());

        referencesByFile.putAll(jvm.references());
        unresolved.addAll(jvm.errors());
        List<SourceEvidence> results = new ArrayList<>();
        List<SourceEvidence> re2jReferences = new ArrayList<>();
        for (Map.Entry<Path, List<String>> entry : referencesByFile.entrySet()) {
            List<String> references = entry.getValue();
            List<String> re2j = references.stream().filter(reference -> matches(reference, "com.google.re2j")).toList();
            if (!re2j.isEmpty()) {
                re2jReferences.add(new SourceEvidence("jsoup", entry.getKey().toString(), re2j));
            }

            for (String family : families) {
                List<String> matched = references.stream().filter(reference -> BundleDependencyModel.family(family)
                                .packagePrefixes().stream().anyMatch(prefix -> matches(reference, prefix)))
                        .sorted().toList();

                if (!matched.isEmpty()) {
                    if (family.equals("jsoup")) {
                        matched = Stream.concat(matched.stream(), re2j.stream()).distinct().sorted().toList();
                    }

                    results.add(new SourceEvidence(family, entry.getKey().toString(), matched));
                }
            }
        }
        if (results.stream().anyMatch(source -> source.family().equals("jsoup"))) {
            results.addAll(re2jReferences);
        }
        return List.copyOf(results);
    }

    private static Map<Path, List<String>> discoverJavaReferences(List<Path> files, List<String> unresolved)
            throws IOException {
        if (files.isEmpty()) {
            return Map.of();
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("A JDK is required for Java syntax discovery");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<Path, List<String>> results = new LinkedHashMap<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                    manager.getJavaFileObjectsFromPaths(files));

            for (CompilationUnitTree unit : task.parse()) {
                Set<String> references = new LinkedHashSet<>();
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                        String reference = tree.toString();
                        if (reference.matches("[A-Za-z0-9_$.*]+")) {
                            references.add(reference);
                            return null;
                        }

                        return super.visitMemberSelect(tree, unused);
                    }
                }.scan(unit, null);
                results.put(Path.of(unit.getSourceFile().toUri()), references.stream().sorted().toList());
            }
        }

        diagnostics.getDiagnostics().stream().filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .forEach(diagnostic -> unresolved.add("Java syntax could not be parsed: " + diagnostic));
        return results;
    }

    private static boolean belongsToModule(SourceEvidence source, Path module) {
        Path file = Path.of(source.sourceFile());
        return SOURCE_ROOTS.stream().anyMatch(root -> file.startsWith(module.resolve(root)));
    }

    private static boolean matches(String reference, String prefix) {
        return reference.equals(prefix) || reference.startsWith(prefix + ".");
    }

    private static List<Path> canonicalModules(Path workspace, List<Path> requested, List<Path> embedded) throws IOException {
        List<Path> modules = new ArrayList<>();
        List<Path> candidates = Stream.concat((requested.isEmpty() ? List.of(workspace) : requested).stream(), embedded.stream()).toList();
        for (Path candidate : candidates) {
            Path module = workspace.resolve(candidate).toRealPath();
            if (!module.startsWith(workspace) || !Files.isRegularFile(module.resolve("pom.xml"))
                    || !module.resolve("pom.xml").toRealPath().startsWith(workspace)) {
                throw new IOException("Module is outside workspace or has no pom.xml: " + candidate);
            }

            if (!modules.contains(module)) {
                modules.add(module);
            }
        }

        return List.copyOf(modules);
    }

    private static List<Path> sourceFiles(List<Path> modules) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path module : modules) {
            for (String sourceRoot : SOURCE_ROOTS) {
                Path root = module.resolve(sourceRoot);
                if (!Files.exists(root)) {
                    continue;
                }

                try (Stream<Path> paths = Files.walk(root)) {
                    for (Path file : paths.filter(path -> Files.isRegularFile(path)
                            && SOURCE_EXTENSIONS.stream().anyMatch(extension -> path.toString().endsWith(extension))).toList()) {
                        Path real = file.toRealPath();
                        if (!real.startsWith(module.toRealPath())) {
                            throw new IOException("Source symlink escapes approved module: " + file);
                        }

                        files.add(real);
                    }
                }
            }
        }

        return files.stream().distinct().sorted().toList();
    }

    /** Includes source additions and deletions, local parent POMs and Maven configuration. */
    public static String fingerprint(Path workspace, List<Path> modules) throws IOException {
        workspace = workspace.toRealPath();
        List<Path> canonical = new ArrayList<>();
        for (Path module : modules) {
            canonical.add(module.toRealPath());
        }
        modules = List.copyOf(canonical);
        Set<Path> inputs = new LinkedHashSet<>(sourceFiles(modules));
        for (Path module : modules) {
            for (Path directory = module; directory != null && directory.startsWith(workspace); directory = directory.getParent()) {
                for (String name : List.of("pom.xml", ".mvn/maven.config", ".mvn/jvm.config", ".mvn/extensions.xml")) {
                    Path file = directory.resolve(name);
                    if (Files.isRegularFile(file)) {
                        inputs.add(file.toRealPath());
                    }
                }
            }
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : inputs.stream().sorted().toList()) {
                digest.update(file.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Document parse(Path path) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(path.toFile());
        } catch (Exception exception) {
            throw new IOException("Cannot parse Maven model " + path, exception);
        }
    }

    private static Element dependency(Element owner, String coordinate) {
        return children(child(owner, "dependencies"), "dependency").stream()
                .filter(element -> (value(element, "groupId", "") + ":" + value(element, "artifactId", "")).equals(coordinate))
                .findFirst().orElse(null);
    }

    private static Element child(Element parent, String name) {
        return children(parent, name).stream().findFirst().orElse(null);
    }

    private static List<Element> children(Element parent, String name) {
        if (parent == null) {
            return List.of();
        }

        List<Element> matches = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName() == null ? element.getTagName() : element.getLocalName())) {
                matches.add(element);
            }
        }

        return List.copyOf(matches);
    }

    private static String value(Element owner, String name, String fallback) {
        Element element = child(owner, name);
        return element == null ? fallback : element.getTextContent().trim();
    }

    private record PomModel(Document local, Document effective, Path evidence) {}

    private record PreflightResult(DependencySpec specification, String status, List<String> packages,
                                   String reason) {
    }
}
