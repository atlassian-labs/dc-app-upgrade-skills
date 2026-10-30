package com.atlassian.dc.p9enablement.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.Duration;

/** Command-line entrypoint shared by the standalone skill and the orchestrator worker. */
public final class PlatformBundlingCli {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private PlatformBundlingCli() {
    }

    public static void main(final String[] arguments) {
        try {
            int exitCode = execute(arguments);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (Exception exception) {
            System.err.println(exception.getMessage());
            System.exit(3);
        }
    }

    static int execute(final String[] arguments) throws IOException {
        if (arguments.length == 0 || "--help".equals(arguments[0]) || "help".equals(arguments[0])
                || java.util.Arrays.asList(arguments).contains("--help")) {
            usage();
            return arguments.length == 0 ? 3 : 0;
        }
        Options options = Options.parse(arguments);
        Path output = options.output;
        Files.createDirectories(output);
        if ("analyze".equals(options.mode)) {
            BundlePlan plan = BundleDependencyAnalyzer.analyze(options.workspace, options.modules,
                    options.embeddedModules, options.targetProduct, options.targetVersion, options.profiles,
                    List.of(), options.maven, options.evidenceDirectory(output), options.exportSnapshot,
                    options.targetJavaVersion());
            Path planPath = options.plan == null ? output.resolve("bundle-plan.json") : options.plan;
            write(planPath, plan);
            return plan.unresolved().isEmpty() ? 0 : 2;
        }
        if ("approve".equals(options.mode)) {
            if (options.plan == null) {
                throw new IllegalArgumentException("approve requires --plan");
            }

            BundlePlan fullPlan = JSON.readValue(options.plan.toFile(), BundlePlan.class);
            validateApprovalPlan(options, fullPlan);
            Path approvedPlanPath = output.resolve("bundle-plan.json").toAbsolutePath().normalize();
            if (Files.exists(approvedPlanPath) && Files.isSameFile(approvedPlanPath, options.plan)) {
                throw new IllegalArgumentException("Approval output must not overwrite the full source plan");
            }
            Path exportSnapshot = options.exportSnapshot;
            if (exportSnapshot == null && fullPlan.exportEvidence() != null
                    && fullPlan.exportEvidence().source() != null
                    && Files.isRegularFile(Path.of(fullPlan.exportEvidence().source()))) {
                exportSnapshot = Path.of(fullPlan.exportEvidence().source());
            }
            int targetJavaVersion = options.targetJavaVersion == 0
                    ? fullPlan.targetJavaVersion() : options.targetJavaVersion;
            if (targetJavaVersion == 0) {
                targetJavaVersion = Runtime.version().feature();
            }
            BundlePlan approvedPlan = BundleDependencyAnalyzer.analyze(
                    options.workspace,
                    fullPlan.modules().stream().map(Path::of).toList(),
                    fullPlan.embeddedModules().stream().map(Path::of).toList(),
                    fullPlan.targetProduct(), fullPlan.targetVersion(), fullPlan.profiles(),
                    options.approveFamilies, options.maven, options.evidenceDirectory(output), exportSnapshot,
                    targetJavaVersion);
            validateApprovalEvidence(fullPlan, approvedPlan, options);
            write(output.resolve("bundle-plan.json"), approvedPlan);
            return approvedPlan.unresolved().isEmpty() ? 0 : 2;
        }
        if ("check".equals(options.mode) || "apply".equals(options.mode)) {
            if (options.plan == null) {
                throw new IllegalArgumentException(options.mode + " requires --plan");
            }
            BundlePlan plan = JSON.readValue(options.plan.toFile(), BundlePlan.class);
            PlanValidation validation = validatePlan(options, plan);

            if ("check".equals(options.mode)) {
                write(output.resolve("bundle-check.json"), validation.result());
                return validation.ready() ? 0 : 2;
            }

            if (!validation.ready()) {
                write(output.resolve("bundle-result.json"), validation.result());
                return 2;
            }

            // Every apply invocation revalidates the plan, even when an earlier check passed.
            Instant started = Instant.now();
            List<String> changedFiles = BundleRecipeRunner.apply(plan.operations()).stream()
                    .map(Path::of)
                    .map(path -> validation.workspace().relativize(path.toAbsolutePath().normalize()).toString())
                    .toList();
            Instant finished = Instant.now();
            write(output.resolve("bundle-result.json"), ApplyResult.success(changedFiles, started, finished, plan));
            return 0;
        }
        throw new IllegalArgumentException("Unsupported mode: " + options.mode);
    }

    private static int usage() {
        System.err.println("Usage: PlatformBundlingCli analyze|approve|check|apply --workspace DIR [options]");
        System.err.println("Options: --module DIR --embedded-module DIR --approve-family ID --profile ID "
                + "--target-product ID --target-version VERSION --target-java-version INTEGER "
                + "--export-snapshot FILE --maven PATH --plan FILE --output DIR");
        System.err.println("analyze scans the full catalog; --approve-family is only accepted by approve");
        return 3;
    }

    private static void validateApprovalPlan(final Options options, final BundlePlan plan) throws IOException {
        if (plan.schemaVersion() != 1 && plan.schemaVersion() != 2) {
            throw new IllegalArgumentException("Unsupported bundling plan schema: " + plan.schemaVersion());
        }

        validateEvidenceContext(plan);

        List<String> catalog = BundleDependencyModel.families().keySet().stream().sorted().toList();
        List<String> plannedFamilies = plan.selectedFamilies();
        if (plannedFamilies.size() != catalog.size()
                || !new java.util.LinkedHashSet<>(plannedFamilies).equals(new java.util.LinkedHashSet<>(catalog))) {
            throw new IllegalArgumentException("Approval requires a full-catalog plan");
        }

        Path workspace = Path.of(plan.workspace()).toRealPath();
        if (!workspace.equals(options.workspace.toRealPath())) {
            throw new IllegalArgumentException("--workspace does not match the workspace recorded in the plan");
        }

        List<Path> modules = plan.modules().stream().map(Path::of).toList();
        if (modules.isEmpty()) {
            throw new IllegalArgumentException("Approval requires a plan with at least one module");
        }
        validateOperations(plan, workspace, modules);

        String currentFingerprint = BundleDependencyAnalyzer.fingerprint(workspace, modules);
        if (!currentFingerprint.equals(plan.inputFingerprint())) {
            throw new IllegalArgumentException("The full plan is stale; rerun analyze before approval");
        }

        if (options.approveFamilies.isEmpty()) {
            throw new IllegalArgumentException("approve requires at least one --approve-family");
        }
        options.approveFamilies.forEach(BundleDependencyModel::family);
    }

    private static void validateOperations(final BundlePlan plan, final Path workspace,
                                           final List<Path> modules) throws IOException {
        for (Path module : modules) {
            if (!module.toRealPath().startsWith(workspace)) {
                throw new IllegalArgumentException("Plan module is outside workspace: " + module);
            }
        }
        java.util.Set<Path> allowedPoms = modules.stream()
                .map(module -> module.resolve("pom.xml").toAbsolutePath().normalize())
                .map(path -> {
                    try {
                        return path.toRealPath();
                    } catch (IOException exception) {
                        throw new IllegalArgumentException("Plan module has no pom.xml: " + path, exception);
                    }
                })
                .collect(java.util.stream.Collectors.toSet());
        for (BundlePlan.DependencyOperation operation : plan.operations()) {
            Path pom = Path.of(operation.pom()).toAbsolutePath().normalize().toRealPath();
            if (!pom.startsWith(workspace) || !allowedPoms.contains(pom)) {
                throw new IllegalArgumentException("Plan operation targets an unapproved POM: " + pom);
            }
            if ("grouped-bundling".equals(operation.family())) {
                boolean approved = plan.selectedFamilies().stream().anyMatch(family -> {
                    List<String> references = plan.sourceEvidence().stream()
                            .filter(source -> source.family().equals(family))
                            .flatMap(source -> source.references().stream()).distinct().toList();
                    BundleDependencyModel.Family model = BundleDependencyModel.family(family);
                    return java.util.stream.Stream.concat(model.dependencies().stream(),
                                    model.conditionalDependencies().stream())
                            .anyMatch(specification -> specification.coordinate().equals(operation.coordinate()));
                });
                if (!approved) {
                    throw new IllegalArgumentException("Plan operation is outside the selected family closure: "
                            + operation.coordinate());
                }
                continue;
            }
            if (!plan.selectedFamilies().contains(operation.family())) {
                throw new IllegalArgumentException("Plan operation uses a family that was not selected: "
                        + operation.family());
            }
            BundleDependencyModel.Family family = BundleDependencyModel.family(operation.family());
            List<String> references = plan.sourceEvidence().stream()
                    .filter(source -> source.family().equals(operation.family())
                            || (operation.family().equals("rome") && source.family().equals("jdom2")))
                    .flatMap(source -> source.references().stream()).distinct().toList();
            boolean knownCoordinate = java.util.stream.Stream.concat(family.dependencies().stream(),
                            family.conditionalDependencies().stream())
                    .anyMatch(specification -> specification.coordinate().equals(operation.coordinate()));
            boolean knownImport = operation.action() == BundlePlan.Action.APPEND_OPTIONAL_IMPORT
                    && BundleDependencyModel.optionalPackages(family, references).contains(operation.artifactId());
            boolean knownExclusion = operation.action() == BundlePlan.Action.REMOVE_IMPORT_EXCLUSION
                    && BundleDependencyModel.bundledPackages(family, references).stream()
                    .anyMatch(prefix -> packagePatternMatches(prefix, operation.artifactId()));
            if (!knownCoordinate && !knownImport && !knownExclusion) {
                throw new IllegalArgumentException("Plan operation is outside the selected family rules: "
                        + operation.coordinate());
            }
        }
    }

    private static PlanValidation validatePlan(final Options options, final BundlePlan plan) throws IOException {
        if (plan.schemaVersion() != 1 && plan.schemaVersion() != 2) {
            throw new IllegalArgumentException("Unsupported bundling plan schema: " + plan.schemaVersion());
        }

        validateEvidenceContext(plan);

        Path workspace = Path.of(plan.workspace()).toRealPath();
        if (!workspace.equals(options.workspace.toRealPath())) {
            throw new IllegalArgumentException("--workspace does not match the workspace recorded in the plan");
        }

        List<Path> modules = plan.modules().stream().map(Path::of).toList();
        validateOperations(plan, workspace, modules);

        String currentFingerprint = BundleDependencyAnalyzer.fingerprint(workspace, modules);
        if (!currentFingerprint.equals(plan.inputFingerprint())) {
            return PlanValidation.failure("stale-plan",
                    "The application changed after discovery; rerun analyze before apply", plan,
                    workspace, modules);
        }

        if (!plan.unresolved().isEmpty()) {
            return PlanValidation.failure("unresolved", String.join("; ", plan.unresolved()), plan,
                    workspace, modules);
        }

        return PlanValidation.ready(plan, workspace, modules);
    }

    private static void validateEvidenceContext(final BundlePlan plan) throws IOException {
        if (plan.schemaVersion() < 2) {
            throw new IllegalArgumentException("Legacy bundling plan schema is unsupported; rerun analyze to record exact target export evidence");
        }
        BundlePlan.ExportEvidence evidence = plan.exportEvidence();
        if (plan.targetJavaVersion() <= 0 || evidence == null) {
            throw new IllegalArgumentException("Product-aware plan is missing target export evidence context");
        }
        if (!plan.targetProduct().equalsIgnoreCase(evidence.product())
                || !plan.targetVersion().equals(evidence.targetVersion())
                || plan.targetJavaVersion() != evidence.targetJavaVersion()) {
            throw new IllegalArgumentException("Plan target and export evidence context do not match");
        }
        String sourceName = evidence.source();
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("The plan has no export evidence source; rerun analyze");
        }
        if ("none".equals(sourceName)) {
            if (plan.sourceEvidence().isEmpty()) {
                return;
            }
            if (!plan.unresolved().isEmpty()) {
                return;
            }
            throw new IllegalArgumentException("The plan has no export evidence for affected source usage; rerun analyze");
        }
        OsgiExportCatalog.Selection current;
        if (sourceName.startsWith("osgi-exports/")) {
            current = OsgiExportCatalog.selectBundled(PlatformBundlingCli.class.getClassLoader(),
                    plan.targetProduct(), plan.targetVersion(), plan.targetJavaVersion());
        } else {
            Path source = Path.of(sourceName);
            if (!source.isAbsolute() || !Files.isRegularFile(source)) {
                throw new IllegalArgumentException("The export snapshot is missing; rerun analyze");
            }
            current = OsgiExportCatalog.selectOverride(source, plan.targetProduct(), plan.targetVersion(),
                    plan.targetJavaVersion());
        }
        BundlePlan.ExportEvidence currentEvidence = BundleDependencyPreflight.evidence(current,
                plan.targetProduct(), plan.targetVersion(), plan.targetJavaVersion(), sourceName);
        if (!evidence.snapshotDigest().equals(currentEvidence.snapshotDigest())) {
            throw new IllegalArgumentException("The export snapshot changed after discovery; rerun analyze");
        }
    }

