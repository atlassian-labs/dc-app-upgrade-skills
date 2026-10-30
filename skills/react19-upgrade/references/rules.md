# React 19 rule interpretation

The scanner follows the official [React 19 upgrade guide](https://react.dev/blog/2024/04/25/react-19-upgrade-guide). The impact column is intentional: removal, behavior change, type break, deprecation, and local ecosystem policy are not interchangeable.

## Core runtime and behavior rules

| Rule ID | Impact | Fix support | Notes |
|---|---|---|---|
| `react-dom.render` | runtime-break | contextual | Root lifetime and later unmount ownership must remain coherent. |
| `react-dom.hydrate` | runtime-break | contextual | Preserve server markup and hydration options. |
| `react-dom.unmount-component-at-node` | runtime-break | contextual | Requires the exact root created for the container. |
| `react-dom.find-dom-node` | runtime-break | contextual | The intended host node must be unambiguous. |
| `react-dom.unstable-*` | runtime-break | manual | Removed internal/unstable APIs do not have mechanical equivalents. |
| `react.create-factory` | runtime-break | deterministic | Safe when props and children are explicit at the call site. |
| `react.legacy-context` | runtime-break | contextual | A self-contained provider/consumer cluster can be migrated together. |
| `react.string-ref` | runtime-break | contextual | Ref lifetime and all `this.refs` consumers must be local and clear. |
| `react.module-pattern-factory` | runtime-break | deterministic | Only reported when a JSX-used local component returns an object with `render`. |
| `react.function-default-props` | behavior-change | deterministic | Reported only for a locally declared function component, not class `defaultProps`. |
| `react.function-prop-types` | behavior-change | manual | React 19 silently ignores function-component propTypes. |
| `react.use-form-state` | runtime-break | contextual | Verify the returned tuple before renaming to `useActionState`. |
| `react.internal-api` | runtime-break | manual | React internals are not an upgrade-compatible API. |

Legacy `componentWillMount`, `componentWillReceiveProps`, and `componentWillUpdate` are not classified as React 19 removals. Do not rename them merely to make this report clean.

## Tests and deprecations

| Rule ID | Impact | Meaning |
|---|---|---|
| `react-dom-test-utils.act` | deprecation | Import `act` from `react`. |
| `react-dom-test-utils.removed-api` | runtime-break | Non-`act` test-utils calls error in React 19. |
| `react-test-renderer.shallow` | runtime-break | The shallow entrypoint was removed. |
| `react-test-renderer.deprecated` | deprecation | It still exists in React 19, warns, and uses concurrent rendering. Plan migration, but do not call it a removed runtime API. |

## TypeScript and configuration

| Rule ID | Impact | Meaning |
|---|---|---|
| `react-types.ref-callback-return` | type-error | Assignment-expression ref callbacks must not return the assigned value. |
| `react-types.use-ref-argument` | type-error | `useRef` requires an argument in React 19 types. |
| `react-types.react-element-props` | type-error | Bare `ReactElement` props default to `unknown`. |
| `react-types.jsx-namespace` | type-error | Global `JSX` moves to `React.JSX` or the configured JSX runtime module. |
| `react-types.use-reducer-generic` | type-error | React 19 changed explicit `useReducer` generic parameters. |
| `react.jsx-transform` | configuration | An explicit classic TypeScript/Babel JSX transform must become modern/automatic. |

## Ecosystem policy

Ecosystem findings are readiness policy, not React core removals. They require package/test/story review and are deliberately marked `manual`; they must still appear in the assessment even when no automatic migration applies.

| Rule ID | Evidence |
|---|---|
| `ecosystem.enzyme` | Enzyme, adapter, matcher, or serializer package declarations, source imports/re-exports (including types), CommonJS loads, and calls resolved to those modules. |
| `ecosystem.storybook-pre-8` | Declared Storybook package versions below 8. |
| `ecosystem.storybook-stories-of` | Named imports/re-exports or calls to `storiesOf` resolved to a Storybook module. |
| `ecosystem.storybook-addon-info` | Legacy addon-info declarations or source usage, independent of declared version. |
| `ecosystem.storybook-addon-knobs` | Legacy addon-knobs declarations or source usage, independent of declared version. |

Source detection does not require the dependency to be declared in the scanned package. Unbound names such as `mount`, `shallow`, `ReactWrapper`, or `storiesOf` alone are not evidence; wrapper modules and indirect re-export chains may still require manual inspection.

Direct and transitive dependency findings are never dropped from the bundle report. Upgrade or replace the owning dependency; do not edit files under `node_modules`.

