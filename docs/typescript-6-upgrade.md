---
skill: typescript-6-upgrade
skill-sha256: 61e8a8c54bba
---

# Upgrade to TypeScript 6

TypeScript 6 changes compiler defaults (strictness, ambient types, interop),
deprecates several options and drops the inferred common source root that some
`tsc` emit relied on. A project that still compiles with TypeScript 5 must be
upgraded and its configuration checked. This guide covers the manual
migration. The [last section](#use-the-skill) describes the `typescript-6-upgrade`
skill, which assesses the project and applies the confirmed repairs.

## Is my code affected?

The migration applies when a project you own is compiled by TypeScript 5.x, or
a TypeScript 6 compiler already reports a problem in an in-scope project. It
does not apply when there is no TypeScript project or compiler usage at all.

1. Find every compiler declaration, config and invocation, including nested
   projects and configs passed by scripts, not only a root `tsconfig.json`:

   ```bash
   git ls-files '*package.json' '*tsconfig*.json' | xargs grep -nE '"typescript"|tsc|--project|-p '
   ```
2. Check the installed compiler, which can differ from the declaration:

   ```bash
   npx --no-install tsc --version
   ```
3. See the effective options of each build or check project. Configs support
   comments, `extends` and references, so text matches are not enough:

   ```bash
   npx --no-install tsc --showConfig --project path/to/tsconfig.json
   ```

Findings to look for in the effective options: `baseUrl` used only as a `paths`
prefix, `tsc` emit without an explicit `rootDir`, projects relying on implicit
ambient `@types` packages, non-strict code with no explicit strictness, and the
deprecated options listed below. An omitted option or a dependency name is a
candidate, not proof of a problem.

If the declared, locked and installed compilers all already agree on TypeScript 6
and nothing in the project fails, there is nothing to do.

## Before you start

- Decide the exact TypeScript 6 version (prefer a specific release within major
  6, not `typescript@latest`) and check its Node engine requirement.
- Know who emits JavaScript, JSX, declarations and maps: `tsc`, a loader or a
  separate transpiler. Webpack or Babel being present does not tell you.
  A bundler can consume JavaScript emitted by `tsc`, so keep emit settings.
- Record existing diagnostics from a TypeScript 5 build so you can tell old
  errors from new ones.
- Decide whether suppressing deprecation diagnostics (`ignoreDeprecations`) is
  acceptable. See the last item under Migrate.
- A TypeScript upgrade is not a React upgrade, and it does not add the native
  preview compiler.

## Migrate

### 1. Install the compiler

Keep the existing package manager, dependency section and lockfile. For an
existing development dependency:

```bash
npm install --save-dev --save-exact typescript@6
corepack yarn add --dev --exact typescript@6
corepack pnpm add --save-dev --save-exact typescript@6
```

Do not uninstall related packages or refresh unrelated React or Babel
dependencies. Update another dependency only for a confirmed TypeScript 6
compatibility issue, keeping its major.

### 2. Aliases and emit roots

| Situation | Change |
|---|---|
| `baseUrl` used only as a `paths` prefix | Remove it; make each mapping explicit, relative to the config that owns it |
| `tsc` emit that relied on the old inferred common source root | Set `rootDir` to that source root, after checking every emitted input and its consumers |

```jsonc
// Before
{ "compilerOptions": { "baseUrl": "./src", "paths": { "@app/*": ["app/*"] } } }

// After
{ "compilerOptions": { "paths": { "@app/*": ["./src/app/*"] } } }
```

Check actual bare-import resolution first; keep a real lookup root, and do not
invent a catch-all mapping. `paths` does not rewrite emitted imports, so verify
aliases in both TypeScript and your bundler or runtime. Do not set every
project's `rootDir` to `src` or add one to a typecheck-only project. Check the
emitted layout, not only a passing no-emit check.

The optional [`ts5to6`](https://github.com/andrewbranch/ts5to6) codemod does
these two repairs; run only the relevant fix on a config you have inspected:

```bash
npm exec --yes --package=@andrewbranch/ts5to6@1.1.1 -- ts5to6 --fixBaseUrl ./path/to/tsconfig.json
npm exec --yes --package=@andrewbranch/ts5to6@1.1.1 -- ts5to6 --fixRootDir ./path/to/tsconfig.json
```

It follows references and related `extends` configs, so run it only when every
possible edit is acceptable, and review the diff afterwards.

### 3. Ambient types and strictness

TypeScript 6 no longer loads every ambient type package implicitly. Add to the
affected project's `types` list only packages whose globals are actually used,
keeping intentional `typeRoots`:

```jsonc
{ "compilerOptions": { "types": ["<ambient-package>"] } }
```

Use real package names. Do not add Node globals to browser projects or install
React 19 typings to fix an unrelated missing global.

TypeScript 6 defaults to strict checking when you set no explicit choice. Fix
errors with annotations derived from real call sites and API contracts, and keep
existing explicit strictness flags. Do not widen to `any`, cast the error away,
add runtime guards or defaults, or disable checking just to get green. Going back
to non-strict has to be a deliberate decision.

### 4. Other options and defaults

Use the [release notes](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-6-0.html)
and the compiler's own diagnostics. Change only what your project uses.

| Item | What to check |
|---|---|
| `esModuleInterop`, `allowSyntheticDefaultImports` | Default to `true`; explicit `false` is deprecated. Check import callability and emitted interop helpers first, especially for CommonJS consumers |
| `noUncheckedSideEffectImports` | Defaults to `true`. Tell a missing module from an asset the build supports, and supply accurate declarations rather than a blanket wildcard |
| `downlevelIteration`, `moduleResolution: node` / `node10` | Deprecated. Inspect usage; do not silently change runtime support |
| AMD, UMD, SystemJS or `none` emit, `classic` resolution, `outFile` | In 6.0.3 these produce deprecation errors scheduled to become unsupported in TypeScript 7. Replacing compiler-owned legacy emit may need a separate plan |
| Changed `target`, `lib` or `module` defaults | Pin them when the default would change intended output or available APIs; do not raise the supported browser or Node level |
| `moduleResolution` choice | Match the real consumer: usually `bundler` for bundled code, or a matching Node module and resolution pair |
| Import assertions | `assert { type: 'json' }` becomes `with { type: 'json' }`, only if your loader or runtime supports it |

```ts
// Before
import data from './data.json' assert { type: 'json' };
// After
import data from './data.json' with { type: 'json' };
```

Look for import assertions and legacy namespace syntax in executable syntax,
not in strings, comments or supported ambient `declare module` declarations.
Investigate changed library declarations and inference at their source; do not
apply unrelated dependency upgrades.

Do not add `ignoreDeprecations`, weaken strictness, enable `skipLibCheck`,
exclude failing projects or disable emit and tests to finish. If you do accept a
gradual migration with `ignoreDeprecations: "6.0"`, apply it only to the agreed
projects, list each suppressed finding with its follow-up, and still compile,
package and test. It does not fix type errors, restore removed features or make
the project TypeScript 7 ready.

## Verify

1. Confirm the compiler your scripts actually run is TypeScript 6, and that it
   agrees with the manifest and lockfile for every workspace:

   ```bash
   npx --no-install tsc --version
   ```

   Do not rely on an unbounded `npx tsc` or a version string in a script.
2. Run your existing check, compile, build and package commands for every
   affected project, keeping their `-p` or `--build` flags and profile
   restrictions.
3. For emit pipelines, do a clean build and check that JavaScript, JSX,
   declarations, maps and alias consumers are where you expect after bundling.
   `--noEmit` passing cannot prove the output layout.
4. Run the existing tests, including browser checks of packaged output if you
   have them.
5. Re-run `tsc --showConfig` on each changed config and review the diff for
   React or runtime dependency changes, JSX or output changes, suppressed
   errors, lost source coverage and unintended runtime edits.

A native preview compiler result (`tsgo`) is not evidence of TypeScript 6
compatibility. If your project already has a native check, compare its
diagnostics with TypeScript 6 on the same inputs; for example TS7016 on a
JavaScript import means the declaration is not found, so check package typings
and included declarations before calling it a regression.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Aliased imports fail to resolve after removing `baseUrl` | Mappings are not relative to the owning config, or the real lookup root was removed |
| Emitted files land in a different directory | The inferred common source root changed; set `rootDir` deliberately |
| Missing global names such as a custom ambient type | Implicit ambient packages are no longer loaded; add them to `types` |
| New strict-mode errors | TypeScript 6 defaults to strict. Fix with accurate annotations |
| Deprecation errors for AMD/UMD/SystemJS emit, `classic` resolution or `outFile` | Deprecated in 6.0.3 and scheduled to become unsupported in TypeScript 7 |
| Build still reports TypeScript 5 | A script uses another compiler binary; resolve the one it invokes |
| TS7016 on a JavaScript module under a native check | Declaration not found by that check; compare with TypeScript 6 |

## Use the skill

Ask your agent to "upgrade to TypeScript 6". The `typescript-6-upgrade` skill
assesses first and edits only when supported findings remain and edits are
authorized.

**Discover.** If there is no TypeScript project or compiler usage it reports
not applicable and stops without needing Node or installing anything. Otherwise
it inspects every requested module and embedded module (or the whole
repository), finds manifests, sources, configs and compiler invocations,
including configs passed by scripts, and records the package manager,
declared, locked and installed compiler versions, and baseline diagnostics.
It works out who emits output and resolves effective configs with the project's
own `--showConfig`. Evidence goes under `.migration-state/evidence/typescript6/`
(one subdirectory per package, with later numbered verification reports kept
next to the original). The outcome is one of applicable, already compatible,
not applicable, or uncertain, unsupported or blocked. Incomplete inspection is
never reported as clean. A project that is already compatible is left untouched.

**Migrate and verify.** It installs an exact TypeScript 6 release with the
existing package manager, applies the repairs in the sections above, and
verifies the actual compiler version, the project's own check and build commands,
clean emit output and tests, then re-inspects every affected config and accounts
for each initial finding as repaired, intentionally untouched or unresolved. It
reports TypeScript 6 build compatibility separately from runtime behavior, and
a check owned by a calling workflow as deferred rather than passed.

It will not add `ignoreDeprecations` without explicit approval, weaken
strictness, upgrade React, add the native compiler or start a TypeScript 7
migration. Where a repair would need that, a config outside scope or a
behavior decision, it reports a blocker for you to decide.