    private static void validateApprovalEvidence(final BundlePlan fullPlan, final BundlePlan approvedPlan,
                                                final Options options) {
        if (fullPlan.schemaVersion() < 2) {
            return;
        }
        BundlePlan.ExportEvidence expected = fullPlan.exportEvidence();
        BundlePlan.ExportEvidence actual = approvedPlan.exportEvidence();
        if (actual == null || !expected.snapshotDigest().equals(actual.snapshotDigest())
                || !expected.matchQuality().equals(actual.matchQuality())
                || expected.targetJavaVersion() != actual.targetJavaVersion()) {
            throw new IllegalArgumentException("Approval changed the export evidence context; rerun analyze with the exact snapshot");
        }
        if (options.exportSnapshot != null && !Path.of(expected.source()).toAbsolutePath().normalize()
                .equals(options.exportSnapshot.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("--export-snapshot does not match the snapshot recorded in the full plan");
        }
        if (options.targetJavaVersion != 0 && options.targetJavaVersion != expected.targetJavaVersion()) {
            throw new IllegalArgumentException("--target-java-version does not match the plan");
        }
    }

    private static boolean packagePatternMatches(final String resourcePrefix, final String packagePattern) {
        String dotted = resourcePrefix.replace('/', '.');
        String normalized = packagePattern.endsWith(".*")
                ? packagePattern.substring(0, packagePattern.length() - 2) + "."
                : packagePattern;
        return dotted.equals(normalized) || dotted.startsWith(normalized) || resourcePrefix.equals(packagePattern);
    }

    private static void write(final Path path, final Object value) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JSON.writeValue(path.toFile(), value);
    }

