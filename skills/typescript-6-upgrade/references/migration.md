# Migrate confirmed TypeScript 6 findings

## Preserve the application contract

Work from the discovered package roots and approved findings. Recheck inputs after
preceding migrations; reuse discovery evidence only while those inputs match.
Preserve unrelated edits and the existing package manager, workspace/lockfile
ownership, scripts, project graph, source inclusion, React/runtime/type versions,
JSX transform, module integration, output target, paths, declarations and maps.
There is no mandatory minimal tsconfig and no required script name. A bundler can
consume JavaScript emitted by `tsc`; do not remove emit settings because webpack
or Babel also participates in the build.

A TypeScript upgrade does not authorize a React upgrade or adding native-preview
compilers. Update another dependency only for a confirmed TS6 compatibility issue
within the approved scope, preserving its intended runtime major. If a config,
workspace lockfile or required runtime/build redesign lies outside that scope,
return the decision to the caller; automated callers receive a blocker.

For an existing or explicitly requested native compiler check, load
[tsgo.md](tsgo.md) only for that check; it does not replace TS6 verification.

## Select and install TypeScript 6

Activate an existing project Node version when available; check the requested
compiler's engine requirements. Use the discovered manager and install an exact
TS6 release, preserving the existing dependency section. Prefer the caller's
explicit TS6 version; otherwise resolve the latest stable release within major 6.
Do not use `typescript@latest`, uninstall related packages or refresh unrelated
React/Babel dependencies. For an existing development dependency, examples are:

```bash
npm install --save-dev --save-exact typescript@6
corepack yarn add --dev --exact typescript@6
corepack pnpm add --save-dev --save-exact typescript@6
```

Use the workspace/package selection required by the actual repository, with only
approved manifest and lockfile writes. An already installed and locked TS6 does
not need a reinstall. Record the resolved exact version, dependency declaration,
lockfile entry and compiler binary used by each project. Conflicting versions,
missing provenance, or a command still using TS5/TS7 must be resolved before
claiming TS6 validation.

## Repair affected configs and source

Use the smallest supported change for each confirmed finding. Keep independent
projects' options separate; shared base configs can affect projects outside scope.

### Aliases and emit roots

For `baseUrl` used only as a `paths` prefix, remove it and make each mapping
explicit relative to the config that owns it. For example, `baseUrl: "./src"`
with `"@app/*": ["app/*"]` becomes `"@app/*": ["./src/app/*"]`. Inspect actual
bare-import resolution first; do not discard a real lookup root or invent a
catch-all mapping without evidence. Verify aliases through both TypeScript and
the actual bundler/runtime resolver; `paths` does not rewrite emitted imports.

For `tsc` emit that relied on the old inferred common source root, set `rootDir`
to that source root only after checking every emitted input and its consumers.
Preserve the expected relative output layout, not just a passing no-emit check.
Do not set every project's root to `src` or add an emit root to a typecheck-only
project without a demonstrated need.

