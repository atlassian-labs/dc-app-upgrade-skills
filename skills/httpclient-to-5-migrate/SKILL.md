---
name: httpclient-to-5-migrate
description: Migrate Java Maven plugins and libraries from HttpClient 3 (org.apache.commons.httpclient) or 4 (org.apache.http) to HttpClient 5 for Platform 9. Select for executable legacy usage or dependencies; bundle only for plugins.
metadata:
  version: 0.1.0
---

# HttpClient 5 migration

Platform 9 products stop exporting the legacy HTTP clients to apps. Jira 12
removes both `org.apache.commons.httpclient` (3.x) and `org.apache.http` (4.x);
Confluence 11 removes `org.apache.http` (it never exported 3.x). An app that
imports either package without bundling it resolves on the baseline product and
fails with `NoClassDefFoundError` or an unresolved bundle on the upgrade. The
supported repair is to migrate to Apache HttpClient 5 (`org.apache.hc.client5` /
`org.apache.hc.core5`), bundled at compile scope for plugins.

This is an AI-guided skill: apply edits with the available file tools. The
OpenRewrite YAML under `references/` is a mapping lookup and is never executed;
a missing recipe runner is not an `unsupported` result.

## Scope

**Scope:** Maven plugins and independent libraries. Honor caller phase
(`assess`, `apply`, `verify`), modules, goal, and profiles. Without explicit scope,
inspect the selected module, declared children for a component, or actual embedded
modules for a plugin. Platform 9 library work needs no product/version; resolve
exact targets only for product-dependent checks. Do not invent embedding.

Tests, generated sources, and output are not applicability signals. Update
coupled tests/helpers only within approved scope, preserving assertions. Report
protected-test blockers without disabling tests.

## Detect

1. **Source usage.** Search `src/main/{java,kotlin,groovy,scala}` of every
   in-scope module for imports, static imports and fully qualified references
   to `org.apache.commons.httpclient` or `org.apache.http`. Ignore comments,
   string literals and documentation. `org.apache.hc.*` is already HttpClient 5.
2. **Maven dependencies.** Inspect each in-scope `pom.xml` and its effective
   model (`mvn help:effective-pom`, or `mvn dependency:tree
   -Dincludes=org.apache.httpcomponents,commons-httpclient` with the caller's
   profiles) for `commons-httpclient:commons-httpclient` and
   `org.apache.httpcomponents:{httpclient,httpcore,httpmime,httpasyncclient,httpclient-cache,httpcore-nio}`.
   Record the declared scope. For plugins, `provided`, or absent with source
   usage, means reliance on the withdrawn platform export. A legacy client already
   bundled at compile scope is not broken by the export removal, but it is
   still migrated when selected. For libraries, record the consumer dependency
   contract; compile dependencies are not physically embedded classes.
3. **Packaging, when applicable.** For a real plugin packaging owner, record
   the AMPS/bnd `Import-Package` and any explicit
   embedding selector (`Embed-Dependency`, a pinned `Embedded-Dependencies`
   list, `Bundle-ClassPath`). A trailing bare `*` makes unlisted imports
   mandatory; `*;resolution:=optional` makes them optional. Plain-library
   packaging checks are not applicable; do not add AMPS/bnd.

Write evidence to the caller's evidence directory, otherwise to
`.migration-state/evidence/httpclient5/discovery.md` at the repository root.
Record each module, the matched files and references, the legacy dependencies
with scope and origin (local, inherited, transitive), the packaging
configuration, and anything you could not inspect.

## Applicability

- `applicable`: executable in-scope code references a legacy namespace, or an
  in-scope POM declares a legacy HttpClient dependency.
- `already-compatible`: complete inspection finds only HttpClient 5. Verify
  library source/dependencies and build/tests; for plugins also run
  `scripts/inspect-bundle.sh` when available, otherwise defer bundle validation.
- `not-applicable`: no legacy or HttpClient 5 usage in scope.
- `uncertain`: a scope, profile or inherited configuration could not be
  inspected. A missing match in an incompletely inspected module is not a clean
  result.

Stop after assessment, or when no actionable migration remains. Otherwise load
[references/migration.md](references/migration.md) and follow it; it links the
API mapping and verification steps.
