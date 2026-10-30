/**
 * jquery-type-filter.ts
 *
 * Reads grep-style matches from stdin (filePath:lineNo:content) and uses the
 * TypeScript Compiler API to check whether the matched API call is on a
 * jQuery-typed receiver.  Outputs only lines where the receiver resolves to a
 * jQuery type (or is unresolvable / `any`, which we keep as potential jQuery).
 *
 * Usage:
 *   cat grep-matches.txt | npx ts-node jquery-type-filter.ts [tsconfig.json]
 *   — or —
 *   cat grep-matches.txt | node -e "require('typescript')" ... (compiled)
 *
 * Input format  (one per line):  filePath:lineNumber:sourceLineContent
 * Output format (one per line):  filePath:lineNumber:sourceLineContent
 *   (only lines that are jQuery-related are emitted)
 */

const ts = require('typescript');
const fs = require('fs');
const path = require('path');
const readline = require('readline');

// ── jQuery type identifiers we consider a match ─────────────────────────────
const JQUERY_TYPE_PATTERNS = [
    /^JQuery\b/,
    /^JQueryStatic\b/,
    /^JQueryXHR\b/,
    /^JQueryDeferred\b/,
    /^JQueryPromise\b/,
    /^JQueryCallback\b/,
    /^JQueryAjaxSettings\b/,
    /^JQueryGenericPromise\b/,
    /^JQueryEventObject\b/,
    /^JQueryMouseEventObject\b/,
    /^JQueryKeyEventObject\b/,
    /^JQueryInputEventObject\b/,
    /^BaseJQueryEventObject\b/,
    /^JQueryEventConstructor\b/,
    /^JQueryAnimationOptions\b/,
    /^JQueryCoordinates\b/,
    /^JQuerySerializeArrayElement\b/,
    /^JQuerySupport\b/,
    /^JQueryParam\b/,
    /^JQueryPromiseCallback\b/,
    // Namespaced jQuery types
    /^jQuery\./,
    /^JQuery\./,
];

