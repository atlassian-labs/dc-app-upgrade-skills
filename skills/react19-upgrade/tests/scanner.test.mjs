import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { ensureScannerDependencies } from '../react19-scanner-dependencies.mjs';

ensureScannerDependencies();

const { analyzeSource, analyzePackageJson } = await import('../react19-scanner-core.mjs');
const sourceScanner = fileURLToPath(new URL('../scan-react19-source.mjs', import.meta.url));
const bundleScanner = fileURLToPath(new URL('../scan-react19.mjs', import.meta.url));

function findingsFor(source, file = 'src/widget.jsx') {
    const result = analyzeSource({ source, file });
    assert.deepEqual(result.parseErrors, []);
    return result.findings.map(({ ruleId, line }) => ({ ruleId, line }));
}

test('separate AMD callbacks retain their own module bindings', () => {
    assert.deepEqual(findingsFor(`
        require(['atlassian/libs/react-dom'], function(lib) { lib.render(element, container); });
        require(['other-library'], function(lib) { lib.render(element, container); });
    `), [{ ruleId: 'react-dom.render', line: 2 }]);
});

test('parameters, block declarations, and catch variables shadow imports', () => {
    assert.deepEqual(findingsFor(`
        import ReactDOM from 'react-dom';
        function draw(ReactDOM) { ReactDOM.render(element, container); }
        { const ReactDOM = otherLibrary; ReactDOM.render(element, container); }
        try {} catch (ReactDOM) { ReactDOM.render(element, container); }
        ReactDOM.render(element, container);
    `), [{ ruleId: 'react-dom.render', line: 6 }]);
});

test('nested functions inherit React aliases and CommonJS aliases stay local', () => {
    assert.deepEqual(findingsFor(`
        function renderWidget() {
            const { render: mount } = require('react-dom');
            return () => mount(element, container);
        }
        function renderOther(mount) { mount(element, container); }
    `), [{ ruleId: 'react-dom.render', line: 4 }]);
});

test('named and anonymous AMD definitions map dependencies to callback parameters', () => {
    assert.deepEqual(findingsFor(`
        define('widget', ['other-library', 'atlassian/libs/react-dom'], function(other, dom) {
            dom.render(element, container);
        });
        define(['atlassian/libs/react-dom'], (dom) => dom.hydrate(element, container));
    `), [
        { ruleId: 'react-dom.render', line: 3 },
        { ruleId: 'react-dom.hydrate', line: 5 },
    ]);
});

test('typed React module parameters retain their module identity', () => {
    assert.deepEqual(findingsFor(`
        import type * as ReactDOMModule from 'react-dom';
        function draw(dom: typeof ReactDOMModule) { dom.render(element, container); }
        function unrelated(dom: OtherModule) { dom.render(element, container); }
    `, 'src/widget.ts'), [{ ruleId: 'react-dom.render', line: 3 }]);
});

test('useReducer recognizes both imported and ambient React reducer types', () => {
    assert.deepEqual(findingsFor(`
        import { useReducer, Reducer as ReducerType } from 'react';
        import * as R from 'react';
        useReducer<React.Reducer<State, Action>>(reducer, state);
        useReducer<R.Reducer<State, Action>>(reducer, state);
        useReducer<ReducerType<State, Action>>(reducer, state);
        function unrelated(React: OtherModule) {
            useReducer<React.Reducer<State, Action>>(reducer, state);
        }
    `, 'src/widget.ts'), [
        { ruleId: 'react-types.use-reducer-generic', line: 4 },
        { ruleId: 'react-types.use-reducer-generic', line: 5 },
        { ruleId: 'react-types.use-reducer-generic', line: 6 },
    ]);
});

test('invalid declarations are reported without crashing the scan', () => {
    const result = analyzeSource({
        source: "import ReactDOM from 'react-dom'; const ReactDOM = other;",
        file: 'src/invalid.js',
    });

    assert.ok(result.parseErrors.length > 0);
});

test('component and element identities do not leak across functions', () => {
    assert.deepEqual(findingsFor(`
        function first() {
            function Widget() { return <div />; }
            Widget.defaultProps = { label: 'default' };
            const element = <Widget />;
            consume(element.ref);
        }
        function second() {
            function Widget() { return 1; }
            Widget.defaultProps = {};
            const element = unrelated;
            consume(element.ref);
        }
    `), [
        { ruleId: 'react.function-default-props', line: 4 },
        { ruleId: 'react.element-ref', line: 6 },
    ]);
});