    public record ApplyResult(String status, List<String> changedFiles, String message,
                              String startedAt, String finishedAt, long durationMillis,
                              int recipeInvocations, Map<String, String> familyOutcomes) {
        static ApplyResult failure(final String status, final String message, final BundlePlan plan) {
            return new ApplyResult(status, List.of(), message, null, null, 0, 0,
                    pendingOutcomes(plan));
        }

        static ApplyResult success(final List<String> changedFiles, final Instant started,
                                   final Instant finished, final BundlePlan plan) {
            Map<String, String> outcomes = new LinkedHashMap<>();
            for (String family : plan.selectedFamilies()) {
                boolean sourceUsage = plan.sourceEvidence().stream().anyMatch(source -> source.family().equals(family));
                boolean directOperation = plan.operations().stream().anyMatch(operation -> operationBelongsToFamily(operation, family)
                        && changedFiles.contains(Path.of(plan.workspace()).relativize(Path.of(operation.pom())).toString()));
                outcomes.put(family, !sourceUsage ? "not-applicable" : directOperation ? "applied" : "already-compatible");
            }
            return new ApplyResult("applied", changedFiles, "", started.toString(), finished.toString(),
                    Duration.between(started, finished).toMillis(), plan.operations().isEmpty() ? 0 : 1, outcomes);
        }

        private static boolean operationBelongsToFamily(final BundlePlan.DependencyOperation operation,
                                                        final String family) {
            if (family.equals(operation.family())) {
                return true;
            }
            if (!"grouped-bundling".equals(operation.family())) {
                return false;
            }

            BundleDependencyModel.Family model = BundleDependencyModel.family(family);
            return java.util.stream.Stream.concat(model.dependencies().stream(),
                            model.conditionalDependencies().stream())
                    .anyMatch(specification -> specification.coordinate().equals(operation.coordinate()));
        }
    }

