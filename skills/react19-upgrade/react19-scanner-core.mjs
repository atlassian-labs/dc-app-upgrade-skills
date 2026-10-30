import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { parse as parseJavaScript } from '@babel/parser';
import babelTraverse from '@babel/traverse';

const traverse = babelTraverse.default;

export const SCHEMA_VERSION = 1;

export const RULES = Object.freeze({
    'react-dom.render': rule('runtime-break', 'high', 'contextual', 'ReactDOM.render was removed', 'Use createRoot from react-dom/client and retain one root per container.'),
    'react-dom.hydrate': rule('runtime-break', 'high', 'contextual', 'ReactDOM.hydrate was removed', 'Use hydrateRoot from react-dom/client.'),
    'react-dom.unmount-component-at-node': rule('runtime-break', 'high', 'contextual', 'unmountComponentAtNode was removed', 'Unmount the corresponding createRoot or hydrateRoot instance.'),
    'react-dom.find-dom-node': rule('runtime-break', 'high', 'contextual', 'findDOMNode was removed', 'Thread an explicit ref to the intended DOM node.'),
    'react-dom.unstable-render-subtree': rule('runtime-break', 'high', 'manual', 'unstable_renderSubtreeIntoContainer was removed', 'Redesign the ownership boundary, usually with createPortal and explicit context.'),
    'react-dom.unstable-flush-controlled': rule('runtime-break', 'high', 'manual', 'unstable_flushControlled was removed', 'Remove the internal scheduling dependency and use a supported public API.'),
    'react-dom.unstable-create-event-handle': rule('runtime-break', 'high', 'manual', 'unstable_createEventHandle was removed', 'Replace the unstable event API with supported DOM or React events.'),
    'react-dom.unstable-run-with-priority': rule('runtime-break', 'high', 'manual', 'unstable_runWithPriority was removed', 'Remove the React DOM scheduling dependency or use the supported scheduler package deliberately.'),
    'react.create-factory': rule('runtime-break', 'high', 'deterministic', 'React.createFactory was removed', 'Replace the local factory call with JSX or React.createElement.'),
    'react.legacy-context': rule('runtime-break', 'high', 'contextual', 'Legacy context was removed', 'Replace contextTypes, childContextTypes, and getChildContext with createContext.'),
    'react.string-ref': rule('runtime-break', 'high', 'contextual', 'String refs were removed', 'Replace the string ref with a callback ref or createRef/useRef.'),
    'react.module-pattern-factory': rule('runtime-break', 'high', 'deterministic', 'Module-pattern component factories were removed', 'Return the rendered React node directly from the local component.'),
    'react.function-default-props': rule('behavior-change', 'high', 'deterministic', 'Function-component defaultProps were removed', 'Move local defaults into the function parameter destructuring.'),
    'react.function-prop-types': rule('behavior-change', 'high', 'manual', 'Function-component propTypes checks are ignored', 'Replace runtime propTypes with TypeScript or another explicit validator.'),
    'react.use-form-state': rule('runtime-break', 'high', 'contextual', 'useFormState was renamed', 'Use useActionState after verifying tuple usage and arguments.'),
    'react.element-ref': rule('deprecation', 'high', 'deterministic', 'element.ref is deprecated', 'Read element.props.ref instead.'),
    'react.internal-api': rule('runtime-break', 'high', 'manual', 'React internal APIs are not upgrade compatible', 'Remove the dependency on React internals.'),
    'react-dom-test-utils.act': rule('deprecation', 'high', 'deterministic', 'act moved out of react-dom/test-utils', 'Import act from react.'),
    'react-dom-test-utils.removed-api': rule('runtime-break', 'high', 'manual', 'A non-act react-dom/test-utils API errors in React 19', 'Replace it with a user-facing testing-library interaction.'),
    'react-test-renderer.shallow': rule('runtime-break', 'high', 'manual', 'react-test-renderer/shallow was removed', 'Prefer React Testing Library; react-shallow-renderer is only a temporary compatibility option.'),
    'react-test-renderer.deprecated': rule('deprecation', 'high', 'manual', 'react-test-renderer is deprecated and uses concurrent rendering', 'Plan migration to React Testing Library; do not classify this warning alone as a runtime removal.'),
    'react-types.ref-callback-return': rule('type-error', 'high', 'deterministic', 'Implicit ref callback returns conflict with ref cleanup typing', 'Use a block-bodied callback that does not return the assignment value.'),
    'react-types.use-ref-argument': rule('type-error', 'high', 'deterministic', 'useRef now requires an argument', 'Pass undefined or the intended initial value.'),
    'react-types.react-element-props': rule('type-error', 'medium', 'manual', 'ReactElement props now default to unknown', 'Supply the intended props type argument instead of relying on any.'),
    'react-types.jsx-namespace': rule('type-error', 'high', 'contextual', 'The global JSX namespace moved under React', 'Use React.JSX or augment the module selected by the configured JSX runtime.'),
    'react-types.use-reducer-generic': rule('type-error', 'high', 'contextual', 'useReducer generic arguments changed', 'Prefer inference, or pass state and action tuple types in the React 19 form.'),
    'react.jsx-transform': rule('configuration', 'high', 'contextual', 'React 19 requires the modern JSX transform', 'Set the TypeScript/Babel JSX runtime to react-jsx/automatic and rebuild.'),
    'ecosystem.enzyme': rule('ecosystem-policy', 'high', 'manual', 'Enzyme has no React 19 adapter', 'Migrate these tests to React Testing Library.'),
    'ecosystem.storybook-stories-of': rule('ecosystem-policy', 'high', 'manual', 'Legacy Storybook storiesOf API needs migration review', 'Migrate storiesOf stories to Component Story Format separately.'),
    'ecosystem.storybook-addon-info': rule('ecosystem-policy', 'high', 'manual', 'Legacy Storybook addon-info needs migration review', 'Replace addon-info with supported Storybook documentation features.'),
    'ecosystem.storybook-addon-knobs': rule('ecosystem-policy', 'high', 'manual', 'Legacy Storybook addon-knobs needs migration review', 'Migrate knobs to Storybook args and controls separately.'),
    'ecosystem.storybook-pre-8': rule('ecosystem-policy', 'high', 'manual', 'Storybook versions below 8 are not accepted by this readiness policy', 'Upgrade Storybook and migrate legacy stories/addons separately.'),
});

