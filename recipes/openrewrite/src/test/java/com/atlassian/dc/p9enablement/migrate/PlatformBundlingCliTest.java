package com.atlassian.dc.p9enablement.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlatformBundlingCliTest {
    @TempDir
    Path directory;

    @Test
    void analyzeRejectsPublicFamilyFilter() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();

        assertThrows(IllegalArgumentException.class, () -> PlatformBundlingCli.execute(new String[]{
                "analyze", "--workspace", workspace.toString(), "--family", "gson"}));
        assertThrows(IllegalArgumentException.class, () -> PlatformBundlingCli.execute(new String[]{
                "analyze", "--workspace", workspace.toString(), "--approve-family", "gson"}));
    }

    @Test
    void approveRecomputesRomeDependencyClosureAndPreservesFullPlan() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();
        writeRomeWorkspace(workspace);
        Path sourcePlan = writeFullPlan(workspace);
        String sourcePlanBefore = Files.readString(sourcePlan);
        Path output = directory.resolve("approved");
        Path maven = writeEffectivePomMaven();

        assertEquals(0, PlatformBundlingCli.execute(new String[]{
                "approve", "--workspace", workspace.toString(), "--plan", sourcePlan.toString(),
                "--approve-family", "rome", "--output", output.toString(), "--maven", maven.toString()}));

        assertEquals(sourcePlanBefore, Files.readString(sourcePlan));
        JsonNode approved = new ObjectMapper().readTree(output.resolve("bundle-plan.json").toFile());
        assertEquals(1, approved.get("selectedFamilies").size());
        assertEquals("rome", approved.get("selectedFamilies").get(0).asText());
        String approvedJson = approved.toString();
        assertTrue(approvedJson.contains("\"family\":\"jdom2\""));
        assertTrue(approvedJson.contains("\"groupId\":\"com.rometools\""));
        assertTrue(approvedJson.contains("\"artifactId\":\"rome\""));
        assertTrue(approvedJson.contains("\"artifactId\":\"rome-utils\""));
        assertTrue(approvedJson.contains("\"groupId\":\"org.jdom\""));
        assertTrue(approvedJson.contains("\"artifactId\":\"jdom2\""));
        assertTrue(approvedJson.contains("\"groupId\":\"jaxen\""));
        assertTrue(approvedJson.contains("\"artifactId\":\"jaxen\""));
    }

    @Test
    void approveRejectsStaleFullPlanBeforeReanalysis() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();
        Path pom = workspace.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        Path sourcePlan = writeFullPlan(workspace);
        Files.writeString(pom, "<project><name>changed</name></project>");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PlatformBundlingCli.execute(new String[]{
                        "approve", "--workspace", workspace.toString(), "--plan", sourcePlan.toString(),
                        "--approve-family", "gson"}));
        assertTrue(exception.getMessage().contains("stale"));
        assertTrue(Files.readString(sourcePlan).contains("selectedFamilies"));
    }

    @Test
    void checkReportsReadyWithoutEditingOrOverwritingApplyResult() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();
        Path pom = workspace.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        Path plan = writePlan(workspace, workspace);
        Path output = Files.createDirectory(directory.resolve("output"));
        Path result = output.resolve("bundle-result.json");
        Files.writeString(result, "previous apply result");

        assertEquals(0, PlatformBundlingCli.execute(new String[]{"check", "--workspace", workspace.toString(),
                "--plan", plan.toString(), "--output", output.toString()}));

        JsonNode check = new ObjectMapper().readTree(output.resolve("bundle-check.json").toFile());
        assertEquals("ready", check.get("status").asText());
        assertEquals("pending", check.get("familyOutcomes").get("gson").asText());
        assertEquals(0, check.get("recipeInvocations").asInt());
        assertEquals("previous apply result", Files.readString(result));
        assertEquals("<project/>", Files.readString(pom));
    }

    @Test
    void checkReportsStalePlanAfterSourceEdit() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();
        Path pom = workspace.resolve("pom.xml");
        Path source = workspace.resolve("src/main/java/example/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(pom, "<project/>");
        Files.writeString(source, "class App {}");
        Path plan = writePlan(workspace, workspace);
        Files.writeString(source, "class App { int changed; }");

        assertEquals(2, PlatformBundlingCli.execute(new String[]{"check", "--workspace", workspace.toString(),
                "--plan", plan.toString()}));
        JsonNode check = result(workspace, "bundle-check.json");
        assertEquals("stale-plan", check.get("status").asText());
        assertEquals("pending", check.get("familyOutcomes").get("gson").asText());
        assertEquals("class App { int changed; }", Files.readString(source));
        assertFalse(Files.exists(workspace.resolve(".migration-state/bundling/bundle-result.json")));
    }

    @Test
    void kotlinEditPreventsApplyingAnOldPlan() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();
        Path pom = workspace.resolve("pom.xml");
        Path source = workspace.resolve("src/main/kotlin/App.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(pom, "<project/>");
        Files.writeString(source, "class App");
        Path plan = writePlan(workspace, workspace);
        Files.writeString(source, "class App { val changed = 1 }");

        assertEquals(2, PlatformBundlingCli.execute(new String[]{"apply", "--workspace", workspace.toString(),
                "--plan", plan.toString()}));
        assertEquals("stale-plan", result(workspace, "bundle-result.json").get("status").asText());
        assertEquals("<project/>", Files.readString(pom));
    }

    @Test
    void applyReportsStalePlansBeforeEditing() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();
        Path pom = workspace.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        Path plan = writePlan(workspace, workspace);
        Files.writeString(pom, "<project><name>Changed after discovery</name></project>");

        assertEquals(2, PlatformBundlingCli.execute(new String[]{"apply", "--workspace", workspace.toString(),
                "--plan", plan.toString()}));
        assertTrue(Files.readString(pom).contains("Changed after discovery"));
        JsonNode result = result(workspace, "bundle-result.json");
        assertEquals("stale-plan", result.get("status").asText());
        assertEquals("pending", result.get("familyOutcomes").get("gson").asText());
    }

    @Test
    void applyUsesRefreshedPlanAfterStaleCheck() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();
        Path pom = workspace.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        Path stalePlan = writePlan(workspace, workspace);
        Files.writeString(pom, "<project><name>Changed after discovery</name></project>");

        assertEquals(2, PlatformBundlingCli.execute(new String[]{"check", "--workspace", workspace.toString(),
                "--plan", stalePlan.toString()}));

        Path refreshedPlan = writePlan(workspace, workspace);
        assertEquals(0, PlatformBundlingCli.execute(new String[]{"apply", "--workspace", workspace.toString(),
                "--plan", refreshedPlan.toString()}));
        JsonNode result = result(workspace, "bundle-result.json");
        assertEquals("applied", result.get("status").asText());
        assertTrue(Files.readString(pom).contains("com.google.code.gson"));
    }

    @Test
    void blockedApplyMarksEverySelectedFamilyPending() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();
        Path pom = workspace.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        Path plan = writePlan(workspace, workspace, List.of("gson", "jdom2"), List.of("dependency needs review"));

        assertEquals(2, PlatformBundlingCli.execute(new String[]{"apply", "--workspace", workspace.toString(),
                "--plan", plan.toString()}));
        JsonNode result = result(workspace, "bundle-result.json");
        assertEquals("unresolved", result.get("status").asText());
        assertEquals("pending", result.get("familyOutcomes").get("gson").asText());
        assertEquals("pending", result.get("familyOutcomes").get("jdom2").asText());
        assertEquals("<project/>", Files.readString(pom));
    }

    @Test
    void groupedPackagingOperationIsAttributedToItsDependencyFamily() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();
        Path pom = workspace.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <build><plugins><plugin>
                    <groupId>com.atlassian.maven.plugins</groupId>
                    <artifactId>jira-maven-plugin</artifactId>
                    <configuration><instructions><Embedded-Dependencies/></instructions></configuration>
                  </plugin></plugins></build>
                </project>
                """);
        Path plan = writeGroupedPackagingPlan(workspace);

        assertEquals(0, PlatformBundlingCli.execute(new String[]{"apply", "--workspace", workspace.toString(),
                "--plan", plan.toString()}));
        JsonNode result = result(workspace, "bundle-result.json");
        assertEquals("applied", result.get("familyOutcomes").get("gson").asText());
        assertEquals("already-compatible", result.get("familyOutcomes").get("commons-io").asText());
        assertTrue(Files.readString(pom).contains("Embedded-Dependencies>gson"));
    }

    @Test
    void rejectsPlanModulesOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();
        Path outside = Files.createDirectory(directory.resolve("outside")).toRealPath();
        Files.writeString(outside.resolve("pom.xml"), "<project/>");
        Path plan = writePlan(workspace, outside);

        assertThrows(IllegalArgumentException.class, () -> PlatformBundlingCli.execute(new String[]{
                "apply", "--workspace", workspace.toString(), "--plan", plan.toString()}));
        assertEquals("<project/>", Files.readString(outside.resolve("pom.xml")));
    }

    @Test
    void rejectsCheckWhenExplicitExportSnapshotChangesOrDisappears() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath();
        Path source = Files.createDirectories(workspace.resolve("src/main/java/example"))
                .resolve("Usage.java");
        Files.writeString(workspace.resolve("pom.xml"), """
                <project><dependencies><dependency><groupId>com.google.code.gson</groupId>
                <artifactId>gson</artifactId><version>2.14.0</version><scope>compile</scope></dependency></dependencies></project>
                """);
        Files.writeString(source, "package example; import com.google.gson.Gson; class Usage { Gson gson; }");
        Path snapshot = directory.resolve("jira-exports.json");
        writeExactSnapshot(snapshot);
        Path output = directory.resolve("analysis");
        Path maven = writeEffectivePomMaven();

        assertEquals(0, PlatformBundlingCli.execute(new String[]{
                "analyze", "--workspace", workspace.toString(), "--target-product", "jira",
                "--target-version", "12.0.0-m0204", "--target-java-version", "25",
                "--export-snapshot", snapshot.toString(), "--maven", maven.toString(),
                "--output", output.toString()}));
        Files.writeString(snapshot, Files.readString(snapshot).replace("2026-01-01", "2026-01-02"));

        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> PlatformBundlingCli.execute(new String[]{"check", "--workspace", workspace.toString(),
                        "--plan", output.resolve("bundle-plan.json").toString()}));
        assertTrue(changed.getMessage().contains("changed"));

        Files.delete(snapshot);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> PlatformBundlingCli.execute(new String[]{"apply", "--workspace", workspace.toString(),
                        "--plan", output.resolve("bundle-plan.json").toString()}));
        assertTrue(missing.getMessage().contains("missing"));
    }

    private Path writePlan(Path workspace, Path module) throws Exception {
        return writePlan(workspace, module, List.of("gson"), List.of());
    }

    private Path writeFullPlan(Path workspace) throws Exception {
        BundlePlan plan = new BundlePlan(2, workspace.toString(), List.of(workspace.toString()), List.of(),
                "jira", "12.0.0-m0204", 25, List.of(), BundleDependencyModel.families().keySet().stream().sorted().toList(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                BundleDependencyAnalyzer.fingerprint(workspace, List.of(workspace)), jiraEvidence(), List.of());
        Path output = workspace.resolve("full-plan.json");
        new ObjectMapper().writeValue(output.toFile(), plan);
        return output;
    }

    private void writeRomeWorkspace(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>fixture</artifactId><version>1</version>
                  <dependencies>
                    <dependency><groupId>com.rometools</groupId><artifactId>rome</artifactId><version>2.1.0</version><scope>provided</scope></dependency>
                    <dependency><groupId>com.rometools</groupId><artifactId>rome-utils</artifactId><version>2.1.0</version><scope>provided</scope></dependency>
                    <dependency><groupId>org.jdom</groupId><artifactId>jdom2</artifactId><version>2.0.6.1</version><scope>provided</scope></dependency>
                    <dependency><groupId>jaxen</groupId><artifactId>jaxen</artifactId><version>1.2.0</version><scope>provided</scope></dependency>
                  </dependencies>
                </project>
                """);
        Path source = Files.createDirectories(workspace.resolve("src/main/java")).resolve("FeedUsage.java");
        Files.writeString(source, """
                import com.rometools.rome.feed.synd.SyndFeedImpl;
                import org.jdom2.xpath.XPathFactory;
                class FeedUsage {
                    Object use() { return new SyndFeedImpl().toString() + XPathFactory.instance(); }
                }
                """);
    }

    private Path writeEffectivePomMaven() throws Exception {
        Path maven = directory.resolve("fake-mvn.sh");
        Files.writeString(maven, """
                #!/bin/sh
                pom=""
                previous=""
                for argument in "$@"; do
                  if [ "$previous" = "-f" ]; then pom="$argument"; fi
                  case "$argument" in
                    -Doutput=*) cp "$pom" "${argument#-Doutput=}" ;;
                  esac
                  previous="$argument"
                done
                """);
        assertTrue(maven.toFile().setExecutable(true));
        return maven;
    }

    private Path writePlan(Path workspace, Path module, List<String> families,
                           List<String> unresolved) throws Exception {
        BundlePlan plan = new BundlePlan(2, workspace.toString(), List.of(module.toString()), List.of(),
                "jira", "12.0.0-m0204", 25, List.of(), families, List.of(), List.of(), List.of(
                new BundlePlan.DependencyOperation(module.resolve("pom.xml").toString(),
                        BundlePlan.Action.ADD_DEPENDENCY, "gson", "com.google.code.gson", "gson", "2.14.0", List.of(), "test")),
                unresolved, List.of(), BundleDependencyAnalyzer.fingerprint(workspace, List.of(module)), jiraEvidence(), List.of());
        Path output = workspace.resolve("plan.json");
        new ObjectMapper().writeValue(output.toFile(), plan);
        return output;
    }

    private Path writeGroupedPackagingPlan(Path workspace) throws Exception {
        BundlePlan plan = new BundlePlan(2, workspace.toString(), List.of(workspace.toString()), List.of(),
                "jira", "12.0.0-m0204", 25, List.of(), List.of("commons-io", "gson"),
                List.of(new BundlePlan.SourceEvidence("commons-io", "source-io.java", List.of("org.apache.commons.io.IOUtils")),
                        new BundlePlan.SourceEvidence("gson", "source-gson.java", List.of("com.google.gson.Gson"))),
                List.of(), List.of(new BundlePlan.DependencyOperation(workspace.resolve("pom.xml").toString(),
                        BundlePlan.Action.APPEND_EMBEDDED_DEPENDENCY, "grouped-bundling", "com.google.code.gson",
                        "gson", "2.14.0", List.of(), "test")), List.of(), List.of(),
                BundleDependencyAnalyzer.fingerprint(workspace, List.of(workspace)), jiraEvidence(), List.of());
        Path output = workspace.resolve("grouped-plan.json");
        new ObjectMapper().writeValue(output.toFile(), plan);
        return output;
    }

    private JsonNode result(Path workspace, String filename) throws Exception {
        return new ObjectMapper().readTree(workspace.resolve(".migration-state/bundling").resolve(filename).toFile());
    }

    private BundlePlan.ExportEvidence jiraEvidence() {
        OsgiExportCatalog.Selection selection = OsgiExportCatalog.selectBundled(
                PlatformBundlingCliTest.class.getClassLoader(), "jira", "12.0.0-m0204", 25);
        return BundleDependencyPreflight.evidence(selection, "jira", "12.0.0-m0204", 25,
                selection.provenance());
    }

    private void writeExactSnapshot(Path path) throws Exception {
        OsgiExportCatalog.Snapshot snapshot = new OsgiExportCatalog.Snapshot(2, "jira", "12.0.0",
                "12.0.0-m0204", 25, "2026-01-01T00:00:00Z", "fixture", Map.of(),
                List.of(), List.of("com.google.gson"));
        new ObjectMapper().writeValue(path.toFile(), snapshot);
    }
}