test('string refs are detected in React createElement props and JSX', () => {
    assert.deepEqual(findingsFor(`
        import React, { createElement as h } from 'react';
        React.createElement('input', { ref: 'input' });
        h('input', { ['ref']: 'other' });
        const view = <input ref="jsx" />;
        React.createElement('input', { ref: callback });
        React.createElement('input', { [ref]: 'not-a-known-ref-key' });
        otherLibrary.createElement('input', { ref: 'unrelated' });
        function draw(React) { React.createElement('input', { ref: 'unrelated' }); }
    `), [
        { ruleId: 'react.string-ref', line: 3 },
        { ruleId: 'react.string-ref', line: 4 },
        { ruleId: 'react.string-ref', line: 5 },
    ]);
});

function scanBundle(t, sourceMapFor, { includeSourceContent = true, source = `import React from 'react'; React.createElement('input', { ref: 'input' });` } = {}) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'react19-scanner-test-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const dist = path.join(root, 'dist');
    fs.mkdirSync(dist);
    fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({
        name: 'app',
        dependencies: { dependency: '1.0.0' },
    }));

    const sourceMap = {
        version: 3,
        names: [],
        mappings: 'AAAA',
        ...sourceMapFor(root),
    };

    fs.writeFileSync(path.join(dist, 'widget.bundle.js'), source);
    fs.writeFileSync(path.join(dist, 'widget.bundle.js.map'), JSON.stringify({
        ...sourceMap,
        ...(includeSourceContent ? { sourcesContent: sourceMap.sources.map(() => source) } : {}),
    }));

    const output = path.join(root, 'report.json');
    const run = spawnSync(process.execPath, [bundleScanner], {
        cwd: root,
        env: {
            ...process.env,
            DIST_DIR: dist,
            PKG_JSON: path.join(root, 'package.json'),
            BUNDLE_GLOB: 'bundle',
            OUTPUT_JSON: output,
            OUTPUT_FILE: path.join(root, 'report.md'),
        },
        encoding: 'utf8',
    });

    assert.equal(run.status, 0, run.stderr);
    return JSON.parse(fs.readFileSync(output, 'utf8'));
}

for (const [name, sourceMapFor] of Object.entries({
    'relative sources': () => ({ sources: ['../src/widget.js'] }),
    'relative sourceRoot': () => ({ sourceRoot: '../src/', sources: ['widget.js'] }),
    'absolute sourceRoot': (root) => ({ sourceRoot: path.join(root, 'src'), sources: ['widget.js'] }),
    'absolute sources': (root) => ({ sources: [path.join(root, 'src/widget.js')] }),
    'file URLs': (root) => ({ sources: [pathToFileURL(path.join(root, 'src/widget.js')).href] }),
    'webpack URLs': () => ({ sources: ['webpack://app/./src/widget.js'] }),
})) {
    test(`bundle attribution resolves ${name} to package-relative paths`, (t) => {
        const report = scanBundle(t, sourceMapFor);
        assert.deepEqual(report.parseErrors, []);
        assert.equal(report.findings.length, 1);
        assert.equal(report.findings[0].ruleId, 'react.string-ref');
        assert.equal(report.findings[0].file, 'src/widget.js');
        assert.equal(report.findings[0].ownership, 'own');
    });
}

test('bundle attribution retains direct and transitive dependency ownership', (t) => {
    const report = scanBundle(t, () => ({
        sourceRoot: '../node_modules/',
        sources: ['dependency/index.js', '@scope/transitive/index.js'],
    }));

    assert.deepEqual(report.parseErrors, []);
    assert.deepEqual(report.findings.map(({ file, ownership, packageName }) => ({ file, ownership, packageName })), [
        { file: 'node_modules/@scope/transitive/index.js', ownership: 'third-party-transitive', packageName: '@scope/transitive' },
        { file: 'node_modules/dependency/index.js', ownership: 'third-party-direct', packageName: 'dependency' },
    ]);
});

test('generated-bundle fallback uses the same resolved sourceRoot', (t) => {
    const report = scanBundle(t, () => ({ sourceRoot: '../src/', sources: ['widget.js'] }), {
        includeSourceContent: false,
    });

    assert.equal(report.parseErrors.length, 1);
    assert.match(report.parseErrors[0].message, /omits sourcesContent/);
    assert.equal(report.findings.length, 1);
    assert.equal(report.findings[0].file, 'src/widget.js');
    assert.equal(report.findings[0].evidence, 'generated-bundle-mapped');
});


