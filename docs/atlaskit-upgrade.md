---
skill: atlaskit-upgrade
skill-sha256: 90275c14c696
---

# Upgrade Atlaskit packages

Atlaskit packages ship precompiled CSS, and the legacy `@atlaskit/button` is
replaced by a new button family. A frontend project that depends on
`@atlaskit/*` needs its packages upgraded, its legacy button call sites
migrated, and the Atlaskit theme exclusion plugin registered in the bundler.
This guide covers the manual migration. The [last section](#use-the-skill)
describes the `atlaskit-upgrade` skill, which automates it. Extracting and
delivering the compiled CSS is a separate step, covered by
[compiled-react-setup](compiled-react-setup.md), and must run after this one.

## Is my code affected?

Run these checks from each package root that declares an `@atlaskit/*`
dependency in `dependencies` or `devDependencies` (include workspace packages;
skip `node_modules` and generated output). If no package declares one, the
migration does not apply.

1. **Outdated packages.**

   ```bash
   npx npm-check-updates --filter '/^@atlaskit\//'
   ```

   With workspaces or `catalog:` versions, add `--workspaces --root`
   (npm-check-updates 17 or later). Without it, catalog-pinned and workspace
   packages are silently left out.

2. **Legacy button imports.**

   ```bash
   grep -rnE "(from |require\()['\"]@atlaskit/button(/(button|standard-button|loading-button|custom-theme-button(/custom-theme-button)?|custom-theme-button-types|theme|types|old-button/types|new))?['\"]" src
   ```

   Imports of the package root, of those sub-paths, and of the deprecated
   `@atlaskit/button/new` entry point are call sites to migrate. Imports from
   `@atlaskit/button/default/button`, `/link`, `/icon/button`, `/icon/link` or
   `/split-button/*` are already on the new family, and
   `@atlaskit/button/button-group` is the supported `ButtonGroup` entry point.

3. **Theme exclusion plugin.**

   ```bash
   grep -n "atlaskit-theme-excluded-webpack-plugin" package.json webpack.config.* rspack.config.* 2>/dev/null
   ```

   A match is only a candidate. Open the bundler config and confirm the plugin
   is in the `plugins` array, and that `package.json` lists it as a development
   dependency.

You can skip the migration when nothing is outdated, no legacy button call site
remains, and the plugin is declared and registered.

## Before you start

- Detect the package manager from the lockfile (`package-lock.json` for npm,
  `yarn.lock`, `pnpm-lock.yaml`) and the bundler config (`webpack.config.*` or
  `rspack.config.*`). Commands below use npm; substitute yours.
- Note whether the root declares `workspaces` or any dependency uses a
  `catalog:` version. Yarn catalog versions live in `.yarnrc.yml` (pnpm:
  `pnpm-workspace.yaml`), not in `package.json`.
- Keep your existing scripts, unrelated dependencies and version policy. Only
  `package.json` dependency sections, the lockfile, the bundler config and
  application source should change.

## Migrate

### 1. Upgrade the packages

```bash
npx npm-check-updates --filter '/^@atlaskit\//' -u
npm install
```

Add `--workspaces --root` for workspaces or catalogs. The catalog rewrite can
drop comments from `.yarnrc.yml` or `pnpm-workspace.yaml`; diff the file and
restore them before installing.

For every package that moved across a major version, read
`node_modules/@atlaskit/<package>/CHANGELOG.md` between the old and new
versions and fix the `Major Changes` that touch your source: removed or renamed
props, moved entry points, removed exports, changed hook signatures. The
installed package types are the final authority.

### 2. Migrate legacy buttons

Do this after step 1, so the new entry points exist in `node_modules`.

1. Normalize two imports the codemod does not recognize:
   `@atlaskit/button/button` becomes `@atlaskit/button/standard-button`, and
   `@atlaskit/button/custom-theme-button/custom-theme-button` becomes
   `@atlaskit/button/custom-theme-button`. Migrate legacy type imports
   (`types`, `old-button/types`, `custom-theme-button-types`, `theme`) by hand
   to the matching new-button types.
2. Run the official codemod over the directories that contain the call sites:

   ```bash
   npx @atlaskit/codemod-cli --preset migrate-to-new-buttons --parser tsx --extensions ts,tsx src
   ```

   Use `--parser babel --extensions js,jsx` for JavaScript. Delete any
   `plugin_packages/` directory the tool leaves behind.
3. The codemod produces `Button`, `LinkButton`, `IconButton` and
   `LinkIconButton`, turns `<Button href>` into `LinkButton`, turns icon-only
   buttons into `IconButton`, and leaves a `TODO: (from codemod)` comment where
   it needs help.
4. The codemod imports from `@atlaskit/button/new`, which current releases
   deprecate. Replace those imports with the dedicated entry points, keeping
   local names:

   | Export from `@atlaskit/button/new` | Dedicated import |
   |---|---|
   | default (`Button`), `ButtonProps` | `@atlaskit/button/default/button` |
   | `LinkButton`, `LinkButtonProps` | `@atlaskit/button/link` (default export) |
   | `IconButton`, `IconButtonProps` | `@atlaskit/button/icon/button` (default export) |
   | `LinkIconButton`, `LinkIconButtonProps` | `@atlaskit/button/icon/link` (default export) |
   | `SplitButton` | `@atlaskit/button/split-button/split-button` (named export) |
   | shared types (`Appearance`, `Spacing`, `IconProp`, ...) | `@atlaskit/button/variants/types` |

   ```tsx
   // Before
   import Button from '@atlaskit/button';
   // After
   import Button from '@atlaskit/button/default/button';
   import LinkButton from '@atlaskit/button/link';
   ```

   If the table and the installed version disagree, the `@deprecated` notes in
   `node_modules/@atlaskit/button/dist/types/entry-points/new.d.ts` win.

5. Resolve every `TODO: (from codemod)` comment, then delete it.

**Cases that need hand work:**

| Case | What to do |
|---|---|
| Anchor-style buttons | Use `LinkButton` or `LinkIconButton` with `href`. The new `Button` always renders a `<button>`. The `link` and `subtle-link` appearances are gone: use `subtle` or `default`, or `@atlaskit/link` for an inline text link. |
| Icon-only buttons | Use `IconButton` with a required `label`. `icon`, `iconBefore` and `iconAfter` take an icon component (`iconBefore={AddIcon}`), not a rendered element. |
| Loading | `LoadingButton` and `overlay` are replaced by `isLoading`. |
| `component`, `css`, `style`, `className`, `theme` props | Not supported. `CustomThemeButton` has no equivalent; rebuild it with the `Pressable` or `Anchor` primitives. |
| Refs | The new buttons forward `ref` to the rendered element. Replace `innerRef` and `buttonRef` with `ref`. |
| Callbacks | `onClick` keeps the `(event, analyticsEvent)` signature. |

### 3. Register the theme exclusion plugin

Install it as an exact development dependency if missing:

```bash
npm install --save-dev --save-exact @atlassian/atlaskit-theme-excluded-webpack-plugin
```

The package exports one plugin class, `AtlaskitThemeExcludePlugin`, as its
default/CommonJS export. Register one instance in the `plugins` array of every
production bundler config:

```js
// CommonJS (webpack.config.js, .cjs, rspack.config.js)
const AtlaskitThemeExcludePlugin = require('@atlassian/atlaskit-theme-excluded-webpack-plugin');

// ESM (.mjs, TypeScript configs)
import AtlaskitThemeExcludePlugin from '@atlassian/atlaskit-theme-excluded-webpack-plugin';

export default {
  plugins: [new AtlaskitThemeExcludePlugin() /* , existing plugins */],
};
```

The plugin stubs the Atlaskit token theme imports, so a production build must
not emit `@atlaskit-internal_*` theme chunks.

### 4. Hand off the compiled CSS

Upgraded packages import precompiled `.compiled.css` files:

```bash
find node_modules/@atlaskit -name '*.compiled.css' -print -quit
```

If any exist, follow [compiled-react-setup](compiled-react-setup.md) next. Until
CSS extraction is configured, a production build that fails on a `.compiled.css`
import is expected. Do not revert the upgrade, patch dependencies or stub the
CSS.

Also check which `@compiled/react` versions resolve (`npm ls @compiled/react`,
or `yarn why @compiled/react`). This is a check, not a blocker. If more than one
resolves:

- When your own `@compiled/react` declaration (or catalog entry) is older than
  the range the Atlaskit packages require, and your Compiled Babel and bundler
  tooling accepts that range, raise the declaration to it.
- Otherwise, for example when that tooling requires a different major than
  Atlaskit does, leave the versions alone and note both ranges.

## Verify

1. Run the package's existing `lint`, `typecheck`, `test` and `build` scripts
   (skip any it does not define). The production build is required, and it
   needs the compiled CSS setup in place first if step 4 applied.
2. Both searches must come back empty:

   ```bash
   grep -rnE "(from |require\()['\"]@atlaskit/button(/(button|standard-button|loading-button|custom-theme-button(/custom-theme-button)?|custom-theme-button-types|theme|types|old-button/types|new))?['\"]" src
   grep -rniE "TODO.*codemod|codemod.*TODO" src
   ```

3. Declared, locked and installed `@atlaskit/*` versions agree.
4. The production build output contains no `@atlaskit-internal_*` asset. If one
   remains, find its importer in the webpack stats (`--json` with reasons). Do
   not add a custom module replacement for theme imports.
5. Every migrated button still renders, handles clicks and keeps link semantics.
   Update tests and snapshots only where the rendered markup changed.
6. Upgraded modal dialogs, forms and other overlays open and render in a running
   instance of each target product. Products load different frontend runtimes,
   so a component can render in one and not another. Unit tests alone are not
   evidence.

## Troubleshooting

| Symptom | Cause or fix |
|---|---|
| Production build fails on a `.compiled.css` import | Expected until CSS extraction is configured; run [compiled-react-setup](compiled-react-setup.md). |
| Catalog or workspace packages not upgraded | Re-run `npm-check-updates` with `--workspaces --root` (version 17 or later). |
| Comments missing from `.yarnrc.yml` or `pnpm-workspace.yaml` after the bump | The catalog rewrite dropped them; restore them from the diff. |
| `@atlaskit-internal_*` chunk in the build | A theme import is not being stubbed. Check the plugin is registered in the production config, then trace the importer in the webpack stats. |
| Codemod leaves legacy imports untouched | It recognizes only the package root and the `standard-button`, `loading-button` and `custom-theme-button` sub-paths. Rewrite the others first. |
| `plugin_packages/` appears in the working tree | Left by the codemod tool; delete it. |
| Component renders in one product but not another | Products load different frontend runtimes; treat it as an unresolved finding. |

## Use the skill

Ask your agent to upgrade the Atlaskit packages of a frontend project. The
`atlaskit-upgrade` skill honors the phase you ask for (assess, apply or
verify), the module scope and who owns validation. Assessing does not edit
files or install packages.

1. **Locate.** It finds every in-scope `package.json` declaring `@atlaskit/*`,
   excluding `node_modules`, generated output and agent tooling. With none, it
   reports "not applicable" and stops. It detects the package manager, the
   bundler config, workspaces and `catalog:` versions.
2. **Detect.** It runs the three checks above and records package roots,
   outdated packages, each legacy button call site (file and line) and the
   plugin state.
3. **Decide.** The migration is applicable when any package is outdated, any
   legacy button call site remains, or the plugin is missing or unregistered.
   Otherwise it reports "already compatible" and does not edit.
4. **Migrate**, only if edits are authorized: upgrade packages and fix major
   changes, migrate buttons, register the plugin, and check for compiled CSS.
5. **Verify and summarize.** It runs the existing scripts and the checks above,
   then reports upgraded packages with old and new versions, migrated button
   call sites and their variants, plugin state, verification results, and
   whether [`compiled-react-setup`](compiled-react-setup.md) is needed next.

It leaves to you the `CustomThemeButton` rebuild and other unsupported button
props, unresolved `@atlaskit-internal_*` importers, and checking components in a
running instance of each target product.