function rule(impact, confidence, fixSupport, summary, remediation) {
    return { impact, confidence, fixSupport, summary, remediation };
}

const REACT_DOM_REMOVALS = new Map([
    ['render', 'react-dom.render'],
    ['hydrate', 'react-dom.hydrate'],
    ['unmountComponentAtNode', 'react-dom.unmount-component-at-node'],
    ['findDOMNode', 'react-dom.find-dom-node'],
    ['unstable_renderSubtreeIntoContainer', 'react-dom.unstable-render-subtree'],
    ['unstable_flushControlled', 'react-dom.unstable-flush-controlled'],
    ['unstable_createEventHandle', 'react-dom.unstable-create-event-handle'],
    ['unstable_runWithPriority', 'react-dom.unstable-run-with-priority'],
]);

const ENZYME_PACKAGES = new Set(['enzyme', 'jest-enzyme', 'enzyme-to-json']);
const STORYBOOK_ADDON_RULES = new Map([
    ['@storybook/addon-info', 'ecosystem.storybook-addon-info'],
    ['@storybook/addon-knobs', 'ecosystem.storybook-addon-knobs'],
]);

const INTERNAL_NAME = /(?:SECRET_INTERNALS|__SECRET_INTERNALS|_DO_NOT_USE_OR_WARN_USERS_THEY_CANNOT_UPGRADE)/;
const REACT_EXTENSIONS = new Set(['.js', '.jsx', '.ts', '.tsx', '.mjs', '.cjs', '.mts', '.cts']);

export function analyzeSource({ source, file, kind = classifyFile(file), evidence = 'source' }) {
    let ast;
    let aliases;
    try {
        ast = parse(source, file);
        aliases = collectAliases(ast);
    } catch (error) {
        return {
            findings: [],
            parseErrors: [{ file, message: oneLine(error.message) }],
        };
    }

    const findings = [];
    const add = (ruleId, node, overrides = {}) => {
        const metadata = RULES[ruleId];
        if (!metadata) {
            throw new Error(`Unknown React 19 rule: ${ruleId}`);
        }
        const line = node?.loc?.start?.line || 1;
        const column = (node?.loc?.start?.column ?? 0) + 1;
        findings.push({
            ruleId,
            impact: metadata.impact,
            confidence: overrides.confidence || metadata.confidence,
            fixSupport: overrides.fixSupport || metadata.fixSupport,
            summary: metadata.summary,
            remediation: metadata.remediation,
            file: normalizePath(file),
            line,
            column,
            snippet: snippetAt(source, line),
            kind,
            ownership: overrides.ownership || 'own',
            packageName: overrides.packageName || null,
            evidence,
        });
    };

    walk(ast, (node, parent) => {
        inspectImport(node, aliases, add);
        inspectEcosystem(node, aliases, add);
        inspectCall(node, aliases, add, isTypeScriptFile(file));
        inspectLegacyContext(node, aliases, add);
        inspectAssignments(node, aliases, add);
        inspectJsx(node, add);
        inspectTypes(node, aliases, add);
        inspectElementRef(node, parent, aliases, add);
        inspectInternals(node, add);
        inspectModulePatternFactory(node, aliases, add);
    });

    const parseErrors = (ast.errors || []).map((error) => ({
        file: normalizePath(file),
        message: oneLine(error.message),
    }));

    return { findings: deduplicateFindings(findings), parseErrors };
}

function parse(source, file) {
    const extension = path.extname(file).toLowerCase();
    const plugins = [
        'jsx',
        'decorators-legacy',
        'classProperties',
        'classPrivateProperties',
        'classPrivateMethods',
        'dynamicImport',
        'importAttributes',
        'topLevelAwait',
    ];
    if (['.ts', '.tsx', '.mts', '.cts'].includes(extension)) {
        plugins.push('typescript');
    }
    return parseJavaScript(source, {
        sourceType: 'unambiguous',
        errorRecovery: true,
        allowReturnOutsideFunction: true,
        plugins,
    });
}

