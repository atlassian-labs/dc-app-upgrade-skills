package com.atlassian.dc.p9enablement.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openrewrite.maven.Assertions.pomXml;

/** Covers the packaging instructions that are easy to lose while bundling several libraries together. */
class BundlePackagingInstructionsRecipeTest implements RewriteTest {

    @Override
    public void defaults(final RecipeSpec spec) {
        spec.recipe(new BundlePlatformDependenciesRecipe(List.of(
                        new BundlePlan.DependencyOperation("pom.xml", BundlePlan.Action.REMOVE_IMPORT_EXCLUSION,
                                "commons-codec", "", "org/apache/commons/codec/", "", List.of(), "test"),
                        new BundlePlan.DependencyOperation("pom.xml", BundlePlan.Action.APPEND_OPTIONAL_IMPORT,
                                "jdom2", "", "org.jaxen.*", "", List.of(), "test"),
                        new BundlePlan.DependencyOperation("pom.xml", BundlePlan.Action.ALLOW_BUNDLING,
                                "grouped-bundling", "com.google.code.gson", "gson", "2.14.0", List.of(), "test"))))
                .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void preservesImportHeaderAndCreatesMissingBanningExcludes() {
        rewriteRun(pomXml(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>amps-maven-plugin</artifactId>
                                <configuration>
                                    <instructions>
                                        <Import-Package>!org.apache.commons.codec.*,com.example.api;resolution:=optional,*</Import-Package>
                                    </instructions>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """,
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>amps-maven-plugin</artifactId>
                                <configuration>
                                    <instructions>
                                        <Import-Package>com.example.api;resolution:=optional,org.jaxen.*;resolution:=optional,*</Import-Package>
                                    </instructions>
                                    <banningExcludes>
                                        <banningExclude>com.google.code.gson:gson</banningExclude>
                                    </banningExcludes>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """));
    }

    @Test
    void followsExistingBanningExcludeNameAndKeepsCommentedInstructions() {
        rewriteRun(spec -> spec.recipe(new BundlePlatformDependenciesRecipe(List.of(
                        new BundlePlan.DependencyOperation("pom.xml", BundlePlan.Action.ALLOW_BUNDLING,
                                "grouped-bundling", "com.google.code.gson", "gson", "2.14.0", List.of(), "test")))),
                pomXml(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>amps-maven-plugin</artifactId>
                                <configuration>
                                    <instructions>
                                        <Import-Package>
                                            <!-- Product imports -->
                                            com.example.api;resolution:=optional,
                                            <!-- Everything else -->
                                            *
                                        </Import-Package>
                                    </instructions>
                                    <banningExcludes>
                                        <exclude>com.example:library</exclude>
                                    </banningExcludes>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """,
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>amps-maven-plugin</artifactId>
                                <configuration>
                                    <instructions>
                                        <Import-Package>
                                            <!-- Product imports -->
                                            com.example.api;resolution:=optional,
                                            <!-- Everything else -->
                                            *
                                        </Import-Package>
                                    </instructions>
                                    <banningExcludes>
                                        <exclude>com.example:library</exclude>
                                        <exclude>com.google.code.gson:gson</exclude>
                                    </banningExcludes>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """));
    }

    @Test
    void preservesCommentedImportClausesWhenUpdatingSelectedImports() {
        rewriteRun(spec -> spec.cycles(2), pomXml(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>amps-maven-plugin</artifactId>
                                <configuration>
                                    <instructions>
                                        <Import-Package>
                                            !org.apache.commons.codec.*,com.example.api;version="[1,2)",
                                            <!-- Product imports stay required -->
                                            <!-- Keep the catch-all mandatory -->
                                            *
                                        </Import-Package>
                                    </instructions>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """,
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>amps-maven-plugin</artifactId>
                                <configuration>
                                    <instructions>
                                        <Import-Package>
                                            com.example.api;version="[1,2)",
                                            <!-- Product imports stay required -->
                                            <!-- Keep the catch-all mandatory -->
                                            org.jaxen.*;resolution:=optional,*
                                        </Import-Package>
                                    </instructions>
                                    <banningExcludes>
                                        <banningExclude>com.google.code.gson:gson</banningExclude>
                                    </banningExcludes>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            a;version="[1,2)"<!-- keep -->,c | a;version="[1,2)",c,org.jaxen.*;resolution:=optional
            <!-- keep --> | org.jaxen.*;resolution:=optional
            """, delimiter = '|')
    void appendsOptionalImportWithoutAnExistingClauseOrWildcard(
            String original, String expected, @TempDir Path workspace) throws Exception {
        assertImportMigration(workspace, original, BundlePlan.Action.APPEND_OPTIONAL_IMPORT,
                "org.jaxen.*", expected);
    }

    @Test
    void appendsOptionalImportBeforeWildcardWithACommentedFollowingClause(@TempDir Path workspace)
            throws Exception {
        assertImportMigration(workspace, "*,<!-- keep -->com.example.api",
                BundlePlan.Action.APPEND_OPTIONAL_IMPORT, "org.jaxen.*",
                "org.jaxen.*;resolution:=optional,*,com.example.api");
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            a<!--c-->,!org.apache.commons.codec.*<!--d-->,c | a,c
            !org.apache.commons.codec.*<!--c-->,a<!--d-->,c | a,c
            a<!--c-->,c<!--d-->,!org.apache.commons.codec.* | a,c
            a,<!--c-->!org.apache.commons.codec.*<!--d-->,c | a,c
            !org.apache.commons.codec.*<!--c-->,!org.apache.commons.codec.* | ''
            """, delimiter = '|')
    void removesExclusionsWithoutLeavingEmptyClauses(
            String original, String expected, @TempDir Path workspace) throws Exception {
        assertImportMigration(workspace, original, BundlePlan.Action.REMOVE_IMPORT_EXCLUSION,
                "org/apache/commons/codec/", expected);
    }

    @Test
    void createsOptionalImportForEmptyImportPackage(@TempDir Path workspace) throws Exception {
        assertImportMigration(workspace, "", BundlePlan.Action.APPEND_OPTIONAL_IMPORT,
                "org.jaxen.*", "org.jaxen.*;resolution:=optional");
    }

    @Test
    void rejectsCommentsThatSplitAnImportClause() {
        AssertionError failure = assertThrows(AssertionError.class, () -> rewriteRun(pomXml(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>amps-maven-plugin</artifactId>
                                <configuration><instructions>
                                    <Import-Package>com.example.api; <!-- split clause --> version="[1,2)",*</Import-Package>
                                </instructions></configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """)));

        assertEquals("Cannot safely migrate Import-Package when an XML comment splits an OSGi clause",
                rootCauseMessage(failure));
    }

