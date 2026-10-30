import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const skillRoot = path.dirname(fileURLToPath(import.meta.url));
const requiredPackages = [
    '@babel/parser/package.json',
    '@babel/traverse/package.json',
    '@jridgewell/trace-mapping/package.json',
    'semver/package.json',
];

export function ensureScannerDependencies() {
    const dependenciesAreInstalled = requiredPackages.every((packagePath) =>
        fs.existsSync(path.join(skillRoot, 'node_modules', packagePath)));

    if (!dependenciesAreInstalled) {
        console.error('[react19] Installing scanner dependencies with npm install');

        const install = spawnSync(
            'npm',
            ['install', '--no-save', '--package-lock=false', '--ignore-scripts', '--no-audit', '--no-fund'],
            { cwd: skillRoot, env: process.env, stdio: 'inherit' },
        );
        if (install.error || install.status !== 0) {
            throw new Error(
                `Could not install scanner dependencies: ${install.error?.message || `npm exited ${install.status}`}`,
            );
        }
    }

}
