# React 19 migration

Migrate the own-code findings from Phase 1 while preserving behavior. Work from the JSON evidence rather than rediscovering candidates with regex.

## Migration scope

Use the Phase 1 reports and caller scope. When explicit paths are absent, process every in-scope own-code finding whose `fixSupport` is `deterministic` or `contextual`. A necessary local caller, import, type, or root-owner file may be changed when the migration cannot compile or preserve ownership without it; account for that file in the result. Never edit generated bundles, source maps, `node_modules`, expectations, or migration reports. Manifest/lockfile and runtime-ownership changes use the separate bounded procedure in [target-runtime.md](target-runtime.md); source transformations do not authorize arbitrary dependency changes.

Build an edit allowlist from the JSON findings before changing anything. The allowlist may contain only reported JavaScript/TypeScript/configuration files and necessary local JavaScript/TypeScript callers in the same frontend ownership boundary. Never edit Java sources, Maven POMs, backend dependencies, or files associated with another migration capability. If a React finding appears to require one of those changes, leave it unchanged and report the blocker.

## Caller context is authoritative

- Obey declared scope, protected paths, validation commands, and reporting format.
- Read each candidate and its relevant callers fully before editing.
- Preserve public APIs and observable behavior.
- Preserve unrelated strings, diagnostics, and assertions, including success details that mention an old API. An API replacement does not authorize renaming those messages or weakening checks.
- Do not turn deprecations or policy findings into blocking source rewrites unless the caller requested that cleanup.
- If root ownership, DOM target, context flow, or public static behavior is ambiguous, leave the code unchanged and report manual review.
- Do not rename legacy lifecycle methods solely for React 19. They are not removed by React 19.

## Supported migrations

Apply a transformation only when its preconditions hold.

| Rule | Safe transformation and preconditions |
|---|---|
| `react-dom.render` | Use `createRoot`; keep exactly one root per stable container and preserve later cleanup. Thread the root through local callers when necessary. |
| `react-dom.hydrate` | Use `hydrateRoot(container, element, options)` only after resolving its asynchronous completion against the caller's timing and cleanup (see below). Preserve server markup and root ownership. A legacy completion callback is not an options object. |
| `react-dom.unmount-component-at-node` | Use `root.unmount()` only when the corresponding root is provably the one for that container. |
| `react-dom.find-dom-node` | Thread a callback ref, `createRef`, or `useRef` only when the intended host node is unambiguous. |
| `react.create-factory` | Replace a local factory invocation with equivalent JSX or `React.createElement`, preserving props and children. |
| `react.string-ref` | Replace the string and every local `this.refs` consumer with one explicit ref whose lifetime is clear. |
| `react.legacy-context` | Migrate a self-contained provider/consumer cluster together to `createContext`; preserve values, defaults, nesting, and class/function access. |
| `react.module-pattern-factory` | For a local component that returns only `{ render() { return node; } }`, return `node` directly. Otherwise require review. |
| `react.function-default-props` | Move literal or stable local defaults to parameter destructuring when no caller reads or mutates the static `defaultProps`. |
| `react-dom-test-utils.act` | Import `act` from `react` and remove the old import if empty. |
| `react.use-form-state` | Rename to `useActionState` only after confirming compatible tuple use and call arguments. |
| `react.element-ref` | Replace a read of a known React element's `.ref` with `.props.ref`. |
| `react-types.ref-callback-return` | Convert an assignment-expression arrow body to a block body with no return. |
| `react-types.use-ref-argument` | Pass the intended initial value, using `undefined` only when that matches the existing type and behavior. |
| `react-types.jsx-namespace` | Use `React.JSX` or the configured JSX-runtime module; inspect `tsconfig` before choosing. |
| `react-types.use-reducer-generic` | Prefer inference. If inference is insufficient, use the React 19 state/action-tuple form. |
| `react.jsx-transform` | Change an explicit classic transform to modern/automatic only in caller-authorized build configuration. |

Preserve the module system already in use. AMD consumers should request `atlassian/libs/react-dom/client`; ES modules should import from `react-dom/client`.

