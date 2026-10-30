---
name: typescript-6-upgrade
description: Discover and migrate TypeScript 5.x projects to TypeScript 6, including compiler configuration and type errors, while preserving their existing frontend build and runtime behavior.
---

# TypeScript 6 upgrade

## Discover before editing

Honor the caller's `assess`, `apply`, or `verify` phase, module and embedded-module
scope, approval, and validation ownership. In an orchestrated run, discovery
belongs to the orchestrator; workers apply approved findings and return focused
checks. Assessment does not edit application files or install upgrades. Git
writes and a separate React or TypeScript 7 migration require their own scope.
Adding `ignoreDeprecations` requires explicit caller approval to suppress
deprecation diagnostics. Preserving legacy output or deferring a bundler redesign
does not grant that approval. If repair would violate those constraints, report
a blocker instead of adding a suppression.

1. Inspect every requested module and embedded module, or the current repository
   when no scope is supplied. Find package manifests, TypeScript/checkJs sources,
   compiler invocations and configs, including nested projects and custom config
   paths passed by scripts. Exclude generated output, dependency/vendor trees,
   and agent tooling. When embedded scope is not supplied, discover it from
   authoritative build configuration; an explicit caller scope stays binding.
   If there is no TypeScript project or compiler usage, report
   `not-applicable` and stop without requiring Node or installing anything.
2. Identify each package root and its owning workspace manifest/lockfile,
   package manager, Node requirements, declared/locked/installed compiler versions,
   and relevant build/check scripts. Trace script chains, project references and
   shared configs, not only a script named `typecheck` or the root tsconfig.
   Resolve symlinks and distinguish readable shared inputs from editable scope.
   Record any existing baseline diagnostics without assuming the base build is green.
3. Establish who emits JavaScript, JSX, declarations and maps: `tsc`, a TypeScript
   loader, or a separate transpiler. Inspect the actual script and loader chain;
   webpack or Babel's presence alone does not establish who compiles TypeScript.
   Record output paths and consumers, host module delivery and React typings/JSX
   settings. Do not alter these choices during discovery.
4. Resolve effective configs with the project's installed TypeScript config
   parser or local compiler `--showConfig --project <config>`. TypeScript configs
   support JSONC, `extends` and references; plain JSON parsing or text matches
   alone cannot establish effective options. Inspect each build/check project,
   including configs outside reference graphs that scripts compile separately.
   If dependencies are absent, use a caller-permitted locked bootstrap without
   changing manifests/lockfiles, or report the missing inspection as `uncertain`.
5. Assess TS6 changes using the effective options, actual source usage, existing
   diagnostics and emitted-output consumers. Check `baseUrl`/alias resolution,
   implicit ambient packages, strictness, inferred emit roots, resolution/module
   settings, legacy options and import syntax. An omitted option, a dependency
   name, or a lexical match is a candidate, not proof of an incompatible use.
   Use the official [TS6 release notes](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-6-0.html)
   and the requested compiler's diagnostics for less common findings.

## Evidence and applicability

Use the caller's evidence directory, or `state_dir/evidence/typescript6/` when
supplied; otherwise use `.migration-state/evidence/typescript6/` at the repository
root (package root outside Git). Keep evidence outside build output. Give every
package a distinct repository-relative subdirectory and preserve `discovery.json`,
config/command output, and later numbered verification reports rather than
replacing the initial evidence. Reports are evidence, not a prerequisite: inspect
the application afresh when invoked without an earlier report.

Record package/config paths and coverage, compiler provenance, emission pipeline,
findings with file/option or diagnostic evidence, proposed scoped changes, skipped
candidates, unresolved inputs, and validation commands with their owner. Summarize
one of the caller's applicability statuses:

- `applicable`: an owned TS5 compiler needs the requested TS6 upgrade, or a
  confirmed TS6 incompatibility remains in an in-scope project.
- `already-compatible`: the declared/locked/installed compiler agrees on TS6,
  inspection is complete and no actionable finding remains. Record whether
  current validation ran, was reused with matching inputs, or is delegated.
- `not-applicable`: no TypeScript project/compiler usage exists in the full scope.
- `uncertain`, `unsupported`, or `blocked`: explain missing evidence, unsupported
  work, unavailable prerequisites or required scope/behavior decisions. A newer
  compiler major is not permission to downgrade. Incomplete inspection is not a
  clean result; aggregate all packages before classifying the capability.

Stop after assessment or when no supported actionable work remains. Do not
install, rewrite configs, add scripts or load migration instructions merely to
make an already-compatible project look different. On repeated invocation,
reassess current inputs and leave compatible application files unchanged.

## Apply and verify

Only when supported findings remain and edits are authorized, read
[references/migration.md](references/migration.md). Use its checks as shared
checkpoint acceptance criteria when the orchestrator owns build/runtime validation;
do not make every worker run the same build. Return evidence paths, changed
application files, completed migrations, skipped candidates, performed/delegated
checks and blockers using the caller's result contract.
