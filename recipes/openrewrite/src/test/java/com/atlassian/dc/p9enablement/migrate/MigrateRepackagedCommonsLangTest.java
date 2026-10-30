package com.atlassian.dc.p9enablement.migrate;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Validates {@code commons-lang.yml} against representative Commons Lang usage from three
 * different sub-packages ({@code .lang}, {@code .builder}, {@code .math}) to prove the
 * {@code recursive: true} package rename handles nested packages correctly, not just the
 * top-level one.
 */
class MigrateRepackagedCommonsLangTest implements RewriteTest {

    private static final JavaProject PROJECT = new JavaProject(
            UUID.randomUUID(), "commons-lang-test", null);
    private static final JavaSourceSet MAIN_SOURCE_SET = new JavaSourceSet(
            UUID.randomUUID(), "main", List.of(), Map.of());

    @Override
    public void defaults(final RecipeSpec spec) {
        spec.recipeFromResource(
                        "/META-INF/rewrite/commons-lang.yml",
                        "com.atlassian.dc.p9enablement.migrate.MigrateRepackagedCommonsLang")
                .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void addsCommonsLangDependency() {
        rewriteRun(
                pomXml(
                        """
                        <project xmlns="http://maven.apache.org/POM/4.0.0"
                                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>legacy-plugin</artifactId>
                            <version>1.0.0</version>
                            <dependencies>
                                <dependency>
                                    <groupId>com.atlassian.jira</groupId>
                                    <artifactId>jira-api</artifactId>
                                    <version>11.3.7</version>
                                    <scope>provided</scope>
                                </dependency>
                            </dependencies>
                        </project>
                        """,
                        """
                        <project xmlns="http://maven.apache.org/POM/4.0.0"
                                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>legacy-plugin</artifactId>
                            <version>1.0.0</version>
                            <dependencies>
                                <dependency>
                                    <groupId>commons-lang</groupId>
                                    <artifactId>commons-lang</artifactId>
                                    <version>2.6</version>
                                </dependency>
                                <dependency>
                                    <groupId>com.atlassian.jira</groupId>
                                    <artifactId>jira-api</artifactId>
                                    <version>11.3.7</version>
                                    <scope>provided</scope>
                                </dependency>
                            </dependencies>
                        </project>
                        """,
                        spec -> spec.markers(PROJECT)),
                java(
                        """
                        package com.atlassian.gzipfilter.org.apache.commons.lang;

                        public class StringUtils {
                            public static boolean isBlank(String value) {
                                return value == null || value.isBlank();
                            }
                        }
                        """,
                        """
                        package org.apache.commons.lang;

                        public class StringUtils {
                            public static boolean isBlank(String value) {
                                return value == null || value.isBlank();
                            }
                        }
                        """,
                        spec -> spec.markers(PROJECT, MAIN_SOURCE_SET)),
                java(
                        """
                        package com.example;

                        import com.atlassian.gzipfilter.org.apache.commons.lang.StringUtils;

                        class LegacyUsage {
                            boolean blank(String value) {
                                return StringUtils.isBlank(value);
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.apache.commons.lang.StringUtils;

                        class LegacyUsage {
                            boolean blank(String value) {
                                return StringUtils.isBlank(value);
                            }
                        }
                        """,
                        spec -> spec.markers(PROJECT, MAIN_SOURCE_SET)));
    }

    @Test
    void leavesUnrelatedPomAlone() {
        rewriteRun(
                pomXml(
                        """
                        <project xmlns="http://maven.apache.org/POM/4.0.0"
                                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>com.example</groupId>
                            <artifactId>unrelated-plugin</artifactId>
                            <version>1.0.0</version>
                        </project>
                        """,
                        spec -> spec.markers(PROJECT)));
    }

    @Test
    void rewritesTopLevelStringUtilsUsage() {
        rewriteRun(
                java(
                        """
                        package com.atlassian.dc.p9enablement.migrate.jira.commonslang;

                        import com.atlassian.gzipfilter.org.apache.commons.lang.StringUtils;

                        public class LegacyCommonsLangUsage {
                            public boolean isBlankOrNotBlank(final String value) {
                                return StringUtils.isBlank(value) || StringUtils.isNotBlank(value);
                            }
                        }
                        """,
                        """
                        package com.atlassian.dc.p9enablement.migrate.jira.commonslang;

                        import org.apache.commons.lang.StringUtils;

                        public class LegacyCommonsLangUsage {
                            public boolean isBlankOrNotBlank(final String value) {
                                return StringUtils.isBlank(value) || StringUtils.isNotBlank(value);
                            }
                        }
                        """));
    }

    @Test
    void rewritesNestedBuilderPackageUsage() {
        rewriteRun(
                java(
                        """
                        package com.atlassian.dc.p9enablement.migrate.jira.commonslang;

                        import com.atlassian.gzipfilter.org.apache.commons.lang.builder.EqualsBuilder;

                        public class LegacyCommonsLangUsage {
                            public boolean equalsBuilderAppend(final Object a, final Object b) {
                                return new EqualsBuilder().append(a, b).isEquals();
                            }
                        }
                        """,
                        """
                        package com.atlassian.dc.p9enablement.migrate.jira.commonslang;

                        import org.apache.commons.lang.builder.EqualsBuilder;

                        public class LegacyCommonsLangUsage {
                            public boolean equalsBuilderAppend(final Object a, final Object b) {
                                return new EqualsBuilder().append(a, b).isEquals();
                            }
                        }
                        """));
    }

    @Test
    void rewritesNestedMathPackageUsage() {
        rewriteRun(
                java(
                        """
                        package com.atlassian.dc.p9enablement.migrate.jira.commonslang;

                        import com.atlassian.gzipfilter.org.apache.commons.lang.math.NumberUtils;

                        public class LegacyCommonsLangUsage {
                            public boolean isNumberValue(final String value) {
                                return NumberUtils.isNumber(value);
                            }
                        }
                        """,
                        """
                        package com.atlassian.dc.p9enablement.migrate.jira.commonslang;

                        import org.apache.commons.lang.math.NumberUtils;

                        public class LegacyCommonsLangUsage {
                            public boolean isNumberValue(final String value) {
                                return NumberUtils.isNumber(value);
                            }
                        }
                        """));
    }
}
