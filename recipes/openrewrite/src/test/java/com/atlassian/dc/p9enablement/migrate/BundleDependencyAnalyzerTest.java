package com.atlassian.dc.p9enablement.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies grouped discovery against syntax and effective Maven model fixtures. */
class BundleDependencyAnalyzerTest {

    @Test
    void refappTargetBundlesWithdrawnLibrariesAndKeepsProductProvidedPackages(@TempDir Path workspace)
            throws Exception {
        String dependencies = dependency("com.google.code.gson", "gson", "2.14.0", "provided")
                + dependency("commons-logging", "commons-logging", "1.3.5", "provided");
        Path module = createModule(workspace, "plugin", dependencies, "", dependencies);
        writeSource(module, "Usage.java", """
                package example;
                import com.google.gson.Gson;
                import org.apache.commons.logging.Log;
                class Usage { Gson gson; Log log; }
                """);

        BundlePlan plan = BundleDependencyAnalyzer.analyze(workspace, List.of(module), List.of(),
                "refapp", "9.0.6", List.of(), List.of(), fakeMaven(module).toString(),
                workspace.resolve("evidence"), null, 25);

        assertTrue(plan.unresolved().isEmpty(), plan.unresolved().toString());
        assertTrue(hasOperation(plan, BundlePlan.Action.SET_COMPILE_SCOPE, "com.google.code.gson:gson"));
        assertFalse(plan.operations().stream().anyMatch(operation ->
                operation.coordinate().equals("commons-logging:commons-logging")));
    }