    private static Map<String, String> pendingOutcomes(final BundlePlan plan) {
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (String family : plan.selectedFamilies()) {
            outcomes.put(family, "pending");
        }
        return outcomes;
    }

    private record PlanValidation(boolean ready, String status, String message, BundlePlan plan,
                                  Path workspace, List<Path> modules) {
        private static PlanValidation ready(final BundlePlan plan, final Path workspace,
                                             final List<Path> modules) {
            return new PlanValidation(true, "ready", "", plan, workspace, List.copyOf(modules));
        }

        private static PlanValidation failure(final String status, final String message,
                                              final BundlePlan plan, final Path workspace,
                                              final List<Path> modules) {
            return new PlanValidation(false, status, message == null ? "" : message, plan, workspace,
                    List.copyOf(modules));
        }

        private ApplyResult result() {
            return ready
                    ? new ApplyResult(status, List.of(), message, null, null, 0, 0, pendingOutcomes(plan))
                    : ApplyResult.failure(status, message, plan);
        }
    }

    private static final class Options {
        private final String mode;
        private final Path workspace;
        private final List<Path> modules;
        private final List<Path> embeddedModules;
        private final List<String> profiles;
        private final List<String> approveFamilies;
        private final String targetProduct;
        private final String targetVersion;
        private final String maven;
        private final Path plan;
        private final Path output;
        private final Path evidenceDirectory;
        private final Path exportSnapshot;
        private final int targetJavaVersion;