The optional [configuration codemod](https://github.com/andrewbranch/ts5to6)
can perform these two repairs. Run only the relevant fix for a confirmed config:

```bash
# npm
npm exec --yes --package=@andrewbranch/ts5to6@1.1.1 -- ts5to6 --fixBaseUrl ./path/to/tsconfig.json
npm exec --yes --package=@andrewbranch/ts5to6@1.1.1 -- ts5to6 --fixRootDir ./path/to/tsconfig.json

# Yarn / pnpm (choose the detected manager)
corepack yarn dlx @andrewbranch/ts5to6@1.1.1 --fixBaseUrl ./path/to/tsconfig.json
corepack yarn dlx @andrewbranch/ts5to6@1.1.1 --fixRootDir ./path/to/tsconfig.json
corepack pnpm dlx @andrewbranch/ts5to6@1.1.1 --fixBaseUrl ./path/to/tsconfig.json
corepack pnpm dlx @andrewbranch/ts5to6@1.1.1 --fixRootDir ./path/to/tsconfig.json
```

It follows references and related `extends` configs. Inspect the affected graph
first; run it only when every possible edit is approved. Otherwise apply the
same scoped repair manually. The codemod is optional, so its unavailability does
not block a manual repair that can be established and verified independently.
Review the resulting diff; a successful codemod does not verify the build.

### Ambient types and strictness

TS6 no longer implicitly loads every ambient type package. Add only packages
whose global declarations are actually consumed to the affected project's
`types` list, preserving intentional `typeRoots` and local declarations. For
example, a project using a custom ambient package `fixture-host` under its
configured type roots needs `types: ["fixture-host"]`. Use real package names;
do not add Node globals to browser projects or install React 19 typings to fix
an unrelated missing ambient name.

TS6 defaults to strict checking when no explicit choice exists. Prefer erased
annotations derived from actual call sites and API contracts. Preserve existing
explicit strictness flags. If fixes require a behavior change or uncertain API
contract, return that decision rather than widening to `any`, casting away the
error, or disabling checking to get green. Retaining previous non-strict behavior
must be an explicit approved choice, not an automatic fallback. Do not introduce
runtime guards, defaults, coercion, initialization or React API rewrites merely
to silence a type error. Assertions require evidence that the asserted contract
holds; do not add suppression comments as a general repair strategy.

### Other compiler findings

Use the [TS6 release notes](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-6-0.html)
and actual diagnostics to distinguish deprecated options from removed features
and changed defaults. Migrate only usages present in the approved project:

- `esModuleInterop` and `allowSyntheticDefaultImports` default to `true` in TS6;
  explicit `false` settings are deprecated. Check import callability and emitted
  interop helpers before changing them, especially for CommonJS consumers.
- `noUncheckedSideEffectImports` defaults to `true`. `downlevelIteration` and
  `moduleResolution: node`/`node10` are deprecated. Inspect actual usage rather
  than silently disabling the new check or changing runtime support.
- In TS6.0.3, compiler-owned AMD/UMD/SystemJS/`none` emit, `classic` resolution
  and `outFile` produce deprecation errors scheduled to become unsupported in
  TS7. Distinguish these from options already removed by the selected compiler;
  use its diagnostics rather than assuming every deprecation is a removal.
- Pin target/lib/module choices when a changed default would alter the intended
  runtime output or available APIs. Do not raise the supported browser/Node level.
- Choose resolution to match the real consumer: usually `bundler` for bundled
  code or a matching Node module/resolution pair for Node execution. A loader's
  name alone does not determine that choice. Preserve host AMD delivery when a
  bundler owns it; replacing compiler-owned legacy emit may need a separate plan.
- For side-effect imports, distinguish a missing module from an asset declaration
  the build supports. Supply accurate declarations, not a blanket wildcard
  that hides unresolved imports.
- Inspect deprecated import assertions and legacy namespace syntax in executable
  syntax, not strings/comments or supported ambient `declare module` declarations.
  Import assertions such as `import data from './data.json' assert { type: 'json' }`
  become `import data from './data.json' with { type: 'json' }` only when the
  actual loader/runtime supports that form.
- Investigate changed library declarations, inference and interop diagnostics at
  their source; do not apply unrelated dependency upgrades or import rewrites.

Do not add `ignoreDeprecations`, weaken strictness, enable `skipLibCheck`, exclude
failing source projects, or disable emission/tests as a completion shortcut.
Preserve existing intentional settings and report unresolved diagnostics.

### Explicitly approved gradual migration

Only when the caller explicitly approves suppressing deprecation diagnostics,
for example by approving `ignoreDeprecations: "6.0"`, may a config use that setting
for diagnostics that the selected TS6 release
identifies as suppressible deprecations. Apply it only to the approved projects,
list every suppressed finding and its follow-up repair, and still run TS6
compilation, packaging and relevant tests. It does not fix type errors, restore
removed features, or establish TS7 compatibility. Report the result as a
transitional migration with remaining work, not a completed repair of those
findings. Without that approval, repair the findings or return a blocker; do
not infer approval from general migration authorization.
Instructions to preserve AMD output, defer a bundler redesign or keep changes
minimal do not authorize suppression, even when suppression is the only way to
make the constrained build pass. With `on_blocker=stop` or another noninteractive
caller, return a blocker that identifies the required suppression or redesign
decision; leave the config unchanged until that decision is authorized.

## Verify and report

In standalone use, run these checks. Under an orchestrator, workers return
focused checks and these acceptance criteria; shared frontend/Maven builds and
runtime checks stay with their recorded owner. A caller-delegated check is
reported as deferred with owner/reason, never claimed as passed or treated as a
missing-worker-check blocker. A failed required-current check remains a blocker.

1. Resolve the actual local compiler invoked by the project scripts. Confirm its
   installed version is TS6 and agrees with the manifest requirement and lockfile
   (or the manager's equivalent reproducible resolution), including every distinct
   workspace compiler. Do not use an unbounded on-demand `npx tsc` or trust a
   version string in a script/report as proof. Native preview is not TS6 evidence.
2. Run the existing authoritative check and real compilation/build/package commands
   for all affected projects, retaining their `-p`/`--build` flags and product/profile
   restrictions. Run scripts that exist; direct local compiler commands are valid
   when that is the project's established workflow. Do not add `typecheck` solely
   for this skill. If no check exists, resolve a scoped local command with the
   caller instead of assuming the root tsconfig describes the whole package.
3. For emit pipelines, use a clean project-native build that removes stale generated
   output within approved build scope. Confirm expected JavaScript, JSX,
   declarations, maps and alias consumers survive bundling/packaging at the
   intended locations. A successful `--noEmit` check cannot prove output layout.
   Reuse one orchestrator checkpoint for these criteria instead of duplicating
   frontend and Maven builds when one already includes the other.
4. Run relevant existing tests, including browser checks of packaged output when
   permitted/available. Preserve runtime and host module behavior; do not alter
   expected results to accommodate the compiler migration. If runtime checks are
   owned by the caller, record the handoff without claiming runtime compatibility.
5. Reinspect every affected config and source project with TS6; save a numbered
   verification report next to the original evidence. Account for every initial
   finding as repaired, intentionally untouched, or unresolved. Inspect the diff
   for React/runtime dependency changes, JSX/output changes, suppressed errors,
   lost source coverage and unintended runtime code edits. Fix migration-induced
   regressions within scope; return unsupported changes or remaining failures.

Return changed application paths, resolved compiler versions, evidence and actual
command results, deferred checks with owners, skipped candidates and blockers.
Report TS6 build compatibility separately from runtime behavior. A repeat run
with matching compatible inputs should produce no application changes and no
unnecessary dependency install or configuration cleanup.