function collectAliases(ast) {
    const bindings = new WeakMap();
    traverse(ast, {
        enter(nodePath) {
            if (nodePath.isIdentifier() || nodePath.isJSXIdentifier()) {
                bindings.set(nodePath.node, nodePath.scope.getBinding(nodePath.node.name));
            }
        },
    });

    const bindingFor = (node) => bindings.get(node) || node;
    const moduleBindings = new Map();
    const modules = {
        get: (node) => moduleBindings.get(bindingFor(node)),
        hasBinding: (node) => Boolean(bindings.get(node)),
        set: (node, target) => moduleBindings.set(bindingFor(node), target),
    };
    const functions = new Set();
    const functionBindings = new WeakMap();
    const classes = new Set();
    const components = new Set();
    const componentClassMembers = new WeakSet();
    const jsxComponents = new Set();
    const reactElements = new Set();

    walk(ast, (node) => {
        if (node.type === 'ImportDeclaration') {
            const moduleName = normalizeReactModule(node.source.value);
            for (const specifier of node.specifiers) {
                const imported = specifier.type === 'ImportSpecifier'
                    ? nameOf(specifier.imported)
                    : '*';
                modules.set(specifier.local, { module: moduleName, imported });
            }
        }

        if (node.type === 'VariableDeclarator') {
            const requiredModule = requiredModuleName(node.init, modules);
            if (requiredModule) {
                registerRequiredAlias(modules, node.id, requiredModule);
            } else if (node.id?.type === 'Identifier') {
                const target = resolveModuleTarget(node.init, modules);
                if (target) {
                    modules.set(node.id, target);
                }
            }
            if (node.id?.type === 'Identifier' && isFunction(node.init)) {
                functions.add(bindingFor(node.id));
                functionBindings.set(node.init, bindingFor(node.id));
            }
            if (node.id?.type === 'Identifier' && isClass(node.init)) {
                classes.add(bindingFor(node.id));
            }
            if (node.id?.type === 'Identifier' && isReactElementExpression(node.init, modules)) {
                reactElements.add(bindingFor(node.id));
            }
        }

        if (node.type === 'FunctionDeclaration' && node.id) {
            functions.add(bindingFor(node.id));
            functionBindings.set(node, bindingFor(node.id));
        }
        if (isFunction(node)) {
            for (const parameter of node.params || []) {
                if (parameter.type !== 'Identifier') {
                    continue;
                }
                const typeAlias = typeQueryIdentifier(parameter.typeAnnotation);
                if (typeAlias && modules.get(typeAlias)) {
                    modules.set(parameter, modules.get(typeAlias));
                }
            }
        }
        if (node.type === 'ClassDeclaration' && node.id) {
            classes.add(bindingFor(node.id));
        }
        if (node.type === 'CallExpression' && isAmdLoader(node.callee)) {
            const argumentOffset = node.callee.name === 'define'
                && node.arguments[0]?.type === 'StringLiteral' ? 1 : 0;
            const [dependencyList, callback] = node.arguments.slice(argumentOffset);
            if (dependencyList?.type !== 'ArrayExpression' || !isFunction(callback)) {
                return;
            }
            dependencyList.elements.forEach((dependency, index) => {
                const parameter = callback.params[index];
                if (dependency?.type === 'StringLiteral' && parameter?.type === 'Identifier') {
                    modules.set(parameter, { module: normalizeReactModule(dependency.value), imported: '*' });
                }
            });
        }
    });

    walk(ast, (node) => {
        if (node.type === 'JSXOpeningElement' && node.name.type === 'JSXIdentifier' && /^[A-Z]/.test(node.name.name)) {
            jsxComponents.add(bindingFor(node.name));
            components.add(bindingFor(node.name));
        }
        if (isFunction(node) && functionReturnsReactElement(node, modules)) {
            const functionBinding = functionBindingOf(node, functionBindings);
            if (functionBinding) {
                components.add(functionBinding);
            }
        }
        if (isReactComponentClass(node, modules)) {
            for (const member of node.body.body) {
                componentClassMembers.add(member);
            }
            if (node.id?.name) {
                components.add(bindingFor(node.id));
            }
        }
        if (node.type === 'VariableDeclarator' && node.id?.type === 'Identifier') {
            if (isFunction(node.init) && functionReturnsReactElement(node.init, modules)) {
                components.add(bindingFor(node.id));
            }
            if (isReactComponentClass(node.init, modules)) {
                components.add(bindingFor(node.id));
            }
        }
        if (node.type === 'CallExpression') {
            const target = resolveModuleTarget(node.callee, modules);
            const component = node.arguments[0];
            if (target?.module === 'react'
                    && target.imported === 'createElement'
                    && component?.type === 'Identifier') {
                components.add(bindingFor(component));
            }
        }
    });

    return {
        bindingFor,
        modules,
        functions,
        functionBindings,
        classes,
        components,
        componentClassMembers,
        jsxComponents,
        reactElements,
    };
}

function typeQueryIdentifier(typeAnnotation) {
    const typeNode = typeAnnotation?.typeAnnotation;
    if (typeNode?.type !== 'TSTypeQuery' || typeNode.exprName?.type !== 'Identifier') {
        return null;
    }
    return typeNode.exprName;
}

function registerRequiredAlias(modules, id, requiredModule) {
    if (id?.type === 'Identifier') {
        modules.set(id, { module: requiredModule, imported: '*' });
        return;
    }
    if (id?.type === 'ObjectPattern') {
        for (const property of id.properties) {
            if (property.type !== 'ObjectProperty' || property.value.type !== 'Identifier') {
                continue;
            }
            modules.set(property.value, { module: requiredModule, imported: nameOf(property.key) });
        }
    }
}

function ecosystemModuleRule(moduleName) {
    if (!moduleName) {
        return null;
    }
    const parts = moduleName.split('/');
    const packageName = parts.slice(0, moduleName.startsWith('@') ? 2 : 1).join('/');
    if (ENZYME_PACKAGES.has(packageName)
            || /^(?:@[^/]+\/)?enzyme-adapter-react-[\w.-]+$/.test(packageName)) {
        return 'ecosystem.enzyme';
    }
    return STORYBOOK_ADDON_RULES.get(packageName) || null;
}

function isStoriesOf(target) {
    return target?.module.startsWith('@storybook/') && target.imported === 'storiesOf';
}

function inspectEcosystem(node, aliases, add) {
    const isModuleDeclaration = ['ImportDeclaration', 'ExportNamedDeclaration', 'ExportAllDeclaration'].includes(node.type);
    const moduleName = isModuleDeclaration ? node.source?.value : requiredModuleName(node, aliases.modules);
    const moduleRule = ecosystemModuleRule(moduleName);
    if (moduleRule) {
        add(moduleRule, node, { packageName: moduleName });
    }

    if (isModuleDeclaration && moduleName) {
        for (const specifier of node.specifiers || []) {
            if (!['ImportSpecifier', 'ExportSpecifier'].includes(specifier.type)) {
                continue;
            }
            const imported = nameOf(specifier.imported || specifier.local);
            if (isStoriesOf({ module: moduleName, imported })) {
                add('ecosystem.storybook-stories-of', specifier, { packageName: moduleName });
            }
        }
    }

    if (['CallExpression', 'OptionalCallExpression', 'NewExpression'].includes(node.type)) {
        const target = resolveModuleTarget(node.callee, aliases.modules);
        const targetRule = ecosystemModuleRule(target?.module);
        if (targetRule) {
            add(targetRule, node.callee, { packageName: target.module });
        }
        if (isStoriesOf(target)) {
            add('ecosystem.storybook-stories-of', node.callee, { packageName: target.module });
        }
    }
}

