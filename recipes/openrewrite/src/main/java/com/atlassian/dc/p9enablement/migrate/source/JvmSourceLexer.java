package com.atlassian.dc.p9enablement.migrate.source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Tokenizes code while excluding literal text and preserving interpolation expressions. */
final class JvmSourceLexer {
    private JvmSourceLexer() { }

    public static LexedSource lex(Path file, String source, JvmLanguageSyntax syntax) {
        return new Cursor(file, source, syntax).lex();
    }

    private static final class Cursor {
        private final Path file;
        private final String source;
        private final JvmLanguageSyntax syntax;
        private final List<Token> tokens = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private int offset;
        private int line = 1;

        private Cursor(Path file, String source, JvmLanguageSyntax syntax) {
            this.file = file;
            this.source = source;
            this.syntax = syntax;
        }

        private LexedSource lex() {
            code(false);
            return new LexedSource(List.copyOf(tokens), List.copyOf(errors));
        }

        private void code(boolean interpolation) {
            int nestedBraces = 0;
            while (offset < source.length()) {
                char current = source.charAt(offset);
                if (Character.isWhitespace(current)) {
                    advance();
                } else if (startsWith("//")) {
                    lineComment();
                } else if (startsWith("/*")) {
                    blockComment();
                } else if (interpolation && current == '}' && nestedBraces == 0) {
                    return;
                } else if (startsWith("\"\"\"") && syntax.tripleQuotedStrings('"')) {
                    string('"', true);
                    barrier();
                } else if (startsWith("'''") && syntax.tripleQuotedStrings('\'')) {
                    string('\'', true);
                    barrier();
                } else if (isCharacterLiteralStart(current)) {
                    characterLiteral();
                    barrier();
                } else if (isQuotedMacroStart(current)) {
                    errors.add(file + ":" + line + ": unsupported Scala quoted macro syntax");
                    symbol();
                } else if (syntax.symbolLiterals() && current == '\'' && isSymbolLiteralStart()) {
                    symbolLiteral();
                } else if (current == '"' || current == '\'' && !syntax.characterLiterals()) {
                    string(current, false);
                    barrier();
                } else if (current == '\'') {
                    errors.add(file + ":" + line + ": unsupported or malformed character literal");
                    symbol();
                } else if (syntax.dollarSlashyStrings() && startsWith("$/")) {
                    dollarSlashyString();
                    barrier();
                } else if (syntax.slashyStrings() && current == '/') {
                    scanSlashyOrDivision();
                } else if (syntax.backtickIdentifiers() && current == '`') {
                    backtickIdentifier();
                } else if (isIdentifierStart(current)) {
                    identifier();
                } else {
                    if (interpolation && current == '{') {
                        nestedBraces++;
                    } else if (interpolation && current == '}') {
                        nestedBraces--;
                    }
                    symbol();
                }
            }
            if (interpolation) {
                errors.add(file + ":" + line + ": unterminated string interpolation");
            }
        }

        private void string(char quote, boolean triple) {
            int startLine = line;
            String prefix = precedingIdentifier();
            boolean interpolated = syntax.interpolateString(quote, triple, prefix);
            boolean escaped = syntax.escapeBackslashes(quote, triple, prefix);
            int delimiter = triple ? 3 : 1;
            offset += delimiter;
            while (offset < source.length()) {
                if (triple ? repeated(quote, 3) : source.charAt(offset) == quote) {
                    if (triple) {
                        while (offset < source.length() && source.charAt(offset) == quote) {
                            advance();
                        }
                    } else {
                        advance();
                    }
                    return;
                }
                if (escaped && source.charAt(offset) == '\\') {
                    advance();
                    if (offset < source.length()) {
                        advance();
                    }
                    continue;
                }
                if (interpolated && source.charAt(offset) == '$') {
                    if (syntax.escapeDoubleDollars() && startsWith("$$")) {
                        advance();
                        advance();
                        continue;
                    }
                    interpolateAtDollar();
                    continue;
                }
                advance();
            }
            errors.add(file + ":" + startLine + ": unterminated string literal");
        }

