# Apply and verify the HttpClient 5 migration

Load this after discovery reports `applicable`. Use the discovery evidence and
the caller's scope; do not widen it. Migrate module by module: POM, packaging,
then Java sources. API details are in [api-mapping.md](api-mapping.md); the
OpenRewrite YAML in [openrewrite-mapping.md](openrewrite-mapping.md) is a type
and method lookup for HttpClient 4.

## 1. Dependencies

In each module that declares or uses a legacy client:

- Remove `commons-httpclient:commons-httpclient` and
  `org.apache.httpcomponents:{httpclient,httpcore,httpmime,httpasyncclient,httpclient-cache,httpcore-nio}`.
  Keep an exclusion that only prevents another dependency from leaking a legacy
  client into the bundle.
- Add `org.apache.httpcomponents.client5:httpclient5` (it brings `httpcore5`
  and `httpcore5-h2`). Declare `org.apache.httpcomponents.core5:httpcore5`
  explicitly when application code imports `org.apache.hc.core5`. Add
  `httpclient5-cache` only to replace `httpclient-cache`, and
  `httpcore5`/`httpcore5-reactive` only to replace `httpcore-nio` usage.
- Declare `<scope>compile</scope>` explicitly so the app bundles the client.
  An omitted scope inherits any scope a parent POM or imported BOM manages for
  the artifact, which can silently make it `provided`. Do not use
  `provided` scope: `org.apache.hc.*` is not part of either product's supported
  public API. Confluence's system bundle exposes its own copy, but apps must not
  depend on it.
- Use the version managed by an imported target-platform BOM when one manages
  `httpclient5`. Otherwise declare one explicit version in the module's existing
  style (property or literal) and record it. Keep `httpcore5` on the line that
  version depends on.
- Exclude `org.slf4j:slf4j-api` from `httpclient5` and `httpclient5-cache`; the
  product supplies the logging API.

```xml
<dependency>
    <groupId>org.apache.httpcomponents.client5</groupId>
    <artifactId>httpclient5</artifactId>
    <version>${httpclient5.version}</version>
    <scope>compile</scope>
    <exclusions>
        <exclusion>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

Preserve existing exclusions and comments that still apply. Update stale
comments describing the platform-provided legacy client.

## 2. OSGi packaging

The bundled client references optional integrations that no product exports
to apps. bnd turns every reference into an `Import-Package` entry, so these
become mandatory under a trailing bare `*`. The plugin then fails to enable
(`missing requirement osgi.wiring.package; (osgi.wiring.package=org.brotli.dec)`)
and its REST endpoints return 404, although the build and the migration report
look clean.

Edit the `Import-Package` instruction of the module that packages the plugin
(AMPS `jira-maven-plugin`, `confluence-maven-plugin`, `amps-maven-plugin` or
`maven-bundle-plugin` configuration):

| Package | Referenced by |
|---|---|
| `org.brotli.dec` | `httpclient5` 5.2-5.5 Brotli decoding |
| `org.conscrypt` | `httpclient5`, `httpcore5-h2` Conscrypt TLS (all 5.x versions) |
| `com.aayushatharva.brotli4j.*` | `httpclient5` 5.6+ |
| `com.github.luben.zstd` | `httpclient5` 5.6+ |
| `org.apache.commons.compress.compressors` | `httpclient5` 5.6+ |
| `jdk.net` | `httpcore5` 5.3+ `SocketSupport` (so also `httpclient5` 5.4.x, which uses `httpcore5` 5.3.x), `httpclient5` 5.5.2+ |

- Under a trailing bare `*` (or with no `Import-Package` instruction), add all
  six packages as `pkg;resolution:=optional` before the `*`, whichever 5.x
  version you bundle; an optional import of an unreferenced package is
  harmless and avoids a rebuild per discovered reference. Without an
  instruction, add `<Import-Package>…optional entries…,*</Import-Package>`.
- Under `*;resolution:=optional`, every generated import is already optional;
  do not add entries.
- Do not make a package optional when application code itself requires it (for
  example, the app uses Commons Compress from the product); bundle or resolve
  that dependency separately.
- Remove `org.apache.http*` or `org.apache.commons.httpclient*` clauses only
  when no shipped code still references them.
- When an explicit embedding selector pins the bundled artifacts, add
  `httpclient5`, `httpcore5` and `httpcore5-h2` to it.

The table lists references in `httpclient5` 5.1-5.6, `httpcore5` and
`httpcore5-h2`. `jdeps` does not report the `httpcore5` `jdk.net` reference,
but bnd still generates the import. The bundle check in step 4 is
authoritative for the versions you pick.

## 3. Java sources

Replace every legacy import and fully qualified reference in the in-scope
modules with its HttpClient 5 equivalent and fix the API differences using
[api-mapping.md](api-mapping.md). Key points:

- Classic client types move to `org.apache.hc.client5.http.impl.classic` and
  request methods to `org.apache.hc.client5.http.classic.methods`; messages,
  entities, `NameValuePair` and `ContentType` move to `org.apache.hc.core5.http`.
- Timeouts take `Timeout`/`TimeValue`; connect and socket timeouts move to
  `ConnectionConfig`/`SocketConfig` on the connection manager. Keep each
  timeout's meaning: 4.x `setConnectTimeout` is the TCP connect timeout and
  maps to `ConnectionConfig#setConnectTimeout` (or the deprecated
  `RequestConfig#setConnectTimeout(Timeout)`), never to
  `setConnectionRequestTimeout`, which is the pool lease timeout. 4.x
  `setSocketTimeout` maps to `setResponseTimeout` or
  `ConnectionConfig`/`SocketConfig` socket timeouts.
