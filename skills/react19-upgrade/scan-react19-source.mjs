#!/usr/bin/env node

/**
 * React 19 source-tree scanner.
 *
 * Complements `scan-react19.mjs`, which scans built production bundles. Source
 * scanning also covers tests, Storybook configuration, dev entrypoints,
 * package metadata, and JSX configuration that production bundles can omit.
 *
 * Configuration via environment variables:
 *   SRC_DIRS        comma-separated source roots to scan
 *   PKG_JSON        package.json path; defaults to the nearest package.json
 *   IGNORE_DIRS     additional comma-separated directory names to skip
 *   FILE_EXTENSIONS comma-separated extensions to scan
 *   OUTPUT_FILE     Markdown report path
 *   OUTPUT_JSON     machine-readable JSON report path
 */
import fs from 'node:fs';
import path from 'node:path';

import { ensureScannerDependencies } from './react19-scanner-dependencies.mjs';

ensureScannerDependencies();

const {
    analyzeJsxConfiguration,
    analyzePackageJson,
    analyzeSource,
    createReport,
    defaultJsonOutput,
    defaultMarkdownOutput,
    defaultSourceDirectories,
    discoverSourceFiles,
    findUpwards,
    writeReports,
} = await import('./react19-scanner-core.mjs');

const cwd = process.cwd();
const packageJsonPath = path.resolve(
    process.env.PKG_JSON || findUpwards(cwd, 'package.json') || path.join(cwd, 'package.json'),
);
if (!fs.existsSync(packageJsonPath)) {
    fail(`package.json not found: ${packageJsonPath}`);
}

const root = path.dirname(packageJsonPath);
const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));
const sourceDirectories = process.env.SRC_DIRS
    ? process.env.SRC_DIRS.split(',').map((directory) => path.resolve(cwd, directory.trim())).filter(Boolean)
    : defaultSourceDirectories(root);
for (const directory of sourceDirectories) {
    if (!fs.existsSync(directory) || !fs.statSync(directory).isDirectory()) {
        fail(`Source directory does not exist: ${directory}`);
    }
}

const ignoredDirectories = new Set([
    'node_modules', 'dist', 'build', '.next', 'out', 'coverage', '.git', '.turbo',
    '.cache', 'target', '.yarn', '.pnp', 'tmp', '.migration-state',
    ...(process.env.IGNORE_DIRS || '').split(',').map((name) => name.trim()).filter(Boolean),
]);
const extensions = new Set((process.env.FILE_EXTENSIONS || '.ts,.tsx,.js,.jsx,.mjs,.cjs,.mts,.cts')
    .split(',').map((extension) => extension.trim()).filter(Boolean));
const files = discoverSourceFiles(sourceDirectories, ignoredDirectories, extensions);

const configuration = analyzeJsxConfiguration(root, packageJson);
const findings = [
    ...analyzePackageJson(packageJsonPath, packageJson),
    ...configuration.findings,
];
const parseErrors = [...configuration.parseErrors];
for (const file of files) {
    const relativeFile = path.relative(root, file);
    const result = analyzeSource({
        source: fs.readFileSync(file, 'utf8'),
        file: relativeFile,
    });
    findings.push(...result.findings);
    parseErrors.push(...result.parseErrors);
}

const outputMarkdown = path.resolve(cwd,
    process.env.OUTPUT_FILE || defaultMarkdownOutput(root, 'react19-source-blockers.md'));

const outputJson = path.resolve(cwd, process.env.OUTPUT_JSON || defaultJsonOutput(outputMarkdown));
const report = createReport('source', root, files.length, findings, parseErrors);
writeReports(report, outputJson, outputMarkdown);

console.log(`Wrote Markdown report to ${outputMarkdown}`);
console.log(`Wrote JSON report to ${outputJson}`);
console.log(`Scanned ${files.length} source files; found ${report.findings.length} issue(s) and ${report.parseErrors.length} parse error(s).`);

function fail(message) {
    console.error(message);
    process.exit(1);
}
