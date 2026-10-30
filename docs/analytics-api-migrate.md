---
skill: analytics-api-migrate
skill-sha256: 45d7895ecd14
---

# Move off the Atlassian analytics API

Platform 9 holds `analytics-api` and `analytics-client-exported` internal. All
eleven `com.atlassian.analytics.*` packages are internal exports and none is
public, so a product may stop putting any of them on the plugin classpath in
any release. Jira 12 already has. There is no public replacement and no
bundling path, so the migration is to stop calling the API. This guide covers
the manual migration. The [last section](#use-the-skill) describes the
`analytics-api-migrate` skill.

## Is my code affected?

Your code is affected if production code in a plugin or library imports
`com.atlassian.analytics.api.*` or `com.atlassian.analytics.client.*`, or
injects `AnalyticsConfigService` or `AnalyticsUploadService`. Run all three
checks. The first finds imports, the second finds injection sites an import
scan can miss, and the third finds the Spring wiring that makes an optional
import fail anyway.

```bash
grep -rn --include="*.java" --include="*.kt" -E \
  "^import\s+com\.atlassian\.analytics\." .

grep -rn --include="*.java" --include="*.kt" -E \
  "Analytics(Config|Upload)Service|@EventName|AnalyticsEvent|BrowserEvent" .

grep -rn --include="*.java" --include="*.kt" --include="*.xml" -E \
  "ComponentImport[^)]*Analytics|<component-import[^>]*analytics" .
```

If all three come back empty, there is nothing to do. Record which packages
appear, because the remediation differs per package and one app commonly uses
several.

## Before you start

Decide which of two uses applies to each call site before changing anything.

| Use | Surface | Migration |
|---|---|---|
| Reading whether analytics is switched on, as a gate before the app sends its own telemetry | `AnalyticsConfigService` | The app owns the decision: add its own opt-in setting (step 1). |
| Submitting events into Atlassian's analytics pipeline | `AnalyticsUploadService`, `com.atlassian.analytics.api.events.*`, `@EventName` | No replacement. The app cannot publish into the product's pipeline. Send to the app's own backend or drop the capability. |

The first is the common case. Using the annotations or extractor packages to
tag product-side events falls under the second row, because they only have
meaning inside the analytics pipeline.

Do not look for an `analytics-public-api` artifact. Intermediate proposals
mentioned one, but none shipped. The page recording the decision describes two
superseded options alongside the one that shipped, so follow the shipped export
set rather than its body.

## Migrate

### 1. Replace the gate with your own setting

Reading Atlassian's setting was never consent for an app's own collection, so
replacing the call with the app's own setting is a correctness fix as well as a
migration.

```java
// Before: com.atlassian.analytics.api.services.AnalyticsConfigService
if (analyticsConfigService.isAnalyticsEnabled()) {
    myTelemetry.send(event);
}

// After: the app's own opt-in, stored in plugin settings
if (mySettings.isTelemetryEnabled()) {
    myTelemetry.send(event);
}
```

Default the new setting to off unless the app already has explicit consent for
its own collection through another route. Carrying Atlassian's flag across as
the default preserves the defect rather than the behaviour.

### 2. Remove the injection point and component import

Nothing may remain that Spring must satisfy:

```java
// Remove: @ComponentImport AnalyticsConfigService analyticsConfigService
```

Also remove any `<component-import>` in the descriptor that names an analytics
service. Removing the injection matters as much as removing the import, see
[Troubleshooting](#troubleshooting).

### 3. Do not bundle the API

Do not add `analytics-api` or `analytics-client-exported` to the POM at compile
scope. `AnalyticsConfigService` and `AnalyticsUploadService` are interfaces
whose implementations reach an app as OSGi services published by the product.
A bundled copy is a distinct type to the framework, so the product's service
never matches your injection point: a missing class becomes a service that
never arrives.

### Cases that need your judgment

- **Event submission.** There is no substitute pipeline. Decide whether to send
  events to your own backend or drop the capability. Do not invent a
  replacement.

## Verify

1. No import remains, and the app builds:

   ```bash
   grep -rn --include="*.java" --include="*.kt" -E \
     "^import\s+com\.atlassian\.analytics\." . | wc -l          # expect 0
   mvn -q clean package
   ```

2. For plugins, confirm the built bundle neither imports an analytics package
   nor carries an embedded copy. A plain JAR has no bundle-import audit.

   ```bash
   unzip -p target/*.jar META-INF/MANIFEST.MF \
     | tr -d '\r' | sed -e ':a' -e '$!N;s/\n //;ta' -e 'P;D' \
     | grep '^Import-Package' \
     | grep -oE "com\.atlassian\.analytics[^,;]*"

   unzip -l target/*.jar | grep -E "com/atlassian/analytics/|analytics-(api|client)"
   ```

   No output from either is the passing result. A match on the first means a
   call site remains. A match on the second means the dependency was bundled
   rather than removed.

3. Confirm the injection point is gone too:

   ```bash
   grep -rn --include="*.java" --include="*.kt" --include="*.xml" -E \
     "ComponentImport[^)]*Analytics|<component-import[^>]*analytics" . | wc -l   # expect 0
   ```

Removing the import alone is not sufficient evidence: an unsatisfied
`@ComponentImport` still stops the app starting. Bundle and host-service checks
need an actual deployment; for a library, source, dependency and build/test
checks are what you can run.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Plugin installs but fails at startup with a bean-creation error | `resolution:=optional` lets the bundle resolve, but an injected service with no fallback still fails the Spring context. Remove the injection, not only the import. |
| `NoClassDefFoundError` for an analytics class on Jira 12 | None of the eleven packages is published there. Remove the call site. |
| `NoClassDefFoundError` for `com.atlassian.analytics.client.api.browser` on Confluence 11 | That package is no longer published. The four `com.atlassian.analytics.api.*` packages still resolve there, but they remain internal. |
| App still works on one product | Availability is per package and varies by product. A surface that resolves on one product says nothing about another, or about the next build of the same one. A surface that still works is still internal, and the most widely used ones are the most likely to break an app later. |
| Injection never matches after adding `analytics-api` to the POM | Bundling gives a distinct copy of the interface. Remove the dependency (step 3). |

## Use the skill

Ask your agent to "move this app off the Atlassian analytics API". The
`analytics-api-migrate` skill applies to plugin and independent-library
production code.

1. Sorts the app into a case: no analytics imports or injections (nothing to
   do, it stops); imports only to gate the app's own telemetry (it migrates);
   submits events into the pipeline, or uses the annotations or extractor
   packages (no replacement exists, so it reports what the app sends and what it
   appears to need it for, and stops).
2. Detects with the three searches above and records which packages appear.
3. Replaces the gate with the app's own opt-in setting, defaulted off unless
   consent already exists, and removes the injection point and component import.
   It does not add the analytics artifacts to the POM.
4. Verifies with the zero-match searches, `mvn -q clean package`, and, for
   plugins, the manifest and embedded-copy checks.

It leaves to you the design of the app's own setting and consent flow, and the
decision on what to do with event submission.