- `HttpEntity#getContentType()` returns a `String`; status lines are replaced
  by `getCode()`; credentials take `char[]` passwords.
- Preserve checked-exception contracts. `CloseableHttpClient#close()` and
  `getUri()` (`URISyntaxException`) add checked exceptions; catch and wrap them
  when the existing signature must stay unchanged.
- Keep request semantics and client ownership. Do not close an injected or
  shared client per request, and prefer `execute(request, responseHandler)` so
  responses are always consumed and closed.
- Commons HttpClient 3 has no OpenRewrite path; migrate it from the 3.x table.

Do not change test expectations, probe identifiers, user-visible strings or
comments merely because they mention the legacy package.

## 4. Verify

1. Search the in-scope modules again: no executable `org.apache.http` or
   `org.apache.commons.httpclient` reference and no legacy HttpClient dependency
   remains, and no `getContentType().getValue()` remains on a 5.x entity.
   Compare each migrated timeout with the original: a 4.x connect timeout must
   not have become `setConnectionRequestTimeout`.
2. Compile the reactor with the caller's profiles, for example
   `mvn -pl <plugin-module> -am -DskipTests package`. Fix compilation errors.
3. Inspect the built plugin JAR. Set `resolved_skill_directory` to the
   directory containing this skill's `SKILL.md`, and use the caller's evidence
   directory when one is supplied:

   ```bash
   mkdir -p .migration-state/evidence/httpclient5
   bash "$resolved_skill_directory/scripts/inspect-bundle.sh" \
     "<plugin-module>/target/<plugin>.jar" \
     > ".migration-state/evidence/httpclient5/bundle-inspection.txt"
   ```

   Exit status 0 means HttpClient 5 classes are bundled, no legacy client
   package is imported or bundled, and no known optional integration is a
   mandatory import. Exit status 2 lists the failing checks; fix them and rerun.
   Also review the printed mandatory imports for any other non-product package
   introduced by the bundled client.
4. Run the module's unit tests when the caller authorizes it. Runtime
   validation on the target product is required before calling the migration
   complete; in an orchestrated run it belongs to the caller and is reported as
   deferred.

When builds are not authorized, complete the edits, and report the compile and
bundle checks as deferred validation with their owner. Never report a
migration verified from POM edits alone.

## 5. Report

Follow the caller's report contract. For the six-array report, add
`httpclient-to-5-migrate` to `selected_skills`; put the edited in-scope files
in `changed_files`; summarize each legacy API family and its replacement in
`migrated_apis`; put test-only usage, preserved comments/strings and deferred
validation in `skipped_candidates`; and put residual legacy usage, failed
bundle checks and unresolved scope questions in `blockers`. Keep evidence under
`.migration-state/`. Do not write notes files into the application tree, and do
not commit, push or open pull requests unless the user explicitly asks.
