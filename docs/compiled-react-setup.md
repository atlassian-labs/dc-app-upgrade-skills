---
skill: compiled-react-setup
skill-sha256: 045f21b986dc
---

# Set up Compiled CSS extraction

`@atlaskit` components ship precompiled CSS. Without the right `@compiled`
wiring in the bundler and Babel config, the compiled CSS classes end up in the
main bundle in non-deterministic order, which causes cascade bugs. This guide
covers the manual setup. The [last section](#use-the-skill) describes the
`compiled-react-setup` skill, which checks and fixes it. It is the required
companion to [atlaskit-upgrade](atlaskit-upgrade.md), and can also be used on
its own to audit the build wiring.

## Is my code affected?

The setup applies when the frontend project has any `@atlaskit/*` dependency or
a direct `@compiled/react` dependency. A project with no direct
`@compiled/react` dependency is still affected if it uses `@atlaskit`.

```bash
grep -E '@compiled|@atlaskit' package.json
```

You can skip it when neither appears, or when the setup is already correct:
the loader uses `extract: true`, `CompiledExtractPlugin` is registered, and
`@compiled/babel-plugin` is active only in the `test` environment (checks
below).

## Before you start

- Find the directory with the frontend `package.json`.
- Detect the package manager from the lockfile (`yarn.lock`,
  `package-lock.json`, `pnpm-lock.yaml`). Commands below use `yarn`; substitute
  yours.
- Keep your existing scripts and dependency version policy.

## Migrate

### 1. Bundler config (webpack or rspack)

Use `@compiled/webpack-loader` with `extract: true` in the loader rules of both
the dev and prod configs, and add `CompiledExtractPlugin` to the plugins.

```js
// Loader rule (dev and prod)
{
  loader: '@compiled/webpack-loader',
  options: {
    extract: true,
    importReact: false, // not needed with the React 17+ JSX transform
    transformerBabelPlugins: ['@atlaskit/tokens/babel-plugin'],
  },
}

// Plugins array (base config)
// sortShorthand defaults to true: shorthand properties (margin) are placed
// before longhands (margin-top) so the cascade order is correct
new CompiledExtractPlugin()
```

| Mistake | Effect |
|---|---|
| Loader present without `extract: true` | Compiled CSS lands in the main bundle in non-deterministic order |
| `CompiledExtractPlugin` missing | Compiled CSS is not extracted to a separate file |
| `classHashPrefix` set together with `extract: true` | Not supported by `@compiled/webpack-loader` 1.x; the plugin throws. Omit the prefix. |

### 2. Babel config

`@compiled/webpack-loader` and `@compiled/babel-plugin` do the same job, so they
must never both be active for the same files: that causes double transformation
and parse errors. For a webpack or rspack project using `babel-loader`, the
loader handles builds and the Babel plugin is active only under `env.test`
(Jest or Vitest):

```js
// babel.config.js or .babelrc.js
module.exports = {
  env: {
    test: {
      plugins: [
        ['@compiled/babel-plugin', {
          transformerBabelPlugins: ['@atlaskit/tokens/babel-plugin'],
        }],
      ],
    },
  },
};
```

Check where the plugin is active:

```bash
grep -r "compiled/babel-plugin" .babelrc.js babel.config.js .babelrc 2>/dev/null
```

- If it appears **outside** a `test` env block, move it inside `env.test`.
- If it is missing from `env.test`, run the tests first without adding it. If
  they pass, it is not needed: the `@compiled/react` runtime stubs work for
  tests that do not render compiled components directly. Add it only if tests
  fail with an error such as "css() can only be used as part of a
  @compiled/react setup".

## Verify

Run lint, typecheck, tests and a build once, at the end:

```bash
yarn lint
yarn typecheck
yarn test
yarn build
```

Then find the output directory and confirm the build emitted a non-empty CSS
asset containing the extracted atomic rules:

```bash
grep -rE "output.*path" webpack*.config.* rspack*.config.* 2>/dev/null
ls -la <output-path>/*.css
```

Take the asset name from the build output; it depends on the CSS pipeline.
`MiniCssExtractPlugin` with `[name].css` emits `compiled-css.css`, while webpack's
built-in CSS support emits a per-entry file such as `<entry>.bundle.css`. A
different name is not an error. A passing build alone is not evidence: a CSS
asset must contain the Compiled rules.

For a Data Center plugin, also confirm that exactly this CSS asset is packaged
and registered as a `<resource type="download">` in the same web-resource as the
JavaScript bundle. A successful webpack build does not by itself make the CSS
available in Jira or Confluence.

## Troubleshooting

| Symptom | Cause or fix |
|---|---|
| No CSS asset contains the Compiled rules | `CompiledExtractPlugin` is not in the plugins array, or `extract: true` is missing from the loader options. Revisit step 1. |
| Cascade bugs, wrong style order | Loader without `extract: true`, so compiled CSS is in the main bundle. |
| Double transformation or parse errors | `@compiled/babel-plugin` is active during builds as well as the loader. Scope it to `env.test`. |
| `CompiledExtractPlugin` throws on startup | `classHashPrefix` is set together with `extract: true`. Remove it. |
| Test error "css() can only be used as part of a @compiled/react setup" | Add `@compiled/babel-plugin` to `env.test`. |
| CSS built but missing in the product | The asset is not registered as a download resource in the plugin's web-resource. |
| CSS asset is not named `compiled-css.css` | Not an error; the name depends on the CSS pipeline. |

## Use the skill

Ask your agent to check or fix the `@compiled` setup (for example "check if
@compiled is set up correctly", "why isn't compiled-css.css being produced?"),
or run it right after [`atlaskit-upgrade`](atlaskit-upgrade.md). The `compiled-react-setup` skill:

1. **Locates** the frontend directory (asking if it is not obvious) and detects
   the package manager from the lockfile.
2. **Checks applicability.** It reads the `@compiled` entries in `package.json`
   and continues whenever the project has any `@atlaskit/*` dependency or a
   direct `@compiled/react` dependency. If the setup is already correct, it
   stops without editing.
3. **Fixes** the loader options, `CompiledExtractPlugin` and Babel plugin
   scoping as described above, in both dev and prod configs.
4. **Verifies** once at the end with lint, typecheck, tests and build, then
   checks the emitted CSS asset and, for a Data Center plugin, its packaging.
5. **Summarizes** what was fixed, which CSS asset is produced and whether it is
   registered, the script results, and remaining issues. If the project has
   `@atlaskit` dependencies, it reminds you to run `atlaskit-upgrade` to
   configure `AtlaskitThemeExcludePlugin` if that is not done.

It leaves to you any issue the build still reports after the fixes, and the
product-level check that the CSS is actually served in Jira or Confluence.
