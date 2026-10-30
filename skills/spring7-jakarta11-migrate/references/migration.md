# Spring 7 / Jakarta EE 11 migration

Use this reference after Step 1 of the entrypoint selects the module. The
entrypoint owns applicability, the detection reference (Jakarta EE `javax.*`
packages and the removed Spring 7 API scan), and the Step 2 summary; this file
covers how to apply and verify the migration. Apply the migration sections in
the order below, then run `Verify`.

## Bump the platform / product BOM

When the caller's scope includes platform/product dependency changes, bump the
product BOM (and any `platform-*-api` dependency) to a **Spring 7-compatible**
Platform 9 QR / milestone. Do not bump a BOM solely because Spring is present.

> Milestone/QR version numbers change frequently — do **not** hard-code one.
> Confirm the current Spring 7-compatible product/platform version against the
> official DC Core Platform 9 guidance before pinning.

## Rename `javax.*` EE imports to `jakarta.*`

Update application/main imports and authoritative descriptor inputs (not stale
generated files under `target/`) for the EE APIs listed in the entrypoint's
`Jakarta EE javax.* packages` table, renaming each to its `jakarta.*`
equivalent. Do not rename the packages that table excludes; nullability
annotations move to JSpecify, not Jakarta (see `Nullability annotations`).

## Spring XML: versionless XSD + drop autodetect

Spring 7 rejects versioned XSDs and removed `default-autowire="autodetect"`.

```xml
<!-- BEFORE (breaks in Spring 7) -->
<beans xsi:schemaLocation="... http://www.springframework.org/schema/beans/spring-beans-2.5.xsd"
       default-autowire="autodetect">

<!-- AFTER (required for Spring 7) -->
<beans xsi:schemaLocation="... http://www.springframework.org/schema/beans/spring-beans.xsd">
```

- Replace every versioned `spring-*-X.Y.xsd` with the versionless
  `spring-*.xsd` form.
- Remove `default-autowire="autodetect"` (removed in Spring 6+).

## Replace `ListenableFuture` with `CompletableFuture`

Migrate each confirmed hit from the entrypoint's `Removed Spring 7 APIs` scan.

| Before (removed in Spring 7) | After |
|---|---|
| `ListenableFuture<T>` (including return types) | `java.util.concurrent.CompletableFuture<T>` |
| `new SettableListenableFuture<T>()` + `set(v)` | `new CompletableFuture<T>()` + `complete(v)` |
| `SettableListenableFuture#setException(ex)` | `CompletableFuture#completeExceptionally(ex)` |
| `addCallback(new ListenableFutureCallback<T>() { ... })` or `addCallback(success, failure)` | `whenComplete((result, ex) -> { ... })` |
| `ListenableFuture#completable()` | use the `CompletableFuture` directly |

`AsyncResult` still exists in Spring 7 (deprecated), but implements only
`Future`. Returning `new AsyncResult<>(v)` from an `@Async` method declared as
`Future<T>` keeps working and is not a finding. Migrate it only where it is used
as a `ListenableFuture`, replacing it with `CompletableFuture.completedFuture(v)`
or `CompletableFuture.failedFuture(ex)`.

```java
// BEFORE (gone in Spring 7)
SettableListenableFuture<String> future = new SettableListenableFuture<>();
future.addCallback(new ListenableFutureCallback<String>() {
    @Override public void onSuccess(String result) { handle(result); }
    @Override public void onFailure(Throwable ex) { recover(ex); }
});
future.set(value);

// AFTER (works on Spring 6 and Spring 7)
CompletableFuture<String> future = new CompletableFuture<>();
future.whenComplete((result, ex) -> {
    if (ex != null) { recover(ex); } else { handle(result); }
});
future.complete(value);
```

- `addCallback` and non-async `whenComplete` both run the callback on the
  thread that completes the future (or immediately when it is already
  complete). Do not introduce `whenCompleteAsync` or a new executor.
- Spring's callback registry caught and ignored exceptions thrown by
  `onSuccess` / `onFailure`. An exception thrown inside a `whenComplete` action
  likewise does not reach the caller, so the behavior is equivalent. Do not add
  `join()` or `get()` to preserve a throw: that blocks and changes behavior.
- Change public signatures that expose a removed type, as in the table, and
  update every directly affected caller in the same change. If the signature is
  consumed outside the migrated code (e.g. an exported OSGi service or an API
  used by other plugins), report it as `ambiguous` instead.
- Preserve cancellation and exceptional-completion behavior.