function inspectImport(node, aliases, add) {
    if (node.type !== 'ImportDeclaration') {
        return;
    }
    const moduleName = normalizeReactModule(node.source.value);
    if (moduleName === 'react-test-renderer') {
        add('react-test-renderer.deprecated', node);
    } else if (moduleName === 'react-test-renderer/shallow') {
        add('react-test-renderer.shallow', node);
    } else if (moduleName === 'react-dom/test-utils') {
        for (const specifier of node.specifiers) {
            if (specifier.type !== 'ImportSpecifier') {
                continue;
            }
            const imported = nameOf(specifier.imported);
            add(imported === 'act' ? 'react-dom-test-utils.act' : 'react-dom-test-utils.removed-api', specifier);
        }
    }
}

function inspectCall(node, aliases, add, typeScriptFile) {
    if (node.type !== 'CallExpression' && node.type !== 'OptionalCallExpression') {
        return;
    }

    const target = resolveModuleTarget(node.callee, aliases.modules);
    if (target?.module === 'react-dom' && REACT_DOM_REMOVALS.has(target.imported)) {
        add(REACT_DOM_REMOVALS.get(target.imported), node.callee);
    }
    if (target?.module === 'react' && target.imported === 'createElement') {
        const props = node.arguments[1];
        if (props?.type === 'ObjectExpression') {
            for (const property of props.properties) {
                if (property.type === 'ObjectProperty'
                        && (!property.computed || property.key.type === 'StringLiteral')
                        && nameOf(property.key) === 'ref'
                        && property.value.type === 'StringLiteral') {
                    add('react.string-ref', property);
                }
            }
        }
    }
    if (target?.module === 'react' && target.imported === 'createFactory') {
        add('react.create-factory', node.callee);
    }
    if ((target?.module === 'react' || target?.module === 'react-dom') && target.imported === 'useFormState') {
        add('react.use-form-state', node.callee);
    }
    if (target?.module === 'react-dom/test-utils') {
        add(target.imported === 'act' ? 'react-dom-test-utils.act' : 'react-dom-test-utils.removed-api', node.callee);
    }
    if (target?.module === 'react-test-renderer') {
        add('react-test-renderer.deprecated', node.callee);
    }
    if (target?.module === 'react-test-renderer/shallow') {
        add('react-test-renderer.shallow', node.callee);
    }
    if (typeScriptFile
            && target?.module === 'react'
            && target.imported === 'useRef'
            && node.arguments.length === 0) {
        add('react-types.use-ref-argument', node);
    }
    if (target?.module === 'react'
            && target.imported === 'useReducer'
            && isLegacyUseReducerGeneric(node, aliases.modules)) {
        add('react-types.use-reducer-generic', node);
    }

    const requiredModule = requiredModuleName(node, aliases.modules);
    if (requiredModule === 'react-test-renderer') {
        add('react-test-renderer.deprecated', node);
    } else if (requiredModule === 'react-test-renderer/shallow') {
        add('react-test-renderer.shallow', node);
    }
}

function inspectLegacyContext(node, aliases, add) {
    if (aliases.componentClassMembers.has(node)
            && (node.type === 'ClassMethod' || node.type === 'ClassPrivateMethod')
            && nameOf(node.key) === 'getChildContext') {
        add('react.legacy-context', node.key);
    }
    if (aliases.componentClassMembers.has(node)
            && (node.type === 'ClassProperty' || node.type === 'PropertyDefinition')) {
        if (['contextTypes', 'childContextTypes'].includes(nameOf(node.key))) {
            add('react.legacy-context', node.key);
        }
    }
}

function inspectAssignments(node, aliases, add) {
    if (node.type !== 'AssignmentExpression' || node.left.type !== 'MemberExpression') {
        return;
    }
    const property = memberPropertyName(node.left);
    const owner = node.left.object.type === 'Identifier' ? aliases.bindingFor(node.left.object) : null;
    if (owner && aliases.components.has(owner) && ['contextTypes', 'childContextTypes'].includes(property)) {
        add('react.legacy-context', node.left);
    }
    if (property === 'defaultProps' && owner && aliases.functions.has(owner)
            && aliases.components.has(owner) && !aliases.classes.has(owner)) {
        add('react.function-default-props', node.left);
    }
    if (property === 'propTypes' && owner && aliases.functions.has(owner)
            && aliases.components.has(owner) && !aliases.classes.has(owner)) {
        add('react.function-prop-types', node.left);
    }
}

function inspectJsx(node, add) {
    if (node.type !== 'JSXAttribute' || node.name.name !== 'ref') {
        return;
    }
    if (node.value?.type === 'StringLiteral') {
        add('react.string-ref', node);
        return;
    }
    const expression = node.value?.type === 'JSXExpressionContainer' ? node.value.expression : null;
    if (expression?.type === 'ArrowFunctionExpression'
            && expression.body.type === 'AssignmentExpression') {
        add('react-types.ref-callback-return', expression);
    }
}

function inspectTypes(node, aliases, add) {
    if (node.type === 'TSTypeReference') {
        const typeName = qualifiedName(node.typeName);
        if ((typeName === 'ReactElement' || typeName === 'React.ReactElement') && !node.typeParameters?.params?.length) {
            add('react-types.react-element-props', node);
        }
        if (typeName.startsWith('JSX.')) {
            add('react-types.jsx-namespace', node);
        }
    }
}

function inspectElementRef(node, parent, aliases, add) {
    if (node.type !== 'MemberExpression' || memberPropertyName(node) !== 'ref') {
        return;
    }
    if (parent?.type === 'AssignmentExpression' && parent.left === node) {
        return;
    }
    if (node.object.type === 'Identifier' && aliases.reactElements.has(aliases.bindingFor(node.object))) {
        add('react.element-ref', node);
    }
}

function inspectInternals(node, add) {
    if (node.type === 'Identifier' && INTERNAL_NAME.test(node.name)) {
        add('react.internal-api', node);
    }
    if ((node.type === 'StringLiteral' || node.type === 'Literal') && INTERNAL_NAME.test(String(node.value))) {
        add('react.internal-api', node);
    }
}

function inspectModulePatternFactory(node, aliases, add) {
    if (!isFunction(node)) {
        return;
    }
    const functionBinding = functionBindingOf(node, aliases.functionBindings);
    if (!functionBinding || !aliases.jsxComponents.has(functionBinding)) {
        return;
    }
    const returnedObjects = returnedObjectExpressions(node);
    if (returnedObjects.some(hasRenderMethod)) {
        add('react.module-pattern-factory', node);
    }
}

