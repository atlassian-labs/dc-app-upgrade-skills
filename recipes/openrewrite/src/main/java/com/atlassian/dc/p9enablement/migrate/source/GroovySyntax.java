package com.atlassian.dc.p9enablement.migrate.source;

final class GroovySyntax implements JvmLanguageSyntax {
    public static final GroovySyntax INSTANCE = new GroovySyntax();

    private GroovySyntax() { }

    @Override
    public boolean nestedBlockComments() { return false; }

    @Override
    public boolean backtickIdentifiers() { return false; }

    @Override
    public boolean slashyStrings() { return true; }

    @Override
    public boolean dollarSlashyStrings() { return true; }

    @Override
    public boolean commaSeparatedImports() { return false; }

    @Override
    public boolean groupedImports() { return false; }

    @Override
    public boolean staticImports() { return true; }

    @Override
    public boolean importWildcard(String token) { return token.equals("*"); }

    @Override
    public boolean importExclusion(String token) { return false; }

    @Override
    public boolean importAlias(String token) { return token.equals("as"); }

    @Override
    public boolean tripleQuotedStrings(char quote) { return quote == '"' || quote == '\''; }

    @Override
    public boolean characterLiterals() { return false; }

    @Override
    public boolean quotedMacroSyntax() { return false; }

    @Override
    public boolean symbolLiterals() { return false; }

    @Override
    public boolean slashyExpressionKeyword(String token) {
        return token.equals("assert") || token.equals("case") || token.equals("in") || token.equals("return")
                || token.equals("throw");
    }

    @Override
    public boolean interpolateString(char quote, boolean triple, String prefix) {
        return quote == '"';
    }

    @Override
    public boolean escapeBackslashes(char quote, boolean triple, String prefix) { return true; }

    @Override
    public boolean escapeDoubleDollars() { return false; }

    @Override
    public boolean dottedShorthandInterpolation() { return true; }

    @Override
    public String normalizeName(String name) { return name; }
}