Report as `ambiguous`, with file and line, and leave unchanged:
- `ListenableFutureTask` and the `ListenableFuture*Adapter` /
  `ListenableFutureCallbackRegistry` types;
- `AsyncListenableTaskExecutor` and `submitListenable`, whose replacement
  needs an execution-semantics review; and
- custom `ListenableFuture` implementations or code that depends on callback
  ordering or cancellation hooks.

Not a fix: excluding `org.springframework.util.concurrent` from
`Import-Package` (the import resolves anyway and the code stays broken), or
bundling `spring-core` (Spring must stay provided by the platform).

## Remove Spring dependencies made obsolete by the migration

After completing a Spring API replacement, inspect all in-scope main and test
sources and the effective Maven model. If a direct Spring dependency has no
remaining source or build-tool consumer, remove it from its authoritative
declaration, whether it is declared in the module POM, an active profile, or an
in-scope parent POM. Platform-managed Spring XML alone is not a reason to retain
such a compile dependency.

Remove a related dependency-management entry or version property only when it
has no remaining consumers in the in-scope reactor. If the declaration is shared
with an out-of-scope module or its ownership is ambiguous, leave it unchanged
and report it.

## OSGi `Import-Package`: `javax.*` and `jakarta.*` servlet exclusions

Apply this section and AMPS/container guidance only to actual, in-scope
packaging/deployment owners, not plain libraries.

bnd auto-generates `javax.servlet.http` imports from transitive dependency
bytecode. Exclude both `javax.servlet*` and `jakarta.servlet*` when the plugin's
own code and everything it embeds are on the Jakarta servlet API, so neither
import needs to resolve.

**The exclusion is wrong when an embedded dependency has no Jakarta artifact.**
A `javax.servlet` import can resolve on a Platform 9 product: Confluence 11.0.0's
own bundled `webdav-plugin` imports `javax.servlet` and `javax.servlet.http`
alongside their Jakarta equivalents, and enables. An embedded library that exists
only against the javax API — Jackrabbit 2.22's WebDAV server, for example — keeps
working through that import, and excluding it breaks the plugin at runtime
instead of fixing it. Check the target's `package-exports.yaml` `includes` before
excluding it, and leave the import in place where an embedded dependency needs
it. `check-public-api-readiness` does not settle this: `javax.servlet` is
exported but outside the public allowlist, so it scans `internal`, which that
skill treats as a bundle that does not resolve.

Only amend an existing, authoritative `Import-Package` block. **Never create a
new `Import-Package` block solely to add exclusions.** If no such block exists,
leave the manifest inputs unchanged and report the candidate for review.

Preserve every existing clause and directive, including the `*` default
fallback and `resolution:=...`/`uses:=...` semantics. Add only the required
exclusions, in the block's established style; do not replace or normalize the
whole block.

```xml
<!-- existing authoritative Import-Package block -->
<Import-Package>
    com.example.api,
    !jakarta.servlet*,
    !javax.servlet*,
    *
</Import-Package>
```

For plugins pulling in validation / JAXB, you may also need:

```xml
<!-- amend the existing authoritative block only -->
<Import-Package>
    <!-- preserve existing clauses and directives -->
    !javax.validation,
    !javax.xml.bind.*,
    jakarta.xml.bind.*;resolution:="optional",
    *
</Import-Package>
```

Add analogous `!javax.ws.rs*`, `!javax.inject*` exclusions where the plugin used
those APIs.

Also check explicit version ranges on `jakarta.*` clauses in that block. Jakarta
EE 11 raises some API major versions (for example, Jakarta RESTful Web Services
4.0), so a range written for Jakarta EE 10, such as
`jakarta.ws.rs.*;version="[3.0,4.0)"`, no longer resolves. Compare each bounded
range with the package version exported by the exact target product; the BOM is
compile evidence, not proof of runtime availability. When the range excludes
that export, use a verified compatible range or remove the `version` attribute;
leave ranges that already admit it unchanged. An ordinary package or unit-test
build may not reveal this: inspect the built manifest and verify plugin
resolution on the target product.

## Nullability annotations: use JSpecify (not Jakarta)

Do **not** migrate `javax.annotation.Nullable/Nonnull` to `jakarta.annotation.*`
— `jakarta.annotation.Nullable/Nonnull` require **Kotlin 2.4+** (KT-54205).
Prefer **JSpecify**, which is Kotlin-compatible since 2.1.

Add the dependency:

