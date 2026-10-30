package com.atlassian.dc.p9enablement.migrate.source;

import com.atlassian.dc.p9enablement.migrate.source.JvmSourceLexer.Kind;
import com.atlassian.dc.p9enablement.migrate.source.JvmSourceLexer.LexedSource;
import com.atlassian.dc.p9enablement.migrate.source.JvmSourceLexer.Token;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Approximate lexical scanner for JVM source imports and qualified references. */
public final class JvmSourceScanner {
    private JvmSourceScanner() { }

    /** Names found by file and diagnostics that make deterministic edits unsafe. */
    public record Scan(Map<Path, List<String>> references, List<String> errors) {
        public Scan {
            Map<Path, List<String>> immutableReferences = new LinkedHashMap<>();
            references.forEach((path, values) -> immutableReferences.put(path, List.copyOf(values)));
            references = Collections.unmodifiableMap(immutableReferences);
            errors = List.copyOf(errors);
        }
    }

    /** Scans Kotlin, Scala, and Groovy source files without parsing or resolving types. */
    public static Scan scan(List<Path> files) throws IOException {
        Map<Path, List<String>> references = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (Path file : files) {
            Path normalizedFile = file.toAbsolutePath().normalize();
            JvmLanguageSyntax syntax = syntaxFor(file);
            if (syntax == null) {
                errors.add(normalizedFile + ":1: unsupported JVM source extension");
                continue;
            }

            LexedSource lexed = JvmSourceLexer.lex(
                    normalizedFile, Files.readString(file, StandardCharsets.UTF_8), syntax);
            ImportDiscovery discovery = imports(lexed.tokens(), syntax, normalizedFile);
            ImportScan imports = discovery.imports();
            Set<String> names = new LinkedHashSet<>(imports.references());
            names.addAll(qualifiedReferences(lexed.tokens(), syntax, discovery.consumed()));
            references.put(normalizedFile, List.copyOf(names));
            errors.addAll(imports.errors());
            errors.addAll(lexed.errors());
        }
        return new Scan(references, errors);
    }

