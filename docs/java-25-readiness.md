---
skill: java-25-readiness
skill-sha256: c21769e91071
---

# Prepare a Maven module for Java 25

Platform 9 (Jira 12 / Confluence 11) runs on Java 25, and its libraries are Java 25
bytecode (class file version 69). A module that builds or tests on an older JDK
fails against them, and older versions of ByteBuddy, Spotless, AMPS, Scala and a
removed JVM option can stop the module building or starting on JDK 25. This guide
covers the manual preparation. The [last section](#use-the-skill) describes the
`java-25-readiness` skill, which automates it.

## Is my code affected?

A declared compiler level is not evidence of readiness: a module compiled at
release 17 can still fail on JDK 25. Treat each of these as a finding, including
declarations that no current code exercises and configuration in an inactive
profile, because the module owns them:

- a dependency or build plugin below the thresholds in the table under
  [Migrate](#migrate);
- `-Djava.security.manager=allow` in any test, AMPS or run configuration;
- a JDK selection that names a JDK older than 25, or a `requireJavaVersion`
  range that admits one.

```bash
# Security Manager option
grep -rn 'java.security.manager' --include=pom.xml --include='*.xml' --include='*.properties' --include='*.yml' --include='*.yaml' .
# JDK selection files (check nested modules too)
find . -name .java-version -o -name .sdkmanrc -o -name .tool-versions -o -name toolchains.xml -o -name jvm.config -o -name mvnvm.properties
grep -rn 'requireJavaVersion' --include=pom.xml .
# Resolved versions of the libraries that matter
mvn dependency:tree | grep -E 'byte-buddy|mockito|asm|kotlin|scala'
```

A stale version that arrives only through the dependency tree, where nothing
instruments Java 25 classes, is not something to edit: note the resolved version
and its path and fix it in the library that declares it. The same goes for
anything owned by CI infrastructure, another repository or a product
distribution. If nothing is a finding, the module needs no change.

## Before you start

- Work **runtime first, compile second**. Run the module on JDK 25 at its
  existing compile level before changing that level. This separates tooling
  failures from language and target failures, and no intermediate release-21
  step is needed.
- Note the current `maven.compiler.source/target` (or `release`), whether the
  module uses a Java agent or bytecode manipulation (ByteBuddy, inline Mockito,
  cglib), whether it has Kotlin or Scala sources, and whether it configures AMPS
  and at which effective version.
- Have a JDK 25 installed, and know whether your CI can launch it.
- For a module that runs AMPS tests, know the target product. Its declared AMPS
  version matters (see AMPS below).

## Migrate

Minimum versions known to work on JDK 25. Bump anything below them; check
`mvn dependency:tree` for stale transitive versions and override through
`dependencyManagement`.

| Library | JDK 25-compatible from | Notes |
|---|---|---|
| ByteBuddy (`net.bytebuddy`) | 1.17.5+ | Older versions fail to instrument classes; pulled in by Mockito and agents. |
| Mockito | 5.x (5.20+) | Older Mockito pins an incompatible ByteBuddy. Watch build tooling still on Mockito 3.x. |
| ASM | 9.x (9.9+) | Generally fine on recent 9.x. |
| Kotlin | 2.1+ | Confirm the configured compiler and runtime support JDK 25. |
| Scala | 2.12.21+ (within 2.12), or a newer series | Older compilers fail on JDK 25. |
| Spotless | 2.46.1+ | Earlier versions throw `NoSuchMethodError`. |
| AMPS | 9.12.6+ | Baseline when project-owned AMPS runs on JDK 25. |

### 1. ByteBuddy and agent-based libraries

Bump `net.bytebuddy:byte-buddy` and `byte-buddy-agent` where the module declares
them, keeping the version in the declaration you already own. When the stale
version arrives through another library such as Mockito, bump that library to a
version that depends on a compatible ByteBuddy, or pin ByteBuddy with
`dependencyManagement`. Recheck `mvn dependency:tree` afterwards: a managed
version in one scope can leave a stale resolution in another.

### 2. Remove the Security Manager option

`-Djava.security.manager=allow` prevents the JVM starting on Java 25. Remove it
from Surefire/Failsafe `argLine`, AMPS `jvmArgs`, run/debug configurations and CI
JVM arguments. Refactor any code that relies on the Security Manager.

### 3. Upgrade build plugins

- **Spotless:** 2.46.1 or later.
- **AMPS:** raise a project-owned AMPS that runs on JDK 25 to 9.12.6 or later,
  then to the version the target product declares if that is newer. 9.12.6 is
  the floor because that is where AMPS runs its OSGi test runner on Java 25;
  both Platform 9 products declare less than that. Keep newer versions, and do
  not add AMPS to a module that does not configure it. Change the module or the
  parent POM that owns the effective version; if an external parent owns it
  and you cannot safely override it, treat it as a blocker.
- Do not raise AMPS to obtain a container id. 9.12.6 already resolves both
  Platform 9 targets, and the container is covered by [`spring7-jakarta11-migrate`](spring7-jakarta11-migrate.md).

### 4. Scala and Kotlin

Use a JDK 25-compatible Scala (2.12.21+ in the 2.12 series). For Kotlin, confirm
the configured compiler and runtime support JDK 25. Do not raise Kotlin beyond
what JDK 25 needs when another concern also constrains it; flag that instead.

### 5. Make the build select JDK 25

Being able to run the build on JDK 25 is not enough: the project's own JDK
selection must pick it, otherwise the default build still compiles on the old
JDK and fails against the Java 25 bytecode. Selecting JDK 25 does not change the
compile level.

- Update every occurrence of every mechanism that names an older JDK:
  `.java-version`, `.sdkmanrc`, `.tool-versions`, the `<jdk>` requirement in
  `maven-toolchains-plugin` configuration, a project-owned `toolchains.xml`, JDK
  settings in `.mvn/jvm.config` or `mvnvm.properties`, and the JDK or image in
  CI configuration kept in the repository. A CI image or agent the repository
  does not own is a blocker to raise with its owner.
- Make a wrong JDK fail fast. Widen an existing `requireJavaVersion` rule in
  place, or add one to the `maven-enforcer-plugin` configuration of the module or
  parent POM that owns the build:

```xml
<rules>
    <requireJavaVersion>
        <version>[25,)</version>
    </requireJavaVersion>
</rules>
```

### 6. CI runner

If CI refuses or cannot launch JDK 25, check the agent's Java version gate. Use a
documented override only where the CI system supports one (Bamboo:
`-Dbamboo.upgrade.fail.if.java.unsupported=false`); otherwise upgrade the agent.

### 7. Raise the compile level (when in scope)

Only after the module is green on JDK 25 at its existing level:

```xml
<!-- Before -->
<maven.compiler.release>17</maven.compiler.release>
<!-- After -->
<maven.compiler.release>25</maven.compiler.release>
```

Then fix the remaining language, target and deprecation issues.

## Verify

1. Runtime first, with the existing compile level unchanged:

   ```bash
   JAVA_HOME=/path/to/jdk-25 mvn test
   ```

   Fix agent and tooling failures first (ByteBuddy, Security Manager option,
   Spotless, Scala).

2. After raising the compile level:

   ```bash
   JAVA_HOME=/path/to/jdk-25 mvn clean verify
   ```

3. Run the project's plain default build once without setting `JAVA_HOME` or any
   other JDK override, and confirm from `mvn -version` or the build output that
   it runs on JDK 25. A forced `JAVA_HOME` in steps 1 and 2 hides a project JDK
   selection that still picks an older JDK, so steps 1 and 2 alone are not
   evidence that the default build is ready.

Record the JDK used for each check, and keep "runs on JDK 25" separate from
"compiles at release 25". A check you could not run is unverified, not passing.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `class file has wrong version 69.0, should be 65.0` (or another version below 69.0) at compile | The build runs on a JDK older than 25 against Java 25 bytecode. Make the project's JDK selection pick JDK 25 and add `requireJavaVersion` `[25,)`. |
| `Java 25 (69) is not supported by the current version of Byte Buddy` | ByteBuddy too old on an active instrumentation path. Bump `byte-buddy` and `byte-buddy-agent` to 1.17.5+, or bump Mockito. |
| `A command line option has attempted to allow or enable the Security Manager` | `-Djava.security.manager=allow` is present. Remove it from `argLine`, AMPS `jvmArgs` and CI or run configurations. |
| `NoSuchMethodError: com.sun.tools.javac.util.Log$DeferredDiagnosticHandler.getDiagnostics()` during build or format | Spotless is too old. Update to 2.46.1+ and, if used, palantirJavaFormat to a compatible version (for example 2.71.0). |
| Project-owned AMPS runs on JDK 25 below the floor | AMPS is below 9.12.6. Update it, then to the target product's version if newer. |
| `Container <id> not supported` / `Container <id> not found` | AMPS is too old to provide the configured container. Confirm the id against the target product's pom first; raise AMPS only to the version that id requires. |
| CI says it does not support Java 25.x | The CI agent's version gate. Use a documented override if supported, otherwise upgrade the agent. |

## Use the skill

Ask your agent to "check Java 25 readiness", "prepare for Java 25", or "why
won't my plugin start on JDK 25". The `java-25-readiness` skill:

1. Detects the module shape: compile level, agents and bytecode tooling,
   Security Manager arguments, Spotless, Kotlin or Scala sources, the effective
   AMPS version and any hardcoded container, every project-owned JDK selection
   (including nested module files), and whether CI can launch JDK 25. It
   compares each finding with the thresholds above and records where it found it.
2. Repairs actionable findings and only reports the ones that sit in CI
   infrastructure, another repository, a product distribution, or only in the
   dependency tree. With no actionable finding it changes nothing.
3. Follows the runtime-first order: JDK 25 at the existing compile level, then
   release 25, then a plain default build.
4. Reports a JSON verdict on the first line (`{"java25-ready": true|false,
   "confidence-score": 1-100}`) followed by a table with one row per check above,
   marking anything it could not verify as `unverified`.

It leaves to you CI infrastructure it does not own, an external parent POM it
cannot safely override, a Kotlin upgrade that another concern constrains, and
Java 25 blockers that belong to another migration (for example
[`spring7-jakarta11-migrate`](spring7-jakarta11-migrate.md)).
