---
skill: spring7-jakarta11-migrate
skill-sha256: 6b18db1da3cc
---

# Migrate to Spring 7 and Jakarta EE 11

Platform 9 (Jira 12 / Confluence 11) moves from Spring 5/6 to Spring 7, from
`javax.*` to `jakarta.*` (Jakarta EE 11), and from Tomcat 9/10 to Tomcat 11. Code
that uses the old packages or removed Spring APIs fails to compile, to resolve in
OSGi, or at runtime. This guide covers the manual migration. The
[last section](#use-the-skill) describes the `spring7-jakarta11-migrate` skill,
which automates it.

## Is my code affected?

Spring, AMPS, an `Import-Package` block or a platform BOM being present does not
by itself mean you need to migrate, and a stale manifest under `target/` is not
evidence. Migrate when application main sources or authoritative inputs contain
one of these:

- a Jakarta EE `javax.*` package (table in step 2);
- a versioned Spring XSD, or `default-autowire="autodetect"`, in Spring XML;
- a Spring API removed in Spring 7 (the `ListenableFuture` family);
- JSR-305 nullness annotations from `javax.annotation` in a plugin, or in a
  library embedded in it, where a target product does not publish that package;
- an existing `Import-Package` block that names an incompatible package, or a
  `jakarta.*` version range that excludes the API version the target manages;
- AMPS configuration that hardcodes a container for a Platform 9 target.

```bash
grep -rnE --exclude-dir=target --exclude-dir=build --exclude-dir=.git \
  --include='*.java' --include='*.kt' --include='*.groovy' --include='*.xml' \
  'javax\.(servlet|inject|ws\.rs|mail|xml\.bind|validation)|javax\.annotation\.(PostConstruct|PreDestroy|Resource|security)|spring-[a-z-]+-[0-9.]+\.xsd|autodetect' src/main
grep -rnE --exclude-dir=target --exclude-dir=build --exclude-dir=.git \
  --include='*.java' --include='*.kt' --include='*.groovy' \
  'org\.springframework\.(util\.concurrent\.|core\.task\.(\*|AsyncListenableTaskExecutor))|submitListenable' src/main
grep -rn 'containerId' --include=pom.xml . | grep -v /target/
```

Every match of the second search needs a look: a real hit is executable code using
a removed type, not a comment, a string, or a surviving type such as `FutureUtils`.

**Plugins first:** Platform 9 removes the legacy transformed (v1, non-OSGi) plugin
layer. A v1 plugin does not load and must be converted to a v2 (OSGi) plugin
before anything below matters. That is a separate, larger task (see
[`transformerless-plugin-conversion`](transformerless-plugin-conversion.md)).

## Before you start

- Decide whether the module is a plugin or a plain library. Library code needs
  only the source and dependency changes. The OSGi `Import-Package`, AMPS and
  container sections apply to real plugin packaging only.
- If you bump a platform or product BOM (and any `platform-*-api`), use a Spring
  7-compatible Platform 9 version. Do not hard-code a milestone from memory;
  confirm it against official DC Core guidance. Do not bump a BOM only because
  Spring is present.
- Know the target products. Several checks compare against what a specific
  product publishes (see [`check-public-api-readiness`](check-public-api-readiness.md)).
- Do not edit generated files under `target/`.

## Migrate

### 1. Bump the BOM

Where in scope, bump the product BOM to a Spring 7-compatible Platform 9
version (see above).

### 2. Rename `javax.*` to `jakarta.*`

| Before | After |
|---|---|
| `javax.servlet` | `jakarta.servlet` |
| `javax.inject` | `jakarta.inject` |
| `javax.ws.rs` | `jakarta.ws.rs` |
| `javax.mail` | `jakarta.mail` |
| `javax.xml.bind` | `jakarta.xml.bind` |
| `javax.validation` | `jakarta.validation` |
| `javax.annotation.PostConstruct`, `PreDestroy`, `Resource`, `javax.annotation.security.*` | `jakarta.annotation.*` |

Update imports and authoritative descriptor inputs. Do not rename every
`javax.*`; leave these alone:

- `javax.annotation.concurrent.*` (`@Immutable`, `@GuardedBy`): there is no
  Jakarta equivalent, and they are class-retained so bnd generates no import.
- `javax.annotation.Nullable` / `Nonnull`: these go to JSpecify, not Jakarta
  (step 6).
- JDK packages that are not Jakarta EE: `javax.crypto`, `javax.net`, `javax.sql`,
  `javax.naming`.

The JSR-250 annotations are runtime-retained, so like JSR-305 they make bnd
import `javax.annotation`; moving them to `jakarta.annotation` (public on
Platform 9) removes that import.

### 3. Spring XML

Spring 7 rejects versioned XSDs and the removed `default-autowire="autodetect"`.

```xml
<!-- Before -->
<beans xsi:schemaLocation="... http://www.springframework.org/schema/beans/spring-beans-2.5.xsd"
       default-autowire="autodetect">

<!-- After -->
<beans xsi:schemaLocation="... http://www.springframework.org/schema/beans/spring-beans.xsd">
```

Replace every versioned `spring-*-X.Y.xsd` with the versionless `spring-*.xsd`
and remove `default-autowire="autodetect"`.

### 4. Replace `ListenableFuture` with `CompletableFuture`

Spring 7 removes the `ListenableFuture` family from
`org.springframework.util.concurrent`, and `AsyncListenableTaskExecutor` /
`submitListenable` from `org.springframework.core.task`. The package is still
exported, so the plugin loads and fails only when the code runs, with
`NoClassDefFoundError`.

| Before (removed) | After |
|---|---|
| `ListenableFuture<T>`, including return types | `java.util.concurrent.CompletableFuture<T>` |
| `new SettableListenableFuture<T>()` and `set(v)` | `new CompletableFuture<T>()` and `complete(v)` |
| `setException(ex)` | `completeExceptionally(ex)` |
| `addCallback(new ListenableFutureCallback<T>() {...})` or `addCallback(success, failure)` | `whenComplete((result, ex) -> {...})` |
| `ListenableFuture#completable()` | use the `CompletableFuture` directly |

```java
// Before
SettableListenableFuture<String> future = new SettableListenableFuture<>();
future.addCallback(new ListenableFutureCallback<String>() {
    @Override public void onSuccess(String result) { handle(result); }
    @Override public void onFailure(Throwable ex) { recover(ex); }
});
future.set(value);

// After
CompletableFuture<String> future = new CompletableFuture<>();
future.whenComplete((result, ex) -> {
    if (ex != null) { recover(ex); } else { handle(result); }
});
future.complete(value);
```

- `addCallback` and `whenComplete` both run the callback on the thread that
  completes the future, or immediately if it is already complete. Do not add
  `whenCompleteAsync` or a new executor.
- Exceptions thrown inside the callback did not reach the caller before and do
  not in `whenComplete`. Do not add `join()` or `get()` to preserve a throw: it
  blocks and changes behaviour.
- `AsyncResult` still exists (deprecated) and implements only `Future`.
  Returning `new AsyncResult<>(v)` from an `@Async` method declared as
  `Future<T>` keeps working. Replace it only where it is used as a
  `ListenableFuture`, with `CompletableFuture.completedFuture(v)` or
  `failedFuture(ex)`.
- Change public signatures that expose a removed type and update every direct
  caller. If the signature is consumed outside the migrated code (an exported
  OSGi service, an API other plugins use), it needs a decision with its
  consumers.
- Preserve cancellation and exceptional-completion behaviour.

**Needs judgment, do not change mechanically:** `ListenableFutureTask` and the
`ListenableFuture*Adapter` / `ListenableFutureCallbackRegistry` types;
`AsyncListenableTaskExecutor` and `submitListenable`, whose replacement needs an
execution-semantics review; custom `ListenableFuture` implementations and code
that depends on callback ordering or cancellation hooks.

**Not a fix:** excluding `org.springframework.util.concurrent` from
`Import-Package` (the import resolves anyway and the code stays broken), or
bundling `spring-core` (Spring must stay provided by the platform).

After the replacement, remove a direct Spring dependency that nothing in main or
test sources or the build now uses, from whichever POM declares it. Platform-managed
Spring XML alone is not a reason to keep a compile dependency. Remove a related
`dependencyManagement` entry or version property only when nothing else in the
reactor uses it; if it is shared with a module you do not own, leave it.

### 5. OSGi `Import-Package` (plugins only)

bnd generates `javax.servlet.http` imports from transitive bytecode. Exclude both
`javax.servlet*` and `jakarta.servlet*` when your own code and everything you
embed are on the Jakarta servlet API.

**The exclusion is wrong when an embedded dependency has no Jakarta artifact.** A
`javax.servlet` import can resolve on a Platform 9 product: Confluence 11.0.0's
bundled `webdav-plugin` imports `javax.servlet` and `javax.servlet.http`
alongside the Jakarta equivalents. An embedded javax-only library (Jackrabbit
2.22's WebDAV server, for example) keeps working through that import, and
excluding it breaks the plugin at runtime. Check the target's
`package-exports.yaml` `includes` first. [`check-public-api-readiness`](check-public-api-readiness.md) does not
settle this: `javax.servlet` is exported but outside the public allowlist, so it
reports `internal`, which that skill treats as a bundle that does not resolve.

Amend only an existing, authoritative `Import-Package` block. Never create one
just to add exclusions; if there is none, report the candidate instead. Keep every
existing clause, the `*` fallback and `resolution:=` / `uses:=` directives, and
add only what is needed in the block's own style.

```xml
<Import-Package>
    com.example.api,
    !jakarta.servlet*,
    !javax.servlet*,
    *
</Import-Package>
```

For plugins that use validation or JAXB you may also need:

```xml
<Import-Package>
    <!-- preserve existing clauses and directives -->
    !javax.validation,
    !javax.xml.bind.*,
    jakarta.xml.bind.*;resolution:="optional",
    *
</Import-Package>
```

Add analogous `!javax.ws.rs*` and `!javax.inject*` where the plugin used those
APIs.

Also check explicit version ranges on `jakarta.*` clauses. Jakarta EE 11 raises
some API majors (Jakarta RESTful Web Services 4.0, for example), so a Jakarta EE
10 range such as `jakarta.ws.rs.*;version="[3.0,4.0)"` no longer resolves.
Compare each bounded range with the package version the exact target product
exports (the BOM is compile evidence, not proof of runtime availability). If the
range excludes it, use a verified compatible range or drop the `version`
attribute; leave ranges that already admit it. A package or unit-test build may
not reveal this, so inspect the built manifest and test plugin resolution on the
target.

### 6. Nullability annotations: JSpecify, not Jakarta

Do not move `javax.annotation.Nullable` / `Nonnull` to `jakarta.annotation.*`:
the Jakarta versions need Kotlin 2.4+. Use JSpecify, which is Kotlin-compatible
since 2.1.

```xml
<dependency>
    <groupId>org.jspecify</groupId>
    <artifactId>jspecify</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope> <!-- the platform provides it; do not bundle -->
</dependency>
```

| JSR-305 (`javax.annotation`) | JSpecify (`org.jspecify.annotations`) |
|---|---|
| `@Nullable` | `@Nullable` |
| `@Nonnull` | `@NonNull` |
| `@ParametersAreNonnullByDefault` | `@NullMarked` |
| `@Immutable`, `@GuardedBy` | no equivalent; keep `javax.annotation.concurrent.*` |

**Do not bulk find-and-replace.** JSpecify annotations are `TYPE_USE` only and
stricter: they cannot sit before `void`, before `@Override`, before qualified
type names, or before other annotations. Migrate usage by usage. A bulk
replacement on a large codebase had to be reverted for exactly these reasons.
OpenRewrite's `org.openrewrite.java.jspecify.MigrateFromJavaxAnnotationApi`
covers only some `javax.annotation.*` cases, and there is no recipe from
`jakarta.annotation.Nullable/Nonnull` to JSpecify; that is manual.

**When the move is required.** JSR-305 `@Nonnull`, `@Nullable`, `@CheckForNull`
and `@ParametersAreNonnullByDefault` are runtime-retained, so bnd emits a
mandatory `Import-Package: javax.annotation` for every bundle whose classes carry
them, including classes from embedded libraries. A product that keeps
`javax.annotation` internal (Confluence 11.0.0 does) will not resolve that
bundle. Run `check-public-api-readiness` on the built bundle against each target:
any verdict for `javax.annotation` other than `public` makes this move part of
the migration. Only when every target reports `public`, or for a plain library no
plugin embeds, may you defer it.

Other annotation packages do not block resolution: `javax.annotation.concurrent`
is class-retained so no import is generated; `jakarta.annotation.Nonnull/Nullable`
import `jakarta.annotation`, which Platform 9 products publish, so existing uses
may stay and their move to JSpecify can wait (confirm `jakarta.annotation` is
`public` in the same scan); JSpecify's `org.jspecify.annotations` is exported by
Platform 9 products.

### 7. AMPS tests: leave the container to AMPS and the product

Only touch AMPS configuration that is in your scope and runs against a Platform 9
product. Do not name a container for a Platform 9 target and do not copy one from
a product pom. AMPS takes a provisional id from a table compiled into the plugin,
then on each product start reads `amps.product.specific.cargo.container` and
`amps.product.specific.container` from the resolved product's effective pom and
overwrites both the container id and the Tomcat artifact.

```xml
<!-- maven-jira-plugin / maven-confluence-plugin configuration -->
<configuration>
    <!-- no containerId: AMPS and the product resolve it -->
</configuration>
```

An absent `containerId` is correct. Remove a hardcoded one for a Platform 9
target and note the old value: a pin also opts the app out of the product's Tomcat
artifact. The resolved id is not predictable from the target version (Confluence
11.0.0 boots on `tomcat10x` with Tomcat 11.0.25 where the table alone gives
`tomcat11x`; an early Jira 12 build names `tomcat11x` with Tomcat 11.0.24), so read it
from the product start log line `Unpacking container '<id>' from container
artifact: <artifact>` rather than deriving it. Neither product requires a recent
AMPS; do not raise AMPS to reach a container id. The AMPS floor is owned by
[`java-25-readiness`](java-25-readiness.md).

## Verify

```bash
mvn clean compile   # jakarta imports resolve, Spring XML parses
mvn test
mvn verify          # AMPS integration tests on Tomcat 11, if present
```

```bash
# Leftover javax.* in the built plugin manifest (expect only deliberate or JDK packages)
unzip -p <plugin.jar> META-INF/MANIFEST.MF | tr -d '\r\n' | sed 's/ //g' | tr ',' '\n' | grep 'javax\.'

# Hardcoded container for a Platform 9 target (expect none)
grep -rn containerId --include=pom.xml . | grep -v /target/
```

A pass means: no listed Jakarta EE `javax.*` import remains in application code;
re-running the removed-API search from the affected check finds nothing except
cases you consciously left; an existing `Import-Package` block still has all its
clauses, wildcard and directives; and plugin resolution has been checked against
the built manifest on the target product. A clean compile is not evidence for
OSGi resolution or for `jakarta.*` version ranges.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Compile error on a `javax.*` import | A Jakarta EE import was missed, or a JDK `javax.*` (`javax.crypto`, `javax.sql`) was wrongly renamed. |
| Spring rejects the XML config on startup | A versioned `spring-*-X.Y.xsd` or `default-autowire="autodetect"` remains. See step 3. |
| `NoClassDefFoundError: org/springframework/util/concurrent/ListenableFuture...` at runtime | The plugin loads but code still uses the `ListenableFuture` family. Migrate to `CompletableFuture`; an `Import-Package` exclusion does not help. See step 4. |
| Plugin fails to resolve, or `ClassNotFoundException` for a servlet class | A `javax.servlet` / `jakarta.servlet` import the target does not publish was not excluded and bnd re-added it from transitive bytecode. The mirror failure: an embedded javax-only library lost an import the product would have supplied. See step 5. |
| Plugin fails to resolve with a mandatory `javax.annotation` import | JSR-305 annotations on a product that keeps the package internal. See step 6. |
| `jakarta.*` import does not resolve although the package exists | An explicit version range written for Jakarta EE 10 excludes the Jakarta EE 11 export. See step 5. |
| AMPS integration tests hang for 30 to 107 minutes, then time out | A configured `containerId` suppressed AMPS's table and the product's override, so AMPS started a container that cannot run the Spring 7 product. Remove it. |
| `ServiceConfigurationError: tools.jackson.datatype.<X>Module not found` on Spring 7 and Tomcat 11 | Spring 7's `AllEncompassingFormHttpMessageConverter` runs `ServiceLoader.load(JacksonModule.class)`, which finds a Jackson 3 datatype module but cannot load it from the plugin's OSGi classloader. In the affected web context set `<mvc:message-converters register-defaults="false">` and register only the converters you need. See [`jackson-3-migrate`](jackson-3-migrate.md). |

## Use the skill

Ask your agent to "migrate to Spring 7", "fix javax to jakarta", or "make my
plugin work on Jira 12 / Confluence 11". The `spring7-jakarta11-migrate` skill:

1. Inspects application main sources and authoritative build inputs for the
   concrete incompatibilities listed under the affected check. Presence of
   Spring, AMPS, a BOM version, or a generated manifest alone does not select
   the module. It classifies each module as selected, already compatible,
   no actionable signal, protected (the change is outside the scope you gave) or
   ambiguous, and leaves everything except selected modules unchanged.
2. For a selected module, applies the steps above in order: BOM bump when in
   scope, `javax` to `jakarta`, Spring XML, `ListenableFuture`, obsolete Spring
   dependencies, `Import-Package`, nullability, then AMPS only when you
   explicitly included it. It changes build, AMPS and test configuration only
   within the scope you gave.
3. Runs compile, test and verify, then summarises: whether a v1 to v2 plugin
   conversion is needed first, what was renamed and what was deliberately left,
   Spring XML and `Import-Package` changes, nullability moves, removed
   `containerId` values, build results, and candidates left as ambiguous or
   protected with file and line.

It leaves to you the v1 to v2 plugin conversion, signature changes that other
plugins consume, the `ListenableFuture` cases listed under judgment, and the
choice of BOM version. Related skills: [`jackson-3-migrate`](jackson-3-migrate.md),
[`httpclient-to-5-migrate`](httpclient-to-5-migrate.md), [`java-25-readiness`](java-25-readiness.md).