test('Enzyme imports, re-exports, types, adapters and bound calls are reported', () => {
    assert.deepEqual(findingsFor(`
        import { mount as render } from 'enzyme';
        import type { ReactWrapper } from 'enzyme';
        export { shallow } from 'enzyme';
        export * from 'enzyme-to-json';
        import Adapter from '@cfaester/enzyme-adapter-react-18';
        render(<Widget />);
        new Adapter();
        function unrelated(render) { render(<Widget />); }
    `, 'tests/widget.tsx'), [
        { ruleId: 'ecosystem.enzyme', line: 2 },
        { ruleId: 'ecosystem.enzyme', line: 3 },
        { ruleId: 'ecosystem.enzyme', line: 4 },
        { ruleId: 'ecosystem.enzyme', line: 5 },
        { ruleId: 'ecosystem.enzyme', line: 6 },
        { ruleId: 'ecosystem.enzyme', line: 7 },
        { ruleId: 'ecosystem.enzyme', line: 8 },
    ]);
});

test('CommonJS aliases report ecosystem usage without following shadowed require', () => {
    assert.deepEqual(findingsFor(`
        const { shallow: render } = require('enzyme');
        render(<Widget />);
        const Enzyme = require('enzyme');
        Enzyme.configure({});
        function other(require) {
            const Enzyme = require('enzyme');
            Enzyme.mount(<Widget />);
        }
        function shadow(Enzyme) { Enzyme.mount(<Widget />); }
    `), [
        { ruleId: 'ecosystem.enzyme', line: 2 },
        { ruleId: 'ecosystem.enzyme', line: 3 },
        { ruleId: 'ecosystem.enzyme', line: 4 },
        { ruleId: 'ecosystem.enzyme', line: 5 },
    ]);
});

test('storiesOf follows ESM, CommonJS and AMD bindings, including aliases and re-exports', () => {
    assert.deepEqual(findingsFor(`
        import { storiesOf as stories } from '@storybook/react';
        stories('Widget', module);
        export { storiesOf as legacyStories } from '@storybook/react';
        const { storiesOf: makeStories } = require('@storybook/react');
        makeStories('Widget', module);
        import * as Storybook from '@storybook/react';
        Storybook.storiesOf('Widget', module);
        define(['@storybook/react'], function(sb) { sb.storiesOf('Widget', module); });
        function unrelated(stories, Storybook) { stories(); Storybook.storiesOf(); }
    `, 'stories/widget.stories.tsx'), [
        { ruleId: 'ecosystem.storybook-stories-of', line: 2 },
        { ruleId: 'ecosystem.storybook-stories-of', line: 3 },
        { ruleId: 'ecosystem.storybook-stories-of', line: 4 },
        { ruleId: 'ecosystem.storybook-stories-of', line: 6 },
        { ruleId: 'ecosystem.storybook-stories-of', line: 8 },
        { ruleId: 'ecosystem.storybook-stories-of', line: 9 },
    ]);
});

test('legacy addon source findings do not require package metadata', () => {
    assert.deepEqual(findingsFor(`
        import '@storybook/addon-info';
        export * from '@storybook/addon-knobs';
        require('@storybook/addon-info');
    `), [
        { ruleId: 'ecosystem.storybook-addon-info', line: 2 },
        { ruleId: 'ecosystem.storybook-addon-knobs', line: 3 },
        { ruleId: 'ecosystem.storybook-addon-info', line: 4 },
    ]);
});

test('unrelated names, comments and string literals do not trigger ecosystem findings', () => {
    assert.deepEqual(findingsFor(`
        import { mount, shallow, storiesOf, ReactWrapper } from 'other-library';
        import storiesOfDefault from '@storybook/react';
        mount(); shallow(); storiesOf(); storiesOfDefault();
        const Enzyme = { configure() {} }; Enzyme.configure();
        // import { mount } from 'enzyme';
        const example = "require('enzyme')";
    `, 'tests/widget.test.js'), []);
});

