package com.atlassian.dc.p9enablement.migrate;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.xml.XmlParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Runs the composite recipe against the exact POM files listed by a plan. */
public final class BundleRecipeRunner {

    private BundleRecipeRunner() {
    }

    public static List<String> apply(final List<BundlePlan.DependencyOperation> operations) throws IOException {
        if (operations.isEmpty()) {
            return List.of();
        }
        ExecutionContext context = new InMemoryExecutionContext(throwable -> {
            throw new IllegalStateException("OpenRewrite failed while applying the bundle plan", throwable);
        });
        List<Parser.Input> inputs = operations.stream().map(BundlePlan.DependencyOperation::pom).distinct()
                .map(path -> Parser.Input.fromFile(Path.of(path))).toList();
        Path workspace = Path.of(operations.get(0).pom()).toAbsolutePath().normalize().getParent();
        List<SourceFile> sourceFiles = XmlParser.builder().build().parseInputs(inputs, workspace, context)
                .map(source -> (SourceFile) source.withSourcePath(workspace.resolve(source.getSourcePath()).normalize()))
                .toList();
        if (sourceFiles.isEmpty()) {
            throw new IOException("XML parser produced no POM source files for the bundle plan");
        }
        BundlePlatformDependenciesRecipe recipe = new BundlePlatformDependenciesRecipe(operations);
        RecipeRun recipeRun = recipe.run(new InMemoryLargeSourceSet(sourceFiles), context);
        List<Result> results = recipeRun.getChangeset().getAllResults();
        java.util.Set<Path> permitted = operations.stream().map(BundlePlan.DependencyOperation::pom)
                .map(Path::of).map(path -> path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toSet());
        List<SourceFile> changed = results.stream().map(Result::getAfter)
                .filter(java.util.Objects::nonNull).toList();

        for (SourceFile source : changed) {
            if (!permitted.contains(source.getSourcePath().toAbsolutePath().normalize())) {
                throw new IOException("Recipe returned an unexpected POM path: " + source.getSourcePath());
            }
        }

        for (SourceFile source : changed) {
            Files.writeString(source.getSourcePath(), source.printAll(), StandardCharsets.UTF_8);
        }

        return changed.stream().map(source -> source.getSourcePath().toString()).toList();
    }
}
