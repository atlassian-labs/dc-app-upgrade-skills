#!/usr/bin/env node

/**
 * React 19 deprecated-API scanner for built bundles.
 *
 * Reads production JavaScript recursively, requires adjacent source maps, and
 * analyzes their original `sourcesContent`. Findings are attributed to own
 * code, direct dependencies, or transitive dependencies.
 *
 * Configuration via environment variables:
 *   DIST_DIR        directory containing bundles and source maps
 *   PKG_JSON        package.json used for dependency ownership
 *   BUNDLE_GLOB     `bundle` for *.bundle.js or `js` for ordinary *.js files
 *   OUTPUT_FILE     Markdown report path
 *   OUTPUT_JSON     machine-readable JSON report path
 */
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

import { ensureScannerDependencies } from './react19-scanner-dependencies.mjs';

ensureScannerDependencies();

const { originalPositionFor, sourceContentFor, TraceMap } =
    await import('@jridgewell/trace-mapping');
const {
    analyzeSource,
    classifyBundleSource,
    createReport,
    defaultJsonOutput,
    defaultMarkdownOutput,
    findUpwards,
    topLevelDependencies,
    writeReports,
} = await import('./react19-scanner-core.mjs');

const cwd = process.cwd();
const distributionDirectory = path.resolve(cwd, process.env.DIST_DIR || 'dist');
if (!fs.existsSync(distributionDirectory) || !fs.statSync(distributionDirectory).isDirectory()) {
    fail(`DIST_DIR does not exist: ${distributionDirectory}`);
}

const packageJsonPath = path.resolve(
    process.env.PKG_JSON || findUpwards(distributionDirectory, 'package.json') || path.join(cwd, 'package.json'),
);
if (!fs.existsSync(packageJsonPath)) {
    fail(`package.json not found: ${packageJsonPath}`);
}
const root = path.dirname(packageJsonPath);
const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));
const directDependencies = topLevelDependencies(packageJson);
const bundleMode = (process.env.BUNDLE_GLOB || 'bundle').toLowerCase();
const bundleSuffix = bundleMode === 'js' ? '.js' : '.bundle.js';
const bundles = recursivelyFind(distributionDirectory, (file) =>
    file.endsWith(bundleSuffix) && !file.endsWith('.map'));
if (!bundles.length) {
    fail(`No bundles ending in ${bundleSuffix} found under ${distributionDirectory}`);
}

const findings = [];
const parseErrors = [];
const scannedSources = new Set();

for (const bundle of bundles) {
    const sourceMapPath = `${bundle}.map`;
    if (!fs.existsSync(sourceMapPath)) {
        parseErrors.push({
            file: relativeToRoot(bundle),
            message: 'Bundle has no adjacent source map; findings cannot be attributed reliably.',
        });
        continue;
    }

    let traceMap;
    try {
        const rawSourceMap = JSON.parse(fs.readFileSync(sourceMapPath, 'utf8'));
        traceMap = new TraceMap(rawSourceMap, pathToFileURL(sourceMapPath).href);
    } catch (error) {
        parseErrors.push({ file: relativeToRoot(sourceMapPath), message: `Invalid source map: ${error.message}` });
        continue;
    }

    let scannedOriginalSource = false;
    const sources = traceMap.resolvedSources;
    const sourcesContent = traceMap.sourcesContent || [];
    const missingSourceContentCount = sources.filter((_, index) => typeof sourcesContent[index] !== 'string').length;
    if (missingSourceContentCount > 0) {
        parseErrors.push({
            file: relativeToRoot(sourceMapPath),
            message: `Source map omits sourcesContent for ${missingSourceContentCount} of ${sources.length} source(s); bundle attribution is incomplete.`,
        });
    }
    for (let index = 0; index < sources.length; index++) {
        if (typeof sourcesContent[index] !== 'string') {
            continue;
        }
        scannedOriginalSource = true;
        const classification = classifyBundleSource(sources[index], packageJson, directDependencies, root);
        const sourceKey = `${classification.file}\u0000${sourcesContent[index]}`;
        if (scannedSources.has(sourceKey)) {
            continue;
        }
        scannedSources.add(sourceKey);

        const result = analyzeSource({
            source: sourcesContent[index],
            file: classification.file,
            evidence: 'bundle-sourcemap-source',
        });
        findings.push(...result.findings.map((finding) => ({
            ...finding,
            ownership: classification.ownership,
            packageName: classification.packageName,
            bundle: relativeToRoot(bundle),
        })));
        parseErrors.push(...result.parseErrors);
    }

    if (!scannedOriginalSource) {
        scanGeneratedBundle(bundle, traceMap);
    }
}

