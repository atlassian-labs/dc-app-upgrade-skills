package com.atlassian.dc.p9enablement.migrate.source;

/** Language-specific rules used by the shared lexical and import scanners. */
interface JvmLanguageSyntax {
    /** Whether another block-comment opener increases nesting depth before the outer comment closes. */
    boolean nestedBlockComments();

    /** Whether backticks delimit an identifier whose contents should be retained without the backticks. */
    boolean backtickIdentifiers();

    /** Whether a slash can open a {@code /.../} string where an expression is expected. */
    boolean slashyStrings();

    /** Whether {@code $/.../$} delimits a string with dollar-based escaping and interpolation. */
    boolean dollarSlashyStrings();

    /** Whether one import statement can contain several comma-separated import paths. */
    boolean commaSeparatedImports();

    /** Whether imports support brace-enclosed selectors, such as {@code pkg.{Type, Other => Alias}}. */
    boolean groupedImports();

    /** Whether the import scanner should consume an optional {@code static} keyword before the path. */
    boolean staticImports();

    /** Whether the token is the language's wildcard import selector. */
    boolean importWildcard(String token);

    /** Whether the token excludes an import selector. */
    boolean importExclusion(String token);

    /** Whether the token introduces an import alias. */
    boolean importAlias(String token);

    /** Whether this quote character can open a triple-quoted string. */
    boolean tripleQuotedStrings(char quote);

    /** Whether single quotes denote character literals rather than ordinary strings. */
    boolean characterLiterals();

    /** Whether apostrophe-brace or apostrophe-bracket starts quoted macro syntax. */
    boolean quotedMacroSyntax();

    /** Whether an apostrophe followed by an identifier denotes a symbol literal, such as Scala 2's {@code 'fast}. */
    boolean symbolLiterals();

    /** Whether a slash after this token can open a slashy string expression. */
    boolean slashyExpressionKeyword(String token);

    /**
     * Whether dollar interpolation inside this quoted string should be scanned as code.
     * Slashy and dollar-slashy strings are handled separately by the lexer.
     *
     * @param quote the single or double quote character opening the string
     * @param triple whether the delimiter consists of three quotes
     * @param prefix the identifier text immediately preceding the opening quote, or an empty string;
     *               for example, Scala's {@code s} or {@code raw}
     */
    boolean interpolateString(char quote, boolean triple, String prefix);

    /**
     * Whether a backslash escapes the next character inside this quoted string, preventing it
     * from closing the string or starting interpolation. Raw strings can disable this behavior.
     *
     * @param quote the single or double quote character opening the string
     * @param triple whether the delimiter consists of three quotes
     * @param prefix the identifier text immediately preceding the opening quote, or an empty string
     */
    boolean escapeBackslashes(char quote, boolean triple, String prefix);

    /**
     * Whether {@code $$} denotes a literal dollar in an interpolated quoted string.
     * Dollar-slashy strings have their own escaping rules and do not consult this flag.
     */
    boolean escapeDoubleDollars();

    /**
     * Whether unbraced interpolation can include a dotted path, such as {@code $value.property}.
     * Otherwise only the identifier after the dollar is code; the following dot and text are literal.
     */
    boolean dottedShorthandInterpolation();

    /**
     * Converts an extracted name to the form used for dependency-package matching, without resolving
     * types or aliases. For example, Scala removes the leading {@code _root_.} qualifier.
     *
     * @param name an import path or qualified reference extracted from source
     * @return the normalized name, or the original name when no normalization is needed
     */
    String normalizeName(String name);
}