function returnedObjectExpressions(functionNode) {
    if (functionNode.type === 'ArrowFunctionExpression' && functionNode.body.type === 'ObjectExpression') {
        return [functionNode.body];
    }
    if (functionNode.body?.type !== 'BlockStatement') {
        return [];
    }
    return functionNode.body.body
        .filter((statement) => statement.type === 'ReturnStatement' && statement.argument?.type === 'ObjectExpression')
        .map((statement) => statement.argument);
}

function hasRenderMethod(objectExpression) {
    return objectExpression.properties.some((property) =>
        (property.type === 'ObjectMethod' || property.type === 'ObjectProperty')
        && nameOf(property.key) === 'render'
        && (property.type === 'ObjectMethod' || isFunction(property.value)));
}

function functionBindingOf(node, functionBindings) {
    return functionBindings.get(node) || null;
}

function isTypeScriptFile(file) {
    return ['.ts', '.tsx', '.mts', '.cts'].includes(path.extname(file).toLowerCase());
}

function resolveModuleTarget(callee, modules) {
    const requiredModule = requiredModuleName(callee, modules);
    if (requiredModule) {
        return { module: requiredModule, imported: '*' };
    }
    if (callee?.type === 'Identifier') {
        return modules.get(callee) || null;
    }
    if (callee?.type !== 'MemberExpression' && callee?.type !== 'OptionalMemberExpression') {
        return null;
    }
    const moduleAlias = resolveModuleTarget(callee.object, modules);
    if (!moduleAlias || moduleAlias.imported !== '*') {
        return null;
    }
    return { module: moduleAlias.module, imported: memberPropertyName(callee) };
}

function requiredModuleName(node, modules) {
    if (node?.type !== 'CallExpression'
            || node.callee?.type !== 'Identifier'
            || node.callee.name !== 'require'
            || modules.hasBinding(node.callee)
            || node.arguments.length !== 1
            || node.arguments[0]?.type !== 'StringLiteral') {
        return null;
    }
    return normalizeReactModule(node.arguments[0].value);
}

function normalizeReactModule(moduleName) {
    const aliases = {
        'atlassian/libs/react': 'react',
        'atlassian/libs/react-dom': 'react-dom',
        'atlassian/libs/react-dom/client': 'react-dom/client',
        'atlassian/libs/react-dom/test-utils': 'react-dom/test-utils',
    };
    return aliases[moduleName] || moduleName;
}

function isAmdLoader(callee) {
    return callee?.type === 'Identifier' && (callee.name === 'require' || callee.name === 'define');
}

function isFunction(node) {
    return node && ['FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression'].includes(node.type);
}

function isClass(node) {
    return node && ['ClassDeclaration', 'ClassExpression'].includes(node.type);
}

function isReactComponentClass(node, modules) {
    if (!isClass(node) || !node.superClass) {
        return false;
    }
    const target = resolveModuleTarget(node.superClass, modules);
    return target?.module === 'react' && ['Component', 'PureComponent'].includes(target.imported);
}

function functionReturnsReactElement(node, modules) {
    if (!isFunction(node)) {
        return false;
    }
    if (node.type === 'ArrowFunctionExpression' && node.body.type !== 'BlockStatement') {
        return isReactElementExpression(node.body, modules);
    }
    return node.body?.type === 'BlockStatement'
        && node.body.body.some((statement) =>
            statement.type === 'ReturnStatement' && isReactElementExpression(statement.argument, modules));
}

function isLegacyUseReducerGeneric(node, modules) {
    const typeParameters = node.typeParameters?.params || node.typeArguments?.params || [];
    if (typeParameters.length !== 1 || typeParameters[0].type !== 'TSTypeReference') {
        return false;
    }

    const reducerNames = new Set(['Reducer', 'ReducerWithoutAction']);
    const typeName = typeParameters[0].typeName;
    if (typeName.type === 'Identifier') {
        const target = modules.get(typeName);
        return target?.module === 'react' && reducerNames.has(target.imported);
    }

    const qualifiedTypeName = qualifiedName(typeName);
    const [, reducerName, ...remaining] = qualifiedTypeName.split('.');
    if (remaining.length > 0 || !reducerNames.has(reducerName)) {
        return false;
    }
    const target = modules.get(typeName.left);
    return (target?.module === 'react' && target.imported === '*')
        || (typeName.left.name === 'React' && !modules.hasBinding(typeName.left));
}

function isReactElementExpression(node, modules) {
    if (!node) {
        return false;
    }
    if (node.type === 'JSXElement' || node.type === 'JSXFragment') {
        return true;
    }
    if (node.type !== 'CallExpression') {
        return false;
    }
    const target = resolveModuleTarget(node.callee, modules);
    return target?.module === 'react' && target.imported === 'createElement';
}

function memberPropertyName(node) {
    if (!node) {
        return null;
    }
    if (!node.computed && (node.property.type === 'Identifier' || node.property.type === 'PrivateName')) {
        return nameOf(node.property);
    }
    if (node.computed && (node.property.type === 'StringLiteral' || node.property.type === 'Literal')) {
        return String(node.property.value);
    }
    return null;
}

function qualifiedName(node) {
    if (!node) {
        return '';
    }
    if (node.type === 'Identifier') {
        return node.name;
    }
    if (node.type === 'TSQualifiedName') {
        return `${qualifiedName(node.left)}.${qualifiedName(node.right)}`;
    }
    return '';
}

function nameOf(node) {
    if (!node) {
        return '';
    }
    if (node.type === 'Identifier' || node.type === 'JSXIdentifier') {
        return node.name;
    }
    if (node.type === 'PrivateName') {
        return nameOf(node.id);
    }
    if (node.type === 'StringLiteral' || node.type === 'Literal') {
        return String(node.value);
    }
    return '';
}