const outputMarkdown = path.resolve(cwd,
    process.env.OUTPUT_FILE || defaultMarkdownOutput(root, 'react19-bundle-blockers.md'));

const outputJson = path.resolve(cwd, process.env.OUTPUT_JSON || defaultJsonOutput(outputMarkdown));
const report = createReport('bundle', root, bundles.length, findings, parseErrors);
writeReports(report, outputJson, outputMarkdown);

console.log(`Wrote Markdown report to ${outputMarkdown}`);
console.log(`Wrote JSON report to ${outputJson}`);
console.log(`Scanned ${bundles.length} bundle(s) and ${scannedSources.size} mapped source file(s); found ${report.findings.length} issue(s).`);
console.log(`Direct third-party findings: ${report.findings.filter((finding) => finding.ownership === 'third-party-direct').length}`);
console.log(`Transitive third-party findings: ${report.findings.filter((finding) => finding.ownership === 'third-party-transitive').length}`);

function scanGeneratedBundle(bundle, traceMap) {
    const generatedSource = fs.readFileSync(bundle, 'utf8');
    const generatedFile = relativeToRoot(bundle);
    const result = analyzeSource({
        source: generatedSource,
        file: generatedFile,
        kind: 'bundle',
        evidence: 'generated-bundle',
    });
    parseErrors.push(...result.parseErrors);

    for (const finding of result.findings) {
        const original = originalPositionFor(traceMap, { line: finding.line, column: Math.max(0, finding.column - 1) });
        if (!original.source || !original.line) {
            findings.push(finding);
            continue;
        }
        const classification = classifyBundleSource(original.source, packageJson, directDependencies, root);
        let originalSnippet = finding.snippet;
        try {
            const content = sourceContentFor(traceMap, original.source, true);
            if (content) {
                originalSnippet = content.split(/\r?\n/)[original.line - 1]?.trim().replace(/\s+/g, ' ').slice(0, 180)
                    || originalSnippet;
            }
        } catch (_) {
            // Mapping still provides useful ownership and location without sourcesContent.
        }
        findings.push({
            ...finding,
            file: classification.file,
            line: original.line,
            column: (original.column || 0) + 1,
            snippet: originalSnippet,
            ownership: classification.ownership,
            packageName: classification.packageName,
            evidence: 'generated-bundle-mapped',
            bundle: generatedFile,
        });
    }
}

function recursivelyFind(directory, predicate) {
    const matches = [];
    const visit = (currentDirectory) => {
        const entries = fs.readdirSync(currentDirectory, { withFileTypes: true })
            .sort((left, right) => left.name.localeCompare(right.name));
        for (const entry of entries) {
            const entryPath = path.join(currentDirectory, entry.name);
            if (entry.isDirectory()) {
                visit(entryPath);
            } else if (entry.isFile() && predicate(entryPath)) {
                matches.push(entryPath);
            }
        }
    };
    visit(directory);
    return matches;
}

function relativeToRoot(file) {
    return path.relative(root, file).split(path.sep).join('/');
}

function fail(message) {
    console.error(message);
    process.exit(1);
}
