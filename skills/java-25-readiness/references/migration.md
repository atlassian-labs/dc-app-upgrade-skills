# Java 25 readiness migration

Use this reference after discovery finds an actionable Java 25 readiness issue.
The entrypoint owns the minimum-version thresholds, the actionability rule, and
the result format; this file covers how to apply and verify a repair.

The safe order is **runtime-first, compile-second**: get the module running on
JDK 25 at its existing supported compile level, then change that level only when
in scope. No intermediate release-21 upgrade is needed; this separates tooling
failures from language/target failures.

The entrypoint's `Library compatibility reference` table is authoritative for
every threshold repeated here, and covers tools this file does not discuss
(Mockito, ASM). Update that table, not this file, when a threshold changes.

## Migrate the findings

### ByteBuddy and agent-based libraries

Older ByteBuddy versions fail to instrument Java 25 classes; Mockito, mocking
frameworks, and other agents can pull ByteBuddy in transitively.

- Bump `net.bytebuddy:byte-buddy` and `byte-buddy-agent` where the module
  declares them, keeping the version in the declaration the module already owns
  so it stays visible in the POM.
- When the stale version arrives through another library, bump that library
  (such as Mockito) to a version depending on a compatible ByteBuddy, or pin it
  with `dependencyManagement`.
- Recheck `mvn dependency:tree` afterwards: a managed version in one scope can
  leave a stale resolution in another.

### Remove the Security Manager option

The Security Manager is removed/degraded in modern JDKs;
`-Djava.security.manager=allow` prevents startup on Java 25. Remove it from:

- Surefire/Failsafe `argLine`.
- AMPS `jvmArgs`.
- Run/debug configurations and CI JVM arguments.

Refactor any code that relies on the Security Manager.

### Upgrade build plugins when present

- **Spotless:** use **2.46.1+**; earlier versions can break on Java 25.
- **AMPS:** raise a project-owned AMPS that runs on JDK 25 to **9.12.6+**, then
  to the version the target product declares if that is newer. Do not raise it
  to supply a container id the target does not use; the container belongs to
  `spring7-jakarta11-migrate` and comes from the product's own pom. Preserve
  newer versions; do not add AMPS. Update the module or maintained parent POM
  that owns the effective version; if an external parent owns it and the project
  cannot safely override it, report the blocker.

### Check Kotlin and Scala compatibility

- **Scala:** use a JDK 25-compatible release, such as **2.12.21+** within the
  2.12 series; older Scala compilers fail on JDK 25.
- **Kotlin:** confirm that the configured compiler and runtime support JDK 25.
  Report a required Kotlin upgrade that another concern also constrains as a
  finding; do not raise the version beyond what JDK 25 needs.

### Make the build select JDK 25

Checking that JDK 25 can run the build is not enough: the project's own JDK
selection must pick it, or the default build still compiles on the old JDK and
fails against Platform 9's Java 25 bytecode. Selecting JDK 25 does not change
the compile level, so it belongs to the runtime-first step.

- Update every occurrence of every JDK selection mechanism the project owns that
  names an older JDK: `.java-version`, `.sdkmanrc`, `.tool-versions`, the `<jdk>`
  requirement in `maven-toolchains-plugin` configuration, project-owned
  `toolchains.xml`, JDK settings in `.mvn/jvm.config` or `mvnvm.properties`, and
  the JDK or build image in in-repository CI configuration. Report a CI image or
  agent the repository does not own as a blocker.
- Make a wrong JDK fail fast with a clear message rather than a class-file
  version error. Widen an existing `requireJavaVersion` rule in place; otherwise
  add one to the `maven-enforcer-plugin` configuration of the module or
  maintained parent POM that owns the build:

  ```xml
  <rules>
      <requireJavaVersion>
          <version>[25,)</version>
      </requireJavaVersion>
  </rules>
  ```

### Check CI runner support

If CI rejects or cannot launch JDK 25, check the CI agent/runtime version gate.
Use a documented override only when the CI system supports one (for example,
Bamboo: `-Dbamboo.upgrade.fail.if.java.unsupported=false`); otherwise upgrade
the CI agent/runtime.

## Verify runtime first, then compile

1. Preserve the existing supported Java source/target level and run tests on JDK 25:

   ```bash
   JAVA_HOME=/path/to/jdk-25 mvn test
   ```

   Record compiler release separately from the test JVM. Fix agent/tooling
   failures first (ByteBuddy, Security Manager option, Spotless, Scala).

2. Once runtime validation is green, set the compile target to Java 25:

   ```xml
   <maven.compiler.release>25</maven.compiler.release>
   ```

   Then run:

   ```bash
   JAVA_HOME=/path/to/jdk-25 mvn clean verify
   ```

   Fix remaining language, target, and deprecation issues.

3. Run the project's plain default build command once in the developer's
   default environment, without setting `JAVA_HOME` or any other JDK override,
   and confirm from `mvn -version` or the build output that it runs on JDK 25.
   A forced `JAVA_HOME` in the steps above hides a project JDK selection that
   still picks an older JDK.

## Troubleshooting

| Symptom / error message | Cause | Fix |
|---|---|---|
| `class file has wrong version 69.0, should be 65.0` (or another version below 69.0) at compile | The build runs on a JDK older than 25 against Platform 9's Java 25 bytecode | Make the project's JDK selection pick JDK 25 and add `requireJavaVersion` `[25,)`; see `Make the build select JDK 25`. |
| `Java 25 (69) is not supported by the current version of Byte Buddy` | ByteBuddy too old for an active instrumentation path | Bump `net.bytebuddy:byte-buddy` and `byte-buddy-agent` to **1.17.5+**, or bump Mockito to a version that depends on it. |
| `A command line option has attempted to allow or enable the Security Manager` (JVM will not start) | `-Djava.security.manager=allow` is present | Remove it from Surefire/Failsafe `argLine`, AMPS `jvmArgs`, and CI/run configurations; refactor code that relies on the Security Manager. |
| `NoSuchMethodError: com.sun.tools.javac.util.Log$DeferredDiagnosticHandler.getDiagnostics()` (during build/format) | Spotless is too old | Update Spotless to **2.46.1+** and, if used, palantirJavaFormat to a compatible version (for example, 2.71.0). |
| Project-owned AMPS runs on JDK 25 below the Java 25 floor | AMPS version is below 9.12.6 | Update project-owned AMPS to **9.12.6+**, then to the target product's own version if that is newer. |
| `Container <id> not supported` / `Container <id> not found` | AMPS is too old to provide the configured container | Confirm the container id against the target product's pom first; raise AMPS only to the version that id requires. |
| CI runner refuses JDK 25 (for example, a CI error says it does not support Java 25.x) | CI/agent version gate | Use a documented override if supported (for example, Bamboo: `-Dbamboo.upgrade.fail.if.java.unsupported=false`); otherwise upgrade the CI agent/runtime. |

## Report

Report the applied repairs and validation results through the entrypoint's
`Step 1 — Report` section, which specifies the JSON verdict and table. Record
the validation command and the JDK used for each check, and keep runtime
readiness distinct from compile-at-25 readiness.

A Java 25 blocker owned by another migration family stays a reported finding for
the caller; repair only what this skill's scope covers.