function walk(root, visitor) {
    const visit = (node, parent) => {
        if (!node || typeof node !== 'object' || typeof node.type !== 'string') {
            return;
        }
        visitor(node, parent);
        for (const [key, value] of Object.entries(node)) {
            if (['loc', 'start', 'end', 'extra', 'comments', 'tokens', 'errors'].includes(key)) {
                continue;
            }
            if (Array.isArray(value)) {
                value.forEach((child) => visit(child, node));
            } else {
                visit(value, node);
            }
        }
    };
    visit(root, null);
}

export function analyzePackageJson(packageJsonPath, packageJson) {
    const source = fs.readFileSync(packageJsonPath, 'utf8');
    const dependencies = {
        ...(packageJson.dependencies || {}),
        ...(packageJson.devDependencies || {}),
        ...(packageJson.peerDependencies || {}),
        ...(packageJson.resolutions || {}),
    };
    const findings = [];
    const addDependency = (ruleId, dependency) => {
        const metadata = RULES[ruleId];
        const line = lineContaining(source, `"${dependency}"`);
        findings.push({
            ruleId,
            impact: metadata.impact,
            confidence: metadata.confidence,
            fixSupport: metadata.fixSupport,
            summary: metadata.summary,
            remediation: metadata.remediation,
            file: normalizePath(path.basename(packageJsonPath)),
            line,
            column: 1,
            snippet: snippetAt(source, line),
            kind: 'dependency',
            ownership: 'own',
            packageName: dependency,
            packageVersion: dependencies[dependency],
            evidence: 'package-json',
        });
    };

    for (const [dependency, version] of Object.entries(dependencies)) {
        const ecosystemRule = ecosystemModuleRule(dependency);
        if (ecosystemRule) {
            addDependency(ecosystemRule, dependency);
        }
        if (dependency.startsWith('@storybook/') && majorVersion(version) !== null && majorVersion(version) < 8) {
            addDependency('ecosystem.storybook-pre-8', dependency);
        }
    }
    if (dependencies['react-test-renderer']) {
        addDependency('react-test-renderer.deprecated', 'react-test-renderer');
    }

    return findings;
}

export function analyzeJsxConfiguration(projectRoot, packageJson) {
    const findings = [];
    const parseErrors = [];
    const findingKeys = new Set();
    const addFinding = (file, source, needle) => {
        const key = path.resolve(file);
        if (!findingKeys.has(key)) {
            findingKeys.add(key);
            findings.push(configurationFinding(projectRoot, file, source, needle));
        }
    };

    const configurationFiles = discoverJsxConfigurationFiles(projectRoot);
    const tsconfigCache = new Map();
    for (const tsconfigPath of configurationFiles.tsconfigs) {
        try {
            const legacyConfiguration = resolveLegacyTsconfigJsx(tsconfigPath, tsconfigCache, new Set());
            if (legacyConfiguration) {
                addFinding(
                    legacyConfiguration.file,
                    legacyConfiguration.source,
                    '"jsx"',
                );
            }
        } catch (error) {
            parseErrors.push({
                file: normalizePath(path.relative(projectRoot, tsconfigPath)),
                message: `Unable to parse JSX configuration: ${oneLine(error.message)}`,
            });
        }
    }

    for (const babelConfigPath of configurationFiles.babelConfigs) {
        const source = fs.readFileSync(babelConfigPath, 'utf8');
        try {
            if (babelConfigurationUsesClassicRuntime(babelConfigPath, source)) {
                addFinding(babelConfigPath, source, '@babel/preset-react');
            }
        } catch (error) {
            parseErrors.push({
                file: normalizePath(path.relative(projectRoot, babelConfigPath)),
                message: `Unable to parse JSX configuration: ${oneLine(error.message)}`,
            });
        }
    }

    const babelPresets = packageJson.babel?.presets || [];
    if (babelPresets.some(babelPresetUsesClassicRuntime)) {
        const packageJsonPath = path.join(projectRoot, 'package.json');
        const source = fs.readFileSync(packageJsonPath, 'utf8');
        addFinding(packageJsonPath, source, '@babel/preset-react');
    }
    return { findings, parseErrors };
}

function discoverJsxConfigurationFiles(projectRoot) {
    const tsconfigs = [];
    const babelConfigs = [];
    const ignoredDirectories = new Set([
        'node_modules', 'target', 'dist', 'build', 'coverage', '.git', '.cache', '.next', 'out', '.migration-state',
    ]);
    const visit = (directory) => {
        const entries = fs.readdirSync(directory, { withFileTypes: true })
            .sort((left, right) => left.name.localeCompare(right.name));
        for (const entry of entries) {
            if (entry.isDirectory()) {
                if (!ignoredDirectories.has(entry.name)) {
                    visit(path.join(directory, entry.name));
                }
                continue;
            }
            if (!entry.isFile()) {
                continue;
            }
            const file = path.join(directory, entry.name);
            if (/^tsconfig(?:\.[^.]+)?\.json$/i.test(entry.name)) {
                tsconfigs.push(file);
            } else if (/^\.babelrc(?:\.(?:json|js|cjs|mjs|ts|cts|mts))?$/i.test(entry.name)
                    || /^babel\.config\.(?:json|js|cjs|mjs|ts|cts|mts)$/i.test(entry.name)) {
                babelConfigs.push(file);
            }
        }
    };
    visit(projectRoot);
    return { tsconfigs, babelConfigs };
}

function resolveLegacyTsconfigJsx(tsconfigPath, cache, visiting) {
    const resolvedPath = path.resolve(tsconfigPath);
    if (cache.has(resolvedPath)) {
        return cache.get(resolvedPath);
    }
    if (visiting.has(resolvedPath)) {
        throw new Error(`Circular tsconfig extends chain at ${resolvedPath}`);
    }
    visiting.add(resolvedPath);

    const source = fs.readFileSync(resolvedPath, 'utf8');
    const tsconfig = parseJsonWithComments(source);
    let legacyConfiguration = null;
    if (tsconfig?.compilerOptions?.jsx === 'react') {
        legacyConfiguration = { file: resolvedPath, source };
    } else if (typeof tsconfig?.extends === 'string' && tsconfig.extends.startsWith('.')) {
        const extendedPath = resolveExtendedTsconfig(resolvedPath, tsconfig.extends);
        if (extendedPath) {
            legacyConfiguration = resolveLegacyTsconfigJsx(extendedPath, cache, visiting);
        }
    }

    visiting.delete(resolvedPath);
    cache.set(resolvedPath, legacyConfiguration);
    return legacyConfiguration;
}

