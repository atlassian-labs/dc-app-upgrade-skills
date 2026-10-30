---
skill: upgrade-to-jquery-4
skill-sha256: 176ed3d8656e
---

# Upgrade to jQuery 4

jQuery 4 removes utilities that were deprecated in earlier releases, changes a
few behaviors (class toggling, automatic CSS units, AJAX transport) and
removes collection mutators. A plugin that uses jQuery through the host
(`AJS.$`) runs on whatever jQuery the product bundles, so code that relied on
removed APIs breaks when the product moves to jQuery 4. This guide covers the
manual migration of call sites and declared versions. The
[last section](#use-the-skill) describes the `upgrade-to-jquery-4` skill, which
scans for candidates and migrates the confirmed ones.

## Is my code affected?

Check two separate things. Compatible call sites do not prove the declared
versions are ready.

1. **Call sites.** Search sources, templates and patches for the removed or
   changed APIs (tables below). The skill's scanner uses these patterns, and
   they are only candidates:

   ```bash
   git grep -nE '\.(isFunction|isArray|isNumeric|isWindow|type|trim|parseJSON|now|nodeName|camelCase|unique|andSelf|size|live|die|success|complete|error|load|unload|toggleClass|css|ajax|ajaxSetup|get|post|push|sort|splice)[[:space:]]*\(|\.(cssProps|cssNumber|getStackHook|expr)\b|\.fx\.interval' \
     -- '*.js' '*.jsx' '*.mjs' '*.cjs' '*.ts' '*.tsx' '*.soy' '*.vm' '*.ftl' '*.jade' '*.vmd' '*.html' '*.patch'
   ```

   Expect many false positives: native `.trim()`, array `.push`, event-bus and
   Backbone `.load` or `.error`, and Cypress chains all match. Resolve the
   receiver of each hit before editing.
2. **Declarations.** Look at the `jquery`, `@types/jquery` and
   `@atlassian/aui` entries in every `package.json`, and at an `aui.version`
   property in every module `pom.xml`, not only the module that holds the
   frontend.

You can skip the migration only when there is no jQuery usage and no declared
jQuery or AUI version. A non-empty hit list alone does not mean the migration
is needed, and an empty one does not settle the declared versions.

## Before you start

- Know the jQuery major your target product bundles. For example, Confluence
  11.0.0 bundles AUI 11.0.2 and jQuery 4.0.0.8.
- Know whether jQuery reaches you from the host (`AJS.$`, or an
  `atlassian-plugin.xml` dependency on `com.atlassian.auiplugin:jquery-*`).
  If so, the version in `package.json` is a test-time choice; the runtime
  version is the product's.
- Keep the host-provided jQuery delivery as it is, and check your actual
  distribution, including any compatibility shim, before changing anything that
  depends on it.

## Migrate

### 1. Resolve each receiver

Follow imports, AMD factory parameters, assignments and IIFE arguments such as
`(function ($) {...})(AJS.$)`. The name `$`, a method name or a TypeScript
`any` alone does not prove the receiver is jQuery. Leave alone native string
and array methods, `Function.prototype.bind`, AJS event-bus methods,
Backbone, Brace and Marionette events, and Cypress chains. A real jQuery call
inside a test file is still a candidate.

In templates (`.vm`, `.soy`, `.ftl`, `.jade`, `.vmd`, `.html`), edit only
executable JavaScript whose script context you have confirmed. Do not rewrite
template text or quoted examples. For `.patch` files, edit only when you can
validate the resulting source and hunk structure.

### 2. Removed utilities

For confirmed jQuery receivers, including aliases. Evaluate arguments once and
keep their coercion and null handling.

| Existing use | Replacement and conditions |
|---|---|
| `$.trim(value)` | `value.trim()` for a proven string. Otherwise keep null as `''` and string coercion: `value == null ? '' : String(value).trim()` |
| `$.parseJSON(value)` | `JSON.parse(value)`; keep error propagation. Inspect callers when upgrading from 1.x or 2.x, which treated some empty or null inputs differently |
| `$.isArray(value)` | `Array.isArray(value)` |
| `$.isFunction(value)` | `typeof value === 'function'` for ordinary callable values |
| `$.now()` | `Date.now()` |
| `$.unique(elements)` | `$.uniqueSort(elements)` (keeps DOM ordering and mutation) |
| `$.isWindow(value)` | If needed, `value != null && value === value.window`; evaluate the input once |
| `$.isNumeric`, `$.type`, `$.camelCase`, `$.nodeName` | Inspect accepted values first. Numeric strings differ from `Number.isFinite`, arrays, null and dates differ from `typeof`, and name comparison may need case folding. Use a small helper only where callers need that behavior |
| `$.cssProps`, `$.fx.interval` | Inspect the intended customization; do not swap in unrelated CSS or timing settings or drop the behavior |
| `$.cssNumber` | Pass explicit string values with units at the affected `.css()` calls; remove old unit exemptions only after verifying them |

```js
// Before
const name = $.trim(input.val());
if ($.isArray(items)) { /* ... */ }

// After
const name = String(input.val() ?? '').trim();
if (Array.isArray(items)) { /* ... */ }
```

### 3. Older removals

| Existing use | Replacement |
|---|---|
| `.andSelf()` | `.addBack()` |
| collection `.size()` | `.length` (confirm the receiver is a collection) |

These need review rather than a rename:

- jqXHR `.success`, `.error` and `.complete`: `.done`, `.fail` and completion
  callbacks have different argument contracts. Do not turn `.complete` into
  `.always` blindly.
- Legacy event `.load(handler)` differs from the supported AJAX
  `.load(url, ...)`.
- `.live` and `.die`: keep the delegated event root.

Leave `.bind`, `.unbind`, `.delegate`, `.undelegate`, event shorthands such as
`.click`, and `$.proxy`, `$.each`, `$.map`, `$.parseHTML`, `$.isPlainObject`
and `$.isEmptyObject` alone when your target supports them. Do not modernize
supported deprecated calls as part of this migration unless you want to.

### 4. Behavior changes (change only if your code depends on them)

A method name alone is not enough. Confirm the code relies on the changed
behavior.

- `.toggleClass(boolean)` and `.toggleClass()` no longer toggle all classes.
  Keep the actual class-state behavior; name the classes explicitly only if you
  know the original intent.
- jQuery 4 adds `px` to numeric `.css()` values only for its documented
  [auto-unit properties](https://api.jquery.com/css/). Other length properties
  need explicit units; keep unitless values such as opacity numeric:

  ```js
  // Before
  $el.css('letterSpacing', 2);
  // After
  $el.css('letterSpacing', '2px');
  ```
- Collection `.push`, `.sort` and `.splice` are removed. Native array methods
  still work. Keep identity, mutation and return values when you adapt a
  confirmed jQuery collection.
- AJAX no longer promotes JSON to JSONP or implicitly executes same-origin
  script responses. Set `dataType` explicitly only when that transport or script
  execution is intended. Do not enable code execution based only on a response
  MIME type. Review async script transport and binary or `FormData` prefilter
  behavior if you depend on them.
- Custom selector pseudos, `$.Deferred.getStackHook`, slim-build modules, deep
  source imports and Node factory imports need review against your build.

See the [jQuery 4 upgrade guide](https://jquery.com/upgrade-guide/4.0/) and, for
earlier removals, the [jQuery 3 upgrade guide](https://jquery.com/upgrade-guide/3.0/).

### 5. Declared versions

`@atlassian/aui`'s peer range is what blocks the bump, not jQuery itself.
Raising `jquery` to `^4` under an AUI that admits only jQuery 2 or 3 fails the
install with `ERESOLVE` and names the peer range.

| `@atlassian/aui` | `peerDependencies.jquery` |
|---|---|
| 9.13.6 | `^2 \|\| ^3` |
| 10.1.7 | `^2 \|\| ^3 \|\| ^4` |
| 11.0.2 | `^4.0.0` |

1. Raise `@atlassian/aui` to a version whose peer range admits the target
   jQuery major. Matching the product's `auiplugin.version` is the strictest
   alignment, not the only one that installs. Read the peer range from the
   chosen version rather than inferring it from the major.
2. Align `jquery` to the product's bundled major, and `@types/jquery` to the
   same major as `jquery`.
3. For a Maven `aui.version` property, grep every module pom for references and
   check parent poms for a dependency it would override. If nothing resolves
   it, delete the property rather than bumping it; a bumped value nothing
   reads is a false sign of alignment.

## Verify

1. Re-run the search from the first section on the same roots. Account for every
   original hit as repaired, supported, unrelated or unresolved. A native
   `.trim()` can legitimately remain; zero raw matches is not the goal.
2. Run focused checks on each changed call, including null and coercion inputs
   your application accepts.
3. Run the frontend build and the relevant tests against the target jQuery
   distribution. Assertions or mocks that describe expected behavior do not
   prove compatibility with the real runtime.
4. Reinstall and check that `jquery`, `@types/jquery` and `@atlassian/aui` resolve
   without `ERESOLVE`, and that no `aui.version` property remains that nothing reads.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `ERESOLVE` naming a `jquery` peer range on install | `@atlassian/aui` is too old for jQuery 4. Raise AUI (see the table above). |
| `$.trim`, `$.isArray`, `$.parseJSON` and similar are "not a function" | Removed utilities running on a jQuery 4 host. Replace them. |
| A class stops toggling, or `.toggleClass(false)` does nothing | `toggleClass` with a boolean or no argument changed. |
| A numeric `.css()` value is ignored | The property is not in the auto-unit list. Pass a string with units. |
| A script response no longer runs, or a JSON response is not treated as JSONP | AJAX no longer infers these. Set `dataType` explicitly if it was intended. |
| Raw search still matches after the migration | Supported or native calls (for example `.trim()` on a string) remain. Review each hit. |

## Use the skill

Ask your agent to "upgrade to jQuery 4". The `upgrade-to-jquery-4` skill works
in an assess, apply and verify sequence.

**Detect.** It looks at the requested module and any declared embedded
modules, or the current repository, for jQuery usage and aliases such as
`AJS.$`, and for declared jQuery or AUI versions. It stops only when there is
neither. It runs two scripts, both writing under
`.migration-state/evidence/jquery4/<module>/`:

- `find-declared-jquery-versions.sh` lists `jquery`, `@types/jquery` and
  `@atlassian/aui` entries from every `package.json`, plus `aui.version`
  properties from poms, into `declared-versions.json`. It is an inventory, not
  a verdict. The skill compares each entry against the target product.
- `find-jquery-usage.sh` writes `candidates.json`, a list of files and the API
  names matched. In the default mode it also tries `npx
  @atlassian/jquery-eslint-runner` (unpinned; needs Node, npm and registry
  access); `JQUERY_SCAN_MODE=grep` uses local lexical scanning only. It needs
  Bash, Git, Python 3.9+ and grep, and covers tracked files only, so untracked
  sources and computed or multiline calls need a manual look. An optional
  `jquery-type-filter.js` can narrow results against a tsconfig, but it can
  omit calls, so the unfiltered report is kept.

Candidates are not findings. It reports the candidate count and the confirmed
count separately, with each skipped candidate and why, and treats incomplete
inspection as not clean.

**Migrate and verify.** It applies only confirmed, in-scope repairs from the
tables above, then re-scans into `candidates-post-migration.json` (numbered
suffixes for later scans, earlier reports preserved), accounts for every
original candidate, runs focused behavior checks and the project frontend
build and tests where permitted, and inspects the diff. A check owned by
a calling workflow is reported as deferred, not passed.

It leaves to you: calls whose receiver or behavior is ambiguous, template and
patch text it cannot validate, supported deprecated calls, and any runtime check
it was not permitted to run.
