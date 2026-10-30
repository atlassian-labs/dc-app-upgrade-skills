# Migrate confirmed jQuery incompatibilities

## Classify before editing

Read each candidate file and resolve its receiver. Follow imports, AMD factory
parameters, assignments, and IIFE arguments such as `(function ($) {...})(AJS.$)`.
`$`, a method name, or a TypeScript `any` alone does not prove jQuery ownership.
Native string/array methods, `Function.prototype.bind`, AJS event-bus methods,
Backbone/Brace/Marionette events, and Cypress chains must retain their behavior.
Real jQuery calls inside tests are still candidates; do not exclude a whole
test file because another expression uses Cypress.

For templates (`.vm`, `.soy`, `.ftl`, `.jade`, `.vmd`, `.html`), edit executable
JavaScript only after confirming its script context. Inspect external script
sources separately within scope. Do not rewrite template text or quoted examples.
Patch files require understanding the resulting source and hunk structure;
report them for manual handling when that cannot be validated.

## Removed utilities

The following rules apply to confirmed jQuery receivers, including aliases.
Evaluate arguments once and preserve their coercion and null handling.

| Existing use | Replacement and conditions |
|---|---|
| `$.trim(value)` | For a proven string, `value.trim()`. Otherwise preserve null/undefined as `''` and string coercion, using a small helper if needed: `return value == null ? '' : String(value).trim();`. Test the actual accepted input types. |
| `$.parseJSON(value)` | `JSON.parse(value)` for JSON strings; preserve error propagation. Very old jQuery treated some empty/null inputs differently, so inspect callers when upgrading from 1.x/2.x. |
| `$.isArray(value)` | `Array.isArray(value)` |
| `$.isFunction(value)` | `typeof value === 'function'` when callers use ordinary callable values |
| `$.now()` | `Date.now()` |
| `$.unique(elements)` | `$.uniqueSort(elements)`, preserving jQuery's DOM ordering and mutation behavior |
| `$.isWindow(value)` | If needed, `value != null && value === value.window`; evaluate the input once |
| `$.isNumeric`, `$.type`, `$.camelCase`, `$.nodeName` | Inspect accepted values and uses before replacing. Numeric strings differ from `Number.isFinite`, arrays/null/dates differ from `typeof`, and name comparison may require case folding. Use a narrowly scoped helper only when caller behavior requires it. |
| `$.cssProps`, `$.fx.interval` | Inspect the intended customization; do not replace these with unrelated CSS/timing settings or silently discard behavior. |
| `$.cssNumber` | Pass explicit string values with the intended units at affected `.css()` calls. Remove obsolete unit exemptions only after verifying those calls. |

## Older removals and supported APIs

For older code, `.andSelf()` becomes `.addBack()` and collection `.size()` becomes
`.length`. Confirm the collection receiver first. jqXHR `.success`, `.error`,
and `.complete` require callback-specific review (`.done`, `.fail`, and completion
callbacks have different argument contracts); do not blindly change `.complete`
to `.always`. Legacy event `.load(handler)` differs from the supported AJAX
`.load(url, ...)`. `.live` and `.die` require preserving delegated event roots.

Leave `.bind`, `.unbind`, `.delegate`, `.undelegate`, event shorthands such as
`.click`, and utilities such as `$.proxy`, `$.each`, `$.map`, `$.parseHTML`,
`$.isPlainObject`, and `$.isEmptyObject` alone when the selected target supports
them. Only modernize supported deprecated calls if the caller explicitly asks.

## Input-dependent jQuery 4 changes

Inspect these when the source or runtime behavior establishes a dependency on
the changed behavior. A method name alone is not sufficient:

- `.toggleClass(boolean)` and `.toggleClass()` no longer toggle all classes.
  Preserve the actual class-state behavior; naming the classes explicitly is
  appropriate only when the original intent is known.
