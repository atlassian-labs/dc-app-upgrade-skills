# Atlaskit migration

Continue here after Steps 1–3 in `SKILL.md`. Preserve the detected package
manager, existing scripts, unrelated dependencies, and the project's dependency
versioning policy. Only change `package.json` dependency sections, the matching
lockfile, the bundler config, and application source.

## Step 4 — Upgrade `@atlaskit` packages

Bump every declared `@atlaskit/*` package to its latest version and install:

```bash
npx npm-check-updates --filter '/^@atlaskit\//' -u
npm install
```

With workspaces or `catalog:` versions (Step 1), add `--workspaces --root` so
the catalog and every workspace package are updated. The catalog rewrite can
drop comments from `.yarnrc.yml` or `pnpm-workspace.yaml`; diff the file and
restore any removed comment before installing.

For every package bumped across a major version, read
`node_modules/@atlaskit/<package>/CHANGELOG.md` between the old and new versions
and fix the `Major Changes` that affect the project's source: removed or renamed
props, moved entry points, removed exports, and changed hook signatures. The
installed package types are the final authority.

## Step 5 — Migrate legacy buttons

When Step 2 found legacy button call sites, follow
[`button-migration.md`](button-migration.md) after the package upgrade.

## Step 6 — Register the theme exclusion plugin

Install the plugin as an exact development dependency when it is missing:

```bash
npm install --save-dev --save-exact @atlassian/atlaskit-theme-excluded-webpack-plugin
```

The package exports one webpack/rspack plugin class (`AtlaskitThemeExcludePlugin`)
as its default/CommonJS export; the local binding name is free. Register one
instance in the `plugins` array of every production bundler config:

```js
// CommonJS (webpack.config.js, webpack.config.cjs, rspack.config.js)
const AtlaskitThemeExcludePlugin = require('@atlassian/atlaskit-theme-excluded-webpack-plugin');

// ESM (webpack.config.mjs, rspack.config.mjs, TypeScript configs)
import AtlaskitThemeExcludePlugin from '@atlassian/atlaskit-theme-excluded-webpack-plugin';

export default {
  // ...
  plugins: [new AtlaskitThemeExcludePlugin() /* , existing plugins */],
};
```

The plugin stubs the Atlaskit token theme imports, so a production build must
not emit `@atlaskit-internal_*` theme chunks. Step 8 verifies this.

## Step 7 — Hand off Compiled CSS

Upgraded Atlaskit packages import precompiled `.compiled.css` files:

```bash
find node_modules/@atlaskit -name '*.compiled.css' -print -quit
```

When any exist, run `compiled-react-setup` next. Until it configures CSS
extraction, a production build that fails on a `.compiled.css` import is
expected; do not revert the upgrade, patch dependencies, or stub the CSS.

After the upgrade, record which `@compiled/react` versions resolve
(`npm ls @compiled/react`, or `yarn why @compiled/react`). This is a check, not
a blocker; never stop the Atlaskit upgrade over it. When more than one version
resolves:

- If the project's own `@compiled/react` declaration (or catalog entry) is
  older than the range the Atlaskit packages require, and the project's Compiled
  Babel and bundler tooling accepts that range, raise the declaration to it.
- Otherwise, for example when that tooling requires a different major than
  Atlaskit does, leave the versions as they are and report both ranges.

Either way, pass the result to `compiled-react-setup`, which owns the Compiled
tooling.

## Step 8 — Verify

After `compiled-react-setup` (when required), run the package's existing
`lint`, `typecheck`, `test`, and `build` scripts; skip scripts the package does
not define and do not add new ones. The production build is required.

Then confirm:

1. The Step 2 legacy button scan returns no matches, and no codemod TODO comment
   remains: `grep -rniE "TODO.*codemod|codemod.*TODO" src`.
2. The declared, locked, and installed `@atlaskit/*` versions agree.
3. The production build output contains no `@atlaskit-internal_*` asset. If one
   remains, find its importer in the webpack stats (`--json` with reasons) and
   report it as an unresolved finding with that importer. Do not add a custom
   module replacement for theme imports.
4. Every migrated button still renders, handles clicks, and keeps link
   semantics; update tests and snapshots only where the rendered markup changed.
5. Upgraded modal dialogs, forms, and other overlay components open and render
   in a running instance of each target product, not only in unit tests.
   Products load different frontend runtimes, so a component can render in one
   and not another; report such a failure as an unresolved finding.

## Step 9 — Summary

Report:

1. Upgraded `@atlaskit` packages with old and new versions, and the breaking
   changes fixed.
2. Migrated legacy button call sites and the variant each one became.
3. The theme exclusion plugin state and what was added.
4. Verification results, including the theme-chunk check.
5. Whether `compiled-react-setup` is required next, and any remaining issues.
