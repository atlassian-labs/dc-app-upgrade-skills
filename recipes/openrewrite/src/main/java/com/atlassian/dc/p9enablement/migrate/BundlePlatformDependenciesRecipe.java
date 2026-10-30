package com.atlassian.dc.p9enablement.migrate;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.atlassian.dc.p9enablement.migrate.BundlePlan.Action;
import com.atlassian.dc.p9enablement.migrate.BundlePlan.DependencyOperation;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Applies an approved group of dependency and AMPS edits in one OpenRewrite run. */
public final class BundlePlatformDependenciesRecipe extends Recipe {
    /** Maven's canonical dependency element order, which POM formatters such as sortpom enforce. */
    private static final List<String> DEPENDENCY_ELEMENTS = List.of(
            "groupId", "artifactId", "version", "type", "classifier", "scope", "systemPath", "optional", "exclusions");

    private final List<DependencyOperation> operations;

    @JsonCreator
    public BundlePlatformDependenciesRecipe(@JsonProperty("operations") List<DependencyOperation> operations) {
        this.operations = List.copyOf(operations == null ? List.of() : operations);
    }

    @JsonProperty("operations")
    public List<DependencyOperation> getOperations() {
        return operations;
    }

    @Override
    public String getDisplayName() {
        return "Bundle platform dependencies";
    }

