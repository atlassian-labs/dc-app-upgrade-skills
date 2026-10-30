package com.atlassian.dc.p9enablement.migrate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Stable JSON contract exchanged by grouped bundling discovery and application. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BundlePlan(
        int schemaVersion,
        String workspace,
        List<String> modules,
        List<String> embeddedModules,
        String targetProduct,
        String targetVersion,
        int targetJavaVersion,
        List<String> profiles,
        List<String> selectedFamilies,
        List<SourceEvidence> sourceEvidence,
        List<DependencyEvidence> dependencyEvidence,
        List<DependencyOperation> operations,
        List<String> unresolved,
        List<VerificationArtifact> verificationArtifacts,
        String inputFingerprint,
        ExportEvidence exportEvidence,
        List<DependencyClassification> dependencyClassifications) {

    /** Compatibility constructor for plans produced before target export evidence was recorded. */
    public BundlePlan(int schemaVersion, String workspace, List<String> modules, List<String> embeddedModules,
                      String targetProduct, String targetVersion, List<String> profiles,
                      List<String> selectedFamilies, List<SourceEvidence> sourceEvidence,
                      List<DependencyEvidence> dependencyEvidence, List<DependencyOperation> operations,
                      List<String> unresolved, List<VerificationArtifact> verificationArtifacts,
                      String inputFingerprint) {
        this(schemaVersion, workspace, modules, embeddedModules, targetProduct, targetVersion,
                0, profiles, selectedFamilies, sourceEvidence, dependencyEvidence, operations,
                unresolved, verificationArtifacts, inputFingerprint, null, List.of());
    }

    public BundlePlan {
        modules = List.copyOf(modules == null ? List.of() : modules);
        embeddedModules = List.copyOf(embeddedModules == null ? List.of() : embeddedModules);
        profiles = List.copyOf(profiles == null ? List.of() : profiles);
        selectedFamilies = List.copyOf(selectedFamilies == null ? List.of() : selectedFamilies);
        sourceEvidence = List.copyOf(sourceEvidence == null ? List.of() : sourceEvidence);
        dependencyEvidence = List.copyOf(dependencyEvidence == null ? List.of() : dependencyEvidence);
        operations = List.copyOf(operations == null ? List.of() : operations);
        unresolved = List.copyOf(unresolved == null ? List.of() : unresolved);
        verificationArtifacts = List.copyOf(verificationArtifacts == null ? List.of() : verificationArtifacts);
        dependencyClassifications = List.copyOf(dependencyClassifications == null ? List.of() : dependencyClassifications);
    }

    public record SourceEvidence(String family, String sourceFile, List<String> references) {
        public SourceEvidence {
            references = List.copyOf(references == null ? List.of() : references);
        }
    }

    public record DependencyEvidence(String pom, String groupId, String artifactId, String version,
                                     String scope, String status) {
    }

    /** The export snapshot and target runtime used to make product-aware decisions. */
    public record ExportEvidence(String source, String product, String targetVersion, int targetJavaVersion,
                                 String matchQuality, String snapshotProduct, String snapshotVersion,
                                 String snapshotSourceVersion, int snapshotJavaMajor, String snapshotDigest,
                                 String generatedAt, List<String> evidenceGaps) {
        public ExportEvidence {
            evidenceGaps = List.copyOf(evidenceGaps == null ? List.of() : evidenceGaps);
        }
    }

    /** Classification of one dependency root after considering all relevant source packages. */
    public record DependencyClassification(String family, String coordinate, String status,
                                           List<String> packages, String reason) {
        public DependencyClassification {
            packages = List.copyOf(packages == null ? List.of() : packages);
        }
    }

    public static final class DependencyClassificationStatus {
        public static final String PROVIDED_BY_TARGET = "provided-by-target";
        public static final String NEEDS_BUNDLING = "needs-bundling";
        public static final String ALREADY_BUNDLED = "already-bundled";
        public static final String UNCERTAIN = "uncertain";

        private DependencyClassificationStatus() {
        }
    }

    public record DependencyOperation(String pom, Action action, String family, String groupId,
                                      String artifactId, String version, List<BundleDependencyModel.Exclusion> exclusions,
                                      String reason) {
        public DependencyOperation {
            exclusions = List.copyOf(exclusions == null ? List.of() : exclusions);
        }

        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    public record VerificationArtifact(String family, String groupId, String artifactId,
                                       String version, List<String> classPrefixes,
                                       List<String> forbiddenPrefixes) {
        public VerificationArtifact {
            classPrefixes = List.copyOf(classPrefixes == null ? List.of() : classPrefixes);
            forbiddenPrefixes = List.copyOf(forbiddenPrefixes == null ? List.of() : forbiddenPrefixes);
        }

        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    public enum Action {
        ADD_DEPENDENCY,
        SET_COMPILE_SCOPE,
        ADD_EXCLUSIONS,
        APPEND_EMBEDDED_DEPENDENCY,
        APPEND_OPTIONAL_IMPORT,
        REMOVE_IMPORT_EXCLUSION,
        ALLOW_BUNDLING
    }

    /** Packages that preflight proved to be supplied by the exact target snapshot. */
    public List<String> targetProvidedPackages() {
        return dependencyClassifications.stream()
                .filter(classification -> DependencyClassificationStatus.PROVIDED_BY_TARGET
                        .equals(classification.status()))
                .flatMap(classification -> classification.packages().stream())
                .distinct().sorted().toList();
    }
}
