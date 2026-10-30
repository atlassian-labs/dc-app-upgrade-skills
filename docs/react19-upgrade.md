---
skill: react19-upgrade
skill-sha256: 0dd10917d26b
---

# Upgrade to React 19

React 19 removes several legacy APIs, changes some behavior, and tightens its
TypeScript types. This guide covers the manual migration of an app or library.
The [last section](#use-the-skill) describes the `react19-upgrade` skill, which
scans for these problems and migrates the safe ones.

## Is my code affected?

Check two separate things, because a clean source scan does not prove the
dependencies are ready.

1. **Source.** Look for removed or changed APIs (table below) in application
   code, tests and stories.
2. **Dependencies.** A package that declares its own React or React DOM
   dependency or peer may still build against React 18 or advertise a peer range
   that excludes React 19.

If your module receives React from the host product (Jira or Confluence) and has
no React or React DOM declaration of its own, do not change dependencies. Verify
the host's React version and module exports instead. Build-only React type
packages do not make you the runtime owner, and do not need a type major upgrade
unless a confirmed repair requires it.

## Before you start

- Settle the exact React 19 version you are targeting and where it comes from
  (the host runtime, or the registry for an independent package).
- Know who owns the React runtime: the host, or your package.
- For production-bundle checks, build with source maps that include
  `sourcesContent`.

## Migrate

### Removed and changed APIs

Apply a change only when the precondition holds. When it does not, leave the
code alone and review it by hand.

| Old pattern | Replace with | Precondition |
|---|---|---|
| `ReactDOM.render` | `createRoot(container).render(...)` | One root per stable container; keep later cleanup |
| `ReactDOM.hydrate` | `hydrateRoot(container, element, options)` | Resolve completion timing first (see below) |
| `unmountComponentAtNode` | `root.unmount()` | The root provably belongs to that container |
| `findDOMNode` | callback ref, `createRef` or `useRef` | One unambiguous host node |
| `React.createFactory` | JSX or `React.createElement` | Props and children are explicit |
| String refs and `this.refs` | one explicit ref | Ref lifetime is clear |
| Legacy context (`contextTypes`, `getChildContext`) | `createContext` | Provider and consumer migrate together |
| Component returning `{ render() { … } }` | return the node directly | Only for a local component with this shape |
| `defaultProps` on a function component | default parameter values | No caller reads or mutates `defaultProps` |
| `useFormState` | `useActionState` | Tuple usage and arguments are compatible |
| `act` from `react-dom/test-utils` | `act` from `react` | none |
| `element.ref` | `element.props.ref` | A known React element |

Modules keep their existing module system: AMD consumers request
`atlassian/libs/react-dom/client`, ES modules import from `react-dom/client`.

```jsx
// Before
ReactDOM.render(<App />, container);

// After
import { createRoot } from 'react-dom/client';
const root = createRoot(container);
root.render(<App />);
```

**Do not auto-fix** these; they need judgment: React DOM unstable or internal
APIs, function-component `propTypes` (React 19 silently ignores them), other
`react-dom/test-utils` calls, Enzyme and Storybook usage, `react-test-renderer`,
legacy context spread across unclear providers, public or externally mutated
`defaultProps`, and any render/unmount pair without provable root ownership.

React 19 did **not** remove `componentWillMount`, `componentWillReceiveProps`
or `componentWillUpdate`. Do not rename them just for this upgrade.

### Root completion and cleanup

`root.render()` and `hydrateRoot()` returning does not mean React has committed
the DOM, attached refs or installed event handlers. Before replacing a legacy
call, check callers for immediate DOM reads, completion callbacks, return
values and container removal.

- Tie cleanup to the real owner's teardown. Do not add an immediate
  `root.unmount()` in a `finally` around scheduled work; it can cancel the work
  before it commits.
- If a synchronous integration must read the hydrated DOM straight away, wrap
  the call in a narrowly scoped `flushSync`, outside React rendering and
  lifecycle methods:

  ```ts
  let root: ReturnType<typeof hydrateRoot> | undefined;
  flushSync(() => {
      root = hydrateRoot(container, element, options);
  });
  try {
      consumeHydratedDom(container);
  } finally {
      root?.unmount(); // only because this owner disposes the container here
  }
  ```

- For suspending trees, or when a legacy completion callback must be
  preserved, use a real commit signal, or report the case for manual review.
- In TypeScript, derive the root type with `ReturnType<typeof hydrateRoot>`.
  A module namespace cannot be indexed as a type.

### TypeScript types

| Problem | Fix |
|---|---|
| Ref callback returns the assigned value | Use a block body with no `return` |
| `useRef()` with no argument | Pass the initial value (`undefined` only if that matches the existing type) |
| Global `JSX` namespace | `React.JSX`, or the configured JSX-runtime module (check `tsconfig`) |
| `useReducer` explicit generics | Prefer inference, or the React 19 state/action-tuple form |
| Bare `ReactElement` | Props default to `unknown`; give the correct props type |
| Classic JSX transform configured | Change to the automatic transform, in build config you are allowed to edit |

### Dependencies

For a package with its own React declarations:

1. Align React and React DOM runtime and dev declarations with the target,
   keeping the existing dependency sections and version policy.
2. Align React type declarations with the requested major and your peer ranges.
   Type patches need not match runtime patches.
3. Regenerate the lockfile with your package manager and run a reproducible
   install. Do not use force flags to bypass incompatible peers.
4. Confirm the installed version. A broad range admitting React 19 can still
   install React 18.

A library that externalizes `react` can still embed an older JSX runtime.
Inspect the built output, including `react/jsx-runtime`, and the bundler
aliases and externals. Do not patch generated output or `node_modules`.

## Verify

1. Run the type check, tests and production build.
2. Re-run your scan or search for the patterns above.
3. For a library, pack it and install it into a separate consumer with the
   target React and matching renderer, without bypassing peers. Load each
   published entry in its native module format and render a representative
   public component with an observable assertion.
4. For a host-owned module, run the product's existing smoke tests.
5. For hydration, check that the server DOM was reused and that a ref or event
   handler works. A successful return or an empty scan is not evidence.

Do not suppress hydration or recoverable errors to get a passing result.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| A missing-export or "is not a function" error for `render`, `hydrate`, `unmountComponentAtNode`, `findDOMNode` or `createFactory` | The host runs React 19, where these are gone. |
| Legacy context, string refs or module-pattern components fail at runtime | These were removed in React 19. Migrate them. |
| Hydration "works" but refs or events are dead | You checked before commit. Use `flushSync` or a commit signal. |
| Library works in isolation but fails in a React 19 consumer | Peer range admits 19 but installs 18, or an older JSX runtime is bundled. |
| Type errors after the upgrade | The type changes above (`useRef`, `JSX`, ref callbacks, `useReducer`). |

## Use the skill

Ask your agent to "upgrade to React 19". The `react19-upgrade` skill works in
two phases.

**Phase 1: check.** It stops with "not applicable" if the scope has no
JavaScript or TypeScript sources and no React-related manifests, and installs
nothing. Otherwise it assesses the React target for packages with their own
declarations (`assess-react19-target.mjs`) and runs two read-only scanners.
`scan-react19-source.mjs` parses source, tests, stories and package metadata.
`scan-react19.mjs` reads original `sourcesContent` from production source maps,
including transitive dependencies. Both write deterministic JSON and Markdown
reports to `.migration-state/evidence/`, with a rule ID, impact, confidence,
fix support, location and ownership for each finding. An incomplete scan is
reported, never treated as clean. The helpers need Node 18+ and install their
own npm dependencies on first use.

**Phase 2: migrate and verify.** For findings it can fix safely (deterministic or
contextual) in your own code, it applies the table above, then rebuilds,
runs the tests you allow, and re-runs both scanners. It accounts for every
original finding as migrated, already compatible, manual review, protected or
unsupported, and reports source findings, dependency alignment and runtime
verification separately.

It will not edit generated bundles, source maps, `node_modules`, Java sources,
POMs or files belonging to another migration. Dependency-owned findings are
reported, not patched. When root ownership, the DOM target or timing is
ambiguous, it leaves the code unchanged and reports it.
