package com.atlassian.dc.p9enablement.migrate;

import com.atlassian.dc.p9enablement.migrate.BundleDependencyModel.DependencySpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BundleDependencyPreflightTest {
    @Test
    void fingerprintsEquivalentSnapshotsIndependentlyOfMapInsertionOrder() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("z", "last");
        first.put("a", "first");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("a", "first");
        second.put("z", "last");
        OsgiExportCatalog.Snapshot left = new OsgiExportCatalog.Snapshot(2, "jira", "12.0.0", "12.0.0",
                25, "2026-01-01T00:00:00Z", "fixture", first, List.of(), List.of());
        OsgiExportCatalog.Snapshot right = new OsgiExportCatalog.Snapshot(2, "jira", "12.0.0", "12.0.0",
                25, "2026-01-01T00:00:00Z", "fixture", second, List.of(), List.of());

        assertEquals(BundleDependencyPreflight.evidence(exact(left), "jira", "12.0.0", 25, "fixture").snapshotDigest(),
                BundleDependencyPreflight.evidence(exact(right), "jira", "12.0.0", 25, "fixture").snapshotDigest());
    }

    @Test
    void blocksWhenOneReferencedPackageIsOutsideScopedEvidence() {
        DependencySpec gson = BundleDependencyModel.family("gson").dependencies().getFirst();
        OsgiExportCatalog.Snapshot snapshot = snapshot(List.of("com.google.gson"),
                List.of(export("com.google.gson", "1.0.0")));
        BundleDependencyPreflight.Classification result = BundleDependencyPreflight.classify(
                "gson", gson, List.of("com.google.gson.Gson", "com.google.gson.internal.LinkedTreeMap"),
                exact(snapshot), null);

        assertEquals(BundlePlan.DependencyClassificationStatus.UNCERTAIN, result.status());
        assertEquals(List.of("com.google.gson", "com.google.gson.internal"), result.packages());
    }

    @Test
    void treatsMissingExportVersionAsUncertainOnlyForConstrainedImports() {
        DependencySpec collections = BundleDependencyModel.family("commons-collections3").dependencies().getFirst();
        OsgiExportCatalog.Snapshot snapshot = snapshot(List.of("org.apache.commons.collections"),
                List.of(export("org.apache.commons.collections", "")));
        OsgiExportCatalog.Selection selection = exact(snapshot);

        assertEquals(BundlePlan.DependencyClassificationStatus.PROVIDED_BY_TARGET,
                BundleDependencyPreflight.classify("commons-collections3", collections,
                        List.of("org.apache.commons.collections.CollectionUtils"), selection,
                        "org.apache.commons.collections;resolution:=optional").status());
        assertEquals(BundlePlan.DependencyClassificationStatus.UNCERTAIN,
                BundleDependencyPreflight.classify("commons-collections3", collections,
                        List.of("org.apache.commons.collections.CollectionUtils"), selection,
                        "org.apache.commons.collections;version=\"[3,4)\"").status());
        assertEquals(BundlePlan.DependencyClassificationStatus.UNCERTAIN,
                BundleDependencyPreflight.classify("commons-collections3", collections,
                        List.of("org.apache.commons.collections.CollectionUtils"), selection,
                        "org.apache.commons.*;version=\"[3,4)\"").status());
        assertEquals(BundlePlan.DependencyClassificationStatus.PROVIDED_BY_TARGET,
                BundleDependencyPreflight.classify("commons-collections3", collections,
                        List.of("org.apache.commons.collections.CollectionUtils"), selection,
                        "org.apache.commons;version=\"[3,4)\"").status());
        for (String selector : List.of("org.apache.commons.collections.*", "org.apache.commons.collection*")) {
            assertEquals(BundlePlan.DependencyClassificationStatus.UNCERTAIN,
                    BundleDependencyPreflight.classify("commons-collections3", collections,
                            List.of("org.apache.commons.collections.CollectionUtils"), selection,
                            selector + ";version=\"[3,4)\"").status());
        }
    }

    @Test
    void snapshotDigestIsStableAcrossSeparateJvmLaunches() throws Exception {
        String expected = publishedSnapshotDigest();
        for (int attempt = 0; attempt < 6; attempt++) {
            Process process = new ProcessBuilder(
                    java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), getClass().getName())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            assertEquals(0, process.waitFor(), output);
            assertEquals(expected, output);
        }
    }

    public static void main(String[] arguments) {
        System.out.println(publishedSnapshotDigest());
    }

    private static String publishedSnapshotDigest() {
        OsgiExportCatalog.Selection selection = OsgiExportCatalog.selectBundled(
                BundleDependencyPreflightTest.class.getClassLoader(), "jira", "12.0.0-m0204", 25);
        return BundleDependencyPreflight.evidence(selection, "jira", "12.0.0-m0204", 25,
                selection.provenance()).snapshotDigest();
    }

    private static OsgiExportCatalog.Selection exact(OsgiExportCatalog.Snapshot snapshot) {
        return new OsgiExportCatalog.Selection(snapshot, OsgiExportCatalog.MatchQuality.EXACT,
                "fixture", List.of());
    }

    private static OsgiExportCatalog.Snapshot snapshot(List<String> covered, List<OsgiExportCatalog.Export> exports) {
        return new OsgiExportCatalog.Snapshot(2, "jira", "12.0.0", "12.0.0", 25,
                "2026-01-01T00:00:00Z", "fixture", Map.of(), exports, covered);
    }

    private static OsgiExportCatalog.Export export(String packageName, String version) {
        return new OsgiExportCatalog.Export(packageName, version, "fixture", "1.0.0", Map.of(), Map.of());
    }
}
