---
name: jackson-3-migrate
description: Migrates a Java Maven module from Jackson 2 (com.fasterxml.jackson) to Jackson 3 (tools.jackson) for Platform 9
---

Migrate a Java Maven module from **Jackson 2** (`com.fasterxml.jackson`) to
**Jackson 3** (`tools.jackson`).

## When to use

Use this skill when the user asks to:
- "migrate to Jackson 3" / "Jackson 2 → 3"
- "fix `com.fasterxml.jackson` for Platform 9"

## Workflow

1. **Scope:** All requested Maven plugin/library modules and actual in-scope
   embedded/shaded modules (`Embedded-Dependencies` or equivalent). Assess their
   sources and POMs below. Library API migration needs no product target;
   descriptor/manifest changes apply only to actual plugin packaging.
2. If there is nothing to migrate, **exit** (see [When to skip](#when-to-skip)).
3. Otherwise migrate **file by file**, in this order:
   - **`pom.xml` (add):** add the Jackson 3 dependencies *alongside* the existing
     Jackson 2 ones, so both migrated and not-yet-migrated code keeps compiling.
     Match each new dependency's `<scope>` to the Jackson 2 dependency it replaces
     (a `provided` platform dependency stays `provided`; see
     [Preserve the original dependency scope](#1-maven-dependency-migration)).
     If the imported BOM no longer manages Jackson 2 (Maven rejects the POM with
     `'dependencies.dependency.version' for com.fasterxml.jackson.core:jackson-databind:jar is missing`),
     the side-by-side step is impossible: swap the coordinates in one step and
     migrate the sources against Jackson 3 only. See
     [Multi-module reactors](#multi-module-reactors).
   - **Java sources:** migrate each file, then run `mvn clean compile` and
     `mvn test` in the module. Update the corresponding unit tests too.
   - **Other files:** e.g. `atlassian-plugin.xml`.
   - **`pom.xml` (remove):** remove the now-unneeded Jackson 2 dependencies —
     **but keep** `com.fasterxml.jackson.core:jackson-annotations` (shared between
     Jackson 2 and 3).
   - Re-check that the module compiles and unit tests pass.
4. At the end, print a table with two columns: **file** and a **brief description
   of the changes**.

> **Tip — incremental & safe:** While the BOM still manages Jackson 2, add the new dependencies first, migrate the code
> (running unit tests as you go), then remove the old dependencies last and
> re-run `mvn clean compile`. This lets every file be migrated with no
> compilation errors and keeps the tests runnable at each granular step.

---

# Migration guide

## 1. Maven dependency migration

Replace Jackson 2 dependencies with their Jackson 3 equivalents:

| Jackson 2 (`com.fasterxml.jackson…`) | Jackson 3 |
|---|---|
| `core:jackson-annotations` | **KEEP AS-IS** — no Jackson 3 version; use latest 2.x |
| `core:jackson-core` | `tools.jackson.core:jackson-core` |
| `core:jackson-databind` | `tools.jackson.core:jackson-databind` |
| `module:jackson-module-parameter-names` | **REMOVE** — integrated into `tools.jackson.core:jackson-databind` |
| `datatype:jackson-datatype-jdk8` | **REMOVE** — integrated into `tools.jackson.core:jackson-databind` |
| `datatype:jackson-datatype-jsr310` | **REMOVE** — integrated into `tools.jackson.core:jackson-databind` |
| `dataformat:jackson-dataformat-xml` | `tools.jackson.dataformat:jackson-dataformat-xml` |
| `dataformat:jackson-dataformat-yaml` | `tools.jackson.dataformat:jackson-dataformat-yaml` |
| `dataformat:jackson-dataformat-cbor` | `tools.jackson.dataformat:jackson-dataformat-cbor` |
| `datatype:jackson-datatype-joda` | `tools.jackson.datatype:jackson-datatype-joda` |
| `module:jackson-module-jakarta-xmlbind-annotations` | `tools.jackson.module:jackson-module-jakarta-xmlbind-annotations` |
| `jakarta.rs:jackson-jakarta-rs-base` | `tools.jackson.jakarta.rs:jackson-jakarta-rs-base` |
| `jakarta.rs:jackson-jakarta-rs-json-provider` | `tools.jackson.jakarta.rs:jackson-jakarta-rs-json-provider` |
| `com.atlassian.adapter:jackson` | `tools.jackson.core:jackson-databind` |
| `jaxrs:jackson-jaxrs-json-provider` | `tools.jackson.jakarta.rs:jackson-jakarta-rs-json-provider` |

**Preserve the original dependency scope.** Change only the `groupId`/`artifactId`
(and version) — keep each dependency's existing `<scope>`. This matters most for
`provided` Jackson: on Platform 9 the runtime supplies Jackson 3 (`tools.jackson`)
exactly as the baseline runtime supplied Jackson 2, so a Jackson dependency that
was `provided` **must stay `provided`**. Do **not** raise it to compile/default
scope. Atlassian plugins run a banned-dependencies enforcer that fails the build
when platform artifacts are bundled into the plugin (directly or transitively
through an embedded module), e.g.:

```
BannedDependencies failed: make sure platform artifacts are not bundled into plugin
  Found Banned Dependency: tools.jackson.core:jackson-databind:jar:3.x
  Found Banned Dependency: tools.jackson.core:jackson-core:jar:3.x
  Found Banned Dependency: com.fasterxml.jackson.core:jackson-annotations:jar:2.x
```

If you hit this, the migrated Jackson dependency (or the retained
`jackson-annotations`) lost its `provided` scope and is being bundled — restore
`provided`. Only bundle Jackson when the original dependency was compile/default
scope *and* the target platform does not export it.

**A `provided` Jackson 3 module must also be public on the target.** Keeping
`provided` is only correct when the target product exports the Jackson 3
packages to apps. Check every `provided` Jackson 3 module's packages against the
target's public packages (`check-public-api-readiness`, or the product's OSGi
export list) — not just `jackson-core`/`jackson-databind`. Known example:
`tools.jackson.module.jakarta.xmlbind` is internal on Jira 12, so a plugin that
keeps `jackson-module-jakarta-xmlbind-annotations` `provided` fails to resolve
there. For a module that is not public, bundle it (compile scope) or remove the
code that uses it (for xmlbind, the JAXB annotation introspector).

**Java release floor.** Jackson 3 requires Java 17. A module compiled with
`maven.compiler.release` / `<release>` below 17 (e.g. a client library at 8)
still compiles against Jackson 3's class files without a warning, but no longer
runs on the lower Java version it promises. Report this as a compatibility
break: raise the module's release to 17+, or keep that library off Jackson 3.

**Version management:** manage versions via the `dependencyManagement` section by
importing the BOM that defines the library versions. If
`com.atlassian.platform.dependencies:platform-internal-api` or
`platform-public-api` is imported (`type=pom`, `scope=import`) in
`dependencyManagement`, bump it to a Platform 9-compatible version.

> Do **not** hard-code a milestone/QR version — confirm the current
> Platform 9-compatible `platform-*-api` version against official DC Core guidance.

### Multi-module reactors

Once the bumped BOM stops managing Jackson 2, every reactor POM that still
declares a versionless Jackson 2 dependency is invalid, and Maven cannot load
the reactor at all — so per-module `mvn -pl <module> -am` fails before reaching
the module you migrated. Either:

- swap the Jackson coordinates in **all** reactor POMs first, as one sub-step,
  then migrate and verify sources module by module; or
- validate one module at a time with `mvn -N install` on the parent, then
  `mvn -f <module>/pom.xml …`.

Reinstall each sibling (`mvn -f <sibling>/pom.xml install`) after its POM swap.
A previously installed, now-invalid sibling POM in `~/.m2` can silently drop its
transitive dependencies, which surfaces as misleading compile errors (missing
classes unrelated to Jackson) in the modules that depend on it.

## 2. Package renaming (import statements)

Most imports move from `com.fasterxml.jackson` to `tools.jackson`, but this is **not** a
prefix substitution. Replacing the prefix across a module leaves four shapes of breakage,
each of which produces a name that reads as correct and fails at `javac`:

| Shape | Example | Correct target |
|---|---|---|
| the package moved as well | `core.JsonFactory` | `tools.jackson.core.json.JsonFactory` |
| the package moved as well | `databind.JsonMapper` | `tools.jackson.databind.json.JsonMapper` |
| the class was renamed | `databind.SerializerProvider` | `tools.jackson.databind.SerializationContext` |
| the method was renamed | `parser.getCurrentName()` | `parser.currentName()` |
| the method no longer exists | `mapper.registerModule(m)` | `JsonMapper.builder().addModule(m).build()` |

The two package moves are the ones to watch: `JsonFactory` and `JsonMapper` both gain a
`json` segment, so the prefix rename alone leaves them one package too high.

Work from the tables below rather than from the prefix. The straight renames are listed
separately under [Other packages](#other-packages-straight-comfasterxmljackson--toolsjackson-rename);
anything not in that list needs its own row checked.

### jackson-core

| Jackson 2 | Jackson 3 |
|---|---|
| `core.JacksonException` | `tools.jackson.core.JacksonException` |
| `core.JsonParseException` | `tools.jackson.core.exc.StreamReadException` |
| `core.JsonGenerationException` | `tools.jackson.core.exc.StreamWriteException` |
| `core.JsonEncoding` | `tools.jackson.core.JsonEncoding` |
| `core.JsonProcessingException` | `tools.jackson.core.JacksonException` |
| `core.StreamReadConstraints` | `tools.jackson.core.StreamReadConstraints` |
| `core.JsonFactory` | `tools.jackson.core.json.JsonFactory` |
| `core.JsonParser` | `tools.jackson.core.JsonParser` |
| `core.JsonParser.Feature` | `tools.jackson.core.StreamReadFeature` (general) or `tools.jackson.core.json.JsonReadFeature` (JSON-specific) |
| `core.JsonGenerator` | `tools.jackson.core.JsonGenerator` |
| `core.JsonGenerator.Feature` | `tools.jackson.core.StreamWriteFeature` (general) or `tools.jackson.core.json.JsonWriteFeature` (JSON-specific) |
| `core.type.TypeReference` | `tools.jackson.core.type.TypeReference` |
| `core.JsonPointer` | `tools.jackson.core.JsonPointer` |

### jackson-databind

| Jackson 2 | Jackson 3 |
|---|---|
| `databind.ObjectMapper` | `tools.jackson.databind.ObjectMapper` |
| `databind.JsonDeserializer` | `tools.jackson.databind.ValueDeserializer` |
| `databind.JsonSerializer` | `tools.jackson.databind.ValueSerializer` |
| `databind.SerializerProvider` | `tools.jackson.databind.SerializationContext` |
| `core.JsonFactory` | `tools.jackson.core.json.JsonFactory` |
| `databind.DeserializationContext` | `tools.jackson.databind.DeserializationContext` |
| `databind.JsonMappingException` | `tools.jackson.databind.DatabindException` |
| `databind.AnnotationIntrospector` | `tools.jackson.databind.AnnotationIntrospector` |
| `databind.introspect.JacksonAnnotationIntrospector` | `tools.jackson.databind.introspect.JacksonAnnotationIntrospector` |
| `databind.node.ObjectNode` | `tools.jackson.databind.node.ObjectNode` |
| `databind.JsonNode` | `tools.jackson.databind.JsonNode` |
| `databind.type.TypeFactory` | `tools.jackson.databind.type.TypeFactory` |
| `databind.MapperFeature` | `tools.jackson.databind.MapperFeature` |
| `databind.SerializationFeature` | `tools.jackson.databind.SerializationFeature` |
| `databind.DeserializationFeature` | `tools.jackson.databind.DeserializationFeature` |
| `databind.annotation.JsonSerialize` | `tools.jackson.databind.annotation.JsonSerialize` |
| `databind.annotation.JsonDeserialize` | `tools.jackson.databind.annotation.JsonDeserialize` |

### Other packages (straight `com.fasterxml.jackson` → `tools.jackson` rename)

| Jackson 2 | Jackson 3 |
|---|---|
| `dataformat.xml.*` | `tools.jackson.dataformat.xml.*` |
| `dataformat.yaml.*` | `tools.jackson.dataformat.yaml.*` |
| `dataformat.cbor.*` | `tools.jackson.dataformat.cbor.*` |
| `datatype.joda.*` | `tools.jackson.datatype.joda.*` |
| `module.jakarta.xmlbind.*` | `tools.jackson.module.jakarta.xmlbind.*` |
| `jakarta.rs.*` | `tools.jackson.jakarta.rs.*` |

## 3. Class & method signature changes

### Exception hierarchy

- `JacksonException` now extends **`RuntimeException`** (was `IOException` in
  Jackson 2). Update handling to catch `JacksonException` instead of
  `IOException` where appropriate.
- `JsonParseException` → `StreamReadException`
- `JsonGenerationException` → `StreamWriteException`
- `JsonProcessingException` → `JacksonException`

### Serializers / deserializers

- Extend **`ValueDeserializer`** instead of `JsonDeserializer`.
- Extend **`ValueSerializer`** instead of `JsonSerializer`.
- `deserialize()` / `serialize()` now throw `JacksonException` instead of
  `IOException`.
- `SerializerProvider` renamed to `SerializationContext`.

### Feature enum splitting

- `JsonParser.Feature` splits into:
  - `StreamReadFeature` (general, cross-format)
  - `JsonReadFeature` (JSON-specific)
- `JsonGenerator.Feature` splits into:
  - `StreamWriteFeature` (general, cross-format)
  - `JsonWriteFeature` (JSON-specific)

### `DatabindException` constructors are no longer public

Replace `new JsonMappingException(e.getMessage(), e)` with one of the static
factory methods:

```java
DatabindException.from((JsonGenerator) null,        e.getMessage(), e);
DatabindException.from((SerializationContext) null, e.getMessage(), e);
DatabindException.from((JsonParser) null,           e.getMessage(), e);
DatabindException.from((DeserializationContext) null, e.getMessage(), e);
```

### `JsonFactory` moved

`com.fasterxml.jackson.core.JsonFactory` → `tools.jackson.core.json.JsonFactory`.

### `ObjectMapper` construction

Replace `new ObjectMapper()` with the builder form:

```java
// JsonFactory = tools.jackson.core.json.JsonFactory
// JsonMapper  = tools.jackson.databind.json.JsonMapper
JsonFactory jsonFactory = JsonFactory.builderWithJackson2Defaults().build();
ObjectMapper objectMapper = JsonMapper.builder(jsonFactory)
        .configureForJackson2()
        .build();
```

### `@JsonSerialize(include = …)` annotation change

```java
// Before
@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)

// After
@JsonInclude(value = JsonInclude.Include.NON_NULL)
```

Referenced classes: `com.fasterxml.jackson.annotation.JsonInclude`,
`com.fasterxml.jackson.databind.annotation.JsonSerialize`.

## 4. Annotation package retention

**Keep these annotation imports unchanged** — they have no Jackson 3 equivalent
and continue to use the latest 2.x:

- `@JsonProperty`, `@JsonIgnore`, `@JsonCreator`, etc. remain in
  `com.fasterxml.jackson.core`.
- Do **NOT** change `com.fasterxml.jackson.core.*` annotations.

**However, databind annotations DO change:**

- `com.fasterxml.jackson.databind.annotation.*` → `tools.jackson.databind.annotation.*`

## Method renames on the streaming API

The `get` prefix is dropped from the "current" accessors on `JsonParser`. The
Jackson 2 names do not exist in Jackson 3.

| Jackson 2 | Jackson 3 |
|---|---|
| `parser.getCurrentName()` | `parser.currentName()` |
| `parser.getCurrentToken()` | `parser.currentToken()` |
| `parser.getCurrentLocation()` | `parser.currentLocation()` |
| `parser.getTokenLocation()` | `parser.currentTokenLocation()` |

## 5. Module integration (remove explicit registrations)

These modules are now built into `jackson-databind` in Jackson 3. If you register
them explicitly, remove that code:

```java
// Remove — now automatic:
objectMapper.registerModule(new ParameterNamesModule());
objectMapper.registerModule(new Jdk8Module());
objectMapper.registerModule(new JavaTimeModule());
```

(Corresponds to `jackson-module-parameter-names`, `jackson-datatype-jdk8`,
`jackson-datatype-jsr310`.)

A **custom** module is different: keep it, but register it at build time.
`ObjectMapper` is immutable in Jackson 3 and has no `registerModule` method at
all, so a call that compiles against Jackson 2 does not compile against Jackson 3.

```java
// Jackson 2
final SimpleModule module = new SimpleModule();
module.addSerializer(Money.class, new MoneySerializer());
final ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(module);

// Jackson 3
final SimpleModule module = new SimpleModule();
module.addSerializer(Money.class, new MoneySerializer());
final ObjectMapper mapper = JsonMapper.builder()
        .addModule(module)
        .build();
```

`JsonMapper` is `tools.jackson.databind.json.JsonMapper`, and `addModule` takes a
`tools.jackson.databind.JacksonModule`, which `tools.jackson.databind.module.SimpleModule`
implements. Where the mapper is configured in several places, build it once: every
`registerModule` call has to move into the same builder chain.

## Atlassian specifics

In `atlassian-plugin.xml`, find every `<rest …>` tag and ensure it declares
`use-jackson="3"`.

## When to skip

- Skip only when **every** requested source and actual in-scope embedded module
  is free of Jackson 2 usage; one clean module does not clear the others.
- If the module only needs a version bump of
  `com.atlassian.platform.dependencies:platform-internal-api` /
  `platform-public-api`, do not migrate it.

## 6. Testing & verification

After migration:

1. Update all `pom.xml` / `build.gradle` dependencies.
2. Update all import statements.
3. Update exception handling (`IOException` → `JacksonException` where appropriate).
4. Update custom serializer/deserializer class extensions and method signatures.
5. Update `JsonFactory` references to use `json.JsonFactory`.
6. Replace `JsonMappingException` constructors with `DatabindException.from()`.
7. Update `Feature` enums (`JsonParser.Feature` → `StreamReadFeature` / `JsonReadFeature`).
8. Run all tests to confirm compatibility.
9. Check for API changes in any custom Jackson extensions.

Steps 2, 4, 5, 6 and 9 are the ones a prefix rename silently skips, so assert them rather
than reading for them. Every pattern below names something that does not exist in Jackson 3
— either a package the class was moved out of, a Jackson 2 class name under a `tools.jackson`
package, or a method that was dropped — so the search must come back empty:

```bash
grep -rnE \
  'tools\.jackson\.core\.JsonFactory\b|tools\.jackson\.databind\.(JsonMapper|SerializerProvider|JsonSerializer|JsonDeserializer|JsonMappingException)\b|\.registerModule\(|\bnew ObjectMapper\(|\.(getCurrentName|getCurrentToken|getCurrentLocation|getTokenLocation)\(' \
  src/
```

Then compile, which is what turns a missed rename into an error instead of a runtime
surprise. Do this per file as the [workflow](#workflow) describes, not once at the end:

```bash
mvn -q clean compile
```

## 7. Migration checklist

- [ ] Update Maven/Gradle dependencies (groupId changes)
- [ ] Preserve each dependency's original `<scope>` (keep `provided` platform Jackson `provided`; do not bundle platform artifacts)
- [ ] Check every `provided` Jackson 3 module is public on the target product; bundle or drop the ones that are not
- [ ] Report any module with `release` below 17 as a compatibility break
- [ ] Keep `jackson-annotations` with the old groupId (and its original scope)
- [ ] Remove integrated modules (parameter-names, jdk8, jsr310)
- [ ] Update all import statements, per the tables in [Package renaming](#2-package-renaming-import-statements) — a blanket `com.fasterxml.` → `tools.jackson` rename is not sufficient on its own
- [ ] Keep annotation imports unchanged (`com.fasterxml.jackson.core.*`)
- [ ] Update exception handling (`IOException` → `JacksonException`)
- [ ] Rename `JsonDeserializer` → `ValueDeserializer`
- [ ] Rename `JsonSerializer` → `ValueSerializer`
- [ ] Rename `SerializerProvider` → `SerializationContext`
- [ ] Update `JsonFactory` → `json.JsonFactory`
- [ ] Replace `JsonMappingException` constructors with `DatabindException.from()`
- [ ] Update `JsonParser.Feature` → `StreamReadFeature` / `JsonReadFeature`
- [ ] Update `JsonGenerator.Feature` → `StreamWriteFeature` / `JsonWriteFeature`
- [ ] Remove explicit module registrations (`ParameterNamesModule`, `Jdk8Module`, `JavaTimeModule`)
- [ ] Move custom module registrations into `JsonMapper.builder().addModule(...).build()`
- [ ] Drop the `get` prefix on `JsonParser` current-state accessors (`getCurrentName` → `currentName`)
- [ ] Run full test suite (`mvn clean test`)
- [ ] Verify all Jackson functionality works correctly

## Troubleshooting

### `ServiceConfigurationError` loading a Jackson 3 datatype module (Spring 7 + OSGi)

Symptom (typical shape):

```
ServiceConfigurationError: tools.jackson.datatype.<X>Module not found
```

**Cause:** on Spring 7 / Tomcat 11, Spring's
`AllEncompassingFormHttpMessageConverter` runs
`ServiceLoader.load(JacksonModule.class)` at startup. That SPI scan discovers a
Jackson 3 datatype module (e.g. a Guava module) but cannot load it from the
plugin's OSGi classloader because the corresponding Jackson 3 datatype jar isn't
on that bundle's classpath.

**Fix (in the affected admin/web-context plugin):** prevent Spring from
instantiating the all-encompassing converter by disabling default message
converters and registering only what you need:

```xml
<mvc:message-converters register-defaults="false">
    <!-- register only the converters you actually need -->
</mvc:message-converters>
```

(This is a Spring 7 + Jackson 3 interaction — see also `spring7-jakarta11-migrate`.)

## Notes

- Jackson 3 uses the new package root `tools.jackson` instead of
  `com.fasterxml.jackson`.
- Annotations remain in `com.fasterxml.jackson.core` (no 3.x version exists).
- Many `IOException` throws are replaced with `JacksonException`
  (a `RuntimeException`).
- Several modules are now integrated into core `jackson-databind`.
- The migration maintains backward compatibility for most use cases.
- Pay special attention to custom serializers, deserializers, and exception
  handling.