        private String precedingIdentifier() {
            int start = offset;
            while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) {
                start--;
            }
            return source.substring(start, offset);
        }

        private void slashyString() {
            int startLine = line;
            advance();
            while (offset < source.length()) {
                char current = source.charAt(offset);
                if (current == '\\' && offset + 1 < source.length() && source.charAt(offset + 1) == '/') {
                    advance();
                    advance();
                } else if (current == '/') {
                    advance();
                    return;
                } else if (current == '$') {
                    interpolateAtDollar();
                } else {
                    advance();
                }
            }
            errors.add(file + ":" + startLine + ": unterminated Groovy slashy string");
        }

        private void dollarSlashyString() {
            int startLine = line;
            advance();
            advance();
            while (offset < source.length()) {
                if (startsWith("/$")) {
                    advance();
                    advance();
                    return;
                }
                if (startsWith("$$") || startsWith("$/")) {
                    advance();
                    advance();
                    continue;
                }
                if (source.charAt(offset) == '$') {
                    interpolateAtDollar();
                } else {
                    advance();
                }
            }
            errors.add(file + ":" + startLine + ": unterminated Groovy dollar-slashy string");
        }

        private void interpolateAtDollar() {
            advance();
            if (offset < source.length() && source.charAt(offset) == '{') {
                barrier();
                advance();
                code(true);
                if (offset < source.length() && source.charAt(offset) == '}') {
                    advance();
                }
                barrier();
            } else if (offset < source.length() && isIdentifierStart(source.charAt(offset))) {
                barrier();
                identifier();
                while (syntax.dottedShorthandInterpolation() && offset + 1 < source.length()
                        && source.charAt(offset) == '.' && isIdentifierStart(source.charAt(offset + 1))) {
                    symbol();
                    identifier();
                }
                barrier();
            }
        }

        private void lineComment() {
            while (offset < source.length() && source.charAt(offset) != '\n') {
                advance();
            }
        }

        private void blockComment() {
            int startLine = line;
            int depth = 0;
            while (offset < source.length()) {
                if (startsWith("/*") && (depth == 0 || syntax.nestedBlockComments())) {
                    depth++;
                    advance();
                    advance();
                } else if (startsWith("/*")) {
                    advance();
                    advance();
                } else if (startsWith("*/")) {
                    depth--;
                    advance();
                    advance();
                    if (depth == 0) {
                        return;
                    }
                } else {
                    advance();
                }
            }
            errors.add(file + ":" + startLine + ": unterminated block comment");
        }

        private void backtickIdentifier() {
            int startLine = line;
            advance();
            int start = offset;
            while (offset < source.length() && source.charAt(offset) != '`' && source.charAt(offset) != '\n') {
                advance();
            }
            if (offset < source.length() && source.charAt(offset) == '`') {
                add(source.substring(start, offset), startLine, Kind.ESCAPED_IDENTIFIER);
                advance();
            } else {
                errors.add(file + ":" + startLine + ": unterminated backtick identifier");
                barrier();
            }
        }

        private void identifier() {
            int start = offset;
            int startLine = line;
            advance();
            while (offset < source.length() && isIdentifierPart(source.charAt(offset))) {
                advance();
            }
            add(source.substring(start, offset), startLine, Kind.IDENTIFIER);
        }

        private void symbol() {
            int startLine = line;
            if (startsWith("=>") || startsWith("::") || startsWith("?.")) {
                String value = source.substring(offset, offset + 2);
                advance();
                advance();
                add(value, startLine, Kind.SYMBOL);
                return;
            }
            char value = source.charAt(offset);
            advance();
            add(Character.toString(value), startLine, Kind.SYMBOL);
        }

        private void scanSlashyOrDivision() {
            SlashClassification classification = classifySlash();
            if (classification == SlashClassification.DIVISION) {
                symbol();
                return;
            }
            if (classification == SlashClassification.AMBIGUOUS) {
                errors.add(file + ":" + line + ": ambiguous Groovy slash; confirm whether it is division or a slashy string");
                if (!hasSlashyTerminatorOnLine()) {
                    symbol();
                    return;
                }
            }
            slashyString();
            barrier();
        }

