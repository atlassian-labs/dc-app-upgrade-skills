package com.atlassian.dc.p9enablement.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

class BundlingVerificationScopeTest {
    @TempDir
    Path directory;

    @Test
    void checksLibraryRequirementsButLeavesUnrelatedApplicationImportsOutsideScope() throws Exception {
        BundlingArtifactVerifier.Verification result = verify(List.of("api.external", "java.lang"),
                List.of(export("api.external", "1.5"), export("java.lang", "0")));
        assertTrue(result.blockers().isEmpty(), result.blockers().toString());
        assertTrue(result.evidenceGaps().isEmpty(), result.evidenceGaps().toString());
        assertEquals(List.of("api.external", "java.lang"), result.mandatoryImports());
    }

    @Test
    void uncoveredRequirementsAreEvidenceGapsAndCoveredAbsentPackagesAreUnavailable() throws Exception {
        BundlingArtifactVerifier.Verification uncovered = verify(List.of("java.lang"), List.of(export("java.lang", "0")));
        assertTrue(uncovered.missingImports().isEmpty());
        assertTrue(uncovered.evidenceGaps().stream().anyMatch(gap -> gap.contains("outside") && gap.contains("api.external")));

        BundlingArtifactVerifier.Verification absent = verify(List.of("api.external", "java.lang"),
                List.of(export("java.lang", "0")));
        assertEquals(List.of("api.external"), absent.missingImports());
        assertTrue(absent.evidenceGaps().isEmpty());
    }

    @Test
    void scopedChecksStillRejectAnIncompatiblePackageVersion() throws Exception {
        BundlingArtifactVerifier.Verification result = verify(List.of("api.external", "java.lang"),
                List.of(export("api.external", "2.0"), export("java.lang", "0")));
        assertEquals(List.of("api.external"), result.incompatibleImports());
    }

    @Test
    void keepsDeclaredReflectiveGsonRequirementsInScope() throws Exception {
        OsgiExportCatalog.Snapshot snapshot = new OsgiExportCatalog.Snapshot(2, "jira", "12.0.0", "12.0.0",
                Runtime.version().feature(), "test", "test", Map.of(), List.of(export("java.lang", "0")),
                List.of("java.lang", "sun.misc"));
        BundlingArtifactVerifier.Verification result = BundlingArtifactVerifier.verifyBundling(plugin(),
                List.of(new BundlingArtifactVerifier.ArtifactRequirement("com.google.code.gson:gson", List.of("com/google/gson/"))),
                Set.of(), null, Set.of(), new OsgiExportCatalog.Selection(snapshot,
                        OsgiExportCatalog.MatchQuality.EXACT, "test", List.of()), Runtime.version().feature());
        assertEquals(List.of("sun.misc"), result.missingImports());
        assertFalse(result.mandatoryImports().contains("api.external"));
    }

    @Test
    void rechecksTargetProvidedPackagesAgainstRefreshedExportsAndBuiltConstraints() throws Exception {
        BundlingArtifactVerifier.Verification removed = verifyProvided(plugin(), List.of());
        assertEquals(List.of("app.only"), removed.missingImports());
        assertFalse(removed.mandatoryImports().contains("api.external"));

        BundlingArtifactVerifier.Verification incompatible = verifyProvided(
                plugin("app.only;version=\"[1,2)\",unrelated.package"), List.of(export("app.only", "2.0")));
        assertEquals(List.of("app.only"), incompatible.incompatibleImports());
    }

    @Test
    void appUsedOptionalOrMissingImportsCannotHideUnavailableProductPackages() throws Exception {
        BundlingArtifactVerifier.Verification optional = verifyProvided(
                plugin("app.only;resolution:=optional"), List.of());
        assertEquals(List.of("app.only"), optional.missingImports());

        BundlingArtifactVerifier.Verification absent = verifyProvided(plugin("unrelated.package"),
                List.of(export("app.only", "1.0")));
        assertTrue(absent.blockers().stream().anyMatch(message -> message.contains("neither contained nor imported")));
    }

    private BundlingArtifactVerifier.Verification verifyProvided(Path artifact,
                                                                List<OsgiExportCatalog.Export> exports) throws Exception {
        OsgiExportCatalog.Snapshot snapshot = new OsgiExportCatalog.Snapshot(2, "jira", "12.0.0", "12.0.0",
                Runtime.version().feature(), "test", "test", Map.of(), exports, List.of("app.only"));
        OsgiExportCatalog.Selection selection = new OsgiExportCatalog.Selection(snapshot,
                OsgiExportCatalog.MatchQuality.EXACT, "test", List.of());
        return BundlingArtifactVerifier.verifyBundling(artifact, List.of(), Set.of(), null, Set.of(),
                selection, Runtime.version().feature(), Set.of("app.only"));
    }

    private BundlingArtifactVerifier.Verification verify(List<String> covered,
                                                         List<OsgiExportCatalog.Export> exports) throws Exception {
        Path artifact = plugin();
        OsgiExportCatalog.Snapshot snapshot = new OsgiExportCatalog.Snapshot(2, "jira", "12.0.0", "12.0.0",
                Runtime.version().feature(), "test", "test", Map.of(), exports, covered);
        OsgiExportCatalog.Selection selection = new OsgiExportCatalog.Selection(snapshot,
                OsgiExportCatalog.MatchQuality.EXACT, "test", List.of());
        return BundlingArtifactVerifier.verifyBundling(artifact,
                List.of(new BundlingArtifactVerifier.ArtifactRequirement("example:library", List.of("library/"))),
                Set.of(), null, Set.of(), selection, Runtime.version().feature());
    }

    private static OsgiExportCatalog.Export export(String name, String version) {
        return new OsgiExportCatalog.Export(name, version, "provider", "1.0", Map.of(), Map.of());
    }

    private Path plugin() throws Exception {
        return plugin("api.external;version=\"[1,2)\",java.lang,app.only,sun.misc");
    }

    private Path plugin(String imports) throws Exception {
        Map<String, String> sources = Map.of(
                "api/external/External.java", "package api.external; public class External {}",
                "com/google/gson/Reflector.java", "package com.google.gson; public class Reflector { public Class<?> load() throws Exception { return Class.forName(\"sun.misc.Unsafe\"); } }",
                "app/only/Unrelated.java", "package app.only; public class Unrelated {}",
                "library/Library.java", "package library; public class Library { public api.external.External field; }",
                "application/App.java", "package application; public class App { public app.only.Unrelated field; }");
        java.util.ArrayList<String> arguments = new java.util.ArrayList<>(List.of("-d", directory.toString()));
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path source = directory.resolve(entry.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, entry.getValue());
            arguments.add(source.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Import-Package", imports);
        Path artifact = directory.resolve("plugin.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(artifact), manifest)) {
            for (String name : List.of("library/Library.class", "application/App.class", "com/google/gson/Reflector.class")) {
                output.putNextEntry(new JarEntry(name));
                output.write(Files.readAllBytes(directory.resolve(name)));
                output.closeEntry();
            }
        }
        return artifact;
    }
}
