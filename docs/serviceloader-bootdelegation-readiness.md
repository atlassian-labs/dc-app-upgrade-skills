---
skill: serviceloader-bootdelegation-readiness
skill-sha256: 82e2a29394f6
---

# Make service discovery work without boot delegation

Boot delegation tells every OSGi bundle to resolve a set of package prefixes
through the parent classloader instead of through OSGi wiring. While
`META-INF.services` was on that list, `java.util.ServiceLoader` inside a plugin
behaved as it does on a flat classpath. Platform 9 removes it from the list
(verify the exact target build), so a plugin bundle sees only the providers it
ships or imports. This is a readiness assessment: it decides, path by path,
whether discovery still works and what to change. This guide covers doing that
by hand. The [last section](#use-the-skill) describes the
`serviceloader-bootdelegation-readiness` skill.

## Is my code affected?

The module is in scope when its main source or packaged resources do any of
these, or depend on a library that performs discovery on an app call path:

- call a no-argument `newInstance()` on `DocumentBuilderFactory`,
  `SAXParserFactory`, `TransformerFactory`, `XMLInputFactory`,
  `XMLOutputFactory`, `XPathFactory`, `DatatypeFactory` or `SchemaFactory`
- call `JAXBContext.newInstance(...)` or one-argument `ServiceLoader.load(...)`
- ship a `META-INF/services/` provider file

```bash
# factory discovery through the thread-context classloader
grep -rnE '(DocumentBuilderFactory|SAXParserFactory|TransformerFactory|XMLInputFactory|XMLOutputFactory|XPathFactory|DatatypeFactory|SchemaFactory)\.newInstance\s*\(\s*\)' \
    --include='*.java' --include='*.kt' . | grep -v '/src/test/'

# JAXB context discovery, and ServiceLoader without an explicit classloader
grep -rnE 'JAXBContext\.newInstance\s*\(|ServiceLoader\.load\s*\(\s*[A-Za-z0-9_.]+\.class\s*\)' \
    --include='*.java' --include='*.kt' . | grep -v '/src/test/'

# provider files the app ships, and what they name
find . -path '*/META-INF/services/*' -type f -not -path '*/target/*' \
    -exec sh -c 'echo "== $1"; cat "$1"' _ {} \;
```

These cover direct calls and shipped providers only. They do not show that
dependencies never perform discovery. When a dependency or a runtime trace
suggests indirect discovery, inspect that dependency's relevant source,
bytecode, metadata or call path; do not scan every transitive dependency by
default.

OSGi remedies apply only to bundle deployments. Provider metadata that is valid
for a standalone flat-classpath library should be preserved. A clean direct-call
scan, compilation or imports are not evidence of runtime compatibility.

## Before you start

- For each path you find, write down: the location or caller, whether discovery
  is direct or done by a library (and which dependency and version), the
  intended provider and classloader, whether the app requires the provider or
  can operate without it, what the operation does when none is found, and the
  runtime check that could exercise it.
- Decide optionality from the affected operation's contract and required
  behaviour. An empty iterator or a successful startup does not establish that a
  provider is optional.
- Source repairs need no product target. Claims about host-provided providers
  and runtime behaviour do.
- Keep source and dependency evidence separate from runtime results.

## Migrate

Choose the change per path. Nothing here is a blanket rewrite: some paths need
no edit at all.

| Path | Without boot delegation | Change |
|---|---|---|
| `META-INF/services` file naming a class in `com.sun.*` or `jdk.*` | `ServiceConfigurationError` when anything triggers discovery, which for a Spring app is plugin startup | [Remove JDK-internal providers](#1-remove-jdk-internal-providers) |
| Factory `newInstance()` with no arguments | Discovery falls back to the JDK implementation, silently, where it used to find the product's | [Select the provider](#2-select-the-provider-instead-of-discovering-it) |
| `ServiceLoader.load(Iface.class)` | Depends on the thread-context classloader and visible providers | [Pass a classloader](#3-pass-a-classloader-to-serviceloader) |
| A dependency performs discovery for you | It uses whatever loader is available at the call | [Scope the context loader](#4-when-a-library-discovers-on-your-behalf) |
| Required provider confirmed unavailable, no acceptable fallback | The operation cannot work | [Declare or register the provider](#5-declare-or-register-a-required-provider) |
| Loader confirmed to resolve the intended provider, or an acceptable fallback is confirmed | No edit needed | Keep, and note the evidence |
| Explicit loader passed, but its access to the provider is unconfirmed | Unknown | Inspect provider visibility from that loader before deciding |
| Suspected indirect path with no confirmed operation, provider or loader | Unknown | Inspect the implicated dependency or get the missing evidence |

Passing a classloader is not itself compatibility evidence. Confirm that the
loader can resolve the intended provider and, when the operation uses service
discovery, the service metadata.

### 1. Remove JDK-internal providers

A provider file naming a JDK-internal class cannot work inside a bundle: those
classes live in JDK modules such as `java.xml` and no bundle is wired to them.
Delete the line, or the file if it was the only line.

```
# META-INF/services/javax.xml.parsers.DocumentBuilderFactory
- com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl
```

If the app needs a specific provider, name one it ships itself and also select
it explicitly (step 2).

### 2. Select the provider instead of discovering it

Every JAXP factory has a two-argument `newInstance` taking a class name and a
classloader. It skips discovery.

```java
// Before: discovery through the thread-context classloader
final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

// After: the provider the app ships, from the app's own classloader
final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(
        "org.apache.xerces.jaxp.DocumentBuilderFactoryImpl", MyClass.class.getClassLoader());
```

Naming a provider means shipping it: add the implementation at compile scope so
it is inside the bundle (for Xerces, `xerces:xercesImpl`; see
[`bundle-platform-dependencies`](bundle-platform-dependencies.md), the `xerces-xalan` family).

The no-argument form can fall back to the JDK implementation once no provider
is visible. Treat that as no-change only after identifying the provider
behaviour the app requires and confirming it. Parsers differ in what they
accept: documents with an undeclared namespace prefix, a DTD or an external
entity reference are the usual places where one is stricter. Test with the
app's own documents.

### 3. Pass a classloader to ServiceLoader

```java
// Before: thread-context classloader, which on a plugin thread may be any bundle
ServiceLoader.load(MyProvider.class);

// After: providers the app ships
ServiceLoader.load(MyProvider.class, MyProvider.class.getClassLoader());
```

For a host-provided service, identify the supported loader that can see its
declaration and implementation; do not assume the thread-context loader can.
An empty iterator is supported only when the provider is optional for the
operation or an acceptable fallback is established. A missing required provider
stays an incompatibility.

### 4. When a library discovers on your behalf

A dependency may expose no provider or loader parameter. First establish that
its call path performs discovery and which provider it needs. Then, if the call
boundary allows it, set the thread-context classloader around that call and
restore it:

```java
final ClassLoader previous = Thread.currentThread().getContextClassLoader();
Thread.currentThread().setContextClassLoader(MyClass.class.getClassLoader());
try {
    libraryThatDiscoversInternally.doWork();
} finally {
    Thread.currentThread().setContextClassLoader(previous);
}
```

The `finally` is not optional: plugin code runs on threads the host owns and
reuses. Do not guess a loader or apply this to an unconfirmed path.

### 5. Declare or register a required provider

Use an explicit provider or registration API if the consuming library has one.
Otherwise, when the lookup reads service descriptors, ship a declaration in the
consuming bundle:

```text
# src/main/resources/META-INF/services/com.example.spi.SerializerFactory
com.example.app.JsonSerializerFactory
```

The named class must implement the SPI and be visible to the actual lookup
loader. Bundle the implementation, or use a supported public provider available
on every target the declaration covers. A descriptor does not make an
inaccessible implementation reachable, and you must not import hidden product
implementations to bypass API restrictions. Do not list a product-specific
optional provider unless it is available on every target where the descriptor
ships. A factory that accepts an explicit provider class may need no descriptor.

### Cases that need your judgment

- Whether a provider is required or optional, and whether a fallback is
  acceptable.
- Which classloader can see a host-provided service.
- Whether a library's internal discovery is real before wrapping it.

## Verify

```bash
mvn clean package
```

A build proves nothing about discovery: failures do not appear at build time,
and for a Spring app the failure shows up as the plugin never reaching the
enabled state.

1. Exercise every direct and library-owned path you recorded, in its intended
   classloading context. For plugin runtime claims, install on the exact target
   product and drive the operation that reaches the discovery call.
2. Check the product log. Nothing may report a provider it cannot load:

   ```bash
   grep -iE 'ServiceConfigurationError|FactoryConfigurationError|Provider .* not found' <product-log>
   ```

3. Check the app's own function. A provider change is not always an error:
   parsing that keeps working can still accept different documents. For
   required-provider paths, confirm the intended implementation resolves and the
   operation completes. For optional or fallback paths, exercise the
   empty-provider case where feasible and confirm the operation still meets its
   required behaviour.

If you cannot run the runtime check, record it as outstanding with an owner and
reason, and do not describe the result as proven runtime-compatible.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `ServiceConfigurationError` at plugin startup | A provider file names a JDK-internal class such as `com.sun.org.apache.xerces.internal.jaxp...`. No JDK module exports those packages, so a bundle classloader cannot load them. It resolves on a flat classpath, which is why it only fails once installed. Remove it (step 1). |
| A required provider is missing and the operation fails | A mandatory service such as a JAXB provider can fail when none is found. Ship or register the provider (step 5). |
| Lookup throws although the provider is optional | A provider file naming a class the lookup loader cannot load can also throw. Optionality does not make a broken declaration safe. |
| Importing the provider's package did not help | `Import-Package` makes classes reachable, not the `META-INF/services` resource, and discovery reads the resource. |
| Looking for a manifest header to restore boot delegation | A plugin cannot restore it. It is framework configuration, not a manifest header. Explicit resolution is the only remedy. |
| XML parsing works but accepts or rejects different documents | The factory now resolves to the JDK implementation. Select the provider explicitly (step 2) and test with the app's documents. |

## Use the skill

Ask your agent to "check service discovery readiness" or "assess JAXP, JAXB and
ServiceLoader use for Platform 9". The `serviceloader-bootdelegation-readiness`
skill covers plugins and independent libraries.

1. Decides applicability from the criteria above, then runs the three searches.
   It inspects a dependency only when evidence points at one.
2. Records the evidence per path: location or caller, direct or library-owned,
   intended provider and loader, required or optional, fallback behaviour, and
   the runtime check that could exercise it. Each path gets a status from the
   result vocabulary used by [`migrate-dc-app`](migrate-dc-app.md), such as `uncertain` when the
   operation, provider, context or fallback cannot be established, and
   `not-applicable` only after naming what was inspected.
3. Stops without edits when no actionable discovery path is found. Otherwise it
   applies the changes in the Migrate section that the evidence supports.
4. Verifies with `mvn clean package` and the log search, and reports each path
   with the changes made and the checks actually exercised. Checks the caller
   owns or prohibits are recorded as deferred with owner and reason rather than
   claimed as proven. If it finds no discovery, it names the sources and
   dependencies inspected and marks anything not inspected as `uncertain`.

Packaging of libraries the platform stops exporting is owned by
[`bundle-platform-dependencies`](bundle-platform-dependencies.md); this skill handles discovery, though a lookup
repair must still establish that the implementation is visible. Other Platform 9
changes, such as the Crowd and analytics APIs, have their own skills
([`crowd-internal-api-migrate`](crowd-internal-api-migrate.md), [`analytics-api-migrate`](analytics-api-migrate.md)), which `migrate-dc-app`
routes. The skill leaves to you the runtime checks on the target product and the
required-versus-optional decisions that evidence cannot settle.