        private SlashClassification classifySlash() {
            int previous = offset - 1;
            while (previous >= 0 && Character.isWhitespace(source.charAt(previous))) {
                previous--;
            }
            if (previous < 0) {
                return SlashClassification.STRING;
            }
            char character = source.charAt(previous);
            if (Character.isJavaIdentifierPart(character)) {
                int end = previous + 1;
                int start = previous;
                while (start >= 0 && Character.isJavaIdentifierPart(source.charAt(start))) {
                    start--;
                }
                String word = source.substring(start + 1, end);
                if (word.matches("[0-9]+[dDfFlLgG]?")) {
                    return SlashClassification.DIVISION;
                }
                return syntax.slashyExpressionKeyword(word)
                        ? SlashClassification.STRING : SlashClassification.AMBIGUOUS;
            }
            if (character == ')' || character == ']' || character == '}' || character == '\'' || character == '"') {
                return SlashClassification.AMBIGUOUS;
            }
            return SlashClassification.STRING;
        }

        private boolean hasSlashyTerminatorOnLine() {
            boolean escaped = false;
            for (int index = offset + 1; index < source.length() && source.charAt(index) != '\n'; index++) {
                char character = source.charAt(index);
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '/') {
                    return true;
                }
            }
            return false;
        }

        private boolean isSymbolLiteralStart() {
            return source.charAt(offset) == '\'' && offset + 1 < source.length()
                    && isIdentifierStart(source.charAt(offset + 1));
        }

        private void symbolLiteral() {
            int startLine = line;
            advance();
            while (offset < source.length() && isIdentifierPart(source.charAt(offset))) {
                advance();
            }
            add("'", startLine, Kind.SYMBOL);
            barrier();
        }

        private boolean isCharacterLiteralStart(char current) {
            if (current != '\'' || !syntax.characterLiterals()) {
                return false;
            }
            int next = offset + 1;
            if (next >= source.length() || source.charAt(next) == '\n' || source.charAt(next) == '\'') {
                return false;
            }
            if (source.charAt(next) == '\\') {
                next += source.startsWith("\\u", next) ? 6 : 2;
            } else {
                next++;
            }
            return next < source.length() && source.charAt(next) == '\'';
        }

        private boolean isQuotedMacroStart(char current) {
            return current == '\'' && syntax.quotedMacroSyntax() && offset + 1 < source.length()
                    && (source.charAt(offset + 1) == '{' || source.charAt(offset + 1) == '[');
        }

        private void characterLiteral() {
            advance();
            while (offset < source.length() && source.charAt(offset) != '\'') {
                if (source.charAt(offset) == '\\' && offset + 1 < source.length()) {
                    advance();
                }
                advance();
            }
            if (offset < source.length()) {
                advance();
            }
        }

        private boolean repeated(char value, int count) {
            if (offset + count > source.length()) {
                return false;
            }
            for (int index = 0; index < count; index++) {
                if (source.charAt(offset + index) != value) {
                    return false;
                }
            }
            return true;
        }

        private boolean startsWith(String value) {
            return source.startsWith(value, offset);
        }

        private boolean isIdentifierStart(char value) {
            return Character.isJavaIdentifierStart(value);
        }

        private boolean isIdentifierPart(char value) {
            return Character.isJavaIdentifierPart(value);
        }

        private void barrier() {
            add("", line, Kind.BARRIER);
        }

        private void add(String value, int tokenLine, Kind kind) {
            tokens.add(new Token(value, tokenLine, kind));
        }

        private void advance() {
            if (source.charAt(offset++) == '\n') {
                line++;
            }
        }
    }

    private enum SlashClassification { STRING, DIVISION, AMBIGUOUS }

    public enum Kind { IDENTIFIER, ESCAPED_IDENTIFIER, SYMBOL, BARRIER }

    public record Token(String text, int line, Kind kind) { }

    public record LexedSource(List<Token> tokens, List<String> errors) { }
}
