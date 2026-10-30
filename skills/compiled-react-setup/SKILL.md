---
name: compiled-react-setup
description: Verify and fix @compiled/react setup in webpack/rspack and babel configs for any project using @compiled/react or @atlaskit pre-compiled CSS
---

Verify that `@compiled/react` is wired up correctly in the bundler
(webpack/rspack) and babel configs. Fix any missing or misconfigured pieces.

This is a required companion to the `atlaskit-upgrade` skill — `@atlaskit`
components ship pre-compiled CSS that will misbehave without the correct
`@compiled` setup. It can also be run standalone whenever the user asks to
audit the compiled build wiring.

## When to use

Use this skill when the user asks something like:
- "check if @compiled is set up correctly"
- "verify compiled/react webpack config"
- "why isn't compiled-css.css being produced?"
- Or immediately after `atlaskit-upgrade`.

## Step 1 — Locate the frontend directory

Ask the user for the path to the frontend directory containing `package.json`
if not obvious from context. If a path is already mentioned, use it directly.

Detect the package manager from the lockfile in that directory:

- `yarn.lock` → `yarn`
- `package-lock.json` → `npm`
- `pnpm-lock.yaml` → `pnpm`

All commands in the migration reference use `yarn` for brevity — substitute
the detected manager (`npm run …` / `pnpm …`) as appropriate.

## Step 2 — Check current compiled setup

Inspect the current dependency state:

```bash
cd <frontend-dir>
grep -E '@compiled' package.json
```

Even if `@compiled/react` is NOT a direct dependency, `@atlaskit` components
ship pre-compiled CSS that requires `CompiledExtractPlugin` and
`@compiled/webpack-loader` to be configured in the bundler. Without them,
compiled CSS classes end up in the main bundle in non-deterministic order,
causing cascade bugs.

Always continue when the project has any `@atlaskit/*` dependency or a direct
`@compiled/react` dependency.

When the setup needs changes, read
[`references/migration.md`](references/migration.md) completely and continue
with Step 3. If the setup is already correct, stop without editing it.