### Root completion and cleanup

`root.render()` and `hydrateRoot()` returning does not prove that React has committed the DOM, attached refs, or installed working event handlers. Inspect immediate DOM reads, completion callbacks, return values, and container removal in callers before replacing a legacy call.

Keep cleanup tied to the actual owner's teardown. Do not add an immediate `root.unmount()` in `finally` around scheduled rendering or hydration: it can cancel the work before it commits. A short-lived container must remain mounted through completion and any dependent work before cleanup. For an asynchronous lifecycle, use an actual commit signal rather than an arbitrary delay. Preserve an existing synchronous integration only when a narrowly scoped `flushSync` is safe at that call site (outside React rendering or lifecycle methods); do not add it indiscriminately. If the caller's timing contract cannot be preserved within scope, report a blocker.

For example, a synchronous host integration that hydrates a non-suspending tree, immediately consumes its DOM, and then disposes its container needs the hydration call itself inside `flushSync`:

```ts
let root: ReturnType<typeof hydrateRoot> | undefined;
flushSync(() => {
    root = hydrateRoot(container, element, options);
});
try {
    consumeHydratedDom(container); // Existing DOM/ref/event-dependent work.
} finally {
    root?.unmount(); // Only because this owner disposes the container here.
}
```

Calling `hydrateRoot` before `try`, checking a ref inside `try`, and unmounting in `finally` without a completion mechanism is still incorrect. For persistent UI, retain the root until the owner's later teardown instead. For suspending trees or legacy completion callbacks, preserve the actual completion contract with an appropriate commit signal or report manual review; do not assume this synchronous example applies.

In TypeScript, derive the root type from the callable value (`ReturnType<typeof hydrateRoot>` or `ReturnType<typeof ReactDOMClient.hydrateRoot>`). An imported module namespace cannot be indexed as a type (`ReactDOMClientModule['hydrateRoot']`). Omit annotations in JavaScript. Run the project's type check when available; transpiling TSX alone does not validate types, and that limitation belongs in the result.

Validate the observable result before teardown: rendered content for rendering, and reuse of the server DOM plus a committed ref or working event handler for hydration. Account for hydration/recoverable errors; a successful return, unchanged server HTML, or a scanner with no findings is not evidence of completed hydration. Preserve existing error reporting rather than suppressing errors to obtain a passing result.

## Manual-only findings

Do not auto-edit:

- React DOM unstable/internal APIs.
- Function `propTypes`.
- Non-`act` `react-dom/test-utils` calls.
- Enzyme, Storybook, and dependency-owned findings.
- `react-test-renderer` deprecation or shallow-renderer redesign.
- Legacy context spread across unclear providers/consumers or with side effects.
- Public or externally mutated function `defaultProps`.
- Any render/unmount pair without provable root ownership.
- Any `findDOMNode` call without one intended host node.
- ReactElement typing when the correct props type is not evident.

## Procedure

1. Load the JSON reports and select in-scope own-code findings.
2. Group related findings by ownership boundary, such as one root lifecycle or one context cluster.
3. Classify every finding as migrated, already compatible, manual review, protected, or unsupported.
4. Apply the smallest coherent change. Update necessary local callers in the same group.
5. Reopen every changed file and verify imports/module loading, root lifetime, cleanup, refs, context values, types, and rendering behavior.
6. Compare the full diff with the edit allowlist and caller constraints, including protected strings and assertions inside otherwise editable files. Restore any accidental out-of-scope edit before continuing; claim preservation only after checking the diff.
7. Rebuild the frontend and run the caller-permitted project tests.
8. Re-run both readiness scanners with the original inputs. Resolve or report parse errors and any unavailable validation.
9. Return an accounting keyed by original `ruleId`, file, and line, including intentionally retained non-blocking deprecations and manual-review findings. Preserve JSON and Markdown reports when the caller requests evidence. A clean build is not enough when an original supported finding remains.

Stop instead of guessing when a contextual precondition is not proven.