```xml
<dependency>
    <groupId>org.jspecify</groupId>
    <artifactId>jspecify</artifactId>
    <version>1.0.0</version>
    <!-- the platform provides it to plugins; do not bundle it -->
    <scope>provided</scope>
</dependency>
```

Mapping:

| JSR-305 (`javax.annotation`) | JSpecify (`org.jspecify.annotations`) |
|---|---|
| `@Nullable` | `@Nullable` |
| `@Nonnull` | `@NonNull` |
| `@ParametersAreNonnullByDefault` | `@NullMarked` |
| `@Immutable`, `@GuardedBy` | *(no equivalent — keep `javax.annotation.concurrent.*`)* |

> **Do NOT bulk `sed`/find-replace to JSpecify.** JSpecify annotations are
> `@Target(TYPE_USE)` only and are stricter than javax/jakarta: they cannot sit
> before `void`, before `@Override`, before qualified type names, or before other
> annotations. Migrate deliberately, per usage. (A real-world bulk-sed attempt on
> a large codebase had to be reverted for exactly these reasons.)

**OpenRewrite caveats (as of writing):**
- `org.openrewrite.java.jspecify.MigrateFromJavaxAnnotationApi` exists but covers
  only the `javax.annotation.*` cases — it does **not** handle everything.
- There is **no** `MigrateFromJakartaAnnotationApi` recipe — if your code already
  moved to `jakarta.annotation.Nullable/Nonnull`, no automated recipe will move it
  to JSpecify; that is a manual effort.

**The `javax.annotation` move is required for plugins unless every target
product publishes the package.** JSR-305 `@Nonnull`, `@Nullable`,
`@CheckForNull`, and `@ParametersAreNonnullByDefault` are
`@Retention(RUNTIME)`, so bnd emits a mandatory `Import-Package:
javax.annotation` for every bundle whose classes carry them, including classes
from embedded libraries. A product that keeps `javax.annotation` internal does
not resolve that bundle; Confluence 11.0.0 does, for example. Run
`check-public-api-readiness` on the built bundle against each target product:
any verdict for `javax.annotation` other than `public` makes the move part of
this migration. Only when every target reports `public`, or for a plain library
that no plugin embeds, may the move be deferred as a separate follow-up.

The other annotation packages do not block resolution:
- `javax.annotation.concurrent` annotations are `@Retention(CLASS)`, so bnd
  generates no import for them.
- `jakarta.annotation.Nonnull/Nullable` are also runtime-retained and import
  `jakarta.annotation`, which Platform 9 products publish to apps. Existing
  usages may stay as they are, and their manual move to JSpecify may be
  deferred; confirm `jakarta.annotation` is `public` in the same scan.
- JSpecify annotations are runtime-retained too; Platform 9 products export
  `org.jspecify.annotations`, so the import resolves.

## AMPS tests: leave the container to AMPS and the product

Only change AMPS configuration when the caller explicitly includes that
configuration in scope and the module runs against a Platform 9 product.

**AMPS and the product pick the container between them. Do not name one for a
Platform 9 target, and do not copy one out of a product pom.**
`AbstractProductHandlerMojo` first takes a provisional id from
`ProductContainerVersionMapper`, a table compiled into the AMPS plugin. Each
product start then calls
`AbstractWebappProductHandler.addOverridesFromProductPom`, which reads
`amps.product.specific.cargo.container` and `amps.product.specific.container`
from the effective pom of the resolved product artifact and overwrites both the
container id and the Tomcat artifact.

```xml
<!-- in maven-jira-plugin / maven-confluence-plugin configuration -->
<configuration>
    <!-- no containerId: AMPS and the product resolve it -->
    <!-- ... -->
</configuration>
```

An absent `containerId` is therefore not a finding — it is the only state in
which the product's own container applies, because the table and the override
are both gated on the module having named no container. Remove a hardcoded one
that names a container for a Platform 9 target and record the removed value. A
pin opts the app out of the product's Tomcat artifact as well as its container
id.

**The resolved id is not predictable from the target version.** Both Platform 9
products publish the override, so the table decides neither: Confluence 11.0.0
boots on `tomcat10x` with Tomcat 11.0.25 where the table alone gives
`tomcat11x`, and an early Jira 12 build names `tomcat11x` with its own Tomcat
11.0.24. Read the id from the product start log
— `Unpacking container '<id>' from container artifact: <artifact>` — rather than
deriving it, and do not report a container id as wrong on the strength of the
table.

