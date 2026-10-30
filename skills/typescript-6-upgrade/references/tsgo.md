# Existing or explicitly requested native compiler checks

Load this reference only when the caller explicitly requests a native compiler
check or the project already defines one. Native TypeScript uses a separate
compiler generation; it does not establish that a TypeScript 6 migration passed.
Do not introduce it, replace `tsc`, or start a TypeScript 7 migration as part of
this skill's normal path.

Preserve an existing native-check script and its selected projects. Record the
actual binary/package/version and run it only within caller validation limits.
For a new explicitly requested check, first resolve the requested release,
installation and supported API/build integration from the official
[TypeScript native compiler documentation](https://github.com/microsoft/typescript-go).
Do not assume an old `@typescript/native-preview@beta` tag is the intended target.

Compare diagnostics with the authoritative TS6 checks on the same inputs. Record
all differences, including JavaScript/JSDoc and declaration-resolution failures;
do not filter diagnostic codes or rewrite source to satisfy an optional check
without assessing the cause and edit scope. A required native-check failure is a
blocker for that requested check, while a caller-delegated check remains deferred.
Keep the TS6 result and native compiler result separate in the report.

For example, TS7016 on a JavaScript module imported by TypeScript means the
selected check cannot find its declaration. Compare the same import and project
under TS6; inspect package typings/exports and included declarations before
calling it a native compiler regression. Add a declaration only for a known API
within approved source scope, or report the unresolved difference. Do not
automatically convert JavaScript sources or filter TS7016 from the results.
