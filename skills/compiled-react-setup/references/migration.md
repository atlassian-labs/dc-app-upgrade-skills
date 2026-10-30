# Compiled React migration

Continue here after completing Steps 1–2 in `SKILL.md`. Preserve the detected
package manager, existing scripts, and the project's dependency versioning
policy.

## Step 3 — Webpack/rspack config check

The project must use `@compiled/webpack-loader` with `extract: true`, and
`CompiledExtractPlugin` must be added to plugins. Check both dev and prod
webpack/rspack configs.

**Correct configuration:**

```js
// In webpack/rspack config (both dev and prod loader rules):
{
  loader: '@compiled/webpack-loader',
  options: {
    extract: true,
    importReact: false, // not needed with React 17+ JSX transform
    transformerBabelPlugins: ['@atlaskit/tokens/babel-plugin'],
  },
}

// In plugins array (base config):
// sortShorthand defaults to true — shorthand properties (e.g. margin) are placed
// before longhands (e.g. margin-top) to ensure correct cascade order
new CompiledExtractPlugin()
```

Do not set `classHashPrefix` together with `extract: true`.
`@compiled/webpack-loader` 1.x does not support that combination, and
`CompiledExtractPlugin` throws when it receives the option. Omitting the prefix
in extraction mode is expected and is not a blocker.

**Common mistakes to fix:**
- `@compiled/webpack-loader` present but WITHOUT `extract: true` → compiled CSS ends up in the main bundle in non-deterministic order, causing cascade bugs
- `CompiledExtractPlugin` missing → compiled CSS not extracted to a separate file

## Step 4 — Babel config check

`@compiled/webpack-loader` and `@compiled/babel-plugin` do the same thing — they
must **never both be active for the same files at the same time**, as this
causes double transformation and parse errors.

The correct split for webpack/rspack projects with `babel-loader`:

- **`@compiled/webpack-loader`** handles transformation during builds (webpack/rspack)
- **`@compiled/babel-plugin`** should only be active in the `test` environment (Jest/Vitest), where there is no webpack-loader

```js
// babel.config.js or .babelrc.js
module.exports = {
  env: {
    // Only active for Jest — webpack builds use @compiled/webpack-loader instead
    test: {
      plugins: [
        [
          '@compiled/babel-plugin',
          {
            transformerBabelPlugins: ['@atlaskit/tokens/babel-plugin'],
          },
        ],
      ],
    },
  },
};
```

Check the babel config:

```bash
grep -r "compiled/babel-plugin" .babelrc.js babel.config.js .babelrc 2>/dev/null
```

If `@compiled/babel-plugin` is present **outside** a `test` env block (i.e. it
would run during webpack/rspack builds), move it inside `env.test`.

If `@compiled/babel-plugin` is missing entirely from `env.test`, first **run
the tests without adding it**. If tests pass, it is not needed —
`@compiled/react` runtime stubs work fine for tests that don't directly render
compiled components. Only add `@compiled/babel-plugin` to `env.test` if tests
fail with an error like "css() can only be used as part of a @compiled/react
setup" or similar runtime errors from `@compiled/react`.

## Step 5 — Verify

Run lint, typecheck, tests, and a build once, at the end, to confirm the config
changes work and the compiled CSS is produced. Use the package manager detected
in Step 1 (`yarn` / `npm run` / `pnpm`) — the examples below use `yarn` but
substitute as appropriate:

```bash
cd <frontend-dir>
yarn lint
yarn typecheck
yarn test
yarn build
```

Then find the webpack/rspack output directory and confirm that the build emitted
a non-empty CSS asset containing the extracted Compiled atomic rules:

```bash
grep -rE "output.*path" webpack*.config.* rspack*.config.* 2>/dev/null
ls -la <output-path>/*.css
```

The asset name depends on the CSS pipeline, so take it from the build output
rather than expecting a fixed filename. For example, `MiniCssExtractPlugin`
with `[name].css` emits `compiled-css.css`, while webpack's built-in CSS
support emits a per-entry file such as `<entry>.bundle.css`. A different name
is not an error. If no CSS asset contains the Compiled rules, revisit Step 3:
either `CompiledExtractPlugin` is not in the plugins array or `extract: true`
is missing from the `@compiled/webpack-loader` options.

For a Data Center plugin, also verify that exactly this emitted CSS asset is
packaged and registered as a `<resource type="download">` in the same
web-resource as the JavaScript bundle. A successful webpack build alone does
not make the CSS available in Jira or Confluence.

Fix any issues that surface before wrapping up.

## Step 6 — Summary

Report to the user:
1. Whether `@compiled/webpack-loader` + `CompiledExtractPlugin` were configured
   correctly, and what (if anything) was fixed (Step 3)
2. Whether `@compiled/babel-plugin` was correctly scoped to `env.test` only
   (Step 4)
3. Which Compiled CSS asset is produced and whether it is registered, and lint / typecheck /
   test / build results (Step 5)
4. Any remaining issues that need manual attention
5. If the project has `@atlaskit` dependencies, remind the user to run the
   `atlaskit-upgrade` skill to also configure `AtlaskitThemeExcludePlugin`
   (if not already done).
