---
name: upgrade-to-jquery-4
description: Detect and migrate jQuery 4 incompatibilities in JavaScript, TypeScript, and script templates, then verify the result.
---

# Upgrade to jQuery 4

## Detect

Inspect the requested module and declared embedded modules, or the current
repository when no scope is supplied. Check sources, dependencies, and
web-resource declarations for jQuery and aliases such as `AJS.$`. Exclude
vendor/generated code and agent tooling. Stop only when neither jQuery usage
nor a declared jQuery/AUI version exists; compatible call sites do not settle
version readiness. Use the caller's target version/build; resolve distribution
or shim details before changes that depend on them. Preserve host-provided
jQuery delivery.

Read the declared versions as well as the call sites. Compare the `jquery`,
`@types/jquery` and `@atlassian/aui` pins in every `package.json`, and any Maven
`aui.version` property in every module pom rather than only the module carrying
the frontend, against the versions the target product ships. A stale
`@atlassian/aui` pin blocks the jQuery bump; see
[references/migration.md](references/migration.md).

Collect declarations from the project root, including aggregator POMs:

```bash
OUTPUT_FILE="<evidence-directory>/jquery4/<module>/declared-versions.json" \
bash "<skill-directory>/scripts/find-declared-jquery-versions.sh" "<project-root>"
```

The report lists `{filePath, name, scope, declared}`; it does not decide
compatibility. Record the target versions and the comparison for every in-scope
declaration before claiming no changes are needed. Inspect unsupported version
specifications separately; any nonzero scanner exit means inspection is incomplete.

Run the call-site scanner from the target repository for each in-scope module.
Use module subfolders under the caller's evidence directory, or
`state_dir/evidence/` when a state directory is supplied:

```bash
OUTPUT_FILE="<evidence-directory>/jquery4/<module>/candidates.json" \
bash "<skill-directory>/scripts/find-jquery-usage.sh" "<source-directory-or-file>"
```

Scan each package root, not only `src/`: the module directory and every
workspace package its `package.json` `workspaces` declares (for example
`packages/<name>`). Scanner exclusions already skip dependencies and build output.

Requires Bash, Git, Python 3.9+, and grep. Default `auto` mode also attempts
`npx @atlassian/jquery-eslint-runner` (unpinned; requires Node/npm and registry
access). `JQUERY_SCAN_MODE=grep` uses local lexical scanning only. Report the
coverage used and any failures.

The default output is `.migration-state/evidence/jquery4/<module>/candidates.json`
at the repository root, outside build output. The module is the directory with
the nearest `pom.xml` or `package.json` above the scan path, falling back to the
repository directory. If scanning multiple source roots within one module, use
distinct output subfolders to preserve each report. The report is a sorted array of
`{ "filePath": "repo/relative.js", "apis": [...] }`. Preserve it as scan evidence.
Keep `candidates.json` for the initial scan. For each later scan, set `OUTPUT_FILE`
to a distinct filename in the same module folder; never overwrite earlier reports.

Inspect candidates to confirm jQuery receivers and incompatible signatures.
Supported deprecations, native methods, and template text do not require repairs.
Read [references/migration.md](references/migration.md) for version-specific
classification and migration rules.

The scanner covers tracked JS/JSX/MJS/CJS/TS/TSX, templates, and patches.
Inspect untracked sources and relevant computed/multiline calls or imports
separately. Optional `scripts/jquery-type-filter.js` can help with a selected
tsconfig, but its line-level heuristics and Cypress exclusions can omit calls;
retain the unfiltered evidence.

Scanner entries are candidates pending confirmation, not findings. Report the
candidate count and the confirmed count separately, with each skipped candidate
and its reason. Migration is needed when confirmed call-site findings or declared
version mismatches remain; a non-empty inventory alone is not applicability.
Report both scan paths and the declaration comparisons. Incomplete inspection
is not a clean result.

## Migrate and verify

Apply confirmed repairs following [references/migration.md](references/migration.md).
Re-scan, check behavior, and report changed files, skipped candidates, validation,
and unresolved findings. Honor the caller's `assess`, `apply`, or `verify` phase,
edit scope, and result contract; assessment does not change application source.