        private Options(final String mode, final Path workspace, final List<Path> modules,
                        final List<Path> embeddedModules, final List<String> profiles,
                        final List<String> approveFamilies, final String targetProduct, final String targetVersion,
                        final String maven, final Path plan, final Path output, final Path evidenceDirectory,
                        final Path exportSnapshot, final int targetJavaVersion) {
            this.mode = mode;
            this.workspace = workspace;
            this.modules = modules;
            this.embeddedModules = embeddedModules;
            this.profiles = profiles;
            this.approveFamilies = approveFamilies;
            this.targetProduct = targetProduct;
            this.targetVersion = targetVersion;
            this.maven = maven;
            this.plan = plan;
            this.output = output;
            this.evidenceDirectory = evidenceDirectory;
            this.exportSnapshot = exportSnapshot;
            this.targetJavaVersion = targetJavaVersion;
        }

        private static Options parse(final String[] arguments) {
            String mode = arguments[0];
            Path workspace = null;
            List<Path> modules = new ArrayList<>();
            List<Path> embeddedModules = new ArrayList<>();
            List<String> profiles = new ArrayList<>();
            List<String> approveFamilies = new ArrayList<>();
            String targetProduct = "";
            String targetVersion = "";
            String maven = "mvn";
            Path plan = null;
            Path output = null;
            Path evidenceDirectory = null;
            Path exportSnapshot = null;
            int targetJavaVersion = 0;
            for (int index = 1; index < arguments.length; index++) {
                String option = arguments[index];
                String value = requiresValue(arguments, ++index, option);
                switch (option) {
                    case "--workspace" -> workspace = Path.of(value).toAbsolutePath().normalize();
                    case "--module" -> modules.add(Path.of(value).toAbsolutePath().normalize());
                    case "--embedded-module" -> embeddedModules.add(Path.of(value).toAbsolutePath().normalize());
                    case "--profile" -> profiles.addAll(List.of(value.split(",")));
                    case "--approve-family" -> approveFamilies.addAll(List.of(value.split(",")));
                    case "--target-product" -> targetProduct = value;
                    case "--target-version" -> targetVersion = value;
                    case "--maven" -> maven = value;
                    case "--plan" -> plan = Path.of(value).toAbsolutePath().normalize();
                    case "--output" -> output = Path.of(value).toAbsolutePath().normalize();
                    case "--evidence-dir" -> evidenceDirectory = Path.of(value).toAbsolutePath().normalize();
                    case "--export-snapshot" -> exportSnapshot = Path.of(value).toAbsolutePath().normalize();
                    case "--target-java-version" -> targetJavaVersion = Integer.parseInt(value);
                    case "--artifact", "--baseline" -> throw new IllegalArgumentException(
                            option + " is only supported by the artifact verifier");
                    default -> throw new IllegalArgumentException("Unknown option: " + option);
                }
            }
            if (workspace == null) {
                throw new IllegalArgumentException("--workspace is required");
            }
            if (("approve".equals(mode) || "check".equals(mode) || "apply".equals(mode))
                    && (!modules.isEmpty() || !embeddedModules.isEmpty()
                    || !profiles.isEmpty() || !targetProduct.isEmpty()
                    || !targetVersion.isEmpty() || evidenceDirectory != null
                    || (("check".equals(mode) || "apply".equals(mode))
                    && (exportSnapshot != null || targetJavaVersion != 0)))) {
                throw new IllegalArgumentException(mode
                    + " uses the plan; discovery options are only accepted by analyze or approve");
            }
            if (!"approve".equals(mode) && !approveFamilies.isEmpty()) {
                throw new IllegalArgumentException("--approve-family is only supported by approve");
            }
            if (output == null) {
                output = workspace.resolve(".migration-state/bundling");
            }
            return new Options(mode, workspace, List.copyOf(modules), List.copyOf(embeddedModules),
                    List.copyOf(profiles), List.copyOf(approveFamilies), targetProduct, targetVersion, maven, plan,
                    output.toAbsolutePath().normalize(), evidenceDirectory, exportSnapshot, targetJavaVersion);
        }

        private int targetJavaVersion() {
            return targetJavaVersion == 0 ? Runtime.version().feature() : targetJavaVersion;
        }

        private Path evidenceDirectory(final Path output) {
            return evidenceDirectory == null ? output.resolve("evidence") : evidenceDirectory;
        }

        private static String requiresValue(final String[] arguments, final int index, final String option) {
            if (index >= arguments.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return arguments[index];
        }
    }
}