function resolveExtendedTsconfig(tsconfigPath, extendedValue) {
    const unresolvedPath = path.resolve(path.dirname(tsconfigPath), extendedValue);
    const candidates = [
        unresolvedPath,
        `${unresolvedPath}.json`,
        path.join(unresolvedPath, 'tsconfig.json'),
    ];
    return candidates.find((candidate) => fs.existsSync(candidate) && fs.statSync(candidate).isFile()) || null;
}

function babelConfigurationUsesClassicRuntime(file, source) {
    if (file.endsWith('.json') || path.basename(file) === '.babelrc') {
        const configuration = parseJsonWithComments(source);
        return (configuration?.presets || []).some(babelPresetUsesClassicRuntime);
    }

    const ast = parse(source, file);
    let usesClassicRuntime = false;
    walk(ast, (node) => {
        if (node.type !== 'ObjectProperty'
                || nameOf(node.key) !== 'presets'
                || node.value.type !== 'ArrayExpression') {
            return;
        }
        if (node.value.elements.some(babelPresetNodeUsesClassicRuntime)) {
            usesClassicRuntime = true;
        }
    });
    if (ast.errors?.length) {
        throw ast.errors[0];
    }
    return usesClassicRuntime;
}

function babelPresetUsesClassicRuntime(preset) {
    const [name, options = {}] = Array.isArray(preset) ? preset : [preset, {}];
    return name === '@babel/preset-react' && options.runtime !== 'automatic';
}

function babelPresetNodeUsesClassicRuntime(node) {
    if (node?.type === 'StringLiteral') {
        return node.value === '@babel/preset-react';
    }
    if (node?.type !== 'ArrayExpression' || node.elements[0]?.type !== 'StringLiteral'
            || node.elements[0].value !== '@babel/preset-react') {
        return false;
    }
    const options = node.elements[1];
    if (options?.type !== 'ObjectExpression') {
        return true;
    }
    const runtime = options.properties.find((property) =>
        property.type === 'ObjectProperty' && nameOf(property.key) === 'runtime');
    return runtime?.value?.type !== 'StringLiteral' || runtime.value.value !== 'automatic';
}

function configurationFinding(projectRoot, file, source, needle) {
    const metadata = RULES['react.jsx-transform'];
    const line = lineContaining(source, needle);
    return {
        ruleId: 'react.jsx-transform',
        impact: metadata.impact,
        confidence: metadata.confidence,
        fixSupport: metadata.fixSupport,
        summary: metadata.summary,
        remediation: metadata.remediation,
        file: normalizePath(path.relative(projectRoot, file)),
        line,
        column: 1,
        snippet: snippetAt(source, line),
        kind: 'configuration',
        ownership: 'own',
        packageName: null,
        evidence: 'configuration',
    };
}

export function classifyFile(file) {
    const normalized = normalizePath(file).toLowerCase();
    if (/\.(test|spec)\.[mc]?[jt]sx?$/.test(normalized) || /(^|\/)__tests__\//.test(normalized)) {
        return 'test';
    }
    if (/(^|\/)__mocks__\//.test(normalized)) {
        return 'mock';
    }
    if (/\.stories\.[mc]?[jt]sx?$/.test(normalized)) {
        return 'story';
    }
    if (/(^|\/)\.storybook\//.test(normalized)) {
        return 'storybook-config';
    }
    if (/(^|\/)(examples?|playground|sandbox|dev|docs|demo)\//.test(normalized)) {
        return 'dev';
    }
    return 'source';
}

export function discoverSourceFiles(sourceDirectories, ignoredDirectoryNames, extensions = REACT_EXTENSIONS) {
    const files = [];
    const visit = (directory) => {
        const entries = fs.readdirSync(directory, { withFileTypes: true })
            .sort((left, right) => left.name.localeCompare(right.name));
        for (const entry of entries) {
            if (entry.isDirectory() && ignoredDirectoryNames.has(entry.name)) {
                continue;
            }
            const entryPath = path.join(directory, entry.name);
            if (entry.isDirectory()) {
                visit(entryPath);
            } else if (entry.isFile() && extensions.has(path.extname(entry.name).toLowerCase())) {
                files.push(entryPath);
            }
        }
    };
    sourceDirectories.forEach(visit);
    return files;
}

export function createReport(scanner, root, filesScanned, findings, parseErrors = []) {
    return {
        schemaVersion: SCHEMA_VERSION,
        scanner,
        root: '.',
        filesScanned,
        findings: deduplicateFindings(findings).sort(compareFindings),
        parseErrors: parseErrors.slice().sort((left, right) => left.file.localeCompare(right.file)),
    };
}

export function writeReports(report, outputJson, outputMarkdown) {
    fs.mkdirSync(path.dirname(outputJson), { recursive: true });
    fs.mkdirSync(path.dirname(outputMarkdown), { recursive: true });
    fs.writeFileSync(outputJson, `${JSON.stringify(report, null, 2)}\n`);
    fs.writeFileSync(outputMarkdown, renderMarkdown(report));
}

export function renderMarkdown(report) {
    const lines = [
        '# React 19 readiness findings',
        '',
        `Scanner: \`${report.scanner}\``,
        '',
        `Scanned files: ${report.filesScanned}`,
        '',
    ];
    const impacts = [
        'runtime-break',
        'behavior-change',
        'type-error',
        'configuration',
        'deprecation',
        'ecosystem-policy',
    ];
    for (const impact of impacts) {
        const matches = report.findings.filter((finding) => finding.impact === impact);
        lines.push(`## ${impact}`);
        lines.push('');
        if (matches.length === 0) {
            lines.push('_None_');
            lines.push('');
            continue;
        }
        lines.push('| Rule | Location | Confidence | Fix support | Evidence |');
        lines.push('|---|---|---|---|---|');
        for (const finding of matches) {
            lines.push(`| \`${escapeMarkdown(finding.ruleId)}\` | \`${escapeMarkdown(finding.file)}:${finding.line}\` | ${finding.confidence} | ${finding.fixSupport} | ${escapeMarkdown(finding.snippet)} |`);
        }
        lines.push('');
    }
    if (report.parseErrors.length) {
        lines.push('## Parse errors');
        lines.push('');
        report.parseErrors.forEach((error) => lines.push(`- \`${escapeMarkdown(error.file)}\`: ${escapeMarkdown(error.message)}`));
        lines.push('');
    }
    return `${lines.join('\n')}\n`;
}

