---
skill: httpclient-to-5-migrate
skill-sha256: 7fe0487ade56
---

# Migrate to Apache HttpClient 5

Platform 9 products stop exporting the legacy HTTP clients to apps. Jira 12
removes both `org.apache.commons.httpclient` (3.x) and `org.apache.http` (4.x);
Confluence 11 removes `org.apache.http` (it never exported 3.x). An app that
imports either package without bundling it resolves on the current product and
fails with `NoClassDefFoundError` or an unresolved bundle after the upgrade. The
repair is to move to Apache HttpClient 5 (`org.apache.hc.client5` and
`org.apache.hc.core5`), bundled at compile scope in a plugin. This guide covers
the manual migration. The [last section](#use-the-skill) describes the
`httpclient-to-5-migrate` skill.

## Is my code affected?

Your module needs migration if its main sources import, statically import or
fully qualify a legacy package, or its POM declares a legacy client.

```bash
grep -rnE 'org\.apache\.(commons\.httpclient|http)\b' src/main --include='*.java' --include='*.kt' --include='*.groovy' --include='*.scala'
mvn dependency:tree -Dincludes=org.apache.httpcomponents,commons-httpclient
```

The legacy artifacts are `commons-httpclient:commons-httpclient` and
`org.apache.httpcomponents:{httpclient,httpcore,httpmime,httpasyncclient,httpclient-cache,httpcore-nio}`.
Check the effective POM and your build profiles, because a dependency can be
inherited or transitive.

- In a plugin, a legacy client that is `provided`, or absent while sources use
  it, relies on the withdrawn platform export. A legacy client already bundled at
  compile scope is not broken by the export removal, but is still worth migrating.
- `org.apache.hc.*` is already HttpClient 5.
- Matches in tests, generated sources, comments and strings do not make the
  module affected. If an in-scope module has no legacy usage at all, skip it. A
  module you could not fully inspect (profile or inherited configuration) is not
  a clean result.

## Before you start

- Decide whether the module is a **plugin** or a **library**. Plugins bundle
  HttpClient 5 and need the OSGi packaging steps. A library follows its Maven
  consumer contract (normally compile) and needs no AMPS or bnd changes.
- Pick the version. Use the one managed by your target-platform BOM when it
  manages `httpclient5`; otherwise declare one explicit version and keep
  `httpcore5` on the line that version depends on.
- Do not widen scope: change only the modules you own and any modules that are
  genuinely embedded in the plugin.

## Migrate

Work module by module: POM, then packaging (plugins only), then sources.

### 1. Dependencies

Remove the legacy artifacts listed above. Keep an exclusion that only stops
another dependency leaking a legacy client into the bundle. Add:

- `org.apache.httpcomponents.client5:httpclient5` (brings `httpcore5` and
  `httpcore5-h2`);
- `org.apache.httpcomponents.core5:httpcore5` explicitly when your code imports
  `org.apache.hc.core5`;
- `httpclient5-cache` only to replace `httpclient-cache`;
  `httpcore5` or `httpcore5-reactive` only to replace `httpcore-nio` usage.

Remove `httpmime`: `MultipartEntityBuilder` is part of `httpclient5`.

In a plugin, declare `<scope>compile</scope>` explicitly. An omitted scope can
inherit a scope that a parent POM or imported BOM manages, which can silently
make it `provided`. Do not use `provided`: `org.apache.hc.*` is not part of
either product's supported public API. Exclude the product-supplied
`org.slf4j:slf4j-api`:

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

Apply the same exclusion to `httpclient5-cache`. A library keeps its own logging
dependency contract and its existing scope or optionality unless you have
consumer evidence to change them.

### 2. OSGi packaging (plugins only)

The bundled client references optional integrations that no product exports to
apps. bnd turns each reference into an `Import-Package` entry, and under a
trailing bare `*` these become mandatory. The plugin then fails to enable
(`missing requirement osgi.wiring.package; (osgi.wiring.package=org.brotli.dec)`)
and its REST endpoints return 404, even though the build looks clean.

Edit the `Import-Package` instruction of the module that packages the plugin
(AMPS or `maven-bundle-plugin` configuration):

| Package | Referenced by |
|---|---|
| `org.brotli.dec` | `httpclient5` 5.2-5.5 Brotli decoding |
| `org.conscrypt` | `httpclient5`, `httpcore5-h2` (all 5.x) |
| `com.aayushatharva.brotli4j.*` | `httpclient5` 5.6+ |
| `com.github.luben.zstd` | `httpclient5` 5.6+ |
| `org.apache.commons.compress.compressors` | `httpclient5` 5.6+ |
| `jdk.net` | `httpcore5` 5.3+, so `httpclient5` 5.4.x; `httpclient5` 5.5.2+ |
| `org.ietf.jgss` | `httpclient5` SPNEGO/Kerberos auth (all 5.x); a JDK package product frameworks do not export to apps |
| `org.newsclub.net.unix` | `httpclient5` 5.6+ `UnixDomainSocketFactory` |

- Under a trailing bare `*`, or with no `Import-Package` instruction, add all
  eight as `pkg;resolution:=optional` before the `*`, whichever 5.x version you
  bundle. Without an instruction, add
  `<Import-Package>…optional entries…,*</Import-Package>`.
- Under `*;resolution:=optional` everything generated is already optional; add
  nothing.
- Do not make a package optional when your own code requires it (for example,
  Commons Compress from the product).
- Remove `org.apache.http*` or `org.apache.commons.httpclient*` clauses only when
  no shipped code still references them.
- If an explicit embedding selector pins the bundled artifacts, add
  `httpclient5`, `httpcore5` and `httpcore5-h2` to it.
- The AMPS banned-dependencies check rejects compile-scope artifacts the platform
  BOMs list, and they list `httpclient5` and `httpcore5`. For an AMPS plugin,
  add every HC5 artifact you bundle to `<banningExcludes>` in the AMPS plugin
  `<configuration>`, beside `<instructions>`. Reuse an existing block and its
  item element name; otherwise create it as [`bundle-platform-dependencies`](bundle-platform-dependencies.md) does:

  ```xml
  <banningExcludes>
      <banningExclude>org.apache.httpcomponents.client5:httpclient5</banningExclude>
      <banningExclude>org.apache.httpcomponents.core5:httpcore5</banningExclude>
      <banningExclude>org.apache.httpcomponents.core5:httpcore5-h2</banningExclude>
  </banningExcludes>
  ```

The table covers `httpclient5` 5.1-5.6. `jdeps` does not report the `httpcore5`
`jdk.net` reference although bnd still generates the import, so the bundle check
under Verify is authoritative for the versions you pick.

### 3. Java sources

Replace every legacy import and fully qualified reference.

| HttpClient 4 | HttpClient 5 |
|---|---|
| `org.apache.http.impl.client.{HttpClients,CloseableHttpClient,HttpClientBuilder}` | `org.apache.hc.client5.http.impl.classic.*` |
| `org.apache.http.client.methods.{HttpGet,HttpPost,…}` | `org.apache.hc.client5.http.classic.methods.*` |
| `org.apache.http.client.config.RequestConfig` | `org.apache.hc.client5.http.config.RequestConfig` |
| `org.apache.http.impl.conn.PoolingHttpClientConnectionManager` | `org.apache.hc.client5.http.impl.io.*`, built with `PoolingHttpClientConnectionManagerBuilder` |
| `org.apache.http.auth.{AuthScope,UsernamePasswordCredentials,NTCredentials}` | `org.apache.hc.client5.http.auth.*` |
| `org.apache.http.{HttpEntity,HttpHost,HttpStatus,NameValuePair,Header}` | `org.apache.hc.core5.http.*` |
| `org.apache.http.util.EntityUtils` | `org.apache.hc.core5.http.io.entity.EntityUtils` (`toString` also throws `ParseException`) |
| `org.apache.http.client.utils.URIBuilder` | `org.apache.hc.core5.net.URIBuilder` |

Behavior and API changes you will hit:

| HttpClient 4.x | HttpClient 5.x |
|---|---|
| `getURI()` | `getUri()`, throws `URISyntaxException` |
| `getStatusLine().getStatusCode()` | `getCode()` |
| `getStatusLine()` | `new StatusLine(response)` |
| `entity.getContentType().getValue()` | `entity.getContentType()` (a `String`) |
| `client.execute(request)` and closing the response | `client.execute(request, response -> …)`, which consumes and closes it |
| `RequestConfig#setConnectTimeout(int)` | `ConnectionConfig#setConnectTimeout(Timeout)` on the connection manager (the `RequestConfig` form is deprecated) |
| `RequestConfig#setSocketTimeout(int)` | `RequestConfig#setResponseTimeout(Timeout)`, or a `ConnectionConfig`/`SocketConfig` socket timeout |
| `RequestConfig#setConnectionRequestTimeout(int)` | the same name, taking a `Timeout` |
| `setDefaultSocketConfig`, `setMaxConnTotal`, `setMaxConnPerRoute` on the client builder | the same methods on `PoolingHttpClientConnectionManagerBuilder`, then `setConnectionManager(…)` |
| `SSLConnectionSocketFactory` | `ClientTlsStrategyBuilder` / `DefaultClientTlsStrategy` on the connection manager builder |
| `new UsernamePasswordCredentials(user, pw)` | `…(user, pw.toCharArray())` |
| `AuthScope.ANY` | `new AuthScope(null, -1)` |

Keep each timeout's meaning. A 4.x connect timeout is the TCP connect timeout
and must never become `setConnectionRequestTimeout`, which is the pool lease
timeout:

For example, `setConnectTimeout` moves to `ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(…))`
passed to `PoolingHttpClientConnectionManagerBuilder#setDefaultConnectionConfig`, and `setSocketTimeout`
becomes `RequestConfig#setResponseTimeout`.

Prefer `client.execute(request, response -> …)`, which always consumes and closes the response; read the status with `response.getCode()` inside the handler.

**Commons HttpClient 3** has no automated path; use this table:

| Commons HttpClient 3.x | HttpClient 5.x |
|---|---|
| `HttpClient` | `CloseableHttpClient` from `HttpClients.createDefault()` or a builder |
| `HttpClient#getState()` | `BasicCredentialsProvider`, `BasicCookieStore` or an `HttpClientContext` |
| `GetMethod` / `PostMethod` | `HttpGet` / `HttpPost` |
| `PostMethod#addParameter(n, v)` | a list of `BasicNameValuePair` in `new UrlEncodedFormEntity(list)` |
| `executeMethod(method)` | `client.execute(request, responseHandler)` |
| `getResponseBodyAsString()` | `EntityUtils.toString(response.getEntity())` in the handler |
| `releaseConnection()` | remove; the handler closes the response |

Migrate response consumption and client lifecycle, not only the request objects.

**Cases that need judgment; do not change them mechanically:**

- **Checked exceptions.** `CloseableHttpClient#close()` throws `IOException` and
  `getUri()` throws `URISyntaxException`. Catch and wrap them when the existing
  signature must stay unchanged; add `throws` only when every caller and
  implemented interface allows it.
- **Client ownership.** Do not close an injected or shared client per request.
  Clients are thread-safe and expensive to create.
- **Charset fallback.** HC4 `ContentType.getOrDefault(entity)` falls back to
  ISO-8859-1 when the header is absent; HC5 `ContentType.DEFAULT_TEXT` is UTF-8.
  Keep the original fallback explicitly, and keep the caller's fallback when a
  present header has no charset. Honor explicit charsets unchanged.
- **TLS.** HttpClient 5 disables SSL and weak TLS versions by default. Keep any
  TLS policy the app configured explicit.
- **URI normalization.** `URIUtils.normalizeSyntax` is removed, and
  `URIBuilder#optimize()` normalizes more aggressively; check the behavior.
- **Tests, strings and comments.** Update test imports and mocks that break
  because of production API changes, preserving assertions. Do not rewrite
  expectations, identifiers, user-visible strings or comments only because they
  mention a legacy package.

## Verify

1. Search again. This must find no executable legacy reference, no legacy client
   dependency, and no `getContentType().getValue()` on a 5.x entity:

   ```bash
   grep -rnE 'org\.apache\.(commons\.httpclient|http)\b|getContentType\(\)\.getValue\(\)' src/main
   ```

2. Compare each migrated timeout with the original. For response decoding, test
   non-ASCII bytes with no Content-Type, a header without a charset, and an
   explicit charset.
3. Build and test with your profiles, for example
   `mvn -pl <module> -am clean verify`.
4. For a plugin, inspect the built bundle (not a plain library JAR):

   ```bash
   bash scripts/inspect-bundle.sh <plugin-module>/target/<plugin>.jar
   ```

   Exit 0 means each HC5 module the plugin uses (`httpclient5`, `httpcore5`) is
   bundled, no legacy client package is imported or bundled, and no known
   optional integration is a mandatory import. A plugin that only uses
   `httpcore5` needs no `httpclient5`. Exit 2 lists failed checks; fix them and
   rerun. Also read the printed mandatory imports for any other non-product
   package the bundled client introduced.

A clean build is not enough for a plugin: the unresolved-import failure appears
only when the plugin is enabled. Product compatibility needs runtime validation of
the installed plugin. Library tests verify the library, not a consuming plugin.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Plugin fails to enable: `missing requirement osgi.wiring.package; (osgi.wiring.package=org.brotli.dec)` (or another package from the table); REST endpoints return 404 | An optional integration is a mandatory import. Add it as `;resolution:=optional` before the trailing `*`. |
| `inspect-bundle.sh` reports HttpClient 5 used but not bundled, legacy content bundled, or a mandatory optional import | Fix the named cause: restore `compile` scope or the embedding selector, remove leftover legacy dependencies or `Import-Package` clauses, or add `;resolution:=optional`. A banned-dependencies failure on `httpclient5`/`httpcore5` needs `<banningExcludes>`. |
| Compile error: unreported `IOException` or `URISyntaxException` | New checked exceptions from `close()` or `getUri()`. Wrap, or extend `throws` where allowed. |
| Requests wait on the pool, or time out differently | A connect timeout was mapped to `setConnectionRequestTimeout`. Move it to `ConnectionConfig`. |
| Garbled non-ASCII response text | The charset fallback changed from ISO-8859-1 to UTF-8. Restore the original fallback. |

## Use the skill

Ask your agent to "migrate to HttpClient 5" for a plugin or library. The
`httpclient-to-5-migrate` skill is guided: it edits files directly. It does not
execute the bundled OpenRewrite YAML, which serves only as a lookup of HttpClient
4 type and method mappings (HttpClient 3 is migrated from the table above).

1. Scopes the work to the requested modules, the declared children of a
   component, or the modules a plugin actually embeds. It does not invent
   embedding and needs no product or version for library work.
2. Detects usage: legacy imports and fully qualified references in main sources,
   legacy dependencies in the POM and effective model with their scope and origin
   (local, inherited, transitive), and, for plugins, the `Import-Package` and
   embedding configuration. Evidence goes to the caller's evidence directory, else
   `.migration-state/evidence/httpclient5/discovery.md` at the repository root.
3. Classifies the result as applicable, already compatible, not applicable, or
   uncertain (a scope or profile could not be inspected). It stops after
   assessment, or when nothing actionable remains.
4. Applies the steps above in order: dependencies, packaging (plugins only), then
   sources and the tests those changes break.
5. Verifies with a search, a module build with tests, and for plugins
   `scripts/inspect-bundle.sh`, writing its output to
   `.migration-state/evidence/httpclient5/bundle-inspection.txt`. When builds are
   not authorized it reports the compile and bundle checks as deferred.

It leaves runtime validation of the installed plugin, unresolved library
consumer-contract decisions, protected tests, and committing or opening pull
requests to you.
