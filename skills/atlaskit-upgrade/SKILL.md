---
name: atlaskit-upgrade
description: Discover and upgrade @atlaskit packages, migrate legacy @atlaskit/button usage to the new button family, configure the Atlaskit theme exclusion bundler plugin, and verify the frontend build
---

# Atlaskit upgrade

Upgrade the `@atlaskit/*` packages of a frontend project, migrate legacy
`@atlaskit/button` call sites to the new button family, and register the
Atlaskit theme exclusion plugin in the bundler. Upgraded Atlaskit packages ship
precompiled `.compiled.css`; extracting and delivering that CSS belongs to the
companion skill `compiled-react-setup`, which must run after this one.

Honor the caller's phase (assess, apply, or verify), module and embedded-module
scope, and validation ownership. Assessment does not edit files or install
packages.

## Step 1 — Locate the frontend packages

Find every in-scope `package.json` that declares an `@atlaskit/*` dependency in
`dependencies` or `devDependencies`. Exclude `node_modules`, generated output,
and agent tooling. If none exists, report `not-applicable` and stop.

For each package root, detect the package manager from its lockfile
(`package-lock.json` → npm, `yarn.lock` → yarn, `pnpm-lock.yaml` → pnpm) and the
bundler config (`webpack.config.*` or `rspack.config.*`, in `.js`, `.cjs`,
`.mjs`, or `.ts`). Commands below use npm; substitute the detected manager.

Also record whether the root declares `workspaces` and whether any dependency
uses a `catalog:` version. Workspace packages are in scope, and Yarn catalog
versions live in the `catalog`/`catalogs` section of `.yarnrc.yml` (pnpm:
`pnpm-workspace.yaml`), not in `package.json`.

## Step 2 — Detect actionable findings

Run all three checks from each package root:

1. **Outdated packages.** List the declared `@atlaskit/*` versions and the latest
   published versions without changing anything:

   ```bash
   npx npm-check-updates --filter '/^@atlaskit\//'
   ```

   With workspaces or `catalog:` versions, add `--workspaces --root`
   (npm-check-updates 17 or later). Without it, catalog-pinned packages and
   workspace packages are silently left out.

2. **Legacy button usage.** Imports or requires of the package root, of the
   sub-paths that still resolve to the legacy implementation (including
   `@atlaskit/button/button`, which the root deprecation notice suggests), or of
   the deprecated `@atlaskit/button/new` entry point are call sites to migrate:

   ```bash
   grep -rnE "(from |require\()['\"]@atlaskit/button(/(button|standard-button|loading-button|custom-theme-button(/custom-theme-button)?|custom-theme-button-types|theme|types|old-button/types|new))?['\"]" src
   ```

   Imports from `@atlaskit/button/default/button`, `@atlaskit/button/link`,
   `@atlaskit/button/icon/button`, `@atlaskit/button/icon/link`, or
   `@atlaskit/button/split-button/*` are already on the new button family.
   `@atlaskit/button/button-group` is the supported `ButtonGroup` entry point,
   not a legacy call site.

3. **Theme exclusion plugin.** The package must declare
   `@atlassian/atlaskit-theme-excluded-webpack-plugin` as a development
   dependency, and the bundler config must register it:

   ```bash
   grep -n "atlaskit-theme-excluded-webpack-plugin" package.json webpack.config.* rspack.config.* 2>/dev/null
   ```

   A lexical match is a candidate only. Confirm registration by inspecting the
   bundler's `plugins` array.

Record the package roots, the outdated packages, every legacy button call site
(file and line), and the plugin state as the migration evidence.

## Step 3 — Decide

The capability is `applicable` when any `@atlaskit/*` package is outdated, any
legacy button call site remains, or the theme exclusion plugin is missing or not
registered. Otherwise report `already-compatible` and stop without editing.

Only when findings remain and edits are authorized, read
[`references/migration.md`](references/migration.md) completely and follow it.
