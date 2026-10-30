package com.atlassian.dc.p9enablement.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsgiExportCatalogTest {
    @Test
    void selectsCurrentProductEvidenceWithoutRelabelingEarlierTargets() throws IOException {
        ClassLoader loader = OsgiExportCatalogTest.class.getClassLoader();
        OsgiExportCatalog.Selection confluence = OsgiExportCatalog.selectBundled(loader, "confluence", "11.0.0", 25);
        OsgiExportCatalog.Selection refapp = OsgiExportCatalog.selectBundled(loader, "refapp", "9.0.6", 25);
        OsgiExportCatalog.Selection jira = OsgiExportCatalog.selectBundled(loader, "jira", "12.0.0-m0204", 25);

        assertEquals(OsgiExportCatalog.MatchQuality.EXACT, confluence.matchQuality());
        assertEquals(OsgiExportCatalog.MatchQuality.EXACT, refapp.matchQuality());
        assertEquals(OsgiExportCatalog.MatchQuality.EXACT, jira.matchQuality());
        assertEquals(OsgiExportCatalog.MatchQuality.PROVISIONAL,
                OsgiExportCatalog.selectBundled(loader, "jira", "12.0.0-QR-JIRA-QR-2052", 25).matchQuality());
        assertEquals(OsgiExportCatalog.MatchQuality.PROVISIONAL,
                OsgiExportCatalog.selectBundled(loader, "confluence", "11.0.0-beta3", 25).matchQuality());
        assertEquals(OsgiExportCatalog.MatchQuality.PROVISIONAL,
                OsgiExportCatalog.selectBundled(loader, "refapp", "9.0.6", 21).matchQuality());
        assertTrue(refapp.snapshot().covers("com.google.gson"));
        assertTrue(refapp.snapshot().exports().stream().noneMatch(export ->
                export.packageName().equals("com.google.gson")));
        assertTrue(refapp.snapshot().exports().stream().anyMatch(export ->
                export.packageName().equals("org.apache.commons.logging") && export.version().equals("1.2")));
    }

    @Test
    void choosesExactSourceQualifierAndJavaEvidence() throws IOException {
        OsgiExportCatalog.Snapshot exact = snapshot("11.0.0", "11.0.0-beta3", 25);
        ClassLoader loader = loader(Map.of(
                "osgi-exports/index.json", "[\"jira/11.0.0.json\"]",
                "osgi-exports/jira/11.0.0.json", json(exact)));

        OsgiExportCatalog.Selection selection = OsgiExportCatalog.selectBundled(
                loader, "jira", "11.0.0-beta3", 25);

        assertEquals(OsgiExportCatalog.MatchQuality.EXACT, selection.matchQuality());
        assertEquals("11.0.0-beta3", selection.snapshot().sourceVersion());
        assertTrue(selection.evidenceGaps().isEmpty());
        assertEquals(OsgiExportCatalog.MatchQuality.PROVISIONAL,
                OsgiExportCatalog.selectBundled(loader, "jira", "11.0.0", 25).matchQuality());
    }

    @Test
    void nearestEvidenceIsProvisionalAcrossMajorVersions() throws IOException {
        OsgiExportCatalog.Snapshot nearest = snapshot("11.1.0", "11.1.0", 25);
        OsgiExportCatalog.Snapshot differentMajor = snapshot("12.0.0", "12.0.0", 25);
        ClassLoader loader = loader(Map.of(
                "osgi-exports/index.json", "[\"jira/11.1.0.json\",\"jira/12.0.0.json\"]",
                "osgi-exports/jira/11.1.0.json", json(nearest),
                "osgi-exports/jira/12.0.0.json", json(differentMajor)));

        OsgiExportCatalog.Selection selection = OsgiExportCatalog.selectBundled(
                loader, "jira", "11.0.0-beta3", 25);

        assertEquals(OsgiExportCatalog.MatchQuality.PROVISIONAL, selection.matchQuality());
        assertEquals("11.1.0", selection.snapshot().sourceVersion());
        assertTrue(selection.evidenceGaps().stream().anyMatch(gap -> gap.contains("sourceVersion")));
        OsgiExportCatalog.Selection nextMajor = OsgiExportCatalog.selectBundled(loader, "jira", "13.0.0", 25);
        assertEquals("12.0.0", nextMajor.snapshot().normalizedVersion());
        assertEquals(OsgiExportCatalog.MatchQuality.PROVISIONAL, nextMajor.matchQuality());
        assertEquals(OsgiExportCatalog.MatchQuality.UNAVAILABLE,
                OsgiExportCatalog.selectBundled(loader, "confluence", "11.0.0", 25).matchQuality());
    }

    @Test
    void JavaMismatchRemainsProvisionalEvenForNormalizedVersionMatch() throws IOException {
        OsgiExportCatalog.Snapshot snapshot = snapshot("11.0.0", "11.0.0-beta3", 21);
        ClassLoader loader = loader(Map.of(
                "osgi-exports/index.json", "[\"jira/11.0.0.json\"]",
                "osgi-exports/jira/11.0.0.json", json(snapshot)));

        OsgiExportCatalog.Selection selection = OsgiExportCatalog.selectBundled(
                loader, "jira", "11.0.0-beta3", 25);

        assertEquals(OsgiExportCatalog.MatchQuality.PROVISIONAL, selection.matchQuality());
        assertTrue(selection.evidenceGaps().stream().anyMatch(gap -> gap.contains("Java")));
    }

    @Test
    void stripsOnlySuffixWhenNormalizingVersions() {
        assertEquals("11.0.0", OsgiExportCatalog.normalizeVersion("11.0.0-beta3"));
        assertEquals("11.0.0", OsgiExportCatalog.normalizeVersion("11.0.0"));
    }

    private static OsgiExportCatalog.Snapshot snapshot(String normalizedVersion, String sourceVersion,
                                                       int javaMajor) {
        return new OsgiExportCatalog.Snapshot(1, "jira", normalizedVersion, sourceVersion, javaMajor,
                "2026-01-01T00:00:00Z", "runtime", Map.of("fixture", "test"), List.of());
    }

    private static String json(OsgiExportCatalog.Snapshot snapshot) throws IOException {
        return new ObjectMapper().writeValueAsString(snapshot);
    }

    private static ClassLoader loader(Map<String, String> resources) {
        Map<String, byte[]> bytes = new HashMap<>();
        resources.forEach((name, value) -> bytes.put(name, value.getBytes(StandardCharsets.UTF_8)));
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                byte[] value = bytes.get(name);
                return value == null ? null : new ByteArrayInputStream(value);
            }
        };
    }
}
