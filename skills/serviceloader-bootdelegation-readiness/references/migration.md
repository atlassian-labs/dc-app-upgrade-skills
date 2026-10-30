# Make service discovery work without boot delegation

Boot delegation told every bundle to resolve a set of package prefixes through the
parent classloader instead of through OSGi wiring. While `META-INF.services` was on
that list, `java.util.ServiceLoader` inside a plugin behaved as it does on a flat
classpath. Platform 9 product upgrades can remove it; verify the exact target build.
Without it, a bundle sees only the providers it ships or imports.

Boot delegation changes how a provider is found and which implementation is selected.
Whether a remedy also needs dependency packaging depends on the provider's availability.

## Choose the change

Pick the row for each path recorded during discovery. First establish whether the
affected operation requires the provider or supports its absence, and what fallback
behavior the app accepts. A discovery API returning an empty result does not establish
that the application can still perform the operation. Keep an unresolved decision
`uncertain`; do not silently make a required provider optional.

| Path | Without boot delegation | Change |
|---|---|---|
| `META-INF/services` file naming a class in `com.sun.*` or `jdk.*` | `ServiceConfigurationError` when anything triggers discovery, which for a Spring app is plugin startup | [Remove JDK-internal providers](#1-remove-jdk-internal-providers) |
| Factory `newInstance()` with no arguments | Discovery falls back to the JDK implementation, silently, wherever it used to find the product's | [Select the provider](#2-select-the-provider-instead-of-discovering-it) |
| `ServiceLoader.load(Iface.class)` | Resolution depends on the thread-context classloader and visible providers | [Pass a classloader](#3-pass-a-classloader-to-serviceloader) |
| A dependency performs discovery on the app's behalf | The dependency uses the loader available at the call; the provider can change or discovery can fail | [Scope the context loader](#4-when-a-library-discovers-on-the-apps-behalf) |
| Explicit loader passed, but its access to the intended provider (and service metadata, if the operation uses discovery) is unconfirmed | Unknown: the loader may not see the provider | `uncertain`: inspect provider visibility from that loader before deciding whether a change is needed |
| Evidence confirms the loader resolves the intended provider, or confirms an acceptable JDK fallback | No edit needed | `already-compatible` for the no-edit decision, citing the evidence; record outstanding runtime checks separately (see [Verify](#verify)) |
| A required provider is confirmed unavailable and the operation has no acceptable fallback | The operation cannot work correctly | `applicable`: [declare or register the provider](#5-declare-or-register-a-required-provider), or use the supported loader repair for this path |
| No provider is found, but evidence confirms the provider is optional or the fallback preserves required behavior | Supported absence or fallback | `already-compatible` for the no-edit decision, citing behavior evidence; record outstanding runtime checks separately |
| Provider metadata valid for a standalone flat-classpath library | Unaffected | Preserve it; assess the bundle deployment separately if it is also in scope |
| Suspected indirect path with no confirmed operation, provider, or loader | Unknown | `uncertain`: inspect the implicated dependency or request the missing evidence |

Passing a classloader is not itself compatibility evidence. Confirm the loader
can resolve the intended provider and, when the operation uses service discovery, the
required service metadata. If runtime
verification belongs to the caller, record it as `deferred` without claiming runtime
compatibility.

## 1. Remove JDK-internal providers

A provider file that names a JDK-internal class cannot work inside a bundle: those
classes live in JDK modules such as `java.xml` and are not wired to any bundle. Delete
the line, or the file if that was its only line.

```
# META-INF/services/javax.xml.parsers.DocumentBuilderFactory
- com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl
```

If the app needs a specific provider, name one it ships itself, and also
[select it explicitly](#2-select-the-provider-instead-of-discovering-it) so the
choice does not depend on discovery order.

## 2. Select the provider instead of discovering it

Every JAXP factory has a two-argument `newInstance` that takes a class name and a
classloader. It skips discovery, so it does not depend on what the framework
delegates.

```java
// discovery through the thread-context classloader, which is the plugin bundle
final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

// the provider the app ships, loaded from the app's own classloader
final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(
        "org.apache.xerces.jaxp.DocumentBuilderFactoryImpl", MyClass.class.getClassLoader());
```

Naming a provider means shipping it: add the implementation at compile scope so it is
inside the bundle. For Xerces that is `xerces:xercesImpl`; see the
[bundle-platform-dependencies](../../bundle-platform-dependencies/SKILL.md) skill (the
`xerces-xalan` family), which Platform 9 also requires for apps that import those
packages directly.

The no-argument form can fall back to the JDK implementation once no provider is
visible. Treat that as a no-change candidate only after identifying the provider
behavior the app requires and confirming it with evidence; compilation alone does not
validate the runtime result. Parsers differ in what they accept: documents with an
undeclared namespace prefix, a DTD, or an external entity reference are the usual
places where one implementation is stricter than another. Test with the app's own
documents.

## 3. Pass a classloader to ServiceLoader

`ServiceLoader.load(Iface.class)` uses the thread-context classloader, which on a
plugin thread may be any bundle. Name the classloader that can see the providers.

```java
// providers the app ships
ServiceLoader.load(MyProvider.class, MyProvider.class.getClassLoader());
```

For a host-provided service, identify the supported loader that can see its declaration
and implementation; do not assume the current thread-context loader has that visibility.
An empty iterator is supported only when the provider is optional for this operation or
an acceptable fallback is established. A missing required provider remains an
incompatibility; select a supported registration, declaration, or loader repair instead
of suppressing the failure. If the provider requirement or fallback is unknown, record
`uncertain` and investigate.

## 4. When a library discovers on the app's behalf

A dependency that performs discovery internally may not expose a provider or loader
parameter. First establish that its call path performs discovery and identify which
provider the operation requires. Then, if the call boundary allows it, set the
thread-context classloader around that operation to a loader that can see the intended
provider, and restore it afterwards. Do not guess a loader or apply this to an
unconfirmed path.

```java
final ClassLoader previous = Thread.currentThread().getContextClassLoader();
Thread.currentThread().setContextClassLoader(MyClass.class.getClassLoader());
try {
    libraryThatDiscoversInternally.doWork();
} finally {
    Thread.currentThread().setContextClassLoader(previous);
}
```

The `finally` is not optional. Plugin code runs on threads the host owns and reuses.

## 5. Declare or register a required provider

When a required provider is missing, use an explicit provider/registration API if the
consuming library supports one. Otherwise, when the lookup reads service descriptors,
ship a declaration in the consuming bundle, for example:

```text
# src/main/resources/META-INF/services/com.example.spi.SerializerFactory
com.example.app.JsonSerializerFactory
```

The named class must implement the SPI and be visible to the actual lookup loader.
Bundle the implementation or use a supported public provider available on every target
covered by this declaration. A descriptor does not make an inaccessible implementation
reachable; do not import hidden product implementations to bypass API restrictions.
Inspect the packaged descriptor and implementation visibility together.

Do not list a product-specific optional provider unless its implementation is available
on every target where that descriptor is shipped. Preserve supported absence or use the
host's supported registration mechanism for optional providers. A factory that accepts
an explicit provider class may need no service descriptor; follow that API's contract.

## Verify

```bash
mvn clean package
```

Compilation and imports are not runtime evidence. Exercise every direct and
library-owned path recorded during discovery in its intended classloading context. For
plugin runtime claims, install on the exact target product and drive the operation that
reaches the discovery call. Discovery failures do not appear at build time, and for a
Spring app the failure surfaces as the plugin never reaching enabled state. For plugin
deployments, inspect product logs:

```bash
# nothing may report a provider it cannot load
grep -iE 'ServiceConfigurationError|FactoryConfigurationError|Provider .* not found' <product-log>
```

Confirm the app's own function too. A provider change is not always an error: parsing
that keeps working can still accept different documents, so check behaviour, not just
the absence of a stack trace. Verify required-provider paths resolve the intended
implementation and complete their operation. For optional providers or fallback paths,
exercise the empty-provider case where feasible and confirm the operation still meets
its required behavior; successful discovery or startup alone is insufficient.

If the caller owns or prohibits the runtime check, report it as `deferred` with its
owner and reason. A deferred check does not block handoff, but the result must not be
described as proven runtime-compatible.

## Do not confuse this with

- **Libraries Platform 9 stops exporting:** bundling is a packaging change owned by
  [bundle-platform-dependencies](../../bundle-platform-dependencies/SKILL.md). This
  skill handles discovery; a lookup repair must still establish implementation visibility.
- **Deprecated Crowd APIs, the analytics API and BouncyCastle** — also changing in
  Platform 9, each with its own migration. The
  [migration orchestrator](../../migrate-dc-app/SKILL.md) routes them.

## Notes

- **A plugin cannot restore boot delegation.** It is framework configuration, not a
  manifest header, so there is no `Import-Package` or bundle instruction that brings it
  back. Explicit resolution is the only remedy available to an app.
- **`Import-Package` does not help.** Importing the package that holds a provider makes
  its *classes* reachable, not its `META-INF/services` resource, and discovery reads the
  resource.
- **Empty results depend on the SPI and operation.** JAXP factories can fall back to
  JDK implementations whose behavior still needs validation. Mandatory services such
  as a JAXB provider can fail when none is found. A provider file naming a class the
  lookup loader cannot load can also throw; optionality does not make a broken
  declaration safe.
- **Naming a JDK-internal class in a provider file never works inside a bundle.**
  Packages such as `com.sun.org.apache.xerces.internal.jaxp` are not exported by any JDK
  module, so a bundle classloader cannot load them. On a flat classpath the same name
  resolves, which is why this only fails once the app is installed.

## Report

For each path, state its location or caller, the direct or library-owned discovery
operation, the intended provider and loader context, whether the provider is required
or optional for that operation, empty-result/fallback behavior and its evidence, the
changes made, and the checks actually exercised. Keep source and dependency evidence
distinct from runtime results, and list caller-delegated checks with owner and reason.

When producing an orchestrated consolidated report, follow the
[report adapter contract](../../migrate-dc-app/references/contracts.md#consolidated-report-adapter):
keep its six string-array fields and summarize deferred checks in `skipped_candidates`
rather than adding path-status arrays.

If no discovery was found, name the sources and implicated dependencies inspected, and
mark any scope not inspected as `uncertain`.
