# Legacy `@atlaskit/button` → new button family

Reference for Step 5 of [`migration.md`](migration.md). Run it after the
`@atlaskit/button` upgrade so the new entry points exist in `node_modules`.

Official guide:
https://atlassian.design/components/button/button-legacy/migration-guide

## 1 — Run the official codemod

```bash
npx @atlaskit/codemod-cli --preset migrate-to-new-buttons --parser tsx --extensions ts,tsx src
```

Use `--parser babel --extensions js,jsx` for JavaScript sources. Pass the source
directories that contain the Step 2 call sites; do not run it over
`node_modules` or generated output. If the codemod tool leaves a downloaded
`plugin_packages/` directory in the working tree, delete it.

The codemod recognizes only the package root and the `standard-button`,
`loading-button`, and `custom-theme-button` sub-paths. Before running it,
rewrite `@atlaskit/button/button` imports to `@atlaskit/button/standard-button`
and `@atlaskit/button/custom-theme-button/custom-theme-button` imports to
`@atlaskit/button/custom-theme-button`. Migrate legacy type imports (`types`,
`old-button/types`, `custom-theme-button-types`, `theme`) by hand to the
matching new-button types.

The codemod rewrites legacy imports to the new variants (`Button`, `LinkButton`,
`IconButton`, `LinkIconButton`), turns `<Button href>` into `LinkButton`, turns
icon-only buttons into `IconButton`, and leaves a `TODO: (from codemod)` comment
where a call site needs manual work.

## 2 — Use the dedicated entry points

The codemod imports from `@atlaskit/button/new`, which current releases
deprecate. Replace those imports, and any that already existed, with the
dedicated entry points, keeping the local names:

| Export from `@atlaskit/button/new` | Dedicated import |
|---|---|
| default (`Button`), `ButtonProps` | `@atlaskit/button/default/button` |
| `LinkButton`, `LinkButtonProps` | `@atlaskit/button/link` (default export) |
| `IconButton`, `IconButtonProps` | `@atlaskit/button/icon/button` (default export) |
| `LinkIconButton`, `LinkIconButtonProps` | `@atlaskit/button/icon/link` (default export) |
| `SplitButton` | `@atlaskit/button/split-button/split-button` (named export) |
| shared types (`Appearance`, `Spacing`, `IconProp`, …) | `@atlaskit/button/variants/types` |

For example:

```tsx
import Button from '@atlaskit/button/default/button';
import LinkButton from '@atlaskit/button/link';
```

The `@deprecated` notes in
`node_modules/@atlaskit/button/dist/types/entry-points/new.d.ts` are the
authority when this table and the installed version disagree.

## 3 — Resolve what the codemod left

Fix every `TODO: (from codemod)` comment, then remove it. The common cases:

- **Links.** Anchor-style buttons must be `LinkButton` (or `LinkIconButton`) with
  `href`. The new `Button` always renders a `<button>`. The `link` and
  `subtle-link` appearances no longer exist; use `subtle` or `default`, or the
  `@atlaskit/link` component for an inline text link.
- **Icon-only buttons.** Use `IconButton` with a required `label`. Icon props
  (`icon`, `iconBefore`, `iconAfter`) take an icon component
  (`iconBefore={AddIcon}`), not a rendered element.
- **Loading.** `LoadingButton` and the `overlay` prop are replaced by the
  `isLoading` prop on the new buttons.
- **Unsupported props.** `component`, `css`, `style`, `className`, and `theme`
  are not supported. `CustomThemeButton` has no new-button equivalent; rebuild
  it with the `Pressable` or `Anchor` primitives. Migrate these call sites by
  hand.
- **Refs.** The new buttons forward `ref` to the rendered element; replace
  `innerRef` / `buttonRef` with `ref`.
- **Callbacks.** `onClick` keeps the `(event, analyticsEvent)` signature.

The installed package types are the final authority for prop names and values.

## 4 — Check the result

```bash
grep -rnE "(from |require\()['\"]@atlaskit/button(/(button|standard-button|loading-button|custom-theme-button(/custom-theme-button)?|custom-theme-button-types|theme|types|old-button/types|new))?['\"]" src
grep -rn "TODO: (from codemod)" src
```

Both scans must return no matches before Step 6 of `migration.md`.