    @Test
    void discoversKotlinInBothMainRootsAndEmbeddedModules(@TempDir Path workspace) throws Exception {
        Path plugin = createModule(workspace, "plugin", "", "", "");
        Path embedded = createModule(workspace, "embedded", "", "", "");
        Path source = embedded.resolve("src/main/kotlin/example/Usage.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example
                import com.google.gson.Gson as JsonWriter
                class Usage { fun write() = JsonWriter().toJson("value") }
                """);
        Files.writeString(plugin.resolve("src/main/java/example/Xml.kt"), """
                package example
                fun parser() = org.apache.xerces.parsers.DOMParser()
                """);
        Path ignored = plugin.resolve("src/test/kotlin/Unused.kt");
        Files.createDirectories(ignored.getParent());
        Files.writeString(ignored, "import org.w3c.tidy.Tidy");

        BundlePlan plan = analyze(workspace, plugin, List.of(), List.of(embedded));

        assertTrue(plan.unresolved().isEmpty(), plan.unresolved().toString());
        assertTrue(plan.operations().stream().anyMatch(operation ->
                operation.pom().endsWith("embedded/pom.xml")
                        && operation.coordinate().equals("com.google.code.gson:gson")));
        assertTrue(plan.operations().stream().anyMatch(operation ->
                operation.pom().endsWith("plugin/pom.xml")
                        && operation.coordinate().equals("xerces:xercesImpl")));
        assertTrue(plan.sourceEvidence().stream().allMatch(evidence -> evidence.sourceFile().endsWith(".kt")));
        assertTrue(plan.sourceEvidence().stream().noneMatch(evidence -> evidence.family().equals("jtidy")));
    }

    @ParameterizedTest
    @CsvSource({"kotlin,kt", "scala,scala", "groovy,groovy"})
    void discoversJvmLanguagesAndTracksSourceChanges(String language, String extension,
                                                    @TempDir Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "");
        String original = BundleDependencyAnalyzer.fingerprint(workspace, List.of(module));
        Path source = module.resolve("src/main/" + language + "/Usage." + extension);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "import com.google.gson.Gson\n");

        BundlePlan plan = analyze(workspace, module, List.of());

        assertTrue(plan.unresolved().isEmpty(), plan.unresolved().toString());
        assertTrue(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "com.google.code.gson:gson"));
        assertEquals(List.of(source.toRealPath().toString()), plan.sourceEvidence().stream()
                .map(BundlePlan.SourceEvidence::sourceFile).distinct().toList());

        String added = BundleDependencyAnalyzer.fingerprint(workspace, List.of(module));
        assertNotEquals(original, added);
        Files.writeString(source, "import com.google.gson.GsonBuilder\n");
        assertNotEquals(added, BundleDependencyAnalyzer.fingerprint(workspace, List.of(module)));
        Files.delete(source);
        assertEquals(original, BundleDependencyAnalyzer.fingerprint(workspace, List.of(module)));
    }

    @Test
    void mixedLanguageCompanionsAndBlockersUseTheSameModule(@TempDir Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "");
        writeSource(module, "Feed.java", """
                package example;
                import com.rometools.rome.feed.synd.SyndFeed;
                import org.jsoup.Jsoup;
                class Feed { SyndFeed feed; Jsoup jsoup; }
                """);
        Path source = module.resolve("src/main/kotlin/Usage.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                import org.jdom2.xpath.XPathFactory as XPath
                import com.google.re2j.Pattern as RegexPattern
                fun xpath() = XPath.instance()
                """);

        BundlePlan plan = analyze(workspace, module, List.of("rome", "jsoup"));

        assertTrue(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "jaxen:jaxen"));
        assertTrue(plan.unresolved().stream().anyMatch(reason -> reason.contains("jsoup re2j")));
        assertFalse(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "org.jsoup:jsoup"));
    }

    @Test
    void ambiguousGroovyScanCannotProduceACleanNoOp(@TempDir Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "");
        Path source = module.resolve("src/main/groovy/Usage.groovy");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                class Usage {
                    def a() { s.matches /won't/ }
                    def value = com.google.gson.Gson()
                    def b() { s.matches /can't/ }
                }
                """);

        BundlePlan plan = analyze(workspace, module, List.of());

        assertTrue(plan.unresolved().stream().anyMatch(reason -> reason.contains("ambiguous")));
        assertTrue(plan.sourceEvidence().stream().anyMatch(evidence -> evidence.family().equals("gson")));
    }

    @Test
    void incompleteJvmSourceScanIsNotACleanNoOp(@TempDir Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "");
        Path source = module.resolve("src/main/kotlin/Broken.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "/* unfinished comment");

        BundlePlan plan = analyze(workspace, module, List.of());

        assertFalse(plan.unresolved().isEmpty());
        assertTrue(plan.unresolved().stream().anyMatch(reason -> reason.contains("Broken.kt")));
    }

    @Test
    void discoversStaticWildcardAndFullyQualifiedUsageWithoutTextMatches(@TempDir final Path workspace)
            throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "");
        writeSource(module, "Usage.java", """
                package example;

                import static com.google.gson.GsonBuilder.*;
                import com.google.gson.*;

                // com.google.gson.JsonParser in a comment must be ignored.
                final class Usage {
                    private final String literal = "com.google.gson.JsonObject";
                    private final String text = \"\"\"
                            com.google.gson.JsonElement in a text block
                            \"\"\";

                    void use() {
                        com.google.gson.Gson gson = null;
                        gson.toJson(new JsonObject());
                    }
                }
                """);

        BundlePlan plan = analyze(workspace, module, List.of("gson"));

        List<String> references = plan.sourceEvidence().stream()
                .filter(evidence -> evidence.family().equals("gson"))
                .flatMap(evidence -> evidence.references().stream())
                .toList();
        assertTrue(references.contains("com.google.gson.GsonBuilder.*"));
        assertTrue(references.contains("com.google.gson.*"));
        assertTrue(references.contains("com.google.gson.Gson"));
        assertFalse(references.contains("com.google.gson.JsonParser"));
        assertTrue(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "com.google.code.gson:gson"));
    }

    @Test
    void distinguishesMissingProvidedAndCompileDependencies(@TempDir final Path workspace) throws Exception {
        String localDependencies = dependency("org.jdom", "jdom2", "2.0.6.1", "provided")
                + dependency("commons-io", "commons-io", "2.22.0", "compile");
        Path module = createModule(workspace, "plugin", localDependencies, "", localDependencies
                + dependency("com.google.code.gson", "gson", null, "provided"));
        writeSource(module, "Usage.java", """
                package example;

                import com.google.gson.Gson;
                import org.apache.commons.io.IOUtils;
                import org.jdom2.Document;

                final class Usage {
                    Gson gson;
                    IOUtils io;
                    Document document;
                }
                """);

        BundlePlan plan = analyze(workspace, module, List.of("commons-io", "gson", "jdom2"));

        assertTrue(hasOperation(plan, BundlePlan.Action.SET_COMPILE_SCOPE, "org.jdom:jdom2"));
        assertFalse(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "commons-io:commons-io"));
        assertTrue(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "com.google.code.gson:gson"));
        assertEquals("provided", evidenceFor(plan, "com.google.code.gson:gson").scope());
        assertEquals("compile", evidenceFor(plan, "commons-io:commons-io").scope());
    }

    @Test
    void addsRequiredExclusionsToExistingCompileDependency(@TempDir final Path workspace) throws Exception {
        String gson = dependency("com.google.code.gson", "gson", "2.14.0", "compile");
        Path module = createModule(workspace, "plugin", gson, "", gson);
        writeSource(module, "Usage.java", """
                package example;
                import com.google.gson.Gson;
                final class Usage { Gson gson; }
                """);

        BundlePlan plan = analyze(workspace, module, List.of("gson"));

        assertTrue(hasOperation(plan, BundlePlan.Action.ADD_EXCLUSIONS, "com.google.code.gson:gson"));
        assertTrue(plan.operations().stream()
                .filter(operation -> operation.action() == BundlePlan.Action.ADD_EXCLUSIONS)
                .flatMap(operation -> operation.exclusions().stream())
                .anyMatch(exclusion -> exclusion.coordinate().equals("com.google.errorprone:error_prone_annotations")));
    }

    @Test
    void reportsInheritedAmpsInstructionsAsUnresolved(@TempDir final Path workspace) throws Exception {
        String gson = dependency("com.google.code.gson", "gson", "2.14.0", "compile");
        Path module = createModule(workspace, "plugin", gson, "", gson);
        Files.writeString(module.resolve("pom.xml"), pomWithPlugin(gson, ""));
        Files.writeString(module.resolve("effective-pom.xml"), pomWithPlugin(
                gson, """
                        <configuration>
                            <instructions>
                                <Embedded-Dependencies>gson</Embedded-Dependencies>
                            </instructions>
                        </configuration>
                        """));
        writeSource(module, "Usage.java", """
                package example;
                import com.google.gson.Gson;
                final class Usage { Gson gson; }
                """);

        BundlePlan plan = analyze(workspace, module, List.of("gson"));

        assertTrue(plan.unresolved().stream().anyMatch(reason -> reason.contains("inherited AMPS instructions")));
        assertFalse(plan.operations().stream().anyMatch(operation ->
                operation.action() == BundlePlan.Action.APPEND_EMBEDDED_DEPENDENCY));
    }

    @Test
    void usesInheritedEffectiveDependenciesAndReportsAmbiguousProfileOwnership(@TempDir final Path workspace)
            throws Exception {
        Path inherited = createModule(workspace, "inherited", "", "", dependency(
                "com.google.code.gson", "gson", "2.14.0", "compile"));
        writeSource(inherited, "Usage.java", """
                package example;
                import com.google.gson.Gson;
                final class Usage { Gson gson; }
                """);

        BundlePlan inheritedPlan = analyze(workspace, inherited, List.of("gson"));

        assertFalse(hasOperation(inheritedPlan, BundlePlan.Action.ADD_DEPENDENCY, "com.google.code.gson:gson"));
        assertEquals("effective", evidenceFor(inheritedPlan, "com.google.code.gson:gson").status());

        String profile = """
                <profiles>
                    <profile>
                        <id>platform-nine</id>
                        <dependencies>
                            <dependency>
                                <groupId>com.google.code.gson</groupId>
                                <artifactId>gson</artifactId>
                                <version>2.14.0</version>
                                <scope>provided</scope>
                            </dependency>
                        </dependencies>
                    </profile>
                </profiles>
                """;
        Path ambiguous = createModule(workspace, "ambiguous", "", profile, dependency(
                "com.google.code.gson", "gson", "2.14.0", "provided"));
        writeSource(ambiguous, "Usage.java", """
                package example;
                import com.google.gson.Gson;
                final class Usage { Gson gson; }
                """);

        BundlePlan ambiguousPlan = analyze(workspace, ambiguous, List.of("gson"));

        assertTrue(ambiguousPlan.unresolved().stream().anyMatch(reason -> reason.contains("declared in a profile")));
        assertFalse(hasOperation(ambiguousPlan, BundlePlan.Action.SET_COMPILE_SCOPE, "com.google.code.gson:gson"));
    }

    @Test
    void discoversEmbeddedModulesAndFingerprintChangesWhenSourceIsAdded(@TempDir final Path workspace)
            throws Exception {
        Path plugin = createModule(workspace, "plugin", "", "", "");
        Path embedded = createModule(workspace, "embedded", "", "", "");
        writeSource(embedded, "Usage.java", """
                package example;
                import com.google.gson.Gson;
                final class Usage { Gson gson; }
                """);

        BundlePlan plan = analyze(workspace, plugin, List.of("gson"), List.of(embedded));

        assertEquals(2, plan.modules().size());
        assertTrue(plan.modules().stream().anyMatch(path -> path.endsWith("embedded")));
        assertTrue(plan.operations().stream().anyMatch(operation ->
                operation.pom().endsWith("embedded/pom.xml")
                        && operation.coordinate().equals("com.google.code.gson:gson")));

        String before = BundleDependencyAnalyzer.fingerprint(workspace, List.of(plugin, embedded));
        writeSource(embedded, "Added.java", """
                package example;
                final class Added { String value = "new source input"; }
                """);
        String after = BundleDependencyAnalyzer.fingerprint(workspace, List.of(plugin, embedded));
        assertNotEquals(before, after);
    }

    @Test
    void doesNotInvokeMavenWhenNoSupportedUsageExists(@TempDir final Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "");
        writeSource(module, "Usage.java", """
                package example;
                final class Usage { String value = "ordinary application code"; }
                """);
        Path maven = fakeMaven(module);

        BundlePlan plan = BundleDependencyAnalyzer.analyze(
                workspace, List.of(module), List.of(), "jira", "12.0.0-m0204", List.of(), List.of("gson"),
                maven.toString(), workspace.resolve("evidence"));

        assertTrue(plan.sourceEvidence().isEmpty());
        assertTrue(plan.unresolved().isEmpty());
        assertFalse(Files.exists(Path.of(maven + ".count")));
    }

    @Test
    void classifiesTheSameUsageAgainstProductSpecificExportEvidence(@TempDir final Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "");
        writeSource(module, "Usage.java", """
                package example;
                import org.apache.commons.codec.Decoder;
                import org.apache.commons.io.IOUtils;
                import org.apache.commons.text.StringSubstitutor;
                import org.json.JSONObject;
                final class Usage { Decoder decoder; IOUtils io; StringSubstitutor text; JSONObject json; }
                """);
        List<String> families = List.of("commons-codec", "commons-io", "commons-text", "org-json");
        List<String> packages = List.of("org.apache.commons.codec", "org.apache.commons.io",
                "org.apache.commons.text", "org.json");
        Path confluenceEvidence = writeSnapshot(workspace, "confluence", "11.0.0-beta3", packages,
                packages.stream().map(packageName -> export(packageName, "1.0.0")).toList());
        Path jiraEvidence = writeSnapshot(workspace, "jira", "12.0.0-m0204", packages, List.of());

        BundlePlan confluence = analyzeWithSnapshot(workspace, module, families, "confluence", "11.0.0-beta3",
                confluenceEvidence);
        BundlePlan jira = analyzeWithSnapshot(workspace, module, families, "jira", "12.0.0-m0204",
                jiraEvidence);

        assertTrue(confluence.operations().isEmpty());
        assertTrue(confluence.dependencyClassifications().stream()
                .allMatch(classification -> classification.status()
                        .equals(BundlePlan.DependencyClassificationStatus.PROVIDED_BY_TARGET)));
        assertEquals(families.size(), jira.operations().stream()
                .filter(operation -> operation.action() == BundlePlan.Action.ADD_DEPENDENCY).count());
    }

    @Test
    void providedRomeRootDoesNotAddUnusedCompanions(@TempDir final Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "");
        writeSource(module, "Usage.java", """
                package example;
                import com.rometools.rome.feed.synd.SyndFeedImpl;
                final class Usage { SyndFeedImpl feed; }
                """);
        List<String> packages = List.of("com.rometools.rome.feed.synd", "com.rometools.utils", "org.jdom2");
        Path evidence = writeSnapshot(workspace, "jira", "12.0.0-m0204", packages,
                List.of(export("com.rometools.rome.feed.synd", "1.0.0"), export("com.rometools.utils", "1.0.0"),
                        export("org.jdom2", "1.0.0")));

        BundlePlan plan = analyzeWithSnapshot(workspace, module, List.of("rome"), "jira",
                "12.0.0-m0204", evidence);

        assertTrue(plan.operations().isEmpty());
        assertEquals(List.of("com.rometools:rome"), plan.dependencyClassifications().stream()
                .map(BundlePlan.DependencyClassification::coordinate).toList());
    }

    @Test
    void bundledRomeRootRetainsItsCompanionClosure(@TempDir final Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "");
        writeSource(module, "Usage.java", """
                package example;
                import com.rometools.rome.feed.synd.SyndFeedImpl;
                final class Usage { SyndFeedImpl feed; }
                """);
        List<String> packages = List.of("com.rometools.rome.feed.synd", "com.rometools.utils", "org.jdom2");
        List<OsgiExportCatalog.Export> exports = List.of(export("com.rometools.utils", "1.0.0"),
                export("org.jdom2", "1.0.0"));
        Path evidence = writeSnapshot(workspace, "jira", "12.0.0-m0204", packages, exports);

        BundlePlan plan = analyzeWithSnapshot(workspace, module, List.of("rome"), "jira",
                "12.0.0-m0204", evidence);

        assertEquals(List.of("com.rometools:rome", "com.rometools:rome-utils", "org.jdom:jdom2"),
                plan.dependencyClassifications().stream().map(BundlePlan.DependencyClassification::coordinate).toList());
        assertTrue(plan.operations().stream().anyMatch(operation -> operation.coordinate().equals("org.jdom:jdom2")));
        assertTrue(plan.dependencyClassifications().stream()
                .noneMatch(classification -> classification.status()
                        .equals(BundlePlan.DependencyClassificationStatus.PROVIDED_BY_TARGET)));
    }

    @Test
    void existingCompileDependencyRemainsAppOwnedWhenTargetExportsIt(@TempDir final Path workspace) throws Exception {
        String gson = dependency("com.google.code.gson", "gson", "2.14.0", "compile");
        Path module = createModule(workspace, "plugin", gson, "", gson);
        writeSource(module, "Usage.java", """
                package example;
                import com.google.gson.Gson;
                final class Usage { Gson gson; }
                """);
        Path evidence = writeSnapshot(workspace, "jira", "12.0.0-m0204",
                List.of("com.google.gson"), List.of(export("com.google.gson", "2.14.0")));

        BundlePlan plan = analyzeWithSnapshot(workspace, module, List.of("gson"), "jira",
                "12.0.0-m0204", evidence);

        assertEquals(BundlePlan.DependencyClassificationStatus.ALREADY_BUNDLED,
                plan.dependencyClassifications().getFirst().status());
        assertFalse(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "com.google.code.gson:gson"));
        assertFalse(hasOperation(plan, BundlePlan.Action.SET_COMPILE_SCOPE, "com.google.code.gson:gson"));
    }

    @Test
    void constrainedImportCannotSilentlyReuseTargetExport(@TempDir final Path workspace) throws Exception {
        String gson = dependency("com.google.code.gson", "gson", "2.14.0", "provided");
        Path module = createModule(workspace, "plugin", gson, "", gson);
        String pom = pomWithPlugin(gson,
                "<configuration><instructions><Import-Package>com.google.gson;version=\"[2,3)\"</Import-Package>"
                        + "</instructions></configuration>");
        Files.writeString(module.resolve("pom.xml"), pom);
        Files.writeString(module.resolve("effective-pom.xml"), pom);
        writeSource(module, "Usage.java", """
                package example;
                import com.google.gson.Gson;
                final class Usage { Gson gson; }
                """);
        Path evidence = writeSnapshot(workspace, "jira", "12.0.0-m0204",
                List.of("com.google.gson"), List.of(export("com.google.gson", "2.14.0")));

        BundlePlan plan = analyzeWithSnapshot(workspace, module, List.of("gson"), "jira",
                "12.0.0-m0204", evidence);

        assertTrue(plan.unresolved().stream().anyMatch(reason -> reason.contains("constrained Import-Package")));
        assertEquals(BundlePlan.DependencyClassificationStatus.UNCERTAIN,
                plan.dependencyClassifications().getFirst().status());
    }

    @Test
    void unavailableProductEvidenceBlocksAffectedCandidates(@TempDir final Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "");
        writeSource(module, "Usage.java", """
                package example;
                import com.google.gson.Gson;
                final class Usage { Gson gson; }
                """);

        BundlePlan plan = BundleDependencyAnalyzer.analyze(workspace, List.of(module), List.of(), "bamboo", "13.0.0",
                List.of(), List.of("gson"), fakeMaven(module).toString(), workspace.resolve("evidence"), null, 25);

        assertTrue(plan.unresolved().stream().anyMatch(reason -> reason.contains("uncertain target export evidence")));
        assertEquals(BundlePlan.DependencyClassificationStatus.UNCERTAIN,
                plan.dependencyClassifications().getFirst().status());
    }

    @Test
    void plansOptionalImportForLocalAmpsPluginWithoutInstructions(@TempDir final Path workspace) throws Exception {
        String gson = dependency("com.google.code.gson", "gson", "2.14.0", "compile");
        Path module = createModule(workspace, "plugin", gson, "", gson);
        String pom = pomWithPlugin(gson, "<configuration/>");
        Files.writeString(module.resolve("pom.xml"), pom);
        Files.writeString(module.resolve("effective-pom.xml"), pom);
        writeSource(module, "Usage.java", """
                package example;
                import com.google.gson.Gson;
                final class Usage { Gson gson; }
                """);

        BundlePlan plan = analyze(workspace, module, List.of("gson"));

        assertTrue(plan.unresolved().isEmpty());
        assertTrue(plan.operations().stream().anyMatch(operation ->
                operation.action() == BundlePlan.Action.APPEND_OPTIONAL_IMPORT
                        && operation.artifactId().equals("com.google.errorprone.annotations.*")));
    }

    @Test
    void selectedRomeDiscoversJdomXPathCompanions(@TempDir final Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "");
        writeSource(module, "Usage.java", """
                package example;
                import com.rometools.rome.feed.synd.SyndFeed;
                import org.jdom2.xpath.XPathFactory;
                final class Usage {
                    SyndFeed feed;
                    XPathFactory factory;
                }
                """);

        BundlePlan plan = analyze(workspace, module, List.of("rome"));

        assertTrue(plan.sourceEvidence().stream().anyMatch(evidence -> evidence.family().equals("jdom2")));
        assertTrue(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "com.rometools:rome"));
        assertTrue(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "com.rometools:rome-utils"));
        assertTrue(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "org.jdom:jdom2"));
        assertTrue(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "jaxen:jaxen"));
    }

    @Test
    void retainsModuleScopedRe2jBlocker(@TempDir Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "").toRealPath();
        writeSource(module, "Usage.java", """
                package example;
                import org.jsoup.Jsoup;
                final class Usage { Jsoup jsoup; }
                """);
        writeSource(module, "RegexUsage.java", """
                package example;
                import com.google.re2j.Pattern;
                final class RegexUsage { Pattern pattern; }
                """);

        BundlePlan plan = analyze(workspace, module, List.of("jsoup"));

        assertEquals(List.of(module + ": jsoup re2j selector usage requires confirming and bundling its compatible re2j dependency"),
                plan.unresolved());
        assertTrue(plan.operations().isEmpty());
    }

    @Test
    void retainsHtmlFindingAndMigratesSupportedXml(@TempDir Path workspace) throws Exception {
        Path module = createModule(workspace, "plugin", "", "", "").toRealPath();
        writeSource(module, "Usage.java", """
                package example;
                import org.apache.html.dom.HTMLDocumentImpl;
                import org.apache.xerces.parsers.DOMParser;
                final class Usage { HTMLDocumentImpl html; DOMParser parser; }
                """);

        BundlePlan plan = analyze(workspace, module, List.of("xerces-xalan"));

        assertTrue(plan.unresolved().isEmpty());
        assertTrue(hasOperation(plan, BundlePlan.Action.ADD_DEPENDENCY, "xerces:xercesImpl"));
        assertTrue(plan.dependencyEvidence().stream().anyMatch(evidence ->
                evidence.equals(new BundlePlan.DependencyEvidence(module.resolve("pom.xml").toString(),
                        "xerces", "xercesImpl", null, null,
                        "unsupported: org.apache.html.dom usage has no supported bundling repair"))));
    }

    private static BundlePlan analyze(Path workspace, Path module, List<String> families) throws Exception {
        return analyze(workspace, module, families, List.of());
    }

    @Test
    void plansOnlyActualImportChangesAndPreservesOptionalLibraryClauses(@TempDir Path workspace) throws Exception {
        String gson = dependency("com.google.code.gson", "gson", "2.14.0", "compile");
        Path module = createModule(workspace, "plugin", gson, "", gson);
        writeSource(module, "Usage.java", """
                package example;
                import com.google.gson.Gson;
                final class Usage { Gson gson; }
                """);

        for (String imports : List.of(
                "!com.google.gson.*,com.google.gson.*;resolution:=optional,!other.*,*",
                "com.google.gson.*;resolution:=optional,!other.*,com.google.errorprone.annotations.*;resolution:=optional,*",
                "com.google.gson.*;resolution:=optional,!other.*,*;resolution:=optional")) {
            String pom = pomWithPlugin(gson, "<configuration><instructions><Import-Package>"
                    + imports + "</Import-Package></instructions></configuration>");
            Files.writeString(module.resolve("pom.xml"), pom);
            Files.writeString(module.resolve("effective-pom.xml"), pom);

            BundlePlan plan = analyze(workspace, module, List.of("gson"));
            List<BundlePlan.DependencyOperation> importOperations = plan.operations().stream()
                    .filter(operation -> operation.action() == BundlePlan.Action.REMOVE_IMPORT_EXCLUSION
                            || operation.action() == BundlePlan.Action.APPEND_OPTIONAL_IMPORT).toList();

            assertTrue(plan.unresolved().isEmpty());
            if (imports.startsWith("!com.google.gson")) {
                assertEquals(2, importOperations.size());
                assertTrue(importOperations.stream().anyMatch(operation ->
                        operation.action() == BundlePlan.Action.REMOVE_IMPORT_EXCLUSION
                                && operation.artifactId().equals("com.google.gson")));
            } else {
                assertTrue(importOperations.isEmpty(), "Satisfied instructions must not appear as pending operations");
            }
        }
    }

    private static BundlePlan analyze(Path workspace, Path module, List<String> families, List<Path> embedded)
            throws Exception {
        Path maven = fakeMaven(module);
        return BundleDependencyAnalyzer.analyze(
                workspace, List.of(module), embedded, "jira", "12.0.0-m0204", List.of("platform-9"), families,
                maven.toString(), workspace.resolve("evidence"));
    }

    private static Path createModule(Path workspace, String name, String localDependencies, String profiles,
                                     String effectiveDependencies) throws IOException {
        Path module = workspace.resolve(name);
        Files.createDirectories(module.resolve("src/main/java/example"));
        Files.writeString(module.resolve("pom.xml"), pom(localDependencies, profiles));
        Files.writeString(module.resolve("effective-pom.xml"), pom(effectiveDependencies, ""));
        Files.writeString(module.resolve("dependency-tree.tgf"), "#\n");
        return module;
    }

    private static void writeSource(Path module, String name, String source) throws IOException {
        Files.writeString(module.resolve("src/main/java/example").resolve(name), source);
    }

    private static Path fakeMaven(Path module) throws IOException {
        Path script = module.getParent().resolve("fake-maven.sh");
        Files.writeString(script, """
                #!/bin/sh
                set -eu
                printf 'invoked\\n' >> "$0.count"
                pom=''
                effective=''
                tree=''
                while [ "$#" -gt 0 ]; do
                    case "$1" in
                        -f) pom=$2; shift 2 ;;
                        -Doutput=*) effective=${1#-Doutput=}; shift ;;
                        -DoutputFile=*) tree=${1#-DoutputFile=}; shift ;;
                        *) shift ;;
                    esac
                done
                base=$(dirname "$pom")
                if [ -n "$effective" ]; then cp "$base/effective-pom.xml" "$effective"; fi
                if [ -n "$tree" ]; then cp "$base/dependency-tree.tgf" "$tree"; fi
                """);
        Files.setPosixFilePermissions(script, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private static BundlePlan analyzeWithSnapshot(Path workspace, Path module, List<String> families,
                                                  String product, String version, Path snapshot) throws Exception {
        return BundleDependencyAnalyzer.analyze(workspace, List.of(module), List.of(), product, version,
                List.of(), families, fakeMaven(module).toString(), workspace.resolve("evidence"), snapshot, 25);
    }

    private static Path writeSnapshot(Path workspace, String product, String version, List<String> coveredPackages,
                                      List<OsgiExportCatalog.Export> exports) throws IOException {
        Path snapshot = workspace.resolve(product + "-exports.json");
        OsgiExportCatalog.Snapshot value = new OsgiExportCatalog.Snapshot(2, product,
                OsgiExportCatalog.normalizeVersion(version), version, 25, "2026-01-01T00:00:00Z",
                "fixture", Map.of("fixture", "test"), exports, coveredPackages);
        new ObjectMapper().writeValue(snapshot.toFile(), value);
        return snapshot;
    }

    private static OsgiExportCatalog.Export export(String packageName, String version) {
        return new OsgiExportCatalog.Export(packageName, version, "fixture", "1.0.0", Map.of(), Map.of());
    }

    private static String pom(String dependencies, String profiles) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId>
                    <artifactId>fixture</artifactId>
                    <version>1.0.0</version>
                    <dependencies>%s</dependencies>
                    %s
                </project>
                """.formatted(dependencies, profiles);
    }

    private static String pomWithPlugin(String dependencies, String configuration) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId>
                    <artifactId>fixture</artifactId>
                    <version>1.0.0</version>
                    <dependencies>%s</dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>jira-maven-plugin</artifactId>
                                %s
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(dependencies, configuration);
    }

    private static String dependency(String groupId, String artifactId, String version, String scope) {
        String versionElement = version == null ? "" : "<version>" + version + "</version>";
        String scopeElement = scope == null ? "" : "<scope>" + scope + "</scope>";
        return "<dependency><groupId>" + groupId + "</groupId><artifactId>" + artifactId
                + "</artifactId>" + versionElement + scopeElement + "</dependency>";
    }

    private static boolean hasOperation(BundlePlan plan, BundlePlan.Action action, String coordinate) {
        return plan.operations().stream().anyMatch(operation -> operation.action() == action
                && operation.coordinate().equals(coordinate));
    }

    private static BundlePlan.DependencyEvidence evidenceFor(BundlePlan plan, String coordinate) {
        return plan.dependencyEvidence().stream()
                .filter(evidence -> (evidence.groupId() + ":" + evidence.artifactId()).equals(coordinate))
                .findFirst().orElseThrow();
    }
}
