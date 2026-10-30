---
name: react19-upgrade
description: Upgrade applications and libraries to React 19 by checking their target dependencies, migrating supported source findings, and verifying the result.
---

# React 19 upgrade

Assess target dependencies separately from source compatibility, then migrate and verify the requested scope. Running the helpers requires Node 18+ and automatically installs their npm dependencies on first use.

The two scans are complementary:

- `scan-react19-source.mjs` parses source, tests, stories, package metadata, and JSX/TypeScript configuration.
- `scan-react19.mjs` parses original `sourcesContent` from production source maps, including direct and transitive dependencies. It scans bundle directories recursively.

Read [references/rules.md](references/rules.md) when interpreting findings or planning fixes.

## Phase 1: Check whether migration is required

### Preflight without Node.js

Before requiring Node/npm, inspect the requested scope for JS/TS sources (including JSX/TSX, tests, and stories), React/test/Storybook dependencies or frontend configuration in package manifests, and caller-supplied bundles/maps. Exclude generated/vendor files and agent tooling from source discovery. If none exist, report “not applicable” and stop without installing, building, or scanning. Missing `package.json` or incomplete inspection alone does not justify skipping.

### Determine the scan context

Prefer context already supplied by the caller or build system. If it provides build directories, commands, source roots, and output locations, use them without asking again.

Otherwise inspect `package.json` and the bundler configuration. Ask the user only when the production build command or relevant output directory cannot be determined safely. In a monorepo, scan every requested application or package separately so each report has one package root.

Reports default to `.migration-state/evidence/` at the repository root (the package root outside Git), so build cleanup preserves them. Set distinct `OUTPUT_FILE` and `OUTPUT_JSON` paths under that directory when scanning multiple packages.

### Assess the React target

Resolve an exact React 19 version from caller context or the selected host runtime. For an independent package with no supplied version, select a published matching React/React DOM 19 release from its configured registry, respecting the project's version policy. Record that version and its source; unresolved or conflicting targets are `uncertain` or `blocked`.

Establish who supplies React before assessing npm versions. For a host-owned module with no direct React/React DOM runtime, development, or peer declaration, verify the target host's version and module exports instead. Build-only type packages do not establish runtime ownership; preserve their existing versions unless the caller requests a type upgrade or a confirmed source repair requires it. Do not turn transitive development dependencies into a plugin runtime upgrade.

For packages declaring their own React/React DOM dependencies or peers, inspect the owning manifest, lockfile and installed tree, then run the read-only check with the resolved target:

```bash
node "<skill-directory>/assess-react19-target.mjs" \
  --package-json package.json --target <exact-react-19-version> \
  --output .migration-state/evidence/react19-target-before.json
```

This check covers the selected manifest's declarations and installed versions, not source or consumer behavior. Resolve workspace/catalog declarations at their owner. Missing installations or unsupported package-manager resolution remain unverified; collect equivalent package-manager evidence when needed.

For packages with their own React declarations, misaligned dependencies, peers excluding the target, and a bundled consumer-owned JSX runtime are actionable even with no supported source findings. When any remain, load [references/target-runtime.md](references/target-runtime.md). A source-only audit preserves dependency scope and reports target readiness separately.

Do not edit build configuration merely to obtain source maps without telling the user. A source scan remains useful when no production bundle exists; report that bundle attribution was not validated.

### Run the source scan

From the relevant package directory:

```bash
SRC_DIRS=src,packages \
PKG_JSON=package.json \
node "<skill-directory>/scan-react19-source.mjs"
```

All variables are optional:

- `SRC_DIRS` defaults to common source, test, story, and example directories. Paths are comma-separated.
- `PKG_JSON` defaults to the nearest `package.json`.
- `IGNORE_DIRS` augments the built-in generated/vendor-directory exclusions.
- `FILE_EXTENSIONS` defaults to common JavaScript and TypeScript extensions.
- `OUTPUT_FILE` defaults to `.migration-state/evidence/react19-source-blockers.md` at the repository root.
- `OUTPUT_JSON` defaults to the Markdown path with a `.json` suffix.

### Build and run the bundle scan

Run the project-native production build. Require adjacent source maps with `sourcesContent`; a bundle without a usable map is reported as a parse/attribution error.

```bash
DIST_DIR=dist \
PKG_JSON=package.json \
node "<skill-directory>/scan-react19.mjs"
```

`BUNDLE_GLOB=bundle` scans `*.bundle.js` recursively. Use `BUNDLE_GLOB=js` when the build emits ordinary `*.js` files.

`OUTPUT_FILE` defaults to `.migration-state/evidence/react19-bundle-blockers.md` at the repository root; `OUTPUT_JSON` defaults to the same path with a `.json` suffix.

### Interpret the findings

Each report is deterministic and contains:

- `ruleId`: stable rule identifier.
- `impact`: `runtime-break`, `behavior-change`, `type-error`, `configuration`, `deprecation`, or `ecosystem-policy`.
- `confidence`: how directly the syntax proves the finding.
- `fixSupport`: `deterministic`, `contextual`, or `manual`.
- `file`, `line`, `column`, and `snippet`: actionable evidence.
- `ownership`: own code, direct dependency, or transitive dependency.
- `packageName` and bundle evidence where applicable.

Do not infer readiness from the Markdown summary alone. Parse errors mean the scan is incomplete and must be resolved or reported as a blocker.

An empty source scan means no source repairs were found; it does not settle dependency or target runtime readiness. Report dependency-owned and manual-only findings without patching their source. Investigate runtime-break findings against the target dependency tree and built artifacts. Do not treat an incomplete scan as a clean result.

## Phase 2: Migrate and verify

For target dependency or runtime-ownership changes, follow [references/target-runtime.md](references/target-runtime.md), including its library consumer checks. For supported own-code findings, read [references/migration.md](references/migration.md). Both branches belong to this capability and may be required together; preserve caller scope and protected files.

Prioritize runtime breaks, then behavior changes, then type and configuration issues. Preserve the reference's edit boundaries and contextual preconditions, and account for every original finding.

Report source findings, dependency alignment, and target runtime verification separately. `compatible` from the dependency helper is not an overall readiness verdict. A required target consumer check that fails or cannot run keeps the selected migration unverified; explicitly caller-delegated product checks remain deferred with their owner.

## Runtime requirements

The helper entrypoints run `npm install --no-save --package-lock=false --ignore-scripts --no-audit --no-fund` in the skill directory when required local dependencies are missing. First use requires npm, access to the configured npm registry, and a writable skill directory. Direct dependency versions are fixed in `package.json`; transitive versions may vary between installations. No lockfile is shipped or generated.