// Instance method patterns we grep for (dot-prefixed like .click( .find( etc.)
// These need a receiver to type-check.
const INSTANCE_METHOD_RE = /\.([a-zA-Z]+)\s*\(/;

// Static method patterns ($.xxx( or jQuery.xxx()
const STATIC_METHOD_RE = /(\$|jQuery)\s*\.\s*[a-zA-Z]/;

// Direct jQuery constructor call: $( or jQuery(
const CONSTRUCTOR_RE = /(\$|jQuery)\s*\(/;

// ── Cypress type patterns to exclude ────────────────────────────────────────
// Cypress Chainable wraps JQuery internally, so we must check for Cypress
// types BEFORE checking for jQuery types.
const CYPRESS_TYPE_PATTERNS = [
    /Chainable/, // Cypress.Chainable<...>
    /Cypress\./, // Cypress namespace
    /cy\b/, // cy global
];

const CYPRESS_FILE_PATTERNS = [/\/cypress\//i, /cypress\.d\.ts/, /cypress-/, /@cypress\//];

// File path patterns that indicate a Cypress test file
const CYPRESS_TEST_PATH_PATTERNS = [/cypress\//i, /\.cypress\./, /cypress-tests?\//i, /insight-test-cypress\//];

// Source-level patterns that indicate Cypress usage on a line
const CYPRESS_SOURCE_PATTERNS = [
    /\bcy\./, // cy.get(), cy.find(), etc.
    /\bCypress\./, // Cypress.xxx
];

function isCypressType(typeStr) {
    for (const pat of CYPRESS_TYPE_PATTERNS) {
        if (pat.test(typeStr)) return true;
    }
    return false;
}

function isCypressDeclaration(fileName) {
    for (const pat of CYPRESS_FILE_PATTERNS) {
        if (pat.test(fileName)) return true;
    }
    return false;
}

function isJQueryType(typeStr) {
    // Cypress Chainable<JQuery<E>> contains "JQuery" but is NOT a jQuery call
    if (isCypressType(typeStr)) return false;

    // If the type string contains any jQuery type name, it's jQuery
    for (const pat of JQUERY_TYPE_PATTERNS) {
        if (pat.test(typeStr)) return true;
    }
    // Check for jQuery namespace in the type string
    if (typeStr.includes('JQuery') || typeStr.includes('jQuery')) return true;
    return false;
}

function isAmbiguousType(typeStr) {
    // Types we can't determine — keep them as potential jQuery usage
    return typeStr === 'any' || typeStr === 'unknown' || typeStr === 'error' || typeStr === '';
}

/**
 * Check if a symbol's declarations all come from Cypress type files.
 * If ANY declaration is from Cypress (and none from jQuery), it's Cypress.
 */
function isSymbolFromCypress(symbol) {
    const declarations = symbol.getDeclarations();
    if (!declarations || declarations.length === 0) return false;

    let hasCypress = false;
    let hasJQuery = false;

    for (const decl of declarations) {
        const fileName = decl.getSourceFile().fileName;
        if (isCypressDeclaration(fileName)) {
            hasCypress = true;
        } else if (
            fileName.includes('@types/jquery') ||
            fileName.includes('jquery.d.ts') ||
            fileName.toLowerCase().includes('jquery')
        ) {
            hasJQuery = true;
        }
    }

    // If it has Cypress declarations but no direct jQuery declarations, it's Cypress
    return hasCypress && !hasJQuery;
}

/**
 * Check if a symbol's declarations come from jQuery type files.
 */
function isSymbolFromJQuery(symbol) {
    const declarations = symbol.getDeclarations();
    if (!declarations) return false;

    for (const decl of declarations) {
        const fileName = decl.getSourceFile().fileName;
        if (
            fileName.includes('@types/jquery') ||
            fileName.includes('jquery.d.ts') ||
            fileName.toLowerCase().includes('jquery')
        ) {
            // But NOT if it's also from Cypress
            if (!isCypressDeclaration(fileName)) {
                return true;
            }
        }
    }
    return false;
}

function main() {
    const tsconfigPath = process.argv[2] || 'tsconfig.json';

    // Parse tsconfig
    const configFile = ts.readConfigFile(tsconfigPath, ts.sys.readFile);
    if (configFile.error) {
        console.error(`Error reading tsconfig: ${ts.flattenDiagnosticMessageText(configFile.error.messageText, '\n')}`);
        process.exit(1);
    }

    const configDir = path.dirname(path.resolve(tsconfigPath));
    const parsedConfig = ts.parseJsonConfigFileContent(configFile.config, ts.sys, configDir);

    // Collect unique files from stdin first, then create program
    const inputLines = [];
    const fileSet = new Set();

    const stdinContent = fs.readFileSync(0, 'utf-8');
    for (const raw of stdinContent.split('\n')) {
        if (!raw.trim()) continue;
        const firstColon = raw.indexOf(':');
        if (firstColon < 0) continue;
        const secondColon = raw.indexOf(':', firstColon + 1);
        if (secondColon < 0) continue;

        const filePath = raw.substring(0, firstColon);
        const lineNo = Number.parseInt(raw.substring(firstColon + 1, secondColon), 10);
        const content = raw.substring(secondColon + 1);

        if (Number.isNaN(lineNo)) continue;

        // Only process .ts and .tsx files
        if (!filePath.endsWith('.ts') && !filePath.endsWith('.tsx')) {
            // Pass through non-TS files unchanged
            console.log(raw);
            continue;
        }

        inputLines.push({ filePath, lineNo, content, raw });
        fileSet.add(path.resolve(filePath));
    }

    if (inputLines.length === 0) {
        return;
    }

    // Create program with only the files we need + the config's files
    // (we need config files for type resolution context)
    const allFiles = [...new Set([...parsedConfig.fileNames, ...fileSet])];

    const program = ts.createProgram({
        rootNames: allFiles,
        options: {
            ...parsedConfig.options,
            // Relax for speed — we only need type info, not full checking
            noEmit: true,
            skipLibCheck: true,
            noUnusedLocals: false,
            noUnusedParameters: false,
        },
    });

    const checker = program.getTypeChecker();

    // Process each match
    let kept = 0;
    let filtered = 0;
    let cypressFiltered = 0;

    for (const entry of inputLines) {
        // ── Early Cypress file+source check ──
        // If the file is in a Cypress test directory and the line uses cy./Cypress.,
        // skip it without type-checking (these files are excluded from tsconfig).
        const isCypressFile = CYPRESS_TEST_PATH_PATTERNS.some(p => p.test(entry.filePath));
        if (isCypressFile) {
            const lineHasCypress = CYPRESS_SOURCE_PATTERNS.some(p => p.test(entry.content));
            if (lineHasCypress) {
                cypressFiltered++;
                filtered++;
                continue;
            }
            // Even in Cypress files, lines without cy./Cypress. could still be
            // chained calls (e.g. .find(...) on a previous cy.get()). If the
            // file is excluded from tsconfig, we can't type-check it, so filter
            // all matches from Cypress test files.
            cypressFiltered++;
            filtered++;
            continue;
        }

        const absPath = path.resolve(entry.filePath);
        const sourceFile = program.getSourceFile(absPath);

        if (!sourceFile) {
            // Can't resolve the file — keep the match to be safe
            console.log(entry.raw);
            kept++;
            continue;
        }

        // Check if the line has a static call ($.xxx or jQuery.xxx) — always jQuery
        if (STATIC_METHOD_RE.test(entry.content) || CONSTRUCTOR_RE.test(entry.content)) {
            // If it matches $. or jQuery( patterns, it's definitely jQuery
            // But we should still check if $ is actually jQuery
            const isJq = checkLineForJQuery(sourceFile, checker, entry.lineNo, entry.content);
            if (isJq) {
                console.log(entry.raw);
                kept++;
            } else {
                filtered++;
            }
            continue;
        }

        // Instance method call — need to check receiver type
        const isJq = checkLineForJQuery(sourceFile, checker, entry.lineNo, entry.content);
        if (isJq) {
            console.log(entry.raw);
            kept++;
        } else {
            filtered++;
        }
    }

    console.error(`Type filter: kept ${kept}, filtered out ${filtered} matches (${cypressFiltered} Cypress)`);
}

/**
 * Check if a line in the source file contains a jQuery-related call.
 * Walks the AST nodes on the given line and checks types of call expressions.
 */
function checkLineForJQuery(
    sourceFile,
    checker,
    lineNo, // 1-based
    _content
) {
    const lineIndex = lineNo - 1; // 0-based
    const lineStarts = sourceFile.getLineStarts();

    if (lineIndex < 0 || lineIndex >= lineStarts.length) {
        return true; // Can't resolve — keep it
    }

    const lineStart = lineStarts[lineIndex];
    const lineEnd = lineIndex + 1 < lineStarts.length ? lineStarts[lineIndex + 1] : sourceFile.getEnd();

    // Walk all nodes on this line and look for call expressions
    let foundJQuery = false;
    let foundAnyCall = false;

    function visit(node) {
        // Check if node is on our target line
        const nodeStart = node.getStart(sourceFile);
        const nodeEnd = node.getEnd();

        // Skip nodes entirely before or after our line
        if (nodeEnd < lineStart || nodeStart >= lineEnd) return;

        if (ts.isCallExpression(node)) {
            foundAnyCall = true;
            const expr = node.expression;

            if (ts.isPropertyAccessExpression(expr)) {
                // e.g. something.click() — check type of `something`
                const receiver = expr.expression;
                const type = checker.getTypeAtLocation(receiver);
                const typeStr = checker.typeToString(type);

                // Check Cypress FIRST — Chainable<JQuery<E>> contains "JQuery"
                if (isCypressType(typeStr)) {
                    // Definitely Cypress, not jQuery — skip
                    return;
                }

                if (isJQueryType(typeStr) || isAmbiguousType(typeStr)) {
                    foundJQuery = true;
                    return;
                }

                // Also check if the property itself is from jQuery's type
                // (the method might be on a union type that includes jQuery)
                const symbol = checker.getSymbolAtLocation(expr);
                if (symbol) {
                    // Check Cypress declarations first
                    if (isSymbolFromCypress(symbol)) {
                        return; // Cypress, not jQuery
                    }

                    const declType = checker.getTypeOfSymbolAtLocation(symbol, expr);
                    const declTypeStr = checker.typeToString(declType);
                    if (
                        !isCypressType(declTypeStr) &&
                        (declTypeStr.includes('JQuery') || declTypeStr.includes('jQuery'))
                    ) {
                        foundJQuery = true;
                        return;
                    }

                    // Check the declaration source files
                    if (isSymbolFromJQuery(symbol)) {
                        foundJQuery = true;
                        return;
                    }
                }
            } else if (ts.isIdentifier(expr)) {
                // Direct call like $(...) or jQuery(...)
                const name = expr.text;
                if (name === '$' || name === 'jQuery') {
                    // Check if this identifier resolves to jQuery
                    const symbol = checker.getSymbolAtLocation(expr);
                    if (symbol) {
                        // Check Cypress first
                        if (isSymbolFromCypress(symbol)) {
                            return;
                        }

                        const type = checker.getTypeOfSymbolAtLocation(symbol, expr);
                        const typeStr = checker.typeToString(type);
                        if (isCypressType(typeStr)) {
                            return; // Cypress, not jQuery
                        }
                        if (isJQueryType(typeStr) || isAmbiguousType(typeStr) || typeStr.includes('JQueryStatic')) {
                            foundJQuery = true;
                            return;
                        }
                        // Check declaration file
                        if (isSymbolFromJQuery(symbol)) {
                            foundJQuery = true;
                            return;
                        }
                    } else {
                        // Can't resolve $ — assume it's jQuery
                        foundJQuery = true;
                        return;
                    }
                }
            } else if (ts.isElementAccessExpression(expr)) {
                // e.g. something['click']() — check receiver
                const receiver = expr.expression;
                const type = checker.getTypeAtLocation(receiver);
                const typeStr = checker.typeToString(type);

                if (isCypressType(typeStr)) {
                    return; // Cypress, not jQuery
                }
                if (isJQueryType(typeStr) || isAmbiguousType(typeStr)) {
                    foundJQuery = true;
                    return;
                }
            }
        }

        // Also check for property access without call (e.g. $.noop, $.support)
        if (ts.isPropertyAccessExpression(node) && !ts.isCallExpression(node.parent)) {
            const receiver = node.expression;
            if (ts.isIdentifier(receiver) && (receiver.text === '$' || receiver.text === 'jQuery')) {
                const symbol = checker.getSymbolAtLocation(receiver);
                if (symbol) {
                    if (isSymbolFromCypress(symbol)) {
                        return; // Cypress, not jQuery
                    }
                    if (isSymbolFromJQuery(symbol)) {
                        foundJQuery = true;
                        return;
                    }
                } else {
                    foundJQuery = true; // Can't resolve — assume jQuery
                    return;
                }
            }
        }

        ts.forEachChild(node, visit);
    }

    visit(sourceFile);

    // If we didn't find any call expressions on this line, keep it (grep matched
    // something textual we couldn't parse as AST — safer to keep)
    if (!foundAnyCall && !foundJQuery) {
        return true;
    }

    return foundJQuery;
}

main();
