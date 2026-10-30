import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { ensureScannerDependencies } from '../react19-scanner-dependencies.mjs';

ensureScannerDependencies();

const { assessReactTarget } = await import('../react19-target.mjs');
const { analyzeSource } = await import('../react19-scanner-core.mjs');
const target = '19.2.5';
const cli = fileURLToPath(new URL('../assess-react19-target.mjs', import.meta.url));

test('a clean source scan does not hide a React 18 library target mismatch', () => {
    const source = analyzeSource({ source: 'export const Badge = () => <span>Ready</span>;', file: 'src/index.jsx' });
    assert.deepEqual(source.findings, []);

    const result = assessReactTarget({
        devDependencies: { react: '18.3.1', 'react-dom': '18.3.1', '@types/react': '^18.3.0' },
        peerDependencies: { react: '^18', 'react-dom': '^18' },
    }, target, { react: '18.3.1', 'react-dom': '18.3.1', '@types/react': '18.3.25' });

    assert.equal(result.status, 'needs-migration');
    assert.ok(result.declarations.every(({ status }) => status === 'needs-migration'));
    assert.ok(result.installed.every(({ status }) => status === 'needs-migration'));
});

test('installed React 18 still needs alignment when the range admits React 19', () => {
    const result = assessReactTarget({ dependencies: { react: '>=18 <20' } }, target, { react: '18.3.1' });

    assert.equal(result.declarations[0].status, 'compatible');
    assert.equal(result.status, 'needs-migration');
});

test('React DOM and peer declarations are assessed independently', () => {
    const result = assessReactTarget({
        devDependencies: { react: target, 'react-dom': target },
        peerDependencies: { react: '^19', 'react-dom': '^18' },
    }, target, { react: target, 'react-dom': target });

    assert.equal(result.status, 'needs-migration');
    assert.deepEqual(result.declarations.filter(({ status }) => status === 'needs-migration').map(({ name, section }) => ({ name, section })), [
        { name: 'react-dom', section: 'peerDependencies' },
    ]);
});

test('aligned runtime and React 19 types need not share a patch version', () => {
    const result = assessReactTarget({
        devDependencies: { react: target, 'react-dom': target, '@types/react': '^19.2.0', '@types/react-dom': '^19.2.0' },
        peerDependencies: { react: '^18.3 || ^19', 'react-dom': '^19' },
    }, target, { react: target, 'react-dom': target, '@types/react': '19.2.14', '@types/react-dom': '19.2.3' });

    assert.equal(result.status, 'compatible');
    assert.equal(result.declarations.length, 6);
});

test('missing installation, catalog and workspace declarations stay unverified', () => {
    for (const specifier of ['catalog:', 'workspace:*', '^19']) {
        const result = assessReactTarget({ peerDependencies: { react: specifier } }, target);

        assert.equal(result.status, 'unverified');
        assert.equal(result.installed[0].version, null);
    }
});

test('unrelated dependencies do not establish a React target or mutate caller data', () => {
    const pkg = Object.freeze({ dependencies: Object.freeze({ lodash: '^4.0.0' }) });
    const versions = Object.freeze({});
    const result = assessReactTarget(pkg, target, versions);

    assert.equal(result.status, 'not-declared');
    assert.deepEqual(result.declarations, []);
});

test('target assessment requires a resolved React 19 release', () => {
    for (const invalid of [undefined, '19', '^19', '=19.2.5', '18.3.1', '20.0.0']) {
        assert.throws(() => assessReactTarget({}, invalid), /exact React 19 target/);
    }
});

test('accepted target spellings use normalized versions in checks and evidence', () => {
    const pkg = {
        devDependencies: { react: target, 'react-dom': target, '@types/react': '^19' },
        peerDependencies: { react: '^19', 'react-dom': '^19' },
    };

    for (const input of [target, `v${target}`, ` ${target}`, `${target}+build.1`]) {
        const result = assessReactTarget(pkg, input, { react: target, 'react-dom': target, '@types/react': '19.2.18' });

        assert.equal(result.targetVersion, target);
        assert.equal(result.status, 'compatible');
        assert.ok(result.declarations.every(({ status }) => status === 'compatible'));
        assert.ok(result.installed.every(({ status }) => status === 'compatible'));
    }

    const result = assessReactTarget(pkg, target, { react: `${target}+build.1`, 'react-dom': target, '@types/react': '19.2.18' });
    assert.equal(result.status, 'compatible');
});

test('CLI resolves installed packages from the caller project and preserves its files', (t) => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'react19-target-test-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const file = path.join(root, 'package.json');
    const pkg = JSON.stringify({ name: 'widget', devDependencies: { react: '^19' }, peerDependencies: { react: '^19' } });
    fs.writeFileSync(file, pkg);
    fs.writeFileSync(path.join(root, 'yarn.lock'), 'original lockfile');
    fs.mkdirSync(path.join(root, 'node_modules/react'), { recursive: true });
    fs.writeFileSync(path.join(root, 'node_modules/react/package.json'), JSON.stringify({ name: 'react', version: '18.3.1' }));
    const output = path.join(root, 'evidence/target.json');
    const run = spawnSync(process.execPath, [cli, '--package-json', file, '--target', target, '--output', output], { encoding: 'utf8' });

    assert.equal(run.status, 0, run.stderr);
    const report = JSON.parse(fs.readFileSync(output, 'utf8'));
    assert.equal(report.status, 'needs-migration');
    assert.equal(report.installed[0].version, '18.3.1');
    assert.deepEqual(JSON.parse(run.stdout), report);
    assert.equal(fs.readFileSync(file, 'utf8'), pkg);
    assert.equal(fs.readFileSync(path.join(root, 'yarn.lock'), 'utf8'), 'original lockfile');
});