export function classifyBundleSource(rawSource, rootPackage, topLevelDependencies, packageRoot) {
    const normalized = normalizeSourceMapPath(rawSource, rootPackage.name || '', packageRoot);
    const nodeModulesIndex = normalized.lastIndexOf('node_modules/');
    if (nodeModulesIndex >= 0) {
        const packageAndFile = normalized.slice(nodeModulesIndex + 'node_modules/'.length);
        const parts = packageAndFile.split('/');
        const packageName = parts[0].startsWith('@') ? `${parts[0]}/${parts[1] || ''}` : parts[0];
        return {
            file: normalized,
            ownership: topLevelDependencies.has(packageName) ? 'third-party-direct' : 'third-party-transitive',
            packageName,
        };
    }
    return { file: normalized, ownership: 'own', packageName: rootPackage.name || null };
}

export function normalizeSourceMapPath(rawSource, packageName = '', packageRoot) {
    let normalized = String(rawSource || '[unknown]')
        .replace(/^[^!]+!/, '')
        .replace(/^webpack:\/\/+/, '')
        .replace(/[?#].*$/, '')
        .replace(/^\.\//, '');
    if (normalized.startsWith('file:')) {
        normalized = fileURLToPath(normalized);
    }
    if (packageRoot && path.isAbsolute(normalized)) {
        normalized = path.relative(packageRoot, normalized);
    }
    if (packageName && normalized.startsWith(`${packageName}/`)) {
        normalized = normalized.slice(packageName.length + 1);
    }
    return normalizePath(normalized);
}

export function topLevelDependencies(packageJson) {
    return new Set([
        ...Object.keys(packageJson.dependencies || {}),
        ...Object.keys(packageJson.devDependencies || {}),
        ...Object.keys(packageJson.peerDependencies || {}),
    ]);
}

export function findUpwards(startDirectory, fileName) {
    let directory = path.resolve(startDirectory);
    while (true) {
        const candidate = path.join(directory, fileName);
        if (fs.existsSync(candidate)) {
            return candidate;
        }
        const parent = path.dirname(directory);
        if (parent === directory) {
            return null;
        }
        directory = parent;
    }
}

export function defaultSourceDirectories(root) {
    const candidates = [
        'packages', 'src', 'app', 'apps', 'components', 'lib', '.storybook', 'storybook',
        'dev', 'examples', 'example', 'playground', 'demo', 'docs', 'stories', 'test', 'tests',
    ].map((candidate) => path.join(root, candidate))
        .filter((candidate) => fs.existsSync(candidate) && fs.statSync(candidate).isDirectory());
    return candidates.length ? candidates : [root];
}

export function defaultMarkdownOutput(packageRoot, fileName) {
    const gitPath = findUpwards(packageRoot, '.git');
    const repositoryRoot = gitPath ? path.dirname(gitPath) : packageRoot;
    return path.join(repositoryRoot, '.migration-state', 'evidence', fileName);
}

export function defaultJsonOutput(markdownOutput) {
    return markdownOutput.replace(/\.md$/i, '') + '.json';
}

function compareFindings(left, right) {
    return left.file.localeCompare(right.file)
        || left.line - right.line
        || left.column - right.column
        || left.ruleId.localeCompare(right.ruleId);
}

function deduplicateFindings(findings) {
    const byKey = new Map();
    for (const finding of findings) {
        const key = [finding.ruleId, finding.file, finding.line, finding.column, finding.ownership].join('|');
        byKey.set(key, finding);
    }
    return [...byKey.values()];
}

function snippetAt(source, line) {
    return oneLine(source.split(/\r?\n/)[Math.max(0, line - 1)] || '').slice(0, 180);
}

function lineContaining(source, needle) {
    const index = source.indexOf(needle);
    return index < 0 ? 1 : source.slice(0, index).split(/\r?\n/).length;
}

function majorVersion(version) {
    const match = String(version).match(/(?:^|[^0-9])(\d+)(?:\.|$)/);
    return match ? Number(match[1]) : null;
}

function parseJsonWithComments(source) {
    let result = '';
    let inString = false;
    let escaped = false;
    for (let index = 0; index < source.length; index++) {
        const character = source[index];
        const next = source[index + 1];
        if (inString) {
            result += character;
            if (escaped) {
                escaped = false;
            } else if (character === '\\') {
                escaped = true;
            } else if (character === '"') {
                inString = false;
            }
            continue;
        }
        if (character === '"') {
            inString = true;
            result += character;
            continue;
        }
        if (character === '/' && next === '/') {
            result += '  ';
            index += 2;
            while (index < source.length && source[index] !== '\n') {
                result += ' ';
                index++;
            }
            if (index < source.length) {
                result += '\n';
            }
            continue;
        }
        if (character === '/' && next === '*') {
            result += '  ';
            index += 2;
            while (index < source.length && !(source[index] === '*' && source[index + 1] === '/')) {
                result += source[index] === '\n' ? '\n' : ' ';
                index++;
            }
            result += '  ';
            index++;
            continue;
        }
        result += character;
    }
    return JSON.parse(result.replace(/,(\s*[}\]])/g, '$1'));
}

function oneLine(value) {
    return String(value).replace(/\s+/g, ' ').trim();
}

function normalizePath(value) {
    return String(value).split(path.sep).join('/');
}

function escapeMarkdown(value) {
    return String(value).replace(/\|/g, '\\|').replace(/`/g, '\\`');
}
