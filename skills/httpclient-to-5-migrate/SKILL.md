---
name: httpclient-to-5-migrate
description: Migrate Java Maven plugin modules from Commons HttpClient 3 (org.apache.commons.httpclient) or Apache HttpClient 4 (org.apache.http) to a bundled HttpClient 5 for Platform 9. Select for executable legacy imports or legacy HttpClient dependencies; apply AI-guided source, POM and OSGi Import-Package edits and verify the built bundle.
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
`org.apache.hc.core5`) and bundle it at compile scope.

This is an AI-guided skill: apply edits with the available file tools. The
OpenRewrite YAML under `references/` is a mapping lookup and is never executed;
a missing recipe runner is not an `unsupported` result.

## Scope

Honor the caller's phase (`assess`, `apply`, `verify`), selected plugin module,
embedded modules, target product/version and Maven profiles. Without a caller
scope, use the module that packages the plugin and every sibling module whose
classes it ships. Exclude tests, generated sources and build output from the
required scope; report test-only legacy usage as a skipped candidate.

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
   Record the declared scope: `provided`, or absent with source usage, means
   the app relies on the withdrawn platform export. A legacy client already
   bundled at compile scope is not broken by the export removal, but it is
   still migrated when this skill is selected.
3. **Packaging.** Record the AMPS/bnd `Import-Package` and any explicit
   embedding selector (`Embed-Dependency`, a pinned `Embedded-Dependencies`
   list, `Bundle-ClassPath`). A trailing bare `*` makes unlisted imports
   mandatory; `*;resolution:=optional` makes them optional.

Write evidence to the caller's evidence directory, otherwise to
`.migration-state/evidence/httpclient5/discovery.md` at the repository root.
Record each module, the matched files and references, the legacy dependencies
with scope and origin (local, inherited, transitive), the packaging
configuration, and anything you could not inspect.

## Applicability

- `applicable`: executable in-scope code references a legacy namespace, or an
  in-scope POM declares a legacy HttpClient dependency.
- `already-compatible`: inspection is complete and only HttpClient 5 usage
  remains. Verify the bundle with `scripts/inspect-bundle.sh` when an artifact
  is available; an unverified bundle is reported as deferred validation.
- `not-applicable`: no legacy or HttpClient 5 usage in scope.
- `uncertain`: a scope, profile or inherited configuration could not be
  inspected. A missing match in an incompletely inspected module is not a clean
  result.

Stop after assessment, or when no actionable migration remains. Otherwise load
[references/migration.md](references/migration.md) and follow it; it links the
API mapping and verification steps.
