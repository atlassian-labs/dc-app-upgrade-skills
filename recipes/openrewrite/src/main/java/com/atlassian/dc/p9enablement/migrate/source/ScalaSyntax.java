package com.atlassian.dc.p9enablement.migrate.source;

final class ScalaSyntax implements JvmLanguageSyntax {
    public static final ScalaSyntax INSTANCE = new ScalaSyntax();

    private ScalaSyntax() { }

    @Override
    public boolean nestedBlockComments() { return true; }

    @Override
    public boolean backtickIdentifiers() { return true; }

    @Override
    public boolean slashyStrings() { return false; }

    @Override
    public boolean dollarSlashyStrings() { return false; }

    @Override
    public boolean commaSeparatedImports() { return true; }

    @Override
    public boolean groupedImports() { return true; }

    @Override
    public boolean staticImports() { return false; }

    @Override
    public boolean importWildcard(String token) { return token.equals("*") || token.equals("_"); }

    @Override
    public boolean importExclusion(String token) { return token.equals("_"); }

    @Override
    public boolean importAlias(String token) { return token.equals("as") || token.equals("=>"); }

    @Override
    public boolean tripleQuotedStrings(char quote) { return quote == '"'; }

    @Override
    public boolean characterLiterals() { return true; }

    @Override
    public boolean quotedMacroSyntax() { return true; }

    @Override
    public boolean symbolLiterals() { return true; }

    @Override
    public boolean slashyExpressionKeyword(String token) { return false; }

    @Override
    public boolean interpolateString(char quote, boolean triple, String prefix) {
        return quote == '"' && !prefix.isEmpty();
    }

    @Override
    public boolean escapeBackslashes(char quote, boolean triple, String prefix) {
        return !triple && !prefix.equals("raw");
    }

    @Override
    public boolean escapeDoubleDollars() { return true; }

    @Override
    public boolean dottedShorthandInterpolation() { return false; }

    @Override
    public String normalizeName(String name) {
        return name.startsWith("_root_.") ? name.substring(7) : name;
    }
}
