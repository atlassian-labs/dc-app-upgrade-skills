package com.atlassian.dc.p9enablement.migrate;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.TypeValidation;

import java.util.List;

import static org.openrewrite.maven.Assertions.pomXml;

/** Verifies that several family decisions are applied in one POM transformation. */
class BundlePlatformDependenciesRecipeTest implements RewriteTest {

    private static final String POM = "pom.xml";

    @Override
    public void defaults(final RecipeSpec spec) {
        spec.recipe(new BundlePlatformDependenciesRecipe(List.of(
                        operation(BundlePlan.Action.ADD_DEPENDENCY, "gson", "com.google.code.gson", "gson",
                                "2.14.0", List.of(new BundleDependencyModel.Exclusion(
                                        "com.google.errorprone", "error_prone_annotations"))),
                        operation(BundlePlan.Action.SET_COMPILE_SCOPE, "jdom2", "org.jdom", "jdom2",
                                "2.0.6.1", List.of()))))
                .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void appliesMultipleDependencyFamiliesTogether() {
        rewriteRun(pomXml(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.jdom</groupId>
                            <artifactId>jdom2</artifactId>
                            <version>2.0.6.1</version>
                            <scope>provided</scope>
                        </dependency>
                    </dependencies>
                    <properties>
                        <Embedded-Dependencies>common</Embedded-Dependencies>
                    </properties>
                </project>
                """,
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.jdom</groupId>
                            <artifactId>jdom2</artifactId>
                            <version>2.0.6.1</version>
                            <scope>compile</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.google.code.gson</groupId>
                            <artifactId>gson</artifactId>
                            <version>2.14.0</version>
                            <exclusions>
                                <exclusion>
                                    <groupId>com.google.errorprone</groupId>
                                    <artifactId>error_prone_annotations</artifactId>
                                </exclusion>
                            </exclusions>
                        </dependency>
                    </dependencies>
                    <properties>
                        <Embedded-Dependencies>common</Embedded-Dependencies>
                    </properties>
                </project>
                """));
    }

    @Test
    void keepsScopeGroupsAndCanonicalDependencyElementOrder() {
        rewriteRun(pomXml(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.jdom</groupId>
                                <artifactId>jdom2</artifactId>
                                <version>2.0.6.1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>commons-io</groupId>
                            <artifactId>commons-io</artifactId>
                            <version>2.16.1</version>
                        </dependency>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.13</version>
                        </dependency>

                        <dependency>
                            <groupId>javax.inject</groupId>
                            <artifactId>javax.inject</artifactId>
                            <version>1</version>
                            <scope>provided</scope>
                        </dependency>
                        <!-- XML parsing -->
                        <dependency>
                            <groupId>org.jdom</groupId>
                            <artifactId>jdom2</artifactId>
                            <scope>provided</scope>
                        </dependency>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                            <version>4.13.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.jdom</groupId>
                                <artifactId>jdom2</artifactId>
                                <version>2.0.6.1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.code.gson</groupId>
                            <artifactId>gson</artifactId>
                            <version>2.14.0</version>
                            <exclusions>
                                <exclusion>
                                    <groupId>com.google.errorprone</groupId>
                                    <artifactId>error_prone_annotations</artifactId>
                                </exclusion>
                            </exclusions>
                        </dependency>
                        <dependency>
                            <groupId>commons-io</groupId>
                            <artifactId>commons-io</artifactId>
                            <version>2.16.1</version>
                        </dependency>
                        <!-- XML parsing -->
                        <dependency>
                            <groupId>org.jdom</groupId>
                            <artifactId>jdom2</artifactId>
                            <version>2.0.6.1</version>
                            <scope>compile</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.13</version>
                        </dependency>

                        <dependency>
                            <groupId>javax.inject</groupId>
                            <artifactId>javax.inject</artifactId>
                            <version>1</version>
                            <scope>provided</scope>
                        </dependency>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                            <version>4.13.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """));
    }

    private static BundlePlan.DependencyOperation operation(final BundlePlan.Action action, final String family,
                                                            final String groupId, final String artifactId,
                                                            final String version,
                                                            final List<BundleDependencyModel.Exclusion> exclusions) {
        return new BundlePlan.DependencyOperation(POM, action, family, groupId, artifactId, version,
                exclusions, "test");
    }
}
