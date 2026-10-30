#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { parseArgs } from 'node:util';

import { ensureScannerDependencies } from './react19-scanner-dependencies.mjs';

const { values } = parseArgs({
    options: {
        'package-json': { type: 'string', default: 'package.json' },
        target: { type: 'string' },
        output: { type: 'string' },
    },
});

ensureScannerDependencies();

const { assessReactTarget } = await import('./react19-target.mjs');
const packageJsonPath = path.resolve(values['package-json']);
const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));
const requireFromPackage = createRequire(packageJsonPath);
const assessment = assessReactTarget(packageJson, values.target);
const installedVersions = Object.fromEntries(assessment.installed.map(({ name }) => {
    try {
        const file = requireFromPackage.resolve(`${name}/package.json`);
        return [name, JSON.parse(fs.readFileSync(file, 'utf8')).version];
    } catch {
        return [name, null];
    }
}));

const report = {
    packageJson: packageJsonPath,
    ...assessReactTarget(packageJson, values.target, installedVersions),
};

const output = JSON.stringify(report, null, 2) + '\n';

if (values.output) {
    const destination = path.resolve(values.output);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.writeFileSync(destination, output);
}

process.stdout.write(output);
