package com.atlassian.dc.p9enablement.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the same XML parser and composite recipe path used by the CLI. */
class BundleRecipeRunnerTest {

    @Test
    void appliesAPlanToAnOrdinaryPom(@TempDir final Path workspace) throws Exception {
        Path pom = workspace.resolve("pom.xml");
        Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <dependencies/>
                </project>
                """);
        BundlePlan.DependencyOperation operation = new BundlePlan.DependencyOperation(
                pom.toString(), BundlePlan.Action.ADD_DEPENDENCY, "gson", "com.google.code.gson", "gson",
                "2.14.0", List.of(new BundleDependencyModel.Exclusion(
                        "com.google.errorprone", "error_prone_annotations")), "test");

        List<String> changed = BundleRecipeRunner.apply(List.of(operation));

        String result = Files.readString(pom);
        assertTrue(changed.contains(pom.toString()));
        assertTrue(result.contains("<artifactId>gson</artifactId>"));
        assertTrue(result.contains("error_prone_annotations"));
    }

    @Test
    void appliesSiblingModuleOperationsSafelyAndIsIdempotent(@TempDir final Path workspace) throws Exception {
        Path firstPom = writeModulePom(workspace.resolve("first"));
        Path secondPom = writeModulePom(workspace.resolve("second"));
        List<BundlePlan.DependencyOperation> operations = List.of(
                addDependencyOperation(firstPom),
                addDependencyOperation(secondPom));

        List<String> firstChanged = BundleRecipeRunner.apply(operations);
        List<String> secondChanged = BundleRecipeRunner.apply(operations);

        assertEquals(List.of(firstPom.toString(), secondPom.toString()), firstChanged);
        assertEquals(List.of(), secondChanged);
        assertTrue(Files.readString(firstPom).contains("<artifactId>gson</artifactId>"));
        assertTrue(Files.readString(secondPom).contains("<artifactId>gson</artifactId>"));
    }

    private static Path writeModulePom(final Path module) throws Exception {
        Files.createDirectories(module);
        Path pom = module.resolve("pom.xml");
        Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>plugin</artifactId>
                    <version>1</version>
                    <dependencies/>
                </project>
                """);
        return pom;
    }

    private static BundlePlan.DependencyOperation addDependencyOperation(final Path pom) {
        return new BundlePlan.DependencyOperation(
                pom.toString(), BundlePlan.Action.ADD_DEPENDENCY, "gson", "com.google.code.gson", "gson",
                "2.14.0", List.of(new BundleDependencyModel.Exclusion(
                        "com.google.errorprone", "error_prone_annotations")), "test");
    }
}