- jQuery 4 adds `px` to numeric `.css()` values only for its documented
  [auto-unit property list](https://api.jquery.com/css/). Length properties outside
  that list, such as `letterSpacing` and `fontSize`, need explicit units:
  `.css('letterSpacing', 2)` becomes `.css('letterSpacing', '2px')` to preserve
  implicit pixels. Keep unitless values such as opacity numeric.
- Collection `.push`, `.sort`, and `.splice` are removed. Native array methods
  remain supported. Preserve collection identity/mutation and return values
  when adapting confirmed jQuery collection usage.
- AJAX no longer promotes JSON to JSONP or implicitly executes same-origin
  script responses. Make `dataType` explicit only when that transport or script
  execution is the intended behavior. Do not enable code execution based only
  on a response MIME type. Review async script transport and binary/FormData
  prefilter behavior when the application depends on them.
- Custom selector pseudos, `$.Deferred.getStackHook`, slim-build modules, deep
  source imports, and Node factory imports require target/build-specific review.
  The lexical helper does not cover every import or runtime-only behavior.

Consult the official [jQuery 4 upgrade guide](https://jquery.com/upgrade-guide/4.0/)
and, for earlier removals, the [jQuery 3 upgrade guide](https://jquery.com/upgrade-guide/3.0/).
Check the application's actual distribution, including any compatibility shims.

## Declared versions

Work from `declared-versions.json`. Compare each in-scope declaration with the
target using the rules below, and resolve or explain every confirmed mismatch.

**An app's declared jQuery version is a test-time choice; the runtime version is
the product's.** An app that loads jQuery through the host — `AJS.$`, or an
`atlassian-plugin.xml` dependency on `com.atlassian.auiplugin:jquery-*` — runs on
whatever the product bundles, whatever `package.json` says. Confluence 11.0.0
bundles AUI 11.0.2 and jQuery 4.0.0.8.

**`@atlassian/aui`'s peer range is what blocks the bump, not jQuery itself.**
Raising `jquery` to `^4` under an AUI that admits only jQuery 2 or 3 fails the
install with `ERESOLVE`, naming the peer range. Raise AUI to a version whose
peer range admits the target's jQuery major rather than holding jQuery back.
Matching the product's `auiplugin.version` is the strictest alignment, and is not
the only version that installs.

| `@atlassian/aui` | `peerDependencies.jquery` |
|---|---|
| 9.13.6 | `^2 \|\| ^3` |
| 10.1.7 | `^2 \|\| ^3 \|\| ^4` |
| 11.0.2 | `^4.0.0` |

Align `jquery` to the product's bundled major and `@types/jquery` to the same
major as `jquery`. Read the peer range out of the chosen AUI version rather than
inferring it from the major, and confirm the result with the project's own
install and test run.

**Maven `aui.version` is frequently declared and never used.** Resolve what
consumes it before changing it: grep every module pom for the property reference,
then check the parent poms for a dependency that it would override. Where nothing
resolves it, the repair is to delete the property, not to update it — a bumped
value that no dependency reads is a false signal that the app was aligned.

## Apply and verify

1. Apply only confirmed, in-scope repairs. Preserve exceptions, event behavior,
   coercion, and return values. Leave false positives and supported calls intact.
2. Re-run the same scan for each approved source root, setting `OUTPUT_FILE` to
   `candidates-post-migration.json` in its evidence folder. Use numbered suffixes
   for further scans, such as `candidates-post-migration-2.json`, preserving all
   earlier reports. Account for every original
   candidate as repaired, supported, unrelated, or unresolved. Native `.trim()`
   may remain in a lexical report after a correct migration; zero raw matches
   is not the success criterion.
3. Run focused behavior checks for changed calls, including null/coercion inputs
   if they are accepted by the application. Use the project-native frontend
   build and relevant runtime tests on the target jQuery distribution when
   available. Assertions or mocks describing expected behavior do not establish
   compatibility with the target runtime. Respect caller-delegated shared
   build/runtime checks and report them as deferred, not passed.
4. Inspect the application diff. Return changed paths, migrated APIs, skipped
   candidates with reasons, checks performed/deferred, and unresolved blockers.
