# React 19 target dependencies

Use this reference when the package declares React/React DOM dependencies or
peers that do not match the selected target, even if its source scan is clean.
For host-owned modules without those declarations, verify the host runtime and
module exports and preserve the existing source migration flow. Build-only
React types do not require changing the host-owned module's dependency policy.

## Align the declared target

Record the exact target version and its source, runtime ownership, and the
manifest or workspace/catalog that owns each declaration. Respect caller scope;
protected or ambiguous changes remain blockers rather than an implied approval.

- Align existing React and React DOM runtime/development declarations with the
  selected matching release, preserving dependency sections and version policy.
- For that package, align React type declarations with the requested major and
  peer ranges with verified consumer support. Type patches need not match runtime
  patches. Preserve consumer ownership and unrelated dependencies.
- Regenerate the lockfile with the existing package manager and run its
  reproducible install. Do not bypass incompatible peers with force flags.
- Rerun `assess-react19-target.mjs` with the same target. Record declaration and
  installed-version evidence separately; an unresolved or missing version remains
  unverified. A broad range admitting React 19 can still install React 18.

## Verify the built package

Preserve React runtime ownership in every published format. A library may
externalize `react` while accidentally bundling its older JSX runtime; inspect
React/React DOM public subpaths, including `react/jsx-runtime`, and existing
bundler aliases/externals. Make only confirmed repairs within approved scope.
Preserve actual host module mappings and never patch generated output or
`node_modules`.

Use compiler diagnostics from the requested dependency alignment as evidence for
minimal local type repairs within scope. Run the package's typecheck, tests and
production build. For a library, install
its packed output into a separate consumer with the selected React and matching
renderer, without bypassing peer checks. Confirm the renderer works, load each
published entry using its native module format, and render a representative
public component with an observable assertion. Use a browser when required by
the component; an expected import exception is baseline evidence, not success.

Use the existing product smoke tests for host-owned modules. Record exact target
versions, commands, and results. Required checks that fail or cannot run leave
verification incomplete; explicitly caller-delegated checks remain deferred.
Report source findings, dependency alignment and runtime verification separately.