    private static JvmLanguageSyntax syntaxFor(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".kt")) {
            return KotlinSyntax.INSTANCE;
        }
        if (name.endsWith(".scala")) {
            return ScalaSyntax.INSTANCE;
        }
        if (name.endsWith(".groovy")) {
            return GroovySyntax.INSTANCE;
        }
        return null;
    }

    private static ImportDiscovery imports(List<Token> tokens, JvmLanguageSyntax syntax, Path file) {
        Set<Integer> consumed = new LinkedHashSet<>();
        Set<String> result = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (!isDeclarationStart(tokens, index, "import")) {
                continue;
            }
            int end = statementEnd(tokens, index + 1);
            int contentStart = index + 1;
            if (syntax.staticImports() && contentStart < end && tokens.get(contentStart).text().equals("static")) {
                contentStart++;
            }
            List<Token> statement = tokens.subList(contentStart, end);
            ImportScan parsed = syntax.groupedImports() && contains(statement, "{")
                    ? parseGroupedImport(statement, token.line(), file, syntax)
                    : parseSimpleImports(statement, token.line(), file, syntax);
            result.addAll(parsed.references());
            errors.addAll(parsed.errors());
            for (int cursor = index; cursor < end; cursor++) {
                consumed.add(cursor);
            }
            index = Math.max(index, end - 1);
        }
        return new ImportDiscovery(new ImportScan(result, errors), Set.copyOf(consumed));
    }

    private static int statementEnd(List<Token> tokens, int start) {
        int braces = 0;
        int parentheses = 0;
        int brackets = 0;
        for (int index = start; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            Token previous = tokens.get(index - 1);
            if (token.kind() == Kind.BARRIER || token.line() > previous.line()
                    && braces == 0 && parentheses == 0 && brackets == 0
                    && !Set.of(".", ",").contains(previous.text())) {
                return index;
            }
            switch (token.text()) {
                case "{" -> braces++;
                case "}" -> {
                    if (braces == 0) {
                        return index;
                    }
                    braces--;
                }
                case "(" -> parentheses++;
                case ")" -> parentheses--;
                case "[" -> brackets++;
                case "]" -> brackets--;
                case ";" -> {
                    if (braces == 0 && parentheses == 0 && brackets == 0) {
                        return index;
                    }
                }
                default -> { }
            }
        }
        return tokens.size();
    }

    private static ImportScan parseGroupedImport(
            List<Token> statement, int line, Path file, JvmLanguageSyntax syntax) {
        Set<String> result = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        int open = indexOf(statement, "{");
        int close = indexOfAfter(statement, "}", open + 1);
        if (open < 1 || close < 0 || contains(statement.subList(0, open), ",")) {
            errors.add(file + ":" + line + ": incomplete or mixed Scala grouped import requires manual inspection");
            return new ImportScan(result, errors);
        }
        int prefixEnd = open > 0 && statement.get(open - 1).text().equals(".") ? open - 1 : open;
        String prefix = syntax.normalizeName(joinDotted(statement, 0, prefixEnd));
        if (prefix.isEmpty()) {
            errors.add(file + ":" + line + ": unrecognized Scala grouped import prefix");
            return new ImportScan(result, errors);
        }
        List<List<Token>> selectors = split(statement.subList(open + 1, close), ",");
        for (List<Token> part : selectors) {
            if (part.isEmpty()) {
                continue;
            }
            int rename = aliasIndex(part, syntax);
            String selected = isWildcard(part, syntax)
                    ? "*" : joinDotted(part, 0, rename < 0 ? part.size() : rename);
            if (selected.isEmpty() || rename >= 0 && !validAlias(part, rename)) {
                errors.add(file + ":" + line + ": unrecognized import selector");
            } else if (rename >= 0 && isExclusion(part.get(rename + 1), syntax)) {
                // Excluding an import is not evidence that the application uses it.
                continue;
            } else {
                result.add(prefix + "." + selected);
            }
        }
        if (close + 1 < statement.size()) {
            if (statement.get(close + 1).text().equals(",")) {
                ImportScan remainder = parseSimpleImports(statement.subList(close + 2, statement.size()), line, file, syntax);
                result.addAll(remainder.references());
                errors.addAll(remainder.errors());
            } else {
                errors.add(file + ":" + line + ": unrecognized tokens after Scala grouped import");
            }
        }
        return new ImportScan(result, errors);
    }

    private static ImportScan parseSimpleImports(List<Token> tokens, int line, Path file, JvmLanguageSyntax syntax) {
        Set<String> result = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        List<List<Token>> paths = syntax.commaSeparatedImports() ? split(tokens, ",") : List.of(tokens);
        for (List<Token> part : paths) {
            int rename = aliasIndex(part, syntax);
            List<Token> path = part.subList(0, rename < 0 ? part.size() : rename);
            String imported = endsWithWildcard(path, syntax)
                    ? joinDotted(path, 0, path.size() - 2) + ".*"
                    : joinDotted(path, 0, path.size());
            if (imported.isEmpty() || imported.equals(".*") || rename >= 0 && !validAlias(part, rename)) {
                errors.add(file + ":" + line + ": unrecognized import declaration");
            } else if (rename < 0 || !isExclusion(part.get(rename + 1), syntax)) {
                result.add(syntax.normalizeName(imported));
            }
        }
        return new ImportScan(result, errors);
    }

    private static List<List<Token>> split(List<Token> tokens, String delimiter) {
        List<List<Token>> parts = new ArrayList<>();
        List<Token> part = new ArrayList<>();
        for (Token token : tokens) {
            if (token.text().equals(delimiter)) {
                parts.add(List.copyOf(part));
                part.clear();
            } else {
                part.add(token);
            }
        }
        parts.add(List.copyOf(part));
        return List.copyOf(parts);
    }

    private static Set<String> qualifiedReferences(List<Token> tokens, JvmLanguageSyntax syntax, Set<Integer> consumed) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (!isIdentifier(token) || consumed.contains(index)) {
                continue;
            }
            int end = index;
            StringBuilder name = new StringBuilder(token.text());
            while (end + 2 < tokens.size() && tokens.get(end + 1).text().equals(".")
                    && isIdentifier(tokens.get(end + 2)) && !consumed.contains(end + 2)) {
                name.append('.').append(tokens.get(end + 2).text());
                end += 2;
            }
            if (end > index && !isPackageDeclaration(tokens, index)) {
                result.add(syntax.normalizeName(name.toString()));
                index = end;
            }
        }
        return result;
    }

    private static boolean isDeclarationStart(List<Token> tokens, int index, String keyword) {
        Token token = tokens.get(index);
        if (token.kind() != Kind.IDENTIFIER || !token.text().equals(keyword)) {
            return false;
        }
        if (index == 0) {
            return true;
        }
        Token previous = tokens.get(index - 1);
        return !Set.of(".", "?.", "::").contains(previous.text())
                && (previous.line() < token.line() || Set.of(";", "{", "}").contains(previous.text()));
    }

    private static boolean isPackageDeclaration(List<Token> tokens, int index) {
        for (int cursor = index - 1; cursor >= 0; cursor--) {
            Token token = tokens.get(cursor);
            if (token.kind() == Kind.BARRIER || token.text().equals(";") || token.text().equals("{")) {
                return false;
            }
            if (token.line() < tokens.get(index).line()) {
                return false;
            }
            if (isDeclarationStart(tokens, cursor, "package")) {
                return true;
            }
            if (token.text().equals("import")) {
                return false;
            }
        }
        return false;
    }

    private static String joinDotted(List<Token> tokens, int start, int end) {
        StringBuilder result = new StringBuilder();
        boolean expectIdentifier = true;
        for (Token token : tokens.subList(start, end)) {
            if (expectIdentifier && isIdentifier(token) && !(token.kind() == Kind.IDENTIFIER && token.text().equals("_"))) {
                if (!result.isEmpty()) {
                    result.append('.');
                }
                result.append(token.text());
                expectIdentifier = false;
            } else if (!expectIdentifier && token.text().equals(".")) {
                expectIdentifier = true;
            } else {
                return "";
            }
        }
        return expectIdentifier ? "" : result.toString();
    }

    private static boolean isIdentifier(Token token) {
        return token.kind() == Kind.IDENTIFIER || token.kind() == Kind.ESCAPED_IDENTIFIER;
    }

    private static int aliasIndex(List<Token> tokens, JvmLanguageSyntax syntax) {
        for (int index = 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.kind() != Kind.ESCAPED_IDENTIFIER && syntax.importAlias(token.text())
                    && isIdentifier(tokens.get(index - 1))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean validAlias(List<Token> tokens, int index) {
        return index + 2 == tokens.size() && isIdentifier(tokens.get(index + 1));
    }

    private static boolean isExclusion(Token token, JvmLanguageSyntax syntax) {
        return token.kind() != Kind.ESCAPED_IDENTIFIER && syntax.importExclusion(token.text());
    }

    private static boolean isWildcard(List<Token> tokens, JvmLanguageSyntax syntax) {
        return tokens.size() == 1 && tokens.getFirst().kind() != Kind.ESCAPED_IDENTIFIER
                && syntax.importWildcard(tokens.getFirst().text());
    }

    private static boolean endsWithWildcard(List<Token> tokens, JvmLanguageSyntax syntax) {
        return tokens.size() >= 2 && tokens.get(tokens.size() - 2).text().equals(".")
                && isWildcard(tokens.subList(tokens.size() - 1, tokens.size()), syntax);
    }

    private static boolean contains(List<Token> tokens, String value) {
        return tokens.stream().anyMatch(token -> token.text().equals(value));
    }

    private static int indexOf(List<Token> tokens, String value) {
        return indexOfAfter(tokens, value, 0);
    }

    private static int indexOfAfter(List<Token> tokens, String value, int start) {
        for (int index = start; index < tokens.size(); index++) {
            if (tokens.get(index).text().equals(value)) {
                return index;
            }
        }
        return -1;
    }

    private record ImportDiscovery(ImportScan imports, Set<Integer> consumed) { }

    private record ImportScan(Set<String> references, List<String> errors) {
        private ImportScan {
            references = Collections.unmodifiableSet(new LinkedHashSet<>(references));
            errors = List.copyOf(errors);
        }
    }
}