Neither Platform 9 product requires a recent AMPS — each declares an AMPS in the
9.12 series. Do not raise AMPS to reach a container id. `java-25-readiness` owns
the floor.

The existence of AMPS tests is an inspection signal, not permission to modify
test infrastructure. If this configuration is out of scope, leave it unchanged
and classify the candidate as `protected`.

## Verify

Run in this order and fix issues as they surface:

```bash
mvn clean compile          # javax→jakarta imports resolve, Spring XML parses
mvn test                   # unit tests
mvn verify                 # AMPS integration tests on Tomcat 11 (if present)
```

Useful inspection commands:

```bash
# Any leftover javax.* imports in the built plugin manifest?
# (should be empty except deliberately-excluded / JDK javax.* packages)
unzip -p <plugin.jar> META-INF/MANIFEST.MF | tr -d '\r\n' | sed 's/ //g' | tr ',' '\n' | grep "javax\."

# Is a containerId hardcoded for a Platform 9 target? (AMPS should resolve it)
grep -rn "containerId" <repo> --include="pom.xml" | grep -v "/target/"
```

Verification invariants:
- applicability was established from application/main sources and
  authoritative inputs, never from a stale `target/` manifest;
- generated files under `target/` were not edited;
- no listed Jakarta EE `javax.*` imports remain in application code after the
  selected migration (deliberate non-EE JDK `javax.*` packages remain valid);
- rerunning the entrypoint's `Removed Spring 7 APIs` scan finds no confirmed
  removed Spring API, except candidates reported as `ambiguous`;
- an existing `Import-Package` block retains all clauses, wildcard/default,
  and resolution/uses directives, and no block was created solely for an
  exclusion; and
- no AMPS or test infrastructure changed unless the caller explicitly put it
  in scope.

Common failure causes:
- **Compile** — a missed `javax.*` EE import, or one wrongly renamed (e.g. a JDK
  `javax.*` that should have stayed).
- **Runtime / OSGi** — plugin fails to resolve because a `javax.servlet` /
  `jakarta.servlet` import wasn't excluded from `Import-Package`, or because
  JSR-305 annotations left a mandatory `javax.annotation` import the product
  keeps internal.
- **Spring startup** — versioned XSD still present, or `default-autowire`
  remaining.
- **AMPS hang** — a configured `containerId` that overrides what AMPS and the
  product would have resolved.

## Troubleshooting (error → fix)

### Spring 7 rejects the XML config on startup
Versioned `spring-*-X.Y.xsd` still present, or `default-autowire="autodetect"`
remaining. See the Spring XML section.

### `NoClassDefFoundError: org/springframework/util/concurrent/ListenableFuture…` at runtime
The plugin loads, but code still uses the `ListenableFuture` family. Migrate it
to `CompletableFuture`; an `Import-Package` exclusion is not a fix. See the
`ListenableFuture` section.

### Plugin fails to resolve / `ClassNotFoundException` for a servlet class
A `javax.servlet` or `jakarta.servlet` import the target does not publish was
not excluded from the OSGi `Import-Package`; bnd re-added it from transitive
bytecode. The mirror failure is an exclusion the plugin needed: an embedded
javax-only library throws `ClassNotFoundException` for a servlet class the
product would otherwise have supplied. See the OSGi `Import-Package` section.

### AMPS integration tests hang for 30–107 minutes then time out
A configured `containerId` suppressed both the AMPS table and the product's own
override, so AMPS started a container that cannot run the Spring 7 product.
Remove it. See the AMPS tests section.

### `ServiceConfigurationError` / SPI failure loading a Jackson module on Spring 7 + Tomcat 11
Typical message shape:

```
ServiceConfigurationError: tools.jackson.datatype.<X>Module not found
```

**Cause:** Spring 7's `AllEncompassingFormHttpMessageConverter` runs
`ServiceLoader.load(JacksonModule.class)`, which discovers a Jackson 3 datatype
module (e.g. a Guava module) via SPI but cannot load it from the plugin's OSGi
classloader (the Jackson 3 datatype jar isn't on that bundle's classpath).

**Fix:** stop Spring from instantiating the all-encompassing converter by
disabling default message converters in the affected web context, e.g.:

```xml
<mvc:message-converters register-defaults="false">
    <!-- register only the converters you actually need -->
</mvc:message-converters>
```

This commonly surfaces in admin/web-context plugins during the Spring 7 + Jackson
3 combination. (See also `jackson-3-migrate`.)