    @Test
    void rejectsCommentsThatSplitAQuotedImportValue() {
        AssertionError failure = assertThrows(AssertionError.class, () -> rewriteRun(pomXml(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>amps-maven-plugin</artifactId>
                                <configuration><instructions>
                                    <Import-Package>org.jaxen.*;version="[1,<!-- split value -->2)",*</Import-Package>
                                </instructions></configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """)));

        assertEquals("Cannot safely migrate Import-Package when an XML comment splits a quoted OSGi value",
                rootCauseMessage(failure));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            org.jaxen.*;version="[1.0,2.0)";resolution:="optional",* | org.jaxen.*;version="[1.0,2.0)";resolution:="optional",*
            org.jaxen.*;version="[1.0,2.0)";resolution:="mandatory",* | org.jaxen.*;version="[1.0,2.0)";resolution:=optional,*
            """, delimiter = '|')
    void handlesQuotedResolutionDirectivesWithoutDuplicatingThem(
            String original, String expected, @TempDir Path workspace) throws Exception {
        assertImportMigration(workspace, original, BundlePlan.Action.APPEND_OPTIONAL_IMPORT,
                "org.jaxen.*", expected);
    }

    @Test
    void createsInstructionsAndImportPackageForLocalAmpsPlugins() {
        rewriteRun(pomXml(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>jira-maven-plugin</artifactId>
                            </plugin>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>confluence-maven-plugin</artifactId>
                                <configuration>
                                    <instructions>
                                        <Embedded-Dependencies>common</Embedded-Dependencies>
                                    </instructions>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """,
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>jira-maven-plugin</artifactId>
                                <configuration>
                                    <instructions>
                                        <Import-Package>org.jaxen.*;resolution:=optional,*</Import-Package>
                                    </instructions>
                                    <banningExcludes>
                                        <banningExclude>com.google.code.gson:gson</banningExclude>
                                    </banningExcludes>
                                </configuration>
                            </plugin>
                            <plugin>
                                <groupId>com.atlassian.maven.plugins</groupId>
                                <artifactId>confluence-maven-plugin</artifactId>
                                <configuration>
                                    <instructions>
                                        <Embedded-Dependencies>common</Embedded-Dependencies>
                                        <Import-Package>org.jaxen.*;resolution:=optional,*</Import-Package>
                                    </instructions>
                                    <banningExcludes>
                                        <banningExclude>com.google.code.gson:gson</banningExclude>
                                    </banningExcludes>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """));
    }

    private static void assertImportMigration(
            Path workspace, String original, BundlePlan.Action action, String pattern, String expected)
            throws Exception {
        Path pom = workspace.resolve("pom.xml");
        String instruction = original.isEmpty()
                ? "<Import-Package/>" : "<Import-Package>" + original + "</Import-Package>";

        Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <build><plugins><plugin>
                        <groupId>com.atlassian.maven.plugins</groupId>
                        <artifactId>amps-maven-plugin</artifactId>
                        <configuration><instructions>%s</instructions></configuration>
                    </plugin></plugins></build>
                </project>
                """.formatted(instruction));

        List<BundlePlan.DependencyOperation> operations = List.of(new BundlePlan.DependencyOperation(
                pom.toString(), action, "test", "", pattern, "", List.of(), "test"));

        BundleRecipeRunner.apply(operations);
        String migrated = Files.readString(pom);
        String imports = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile())
                .getElementsByTagName("Import-Package").item(0).getTextContent().replaceAll("\\s+", "");

        assertEquals(expected, imports, original);

        java.util.regex.Matcher comments = java.util.regex.Pattern.compile("<!--.*?-->").matcher(original);
        while (comments.find()) {
            assertTrue(migrated.contains(comments.group()), comments.group());
        }

        assertTrue(BundleRecipeRunner.apply(operations).isEmpty(), "Second apply must not change the POM");
        assertEquals(migrated, Files.readString(pom));
    }

    private static String rootCauseMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause.getMessage();
    }
}