    @Override
    public String getDescription() {
        return "Applies the approved platform dependency plan while preserving unrelated Maven configuration.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new XmlIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext context) {
                Path source = document.getSourcePath().toAbsolutePath().normalize();
                List<DependencyOperation> selected = operations.stream()
                        .filter(operation -> Path.of(operation.pom()).toAbsolutePath().normalize().equals(source)).toList();

                if (selected.isEmpty()) {
                    return document;
                }

                Xml.Tag root = migrateDependencies(document.getRoot(), selected);
                Xml.Tag build = root.getChild("build").orElse(null);
                if (build != null) {
                    Xml.Tag plugins = build.getChild("plugins").orElse(null);
                    if (plugins != null) {
                        Xml.Tag updated = mapChildren(plugins, "plugin", plugin -> migratePlugin(plugin, selected));
                        root = replaceChild(root, replaceChild(build, updated));
                    }
                }

                Xml.Document updated = document.withRoot(root);
                if (updated == document) {
                    return document;
                }

                // Auto-format indents inserted elements, but it also starts a new line before every element or
                // comment that follows text, such as comments inside Import-Package, leaving whitespace-only lines.
                Map<UUID, String> mixedPrefixes = mixedContentPrefixes(updated);
                Xml formatted = autoFormat(updated, context);
                return (Xml.Document) new XmlIsoVisitor<Integer>() {
                    @Override
                    public Xml.Tag visitTag(Xml.Tag tag, Integer unused) {
                        Xml.Tag visited = super.visitTag(tag, unused);
                        if (visited.getContent() != null) {
                            visited = visited.withContent(visited.getContent().stream()
                                    .map(item -> mixedPrefixes.containsKey(item.getId())
                                            ? (Content) item.withPrefix(mixedPrefixes.get(item.getId())) : item)
                                    .toList());
                        }
                        if (visited.getClosing() != null && mixedPrefixes.containsKey(visited.getClosing().getId())) {
                            visited = visited.withClosing(visited.getClosing()
                                    .withPrefix(mixedPrefixes.get(visited.getClosing().getId())));
                        }
                        return visited;
                    }
                }.visitNonNull(formatted, 0);
            }
        };
    }

    /** Original prefixes of items and closing tags that follow text content. */
    private static Map<UUID, String> mixedContentPrefixes(Xml.Document document) {
        Map<UUID, String> prefixes = new HashMap<>();
        new XmlIsoVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer unused) {
                List<? extends Content> content = tag.getContent();
                if (content != null) {
                    for (int index = 1; index < content.size(); index++) {
                        if (content.get(index - 1) instanceof Xml.CharData) {
                            prefixes.put(content.get(index).getId(), content.get(index).getPrefix());
                        }
                    }
                    if (!content.isEmpty() && content.get(content.size() - 1) instanceof Xml.CharData
                            && tag.getClosing() != null) {
                        prefixes.put(tag.getClosing().getId(), tag.getClosing().getPrefix());
                    }
                }
                return super.visitTag(tag, unused);
            }
        }.visit(document, 0);
        return prefixes;
    }

    private static Xml.Tag migrateDependencies(Xml.Tag root, List<DependencyOperation> selected) {
        List<DependencyOperation> dependencyOperations = selected.stream()
                .filter(operation -> Set.of(Action.ADD_DEPENDENCY, Action.SET_COMPILE_SCOPE, Action.ADD_EXCLUSIONS)
                        .contains(operation.action())).toList();

        if (dependencyOperations.isEmpty()) {
            return root;
        }

        Xml.Tag dependencies = root.getChild("dependencies").orElse(Xml.Tag.build("<dependencies/>"));
        // Keep an existing grouping by scope, as enforced by POM sorters; otherwise append and edit in place.
        boolean grouped = scopeGrouped(dependencies);
        for (DependencyOperation operation : dependencyOperations) {
            Xml.Tag dependency = dependencies.getChildren("dependency").stream()
                    .filter(tag -> value(tag, "groupId").equals(operation.groupId())
                            && value(tag, "artifactId").equals(operation.artifactId()))
                    .findFirst().orElse(null);

            if (dependency == null) {
                if (operation.action() != Action.ADD_DEPENDENCY) {
                    throw new IllegalArgumentException("Dependency disappeared from approved POM: " + operation.coordinate());
                }

                dependency = Xml.Tag.build("<dependency/>");
                dependency = setDependencyChild(dependency, "groupId", operation.groupId());
                dependency = setDependencyChild(dependency, "artifactId", operation.artifactId());
                dependency = setDependencyChild(dependency, "version", operation.version());
                dependency = mergeExclusions(dependency, operation.exclusions());
                dependency = dependency.withPrefix(childPrefix(dependencies));
                dependencies = grouped ? insertCompileDependency(dependencies, List.of(dependency))
                        : dependencies.withContent(append(dependencies.getContent(), dependency));
            } else {
                Xml.Tag updated = dependency;
                boolean scopeChanged = false;
                if (operation.action() == Action.SET_COMPILE_SCOPE) {
                    scopeChanged = !scope(updated).equals("compile");
                    updated = setDependencyChild(updated, "scope", "compile");
                    updated = setDependencyChild(updated, "version", operation.version());
                }

                updated = mergeExclusions(updated, operation.exclusions());
                dependencies = grouped && scopeChanged ? moveToCompileGroup(dependencies, dependency, updated)
                        : replaceIdentity(dependencies, dependency, updated);
            }
        }

        return replaceChild(root, dependencies);
    }

    private static List<Content> append(List<? extends Content> content, Content item) {
        List<Content> result = new ArrayList<>(content == null ? List.of() : content);
        result.add(item);
        return result;
    }

    /** Whether each scope's dependencies are contiguous, with at least two groups or only compile dependencies. */
    private static boolean scopeGrouped(Xml.Tag dependencies) {
        List<String> runs = new ArrayList<>();
        for (Xml.Tag dependency : dependencies.getChildren("dependency")) {
            if (runs.isEmpty() || !runs.get(runs.size() - 1).equals(scope(dependency))) {
                runs.add(scope(dependency));
            }
        }

        return runs.size() == new LinkedHashSet<>(runs).size() && (runs.size() > 1 || runs.contains("compile"));
    }

    private static String scope(Xml.Tag dependency) {
        String scope = value(dependency, "scope").trim();
        return scope.isEmpty() ? "compile" : scope;
    }

    /** Moves a dependency that became compile scope, with its leading comments, into an existing compile group. */
    private static Xml.Tag moveToCompileGroup(Xml.Tag dependencies, Xml.Tag original, Xml.Tag updated) {
        List<Content> contents = new ArrayList<>(dependencies.getContent());
        int end = contents.indexOf(original);
        int start = leadingComments(contents, end);
        List<Content> moved = new ArrayList<>(contents.subList(start, end));
        moved.add(updated);
        contents.subList(start, end + 1).clear();

        return insertCompileDependency(dependencies.withContent(contents), moved);
    }

    /**
     * Inserts compile-scope dependency content into a scope-grouped list: at its sorted position within a sorted
     * compile group, after an unsorted compile group, or before the first group when none is compile.
     */
    private static Xml.Tag insertCompileDependency(Xml.Tag dependencies, List<Content> items) {
        List<Content> contents = new ArrayList<>(dependencies.getContent() == null ? List.of() : dependencies.getContent());
        contents.addAll(compileInsertionIndex(contents, (Xml.Tag) items.get(items.size() - 1)), items);
        return dependencies.withContent(contents);
    }

    private static int compileInsertionIndex(List<Content> contents, Xml.Tag dependency) {
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < contents.size(); index++) {
            if (contents.get(index) instanceof Xml.Tag tag && tag.getName().equals("dependency")) {
                positions.add(index);
            }
        }
        if (positions.isEmpty()) {
            return contents.size();
        }

        List<Xml.Tag> compile = positions.stream().map(index -> (Xml.Tag) contents.get(index))
                .filter(tag -> scope(tag).equals("compile")).toList();
        if (compile.isEmpty()) {
            return leadingComments(contents, positions.get(0));
        }

        Comparator<Xml.Tag> coordinates = Comparator.comparing((Xml.Tag tag) -> value(tag, "groupId"))
                .thenComparing(tag -> value(tag, "artifactId"));
        boolean sorted = compile.size() > 1;
        for (int index = 1; index < compile.size() && sorted; index++) {
            sorted = coordinates.compare(compile.get(index - 1), compile.get(index)) <= 0;
        }
        if (sorted) {
            for (Xml.Tag existing : compile) {
                if (coordinates.compare(dependency, existing) < 0) {
                    return leadingComments(contents, contents.indexOf(existing));
                }
            }
        }

        return contents.indexOf(compile.get(compile.size() - 1)) + 1;
    }

    private static int leadingComments(List<? extends Content> contents, int index) {
        while (index > 0 && contents.get(index - 1) instanceof Xml.Comment) {
            index--;
        }
        return index;
    }

    /** Sets a dependency child, inserting a missing element in Maven's canonical dependency element order. */
    private static Xml.Tag setDependencyChild(Xml.Tag dependency, String name, String value) {
        if (dependency.getChild(name).isPresent()) {
            return setChild(dependency, name, value);
        }

        List<Content> contents = new ArrayList<>(dependency.getContent() == null ? List.of() : dependency.getContent());
        int rank = DEPENDENCY_ELEMENTS.indexOf(name);
        int position = 0;
        for (int index = 0; index < contents.size(); index++) {
            if (contents.get(index) instanceof Xml.Tag tag) {
                int existing = DEPENDENCY_ELEMENTS.indexOf(tag.getName());
                if (existing >= 0 && existing < rank) {
                    position = index + 1;
                }
            }
        }

        contents.add(position, Xml.Tag.build("<" + name + ">" + text(value) + "</" + name + ">")
                .withPrefix(childPrefix(dependency)));
        return dependency.withContent(contents);
    }

    /** The line break and indentation of the first child, without any blank lines before it. */
    private static String childPrefix(Xml.Tag parent) {
        String prefix = parent.getChildren().stream().findFirst().map(Xml.Tag::getPrefix).orElse("\n    ");
        int newline = prefix.lastIndexOf('\n');
        return newline < 0 ? prefix : prefix.substring(newline);
    }

    private static Xml.Tag migratePlugin(Xml.Tag plugin, List<DependencyOperation> selected) {
        if (!value(plugin, "artifactId").matches("(jira|confluence|bitbucket|bamboo|crowd|amps)-maven-plugin")
                || !value(plugin, "groupId").equals("com.atlassian.maven.plugins")) {
            return plugin;
        }

        Xml.Tag configuration = plugin.getChild("configuration").orElse(Xml.Tag.build("<configuration/>"));
        Xml.Tag instructions = configuration.getChild("instructions").orElse(null);
        boolean hasOptionalImportOperations = selected.stream()
                .anyMatch(operation -> operation.action() == Action.APPEND_OPTIONAL_IMPORT);

        if (instructions == null && hasOptionalImportOperations) {
            instructions = Xml.Tag.build("<instructions/>");
        }
        if (instructions != null) {
            Xml.Tag embedded = instructions.getChild("Embedded-Dependencies").orElse(null);
            List<DependencyOperation> embeddedOperations = selected.stream()
                    .filter(operation -> operation.action() == Action.APPEND_EMBEDDED_DEPENDENCY)
                    .toList();
            if (embedded != null && !embeddedOperations.isEmpty()) {
                List<String> entries = new ArrayList<>(BundlingArtifactVerifier.splitHeader(embedded.getValue().orElse(""), ','));
                for (DependencyOperation operation : embeddedOperations) {
                    if (!entries.contains(operation.artifactId())) {
                        entries.add(operation.artifactId());
                    }
                }

                String updatedEmbedded = String.join(",", entries);
                if (!updatedEmbedded.equals(embedded.getValue().orElse(""))) {
                    instructions = replaceChild(instructions, embedded.withValue(updatedEmbedded));
                }
            }

            List<DependencyOperation> importOperations = selected.stream()
                    .filter(operation -> operation.action() == Action.APPEND_OPTIONAL_IMPORT
                            || operation.action() == Action.REMOVE_IMPORT_EXCLUSION)
                    .toList();
            Xml.Tag imports = instructions.getChild("Import-Package").orElse(null);
            if (imports == null && hasOptionalImportOperations) {
                // Preserve default mandatory resolution; migrateImports makes only the selected packages optional.
                imports = Xml.Tag.build("<Import-Package>*</Import-Package>");
            }
            if (imports != null && !importOperations.isEmpty()) {
                instructions = replaceChild(instructions, migrateImportTag(imports, importOperations));
            }

            configuration = replaceChild(configuration, instructions);
        }

        List<DependencyOperation> banning = selected.stream().filter(operation -> operation.action() == Action.ALLOW_BUNDLING).toList();
        if (!banning.isEmpty()) {
            Xml.Tag excludes = configuration.getChild("banningExcludes").orElse(Xml.Tag.build("<banningExcludes/>"));
            // Maven accepts any list item name; follow the one the POM already uses.
            String item = excludes.getChildren().stream().findFirst().map(Xml.Tag::getName).orElse("banningExclude");
            for (DependencyOperation operation : banning) {
                boolean exists = excludes.getChildren().stream()
                        .anyMatch(tag -> tag.getValue().orElse("").trim().equals(operation.coordinate()));

                if (!exists) {
                    excludes = appendChild(excludes, Xml.Tag.build("<" + item + ">" + text(operation.coordinate()) + "</" + item + ">"));
                }
            }

            configuration = replaceChild(configuration, excludes);
        }

        return configuration.getContent() == null ? plugin : replaceChild(plugin, configuration);
    }

    /** Shared by discovery and application so plans contain only effective import changes. */
    public static String migrateImports(String original, List<DependencyOperation> selected) {
        List<String> entries = new ArrayList<>(BundlingArtifactVerifier.splitHeader(original, ','));
        boolean changed = entries.removeIf(entry -> entry.startsWith("!") && selected.stream()
                .filter(operation -> operation.action() == Action.REMOVE_IMPORT_EXCLUSION)
                .anyMatch(operation -> {
                    String excluded = entry.substring(1);
                    String prefix = operation.artifactId().replace('/', '.');
                    if (prefix.endsWith(".")) {
                        prefix = prefix.substring(0, prefix.length() - 1);
                    }
                    return excluded.equals(prefix) || excluded.equals(prefix + ".*") || excluded.startsWith(prefix + ".");
                }));

        for (DependencyOperation operation : selected) {
            if (operation.action() != Action.APPEND_OPTIONAL_IMPORT) {
                continue;
            }

            String pattern = operation.artifactId();
            String clause = pattern + ";resolution:=optional";
            int exact = -1;
            int wildcard = entries.size();
            for (int index = 0; index < entries.size(); index++) {
                String name = BundlingArtifactVerifier.splitHeader(entries.get(index), ';').get(0);
                if (name.equals(pattern)) {
                    exact = index;
                    break;
                }

                if (name.equals("*") && wildcard == entries.size()) {
                    wildcard = index;
                }
            }

            if (exact >= 0) {
                String existing = entries.get(exact);
                if (!optionalClause(existing)) {
                    String withoutResolution = String.join(";", BundlingArtifactVerifier.splitHeader(existing, ';').stream()
                            .filter(segment -> !segment.replace(" ", "").startsWith("resolution:="))
                            .toList());
                    entries.set(exact, withoutResolution + ";resolution:=optional");
                    changed = true;
                }
            } else if (wildcard < entries.size() && optionalClause(entries.get(wildcard))) {
                // AMPS's existing broad optional clause already covers this unused path.
                continue;
            } else {
                entries.add(wildcard, clause);
                changed = true;
            }
        }

        return changed ? String.join(",", entries) : original;
    }

    /** Applies import edits to mixed XML content without moving or deleting its comments. */
    private static Xml.Tag migrateImportTag(Xml.Tag imports, List<DependencyOperation> selected) {
        if (imports.getValue().isPresent()) {
            String original = imports.getValue().get();
            String updated = migrateImports(original, selected);
            return updated.equals(original) ? imports : imports.withValue(updated);
        }

        List<? extends Content> existingContent = imports.getContent();
        if (existingContent == null) {
            String updated = migrateImports("", selected);
            return updated.isEmpty() ? imports : imports.withValue(updated);
        }

        validateCommentBoundaries(existingContent);

        List<Content> content = new ArrayList<>(existingContent);
        boolean changed = false;
        List<DependencyOperation> removals = selected.stream()
                .filter(operation -> operation.action() == Action.REMOVE_IMPORT_EXCLUSION)
                .toList();
        for (int index = 0; index < content.size(); index++) {
            if (content.get(index) instanceof Xml.CharData data) {
                String updated = migrateImportSegment(data.getText(), removals);
                if (!updated.equals(data.getText())) {
                    Xml.CharData updatedData = data.withText(updated);
                    content.set(index, updated.isBlank() ? updatedData.withAfterText("") : updatedData);
                    changed = true;
                }
            } else if (!(content.get(index) instanceof Xml.Comment)) {
                throw new IllegalArgumentException("Unsupported mixed Import-Package XML content: "
                        + content.get(index).getClass().getSimpleName());
            }
        }

        if (changed) {
            content = normalizeImportSeparators(content);
        }

        for (DependencyOperation operation : selected) {
            if (operation.action() != Action.APPEND_OPTIONAL_IMPORT) {
                continue;
            }

            int target = findImportClause(content, operation.artifactId());
            if (target < 0) {
                String clause = migrateImports("", List.of(operation));
                content.addAll(Xml.Tag.build("<Import-Package>" + text(clause) + "</Import-Package>").getContent());
                changed = true;
                continue;
            }

            Xml.CharData data = (Xml.CharData) content.get(target);
            String updated = migrateImportSegment(data.getText(), List.of(operation));
            if (!updated.equals(data.getText())) {
                content.set(target, data.withText(updated));
                changed = true;
            }
        }

        return changed ? imports.withContent(content) : imports;
    }

    /** Keeps one separator between retained clauses across text and comment boundaries. */
    private static List<Content> normalizeImportSeparators(List<Content> content) {
        String header = String.join("", content.stream()
                .filter(Xml.CharData.class::isInstance)
                .map(Xml.CharData.class::cast)
                .map(Xml.CharData::getText)
                .toList());

        Set<Integer> removed = new HashSet<>();
        boolean hasClause = false;
        boolean quoted = false;
        boolean escaped = false;
        int pendingComma = -1;
        for (int index = 0; index < header.length(); index++) {
            char character = header.charAt(index);
            if (character == ',' && !quoted && !escaped) {
                if (!hasClause || pendingComma >= 0) {
                    removed.add(index);
                } else {
                    pendingComma = index;
                }
            } else {
                if (!Character.isWhitespace(character)) {
                    hasClause = true;
                    pendingComma = -1;
                }
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    quoted = !quoted;
                }
            }
        }
        if (pendingComma >= 0) {
            removed.add(pendingComma);
        }
        if (removed.isEmpty()) {
            return content;
        }

        List<Content> normalized = new ArrayList<>(content.size());
        int offset = 0;
        for (Content item : content) {
            if (item instanceof Xml.CharData data) {
                StringBuilder retained = new StringBuilder();
                for (int index = 0; index < data.getText().length(); index++) {
                    if (!removed.contains(offset + index)) {
                        retained.append(data.getText().charAt(index));
                    }
                }
                offset += data.getText().length();
                String updated = retained.toString();
                normalized.add(updated.equals(data.getText()) ? data : data.withText(updated));
            } else {
                normalized.add(item);
            }
        }

        return normalized;
    }

    /** Preserves a comma at the edge of a text segment after its clauses are migrated. */
    private static String migrateImportSegment(String original, List<DependencyOperation> operations) {
        String leadingWhitespace = original.substring(0, original.length() - original.stripLeading().length());
        String stripped = original.stripLeading();
        String trailingWhitespace = stripped.substring(stripped.stripTrailing().length());
        String header = stripped.stripTrailing();
        int leadingCommaCount = 0;
        while (leadingCommaCount < header.length() && header.charAt(leadingCommaCount) == ',') {
            leadingCommaCount++;
        }
        String leadingCommas = header.substring(0, leadingCommaCount);
        header = header.substring(leadingCommaCount);
        boolean trailingComma = header.endsWith(",");
        if (trailingComma) {
            header = header.substring(0, header.length() - 1);
        }

        String migrated = migrateImports(header, operations);
        if (trailingComma && !migrated.isBlank()) {
            migrated += ",";
        }

        return leadingWhitespace + leadingCommas + migrated + trailingWhitespace;
    }

    /** Rejects comments between parts of an OSGi clause before any mixed content is changed. */
    private static void validateCommentBoundaries(List<? extends Content> content) {
        for (Content item : content) {
            if (item instanceof Xml.CharData data && !hasBalancedQuotes(data.getText())) {
                throw new IllegalArgumentException("Cannot safely migrate Import-Package when an XML comment "
                        + "splits a quoted OSGi value");
            }
        }

        for (int index = 0; index < content.size(); index++) {
            if (!(content.get(index) instanceof Xml.Comment)) {
                continue;
            }

            String before = adjacentImportText(content, index, -1);
            String after = adjacentImportText(content, index, 1);
            if (!before.isEmpty() && !after.isEmpty()
                    && !before.stripTrailing().endsWith(",") && !after.stripLeading().startsWith(",")) {
                throw new IllegalArgumentException("Cannot safely migrate Import-Package when an XML comment "
                        + "splits an OSGi clause");
            }
        }
    }

    private static boolean hasBalancedQuotes(String text) {
        boolean quoted = false;
        boolean escaped = false;
        for (char character : text.toCharArray()) {
            if (escaped) {
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            }
        }

        return !quoted;
    }

    private static String adjacentImportText(List<? extends Content> content, int comment, int direction) {
        for (int index = comment + direction; index >= 0 && index < content.size(); index += direction) {
            Content item = content.get(index);
            if (item instanceof Xml.CharData data && !data.getText().isBlank()) {
                return data.getText();
            }
            if (!(item instanceof Xml.Comment) && !(item instanceof Xml.CharData)) {
                throw new IllegalArgumentException("Unsupported mixed Import-Package XML content: "
                        + item.getClass().getSimpleName());
            }
        }

        return "";
    }

    /** Finds an explicit import, its wildcard fallback, or the last clause for appending. */
    private static int findImportClause(List<Content> content, String packagePattern) {
        int wildcard = -1;
        int lastClause = -1;
        for (int index = 0; index < content.size(); index++) {
            if (!(content.get(index) instanceof Xml.CharData data) || data.getText().isBlank()) {
                continue;
            }

            for (String clause : BundlingArtifactVerifier.splitHeader(data.getText(), ',')) {
                List<String> segments = BundlingArtifactVerifier.splitHeader(clause, ';');
                if (segments.isEmpty()) {
                    continue;
                }

                lastClause = index;
                String packageName = segments.get(0).trim();
                if (packageName.equals(packagePattern)) {
                    return index;
                }
                if (packageName.equals("*") && wildcard < 0) {
                    wildcard = index;
                }
            }
        }

        return wildcard >= 0 ? wildcard : lastClause;
    }

    private static boolean optionalClause(String clause) {
        return BundlingArtifactVerifier.splitHeader(clause, ';').stream()
                .map(segment -> segment.replace(" ", "").replace("\"", ""))
                .anyMatch(segment -> segment.equals("resolution:=optional"));
    }

    private static Xml.Tag mergeExclusions(Xml.Tag dependency, List<BundleDependencyModel.Exclusion> requested) {
        if (requested.isEmpty()) {
            return dependency;
        }

        Xml.Tag exclusions = dependency.getChild("exclusions").orElse(Xml.Tag.build("<exclusions/>"));
        for (BundleDependencyModel.Exclusion exclusion : requested) {
            boolean exists = exclusions.getChildren("exclusion").stream()
                    .anyMatch(tag -> value(tag, "groupId").equals(exclusion.groupId()) && value(tag, "artifactId").equals(exclusion.artifactId()));

            if (!exists) {
                Xml.Tag tag = setChild(Xml.Tag.build("<exclusion/>"), "groupId", exclusion.groupId());
                exclusions = appendChild(exclusions, setChild(tag, "artifactId", exclusion.artifactId()));
            }
        }

        return replaceChild(dependency, exclusions);
    }

    private static Xml.Tag setChild(Xml.Tag parent, String name, String value) {
        Xml.Tag existing = parent.getChild(name).orElse(null);
        if (existing != null) {
            return existing.getValue().orElse("").equals(value) ? parent : replaceChild(parent, existing.withValue(value));
        }

        return appendChild(parent, Xml.Tag.build("<" + name + ">" + text(value) + "</" + name + ">"));
    }

    private static String value(Xml.Tag parent, String name) {
        return parent.getChildValue(name).orElse("");
    }

    private static String text(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Xml.Tag mapChildren(Xml.Tag parent, String name, java.util.function.UnaryOperator<Xml.Tag> transform) {
        if (parent.getContent() == null) {
            return parent;
        }

        List<Content> content = new ArrayList<>(parent.getContent().size());
        boolean changed = false;
        for (Content item : parent.getContent()) {
            Content updated = item instanceof Xml.Tag tag && tag.getName().equals(name) ? transform.apply(tag) : item;
            content.add(updated);
            changed |= updated != item;
        }

        return changed ? parent.withContent(content) : parent;
    }

    private static Xml.Tag replaceChild(Xml.Tag parent, Xml.Tag replacement) {
        Xml.Tag existing = parent.getChild(replacement.getName()).orElse(null);
        return existing == null ? appendChild(parent, replacement) : replaceIdentity(parent, existing, replacement);
    }

    private static Xml.Tag replaceIdentity(Xml.Tag parent, Xml.Tag old, Xml.Tag replacement) {
        if (old == replacement) {
            return parent;
        }

        return parent.withContent(parent.getContent().stream().map(item -> item == old ? replacement : item).toList());
    }

    private static Xml.Tag appendChild(Xml.Tag parent, Xml.Tag child) {
        List<Content> contents = new ArrayList<>(parent.getContent() == null ? List.of() : parent.getContent());
        String prefix = parent.getChildren().stream().findFirst().map(Xml.Tag::getPrefix).orElse("\n    ");
        contents.add(child.withPrefix(prefix));
        return parent.withContent(contents);
    }
}
