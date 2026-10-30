---
name: analytics-api-migrate
description: Move an app off the Atlassian analytics API, which Platform 9 holds internal with no public replacement. Use when an app imports com.atlassian.analytics.api.* or com.atlassian.analytics.client.*, or injects AnalyticsConfigService or AnalyticsUploadService.
---

# Move off the Atlassian analytics API

**Scope:** Plugin and independent-library production code; the same public-API
rules apply to both. Libraries use source/dependency and build/test verification;
bundle and host-service checks require actual deployment context.

Platform 9 declares `analytics-api` and `analytics-client-exported` part of the platform's internal
dependency set. All eleven `com.atlassian.analytics.*` packages are internal exports and none is public,
so a product may stop putting any of them on the plugin classpath in any release. Jira 12 already has.

There is no public replacement. Two intermediate proposals would have left one — keeping
`AnalyticsConfigService` public, or splitting out a new `analytics-public-api` artifact — and neither
shipped. No such artifact exists, so there is no dependency an app can point at instead.

There is also no bundling path, which makes this different from a withdrawn third-party library.
`AnalyticsConfigService` and `AnalyticsUploadService` are interfaces whose implementations reach an app as
OSGi services published by the product. An app that embeds `analytics-api` gets its own copy of the
interface while the product registers its service under the product's copy, so the two are distinct types
to the framework and no injection matches. Bundling turns a missing class into a service that never
arrives.

The migration is therefore always the same shape: stop calling the API, and where the app used it as a
consent signal, ask for consent itself.

## What apps use it for

Two distinct uses, with different migrations. Establish which one applies before changing anything.

| Use | Surface | Migration |
|---|---|---|
| Reading whether analytics is switched on, as a gate before the app sends its own telemetry | `AnalyticsConfigService` | The app owns the decision: add its own opt-in setting. See below. |
| Submitting events into Atlassian's analytics pipeline | `AnalyticsUploadService`, `com.atlassian.analytics.api.events.*`, `@EventName` | No replacement. The app cannot publish into the product's pipeline; send to the app's own backend or drop the capability. |

The first is the common case and the one with known Marketplace callers. Reading Atlassian's setting was
never a grant of consent for an app's own collection, so replacing the call with the app's own setting is
a correctness fix as well as a migration.

## Case analysis

1. **No import of `com.atlassian.analytics.*` and no injection of either service.** Nothing to do. Report
   and stop.
2. **Imports only to gate the app's own telemetry.** Replace with the app's own opt-in. Migrate.
3. **Submits events into the Atlassian pipeline.** There is no replacement. Report what the app sends and
   what it appears to need it for, and stop. Do not invent a substitute pipeline.
4. **Uses the annotations or extractor packages to tag product-side events.** These only have meaning
   inside the analytics pipeline, so case 3 applies.

## Detect

Three checks. The first finds imports, the second finds injection sites an import scan can miss, and the
third finds the Spring wiring that makes an optional import fail anyway.

```bash
grep -rn --include="*.java" --include="*.kt" -E \
  "^import\s+com\.atlassian\.analytics\." .
```

```bash
grep -rn --include="*.java" --include="*.kt" -E \
  "Analytics(Config|Upload)Service|@EventName|AnalyticsEvent|BrowserEvent" .
```

```bash
grep -rn --include="*.java" --include="*.kt" --include="*.xml" -E \
  "ComponentImport[^)]*Analytics|<component-import[^>]*analytics" .
```

Record which packages appear; the remediation differs per package and one app commonly uses several.

## Migrate

Replace the gate with the app's own setting. The shape before:

```java
// Internal: com.atlassian.analytics.api.services.AnalyticsConfigService
if (analyticsConfigService.isAnalyticsEnabled()) {
    myTelemetry.send(event);
}
```

and after, reading a setting the app owns and the administrator controls:

```java
// The app's own opt-in, stored in plugin settings
if (mySettings.isTelemetryEnabled()) {
    myTelemetry.send(event);
}
```

Then remove the injection point and the component import, so nothing remains that Spring must satisfy:

```java
// Remove: @ComponentImport AnalyticsConfigService analyticsConfigService
```

Default the new setting to off unless the app already has explicit consent for its own collection through
another route. Reading Atlassian's flag is not that consent, so carrying its value across as the default
preserves the defect rather than the behaviour.

Do not add `analytics-api` or `analytics-client-exported` to the POM at compile scope. That is the
bundling path, and for these artifacts it yields interfaces that the product's services cannot satisfy.

## Verify

The app compiles without the analytics API:

```bash
grep -rn --include="*.java" --include="*.kt" -E \
  "^import\s+com\.atlassian\.analytics\." . | wc -l          # expect 0
mvn -q clean package
```

For plugins, confirm the built bundle neither imports an analytics package nor
carries an embedded copy (plain JARs have no bundle-import audit):

```bash
unzip -p target/*.jar META-INF/MANIFEST.MF \
  | tr -d '\r' | sed -e ':a' -e '$!N;s/\n //;ta' -e 'P;D' \
  | grep '^Import-Package' \
  | grep -oE "com\.atlassian\.analytics[^,;]*"

unzip -l target/*.jar | grep -E "com/atlassian/analytics/|analytics-(api|client)"
```

No output from either is the passing result. A match on the first means a call site remains; a match on
the second means the dependency was bundled rather than removed.

Removing the import is not sufficient on its own. An app that leaves an unsatisfied
`@ComponentImport` in place still fails to start, so confirm the injection point is gone too:

```bash
grep -rn --include="*.java" --include="*.kt" --include="*.xml" -E \
  "ComponentImport[^)]*Analytics|<component-import[^>]*analytics" . | wc -l   # expect 0
```

## Notes

- **`resolution:=optional` does not protect an injected service.** An optional import lets the bundle
  resolve, so the plugin installs; if the app then injects the service as a Spring bean without a
  fallback, the context fails and the plugin does not start. The failure appears as a bean-creation error
  at startup rather than an OSGi resolution error, so an app can pass a manifest review and still fail.
  Removing the injection matters as much as removing the import.
- **Availability is per package and varies by product.** Jira 12 publishes none of the eleven packages.
  Confluence 11 still publishes the four `com.atlassian.analytics.api.*` packages and has stopped
  publishing `com.atlassian.analytics.client.api.browser`. A surface still resolving on one product says
  nothing about another, and nothing about the next build of the same one.
- **A surface that still works is still internal.** The packages that survive on Confluence 11 are the
  ones with the widest known usage, which makes them the most likely to break an app later rather than
  the safest to keep.
- **Where this was verified.** Every surface listed here resolves on Confluence 10.2.15 and Jira 11.3.7. On
  an early Jira 12 build all five fail with `NoClassDefFoundError`. On an early Confluence 11 build only
  `com.atlassian.analytics.client.api.browser` fails and the four `api.*` packages still resolve. The
  per-call-site replacements are the app's own code and were not measured.
