package com.atlassian.dc.p9enablement.migrate.source;

final class KotlinSyntax implements JvmLanguageSyntax {
    public static final KotlinSyntax INSTANCE = new KotlinSyntax();

    private KotlinSyntax() { }

    @Override
    public boolean nestedBlockComments() { return true; }

    @Override
    public boolean backtickIdentifiers() { return true; }

    @Override
    public boolean slashyStrings() { return false; }

    @Override
    public boolean dollarSlashyStrings() { return false; }

    @Override
    public boolean commaSeparatedImports() { return false; }

    @Override
    public boolean groupedImports() { return false; }

    @Override
    public boolean staticImports() { return false; }

    @Override
    public boolean importWildcard(String token) { return token.equals("*"); }

    @Override
    public boolean importExclusion(String token) { return false; }

    @Override
    public boolean importAlias(String token) { return token.equals("as"); }

    @Override
    public boolean tripleQuotedStrings(char quote) { return quote == '"'; }

    @Override
    public boolean characterLiterals() { return true; }

    @Override
    public boolean quotedMacroSyntax() { return false; }

    @Override
    public boolean symbolLiterals() { return false; }

    @Override
    public boolean slashyExpressionKeyword(String token) { return false; }

    @Override
    public boolean interpolateString(char quote, boolean triple, String prefix) {
        return quote == '"';
    }

    @Override
    public boolean escapeBackslashes(char quote, boolean triple, String prefix) {
        return !triple;
    }

    @Override
    public boolean escapeDoubleDollars() { return false; }

    @Override
    public boolean dottedShorthandInterpolation() { return false; }

    @Override
    public String normalizeName(String name) { return name; }
}
