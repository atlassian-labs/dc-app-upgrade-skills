---
name: java-25-readiness
description: Detect and migrate Java 25 readiness blockers in Java Maven plugin, library, or component modules targeting Platform 9, then verify the requested modules on JDK 25.
---

Assess requested Java Maven modules for **Java 25** (the runtime shipped
with **Platform 9** — Jira 12 / Confluence 11) and apply the known fixes.

**Scope:** Requested plugin/library modules and authorized parent build files.
Resolve the runtime goal from the request or authoritative project/target context;
libraries need no product target. Inspect each module's tooling, apply AMPS fixes
only where configured, and validate shared edits across affected modules.

Platform 9 also raises the JVM baseline. The safest migration order is
**runtime-first, compile-second**: run on JDK 25 at the existing supported compile
level before changing it, separating tooling failures from language/target failures.

Use this skill when the user asks to:
- "check Java 25 readiness" / "prepare for Java 25"
- "why won't my plugin start on JDK 25"
- "Platform 9 JVM upgrade"

## Step 1 — Detect the module shape

Determine, from the module:
- current `maven.compiler.source/target` (or `<release>`) — often 11/17/21,
- whether it uses a Java agent / bytecode manipulation (ByteBuddy, Mockito
  inline, cglib),
- whether any JVM args set `-Djava.security.manager=allow`,
- whether Spotless / other build plugins run,
- whether it contains **Kotlin** or **Scala** sources,
- whether it configures an AMPS plugin and which version is effective,
- which container, if any, AMPS integration tests hardcode, given that AMPS
  resolves one for the target when none is set,
- which JDK the build selects by default, from every occurrence of each
  mechanism the project owns: `.java-version` (jenv), `.sdkmanrc`,
  `.tool-versions`, Maven toolchains (`maven-toolchains-plugin`,
  `toolchains.xml`), JDK settings in `.mvn/jvm.config` or `mvnvm.properties`,
  the JDK or image in in-repository CI configuration, and any enforcer
  `requireJavaVersion` range. Include nested module files, not only the root's,
- whether CI can select and launch JDK 25.

Compare each detected version against the thresholds in the
`Library compatibility reference` table below, and record each finding with its
evidence location. A declared compiler level is not evidence of runtime
readiness.

### What counts as actionable

Treat as actionable and repair:
- a dependency, build plugin, or JVM argument that the module's own POM or
  parent build configuration declares below a threshold — including a
  declaration no current code exercises, and configuration in a currently
  inactive profile, because the module owns it and it blocks the first test or
  build step that reaches it,
- `-Djava.security.manager=allow` in any test, AMPS, or run configuration the
  module owns,
- a project-owned JDK selection, including in-repository CI configuration, or a
  `requireJavaVersion` range that selects or admits a JDK older than 25.
  Platform 9 libraries are Java 25 bytecode (class file version 69), so a build
  on an older JDK fails at the first module compiled against them with
  `class file has wrong version 69.0, should be 65.0`, whatever the compile
  level.

Treat as a reported finding rather than an edit:
- a stale version reached only through the dependency tree where nothing
  instruments Java 25 classes — record the resolved version and its path, and
  repair it at the declaring library,
- anything owned by CI infrastructure, another repository, or a product
  distribution.

If no finding is actionable, report that and stop without changing files.

## Step 2 — Report

Emit, in the first line, a JSON verdict:

```
{"java25-ready": true|false, "confidence-score": 1-100}
```

Then a short summary table of what was changed / what still blocks readiness:

| Item | Status | Notes |
|---|---|---|
| ByteBuddy 1.17.5+ | ✅ / ❌ | current vs required version |
| `-Djava.security.manager=allow` removed | ✅ / ❌ / n-a | where found |
| Spotless 2.46.1+ | ✅ / ❌ / n-a | |
| AMPS Java 25 support | ✅ / ❌ / n-a | 9.12.6+ when project-owned AMPS runs on JDK 25 |
| AMPS matches the target product | ✅ / ❌ / n-a | product's declared version; no container id hardcoded |
| Scala JDK25-compatible | ✅ / ❌ / n-a | version bumped to |
| Kotlin JDK25-compatible | ✅ / ❌ / n-a | |
| Build selects JDK 25 by default | ✅ / ❌ | mechanisms updated; `requireJavaVersion` `[25,)` |
| Runtime on JDK 25 (existing compile level) | ✅ / ❌ | |
| Full compile at release 25 | ✅ / ❌ | |

Report a check you could not verify as `unverified` rather than ✅ or ❌, and
keep runtime readiness distinct from compile-at-25 readiness.

## Library compatibility reference

Minimum versions known to work on JDK 25. Bump anything below these thresholds
(check `mvn dependency:tree` for stale transitive versions and override via
`dependencyManagement`).

| Library | JDK 25-compatible from | Notes |
|---|---|---|
| ByteBuddy (`net.bytebuddy`) | **1.17.5+** | Older versions fail to instrument classes on JDK 25 (pulled transitively by Mockito & agents). |
| Mockito | **5.x** (5.20+) | Older Mockito pins an incompatible ByteBuddy. Watch out for `bamboo-specs`/build tooling still on Mockito 3.x. |
| ASM | 9.x (9.9+) | Generally fine on recent 9.x. |
| Kotlin | 2.1+ | Confirm the configured compiler and runtime support JDK 25. |
| Scala | **2.12.21+** (within 2.12), or newer series | Older Scala compilers fail on JDK 25. Per official Scala JDK-compatibility docs. |
| Spotless | **2.46.1+** | Earlier versions throw `NoSuchMethodError` on JDK 25. |
| AMPS | **9.12.6+** | Java 25 support baseline when project-owned AMPS runs on JDK 25. |

Treat an effective project-owned AMPS version below **9.12.6** as actionable
whenever project-owned AMPS runs on JDK 25. The floor is 9.12.6 because that is
where AMPS runs its OSGi test runner on Java 25, independently of what any
product declares; both Platform 9 products declare less than that. Above the
floor, prefer the AMPS version the target product itself declares. Do not
raise AMPS to reach a container id: 9.12.6 already resolves both Platform 9
targets. Preserve newer versions; do not add AMPS when the project does not
configure it.

## Migration

When Step 1 leaves an actionable finding, load
[`references/migration.md`](references/migration.md) and follow its migration
and verification guidance — including runtime-first validation before any
compile-target change — then report through Step 1. Do not duplicate that
procedure here.
