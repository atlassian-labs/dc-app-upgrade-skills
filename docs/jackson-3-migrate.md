---
skill: jackson-3-migrate
skill-sha256: 61bd2533b754
---

# Migrate from Jackson 2 to Jackson 3

Platform 9 supplies Jackson 3 (`tools.jackson`) in place of Jackson 2
(`com.fasterxml.jackson`). An app that still compiles against Jackson 2 needs
its dependencies, imports and a number of API calls changed. This guide covers
the manual migration. The [last section](#use-the-skill) describes the
`jackson-3-migrate` skill, which automates it.

## Is my code affected?

Your module needs migration if its sources or `pom.xml` reference
`com.fasterxml.jackson`. In a plugin, also check modules that are embedded or
shaded into the plugin artifact (`Embedded-Dependencies` or equivalent): Jackson 2
usage there ships inside the plugin and breaks at runtime the same way.

```bash
grep -rn 'com\.fasterxml\.jackson' --include=pom.xml --include='*.java' --include=atlassian-plugin.xml .
```

You can skip the module when every in-scope module is free of Jackson 2 usage,
or when the only change needed is a version bump of
`platform-internal-api` / `platform-public-api`.

## Before you start

- Jackson 3 requires **Java 17**. A module with `maven.compiler.release` below 17
  still compiles against Jackson 3 without a warning but no longer runs on the
  older Java it promises. Raise the release to 17 or keep that library on
  Jackson 2.
- Check the target product's version of the `platform-*-api` BOM. Do not
  hard-code a milestone version; confirm the Platform 9-compatible one against
  official DC Core guidance.
- Work incrementally: add the Jackson 3 dependencies, migrate the code,
  compile and test at each step, and remove Jackson 2 last.

## Migrate

### 1. Dependencies

Add the Jackson 3 coordinates next to the Jackson 2 ones, so migrated and
not-yet-migrated code both compile. Then replace as follows.

| Jackson 2 (`com.fasterxml.jackson…`) | Jackson 3 |
|---|---|
| `core:jackson-annotations` | keep as is (no Jackson 3 version; use latest 2.x) |
| `core:jackson-core` | `tools.jackson.core:jackson-core` |
| `core:jackson-databind` | `tools.jackson.core:jackson-databind` |
| `module:jackson-module-parameter-names`, `datatype:jackson-datatype-jdk8`, `datatype:jackson-datatype-jsr310` | remove: built into `jackson-databind` |
| `dataformat:jackson-dataformat-xml` / `-yaml` / `-cbor` | `tools.jackson.dataformat:<same artifact>` |
| `datatype:jackson-datatype-joda` | `tools.jackson.datatype:jackson-datatype-joda` |
| `module:jackson-module-jakarta-xmlbind-annotations` | `tools.jackson.module:<same artifact>` |
| `jakarta.rs:jackson-jakarta-rs-*`, `jaxrs:jackson-jaxrs-json-provider` | `tools.jackson.jakarta.rs:jackson-jakarta-rs-*` |
| `com.atlassian.adapter:jackson` | `tools.jackson.core:jackson-databind` |

**Keep each dependency's `<scope>`.** On Platform 9 the runtime supplies Jackson 3
just as the old runtime supplied Jackson 2, so a `provided` dependency stays
`provided`. Raising it to compile scope bundles platform artifacts into your
plugin, and the banned-dependencies enforcer fails the build with
`BannedDependencies failed: make sure platform artifacts are not bundled into plugin`.
The retained `jackson-annotations` keeps its scope too.

**A `provided` Jackson 3 module must also be public on the target.** Check
each one, not just `jackson-core` and `jackson-databind`. For example,
`tools.jackson.module.jakarta.xmlbind` is internal on Jira 12, so keeping
`jackson-module-jakarta-xmlbind-annotations` as `provided` fails to resolve
there. For a module that is not public, bundle it at compile scope or remove
the code that uses it.

If your `dependencyManagement` imports `platform-internal-api` or
`platform-public-api` (`type=pom`, `scope=import`), bump it to a Platform 9
compatible version. Once the bumped BOM no longer manages Jackson 2, any
versionless Jackson 2 dependency makes the POM invalid
(`'dependencies.dependency.version' ... is missing`). Then swap the coordinates
in one step and migrate the sources against Jackson 3 only.

**Multi-module builds:** after the bump, every reactor POM that still declares a
versionless Jackson 2 dependency is invalid and Maven cannot load the reactor.
Swap the coordinates in all reactor POMs first, then migrate module by module,
reinstalling each sibling after its POM swap. A stale, invalid sibling POM in
`~/.m2` can silently drop transitive dependencies and show up as unrelated
"missing class" errors.

### 2. Imports

Do not just replace `com.fasterxml.jackson` with `tools.jackson`. A prefix
rename leaves names that read as correct and fail at `javac`:

| Shape | Jackson 2 | Jackson 3 |
|---|---|---|
| package also moved | `core.JsonFactory` | `tools.jackson.core.json.JsonFactory` |
| package also moved | `databind.JsonMapper` | `tools.jackson.databind.json.JsonMapper` |
| class renamed | `databind.SerializerProvider` | `tools.jackson.databind.SerializationContext` |
| method renamed | `parser.getCurrentName()` | `parser.currentName()` |
| method removed | `mapper.registerModule(m)` | `JsonMapper.builder().addModule(m).build()` |

Core and databind renames you will meet most often:

| Jackson 2 | Jackson 3 |
|---|---|
| `JsonParseException` | `StreamReadException` |
| `JsonGenerationException` | `StreamWriteException` |
| `JsonProcessingException` | `JacksonException` |
| `JsonMappingException` | `DatabindException` |
| `JsonDeserializer` / `JsonSerializer` | `ValueDeserializer` / `ValueSerializer` |
| `JsonParser.Feature` | `StreamReadFeature` (general) or `JsonReadFeature` (JSON-specific) |
| `JsonGenerator.Feature` | `StreamWriteFeature` (general) or `JsonWriteFeature` (JSON-specific) |

The dataformat, datatype, `module.jakarta.xmlbind` and `jakarta.rs` packages are
straight `com.fasterxml.jackson` → `tools.jackson` renames.

**Annotations stay.** `@JsonProperty`, `@JsonIgnore`, `@JsonCreator` and the rest
of `com.fasterxml.jackson.core` / `annotation` keep their imports. The databind
annotations do move: `com.fasterxml.jackson.databind.annotation.*` becomes
`tools.jackson.databind.annotation.*`.

### 3. API changes in your code

**Exceptions.** `JacksonException` now extends `RuntimeException` (it was an
`IOException`). Catch `JacksonException` where you caught `IOException` for
Jackson calls. `serialize()` and `deserialize()` throw `JacksonException`.

**`JsonMappingException` constructors are no longer public.** Use a factory:

```java
// Before
throw new JsonMappingException(e.getMessage(), e);
// After
throw DatabindException.from((JsonParser) null, e.getMessage(), e);
```

Overloads exist for `JsonGenerator`, `SerializationContext`, `JsonParser` and
`DeserializationContext`.

**Mapper construction.** `ObjectMapper` is immutable in Jackson 3:

```java
// Before
ObjectMapper mapper = new ObjectMapper();

// After
JsonFactory jsonFactory = JsonFactory.builderWithJackson2Defaults().build();
ObjectMapper mapper = JsonMapper.builder(jsonFactory)
        .configureForJackson2()
        .build();
```

**Modules.** Remove explicit registrations of the built-in modules
(`ParameterNamesModule`, `Jdk8Module`, `JavaTimeModule`). Register your own
modules at build time, since `registerModule` no longer exists:

```java
// Before
mapper.registerModule(module);
// After
ObjectMapper mapper = JsonMapper.builder().addModule(module).build();
```

Where the mapper is configured in several places, build it once and move every
`registerModule` call into the same builder chain.

**`@JsonSerialize(include = …)`:**

```java
// Before
@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
// After
@JsonInclude(value = JsonInclude.Include.NON_NULL)
```

**Streaming accessors lose the `get` prefix:** `getCurrentName()` →
`currentName()`, `getCurrentToken()` → `currentToken()`, `getCurrentLocation()` →
`currentLocation()`, `getTokenLocation()` → `currentTokenLocation()`.

### 4. Plugin descriptor

In `atlassian-plugin.xml`, every `<rest …>` element must declare
`use-jackson="3"`.

### 5. Remove Jackson 2

When the code compiles and the tests pass, remove the Jackson 2 dependencies
from the POM, except `jackson-annotations`.

## Verify

1. `mvn clean compile`, then `mvn clean test`, in each migrated module.
2. Confirm nothing that does not exist in Jackson 3 remains. This search must
   come back empty:

   ```bash
   grep -rnE 'tools\.jackson\.core\.JsonFactory\b|tools\.jackson\.databind\.(JsonMapper|SerializerProvider|JsonSerializer|JsonDeserializer|JsonMappingException)\b|\.registerModule\(|\bnew ObjectMapper\(|\.(getCurrentName|getCurrentToken|getCurrentLocation|getTokenLocation)\(' src/
   ```

3. Check that no platform artifact is bundled into the plugin and every
   `provided` Jackson 3 module is public on the target product (see
   [`check-public-api-readiness`](check-public-api-readiness.md)).

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `BannedDependencies failed … Found Banned Dependency: tools.jackson…` | A migrated dependency lost its `provided` scope. Restore it. |
| `'dependencies.dependency.version' for …jackson-databind:jar is missing` | The bumped BOM no longer manages Jackson 2. Swap the coordinates in one step. |
| Misleading "missing class" errors unrelated to Jackson in dependent modules | A stale invalid sibling POM is in `~/.m2`. Reinstall the sibling. |
| `ServiceConfigurationError: tools.jackson.datatype.<X>Module not found` on Spring 7 | Spring's `AllEncompassingFormHttpMessageConverter` scans for Jackson modules via `ServiceLoader` and cannot load the module from the plugin's OSGi classloader. In the affected plugin, set `<mvc:message-converters register-defaults="false">` and register only the converters you need. See [`spring7-jakarta11-migrate`](spring7-jakarta11-migrate.md). |
| Plugin fails to resolve a `tools.jackson.module…` package | The module is internal on the target. Bundle it or remove its use. |

## Use the skill

Ask your agent to "migrate to Jackson 3" or "fix `com.fasterxml.jackson` for
Platform 9". The `jackson-3-migrate` skill:

1. Determines scope: the requested modules and any real embedded or shaded
   modules. It exits when nothing needs migrating.
2. Edits file by file in the order above: add dependencies, migrate sources and
   their tests (compiling and testing as it goes), handle other files such as
   `atlassian-plugin.xml`, then remove Jackson 2.
3. Finishes with a table of each changed file and a short description.

It does not choose a product target for library work and applies descriptor
changes only to real plugin packaging. It expects you to review the diff and
the pieces that need judgment: modules that are not public on the target, a
Java release below 17, and Spring 7 message-converter issues.