test('package checks retain ecosystem dependencies and report legacy addons regardless of version', (t) => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'react19-package-test-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const file = path.join(root, 'package.json');
    const pkg = {
        dependencies: { enzyme: '3.11.0' },
        devDependencies: { '@storybook/addon-info': 'latest', '@storybook/addon-knobs': '8.0.0' },
        peerDependencies: { '@wojtekmaj/enzyme-adapter-react-17': '^0.8.0' },
        resolutions: { '@storybook/react': '7.6.0' },
    };
    fs.writeFileSync(file, JSON.stringify(pkg, null, 2));
    const findings = analyzePackageJson(file, pkg);
    assert.deepEqual(findings.map(({ ruleId, packageName }) => [ruleId, packageName]), [
        ['ecosystem.enzyme', 'enzyme'],
        ['ecosystem.storybook-addon-info', '@storybook/addon-info'],
        ['ecosystem.storybook-addon-knobs', '@storybook/addon-knobs'],
        ['ecosystem.enzyme', '@wojtekmaj/enzyme-adapter-react-17'],
        ['ecosystem.storybook-pre-8', '@storybook/react'],
    ]);
    assert.ok(findings.every((finding) => finding.fixSupport === 'manual' && finding.impact === 'ecosystem-policy'));
});

test('both scanner entrypoints report source-only ecosystem findings as manual', (t) => {
    const source = `import { mount } from 'enzyme';
import { storiesOf } from '@storybook/react';
import '@storybook/addon-info';`;
    const bundleReport = scanBundle(t, () => ({ sources: ['../tests/widget.js'] }), { source });
    const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'react19-source-test-')));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    fs.mkdirSync(path.join(root, 'tests'));
    fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({ name: 'app' }));
    fs.writeFileSync(path.join(root, 'tests/widget.js'), source);
    const output = path.join(root, 'source.json');
    const run = spawnSync(process.execPath, [sourceScanner], {
        cwd: root,
        env: { ...process.env, SRC_DIRS: 'tests', PKG_JSON: path.join(root, 'package.json'), OUTPUT_JSON: output, OUTPUT_FILE: path.join(root, 'source.md') },
        encoding: 'utf8',
    });
    assert.equal(run.status, 0, run.stderr);
    const sourceReport = JSON.parse(fs.readFileSync(output, 'utf8'));
    for (const report of [sourceReport, bundleReport]) {
        assert.deepEqual(report.parseErrors, []);
        assert.deepEqual(report.findings.map(({ ruleId, file, line, fixSupport, impact }) => ({ ruleId, file, line, fixSupport, impact })), [
            { ruleId: 'ecosystem.enzyme', file: 'tests/widget.js', line: 1, fixSupport: 'manual', impact: 'ecosystem-policy' },
            { ruleId: 'ecosystem.storybook-stories-of', file: 'tests/widget.js', line: 2, fixSupport: 'manual', impact: 'ecosystem-policy' },
            { ruleId: 'ecosystem.storybook-addon-info', file: 'tests/widget.js', line: 3, fixSupport: 'manual', impact: 'ecosystem-policy' },
        ]);
    }
});


test('React renderer and test-utils detection remains distinct from ecosystem policy', () => {
    assert.deepEqual(findingsFor(`
        import Renderer from 'react-test-renderer';
        import ShallowRenderer from 'react-test-renderer/shallow';
        import { act, Simulate } from 'react-dom/test-utils';
        Renderer.create(<Widget />);
    `), [
        { ruleId: 'react-test-renderer.deprecated', line: 2 },
        { ruleId: 'react-test-renderer.shallow', line: 3 },
        { ruleId: 'react-dom-test-utils.act', line: 4 },
        { ruleId: 'react-dom-test-utils.removed-api', line: 4 },
        { ruleId: 'react-test-renderer.deprecated', line: 5 },
    ]);
});


test('ecosystem subpath imports include addon registration and Enzyme helpers', () => {
    assert.deepEqual(findingsFor(`
        import '@storybook/addon-knobs/register';
        require('@storybook/addon-info/register');
        export * from 'enzyme/build/ReactWrapper';
    `), [
        { ruleId: 'ecosystem.storybook-addon-knobs', line: 2 },
        { ruleId: 'ecosystem.storybook-addon-info', line: 3 },
        { ruleId: 'ecosystem.enzyme', line: 4 },
    ]);
});

test('CommonJS property access resolves direct and aliased storiesOf calls', () => {
    assert.deepEqual(findingsFor(`
        require('@storybook/react').storiesOf('Widget', module);
        const stories = require('@storybook/react').storiesOf;
        stories('Widget', module);
        function unrelated(require) {
            require('@storybook/react').storiesOf('Widget', module);
            const stories = require('@storybook/react').storiesOf;
            stories('Widget', module);
        }
        function shadow(stories) { stories('Widget', module); }
    `), [
        { ruleId: 'ecosystem.storybook-stories-of', line: 2 },
        { ruleId: 'ecosystem.storybook-stories-of', line: 4 },
    ]);
});
