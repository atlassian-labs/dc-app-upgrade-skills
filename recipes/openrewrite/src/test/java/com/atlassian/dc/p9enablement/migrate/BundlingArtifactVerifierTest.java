package com.atlassian.dc.p9enablement.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundlingArtifactVerifierTest {
    @TempDir
    Path directory;

    @Test
    void countsNestedClassesOnlyWhenTheyAreOnTheBundleClassPath() throws IOException {
        byte[] nested = jar(Map.of(), Map.of("com/google/gson/Gson.class", new byte[0]));
        Path artifact = directory.resolve("plugin.jar");
        Files.write(artifact, jar(Map.of(), Map.of("lib/gson.jar", nested)));

        List<BundlingArtifactVerifier.ArtifactRequirement> required = List.of(
                new BundlingArtifactVerifier.ArtifactRequirement("com.google.code.gson:gson", List.of("com/google/gson/")));

        assertFalse(BundlingArtifactVerifier.verify(artifact, required, Set.of(), null, Set.of()).blockers().isEmpty());
        Files.write(artifact, jar(Map.of("Bundle-ClassPath", ".,lib/gson.jar"), Map.of("lib/gson.jar", nested)));
        assertTrue(BundlingArtifactVerifier.verify(artifact, required, Set.of(), null, Set.of()).blockers().isEmpty());
    }

    @Test
    void findsExcludedXmlApisInsideDeclaredNestedJars() throws IOException {
        byte[] nested = jar(Map.of(), Map.of("javax/xml/parsers/DocumentBuilder.class", new byte[0]));
        Path artifact = directory.resolve("plugin.jar");
        Files.write(artifact, jar(Map.of("Bundle-ClassPath", "lib/xml-apis.jar"), Map.of("lib/xml-apis.jar", nested)));

        BundlingArtifactVerifier.Verification result = BundlingArtifactVerifier.verify(
                artifact, List.of(), Set.of("javax/xml/", "org/xml/sax/"), null, Set.of());

        assertEquals(1, result.blockers().size());
    }

    @Test
    void comparesMandatoryImportsWithoutSplittingQuotedVersionRanges() throws IOException {
        Path before = directory.resolve("before.jar");
        Path after = directory.resolve("after.jar");
        Files.write(before, jar(Map.of("Import-Package", "existing;version=\"[1,2)\""), Map.of()));
        Files.write(after, jar(Map.of(
                "Import-Package", "existing;version=\"[1,2)\",required;version=\"[2,3)\",optional;resolution:=optional,provided"), Map.of()));

        BundlingArtifactVerifier.Verification result = BundlingArtifactVerifier.verify(
                after, List.of(), Set.of(), before, Set.of("provided"));

        assertEquals(List.of("required"), result.requirementsToReview());
        assertTrue(result.baselineAvailable());
    }

    @Test
    void missingBaselineRequiresReviewOfAllUnconfirmedMandatoryImports() throws IOException {
        Path artifact = directory.resolve("plugin.jar");
        Files.write(artifact, jar(Map.of("Import-Package", "unknown,optional;resolution:=\"optional\""), Map.of()));

        BundlingArtifactVerifier.Verification result = BundlingArtifactVerifier.verify(
                artifact, List.of(), Set.of(), null, Set.of());

        assertEquals(List.of("unknown"), result.requirementsToReview());
        assertFalse(result.baselineAvailable());
    }

    @Test
    void rejectsBundledPackagesStillImportedOrExported() throws IOException {
        Path artifact = directory.resolve("plugin.jar");
        Files.write(artifact, jar(Map.of(
                "Import-Package", "com.google.gson;resolution:=optional",
                "Export-Package", "com.google.gson.stream"),
                Map.of("com/google/gson/Gson.class", new byte[0])));

        BundlingArtifactVerifier.Verification result = BundlingArtifactVerifier.verify(artifact,
                List.of(new BundlingArtifactVerifier.ArtifactRequirement(
                        "com.google.code.gson:gson", List.of("com/google/gson/"))),
                Set.of(), null, Set.of());

        assertEquals(2, result.blockers().size());
        assertTrue(result.requirementsToReview().isEmpty());
    }

    @Test
    void exportSnapshotChecksVersionEvenWhenBaselineAndProvidedPackageAllowIt() throws IOException {
        Path baseline = directory.resolve("before.jar");
        Path artifact = directory.resolve("plugin.jar");
        String imports = "com.example.api;version=\"[1.0,2.0)\";bundle-symbolic-name=\"platform\"";
        Files.write(baseline, jar(Map.of("Import-Package", "com.example.api"), Map.of()));
        Files.write(artifact, jar(Map.of("Import-Package", imports), Map.of()));

        OsgiExportCatalog.Snapshot snapshot = snapshot(
                new OsgiExportCatalog.Export("com.example.api", "2.1.0", "platform", "2.1.0",
                        Map.of(), Map.of()));
        BundlingArtifactVerifier.Verification result = BundlingArtifactVerifier.verify(
                artifact, List.of(), Set.of(), baseline, Set.of("com.example.api"),
                exact(snapshot), 25);

        assertEquals(List.of("com.example.api"), result.incompatibleImports());
        assertFalse(result.blockers().isEmpty());
        assertTrue(result.requirementsToReview().isEmpty());
    }

    @Test
    void missingProviderFieldsRequireEvidenceOnlyForConstrainedImports() throws IOException {
        Path artifact = directory.resolve("plugin.jar");
        OsgiExportCatalog.Snapshot snapshot = snapshot(
                new OsgiExportCatalog.Export("com.example.api", "1.2.0", "", "", Map.of(), Map.of()));

        for (String constraint : List.of("", ";bundle-symbolic-name=\"platform\"",
                ";bundle-version=\"[1.0,2.0)\"")) {
            Files.write(artifact, jar(Map.of("Import-Package", "com.example.api" + constraint), Map.of()));
            BundlingArtifactVerifier.Verification result = BundlingArtifactVerifier.verify(
                    artifact, List.of(), Set.of(), null, Set.of(), exact(snapshot), 25);

            assertEquals(constraint.isEmpty(), result.evidenceGaps().isEmpty());
            assertTrue(result.blockers().isEmpty());
            assertTrue(result.incompatibleImports().isEmpty());
        }
    }

    @Test
    void checksBundleIdentityAndMandatoryExporterAttributes() throws IOException {
        Path artifact = directory.resolve("plugin.jar");
        Files.write(artifact, jar(Map.of(
                "Import-Package", "com.example.api;bundle-symbolic-name=\"wrong\";tier=\"public\""), Map.of()));

        OsgiExportCatalog.Snapshot snapshot = snapshot(
                new OsgiExportCatalog.Export("com.example.api", "1.2.0", "platform", "3.0.0",
                        Map.of("tier", "public"), Map.of("mandatory", "tier")));
        BundlingArtifactVerifier.Verification result = BundlingArtifactVerifier.verify(
                artifact, List.of(), Set.of(), null, Set.of(), exact(snapshot), 25);

        assertEquals(List.of("com.example.api"), result.incompatibleImports());
    }

    @Test
    void reportsRestrictedAndUsesEvidenceSeparatelyFromPackagingBlockers() throws IOException {
        Path artifact = directory.resolve("plugin.jar");
        Files.write(artifact, jar(Map.of("Import-Package", "com.example.api"), Map.of()));

        OsgiExportCatalog.Snapshot snapshot = snapshot(
                new OsgiExportCatalog.Export("com.example.api", "1.0.0", "platform", "1.0.0",
                        Map.of("x-internal", "true"), Map.of("uses", "com.example.other")));
        BundlingArtifactVerifier.Verification result = BundlingArtifactVerifier.verify(
                artifact, List.of(), Set.of(), null, Set.of(), exact(snapshot), 25);

        assertEquals(List.of("com.example.api"), result.restrictedImports());
        assertEquals(1, result.blockers().size());
        assertTrue(result.uncertainties().stream().anyMatch(gap -> gap.contains("uses")));
    }

    @Test
    void optionalImportsDoNotBecomeMissingSnapshotBlockers() throws IOException {
        Path artifact = directory.resolve("plugin.jar");
        Files.write(artifact, jar(Map.of("Import-Package", "missing;resolution:=optional"), Map.of()));

        BundlingArtifactVerifier.Verification result = BundlingArtifactVerifier.verify(
                artifact, List.of(), Set.of(), null, Set.of(), exact(snapshot()), 25);

        assertTrue(result.missingImports().isEmpty());
        assertTrue(result.blockers().isEmpty());
    }

    @Test
    void bareImportVersionIsAMinimumAndClosedRangeCanRequireExactVersion() throws IOException {
        Path artifact = directory.resolve("plugin.jar");
        OsgiExportCatalog.Snapshot snapshot = snapshot(
                new OsgiExportCatalog.Export("com.example.api", "1.5.0", "platform", "3.0.0",
                        Map.of(), Map.of()));

        for (String range : List.of("1.2", "[1.5,1.5]")) {
            Files.write(artifact, jar(Map.of("Import-Package",
                    "com.example.api;version=\"" + range + "\""), Map.of()));

            assertTrue(BundlingArtifactVerifier.verify(
                    artifact, List.of(), Set.of(), null, Set.of(), exact(snapshot), 25)
                    .incompatibleImports().isEmpty());
        }

        Files.write(artifact, jar(Map.of("Import-Package", "com.example.api;version=\"1.6\""), Map.of()));
        assertEquals(List.of("com.example.api"), BundlingArtifactVerifier.verify(
                artifact, List.of(), Set.of(), null, Set.of(), exact(snapshot), 25).incompatibleImports());
    }

    @Test
    void rejectsAtlassianInternalExportsInBothAttributeAndDirectiveForm() throws IOException {
        Path artifact = directory.resolve("plugin.jar");
        Files.write(artifact, jar(Map.of("Import-Package", "com.example.api"), Map.of()));

        for (boolean directive : List.of(false, true)) {
            Map<String, String> restriction = Map.of("atlassian-access", "INTERNAL");
            OsgiExportCatalog.Snapshot snapshot = snapshot(new OsgiExportCatalog.Export(
                    "com.example.api", "1.0.0", "platform", "1.0.0",
                    directive ? Map.of() : restriction, directive ? restriction : Map.of()));

            assertEquals(List.of("com.example.api"), BundlingArtifactVerifier.verify(
                    artifact, List.of(), Set.of(), null, Set.of(), exact(snapshot), 25).restrictedImports());
        }
    }

    private static OsgiExportCatalog.Selection exact(OsgiExportCatalog.Snapshot snapshot) {
        return new OsgiExportCatalog.Selection(snapshot, OsgiExportCatalog.MatchQuality.EXACT,
                "test", List.of());
    }

    private static OsgiExportCatalog.Snapshot snapshot(OsgiExportCatalog.Export... exports) {
        return new OsgiExportCatalog.Snapshot(1, "jira", "11.0.0", "11.0.0-beta3", 25,
                "2026-01-01T00:00:00Z", "product-distribution", Map.of("test", "fixture"),
                List.of(exports));
    }

    private static byte[] jar(Map<String, String> headers, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        headers.forEach((name, value) -> manifest.getMainAttributes().putValue(name, value));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (JarOutputStream archive = new JarOutputStream(bytes, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                archive.putNextEntry(new JarEntry(entry.getKey()));
                archive.write(entry.getValue());
                archive.closeEntry();
            }
        }

        return bytes.toByteArray();
    }
}
