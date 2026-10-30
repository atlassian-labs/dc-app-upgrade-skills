---
name: transformerless-plugin-conversion
description: Convert an Atlassian Data Center plugin that still requires AMPS runtime transformation into a transformerless OSGi plugin. Use this skill when a plugin lacks the Atlassian-Plugin-Key AMPS instruction, has legacy component declarations, or is otherwise confirmed to require the removed transformed-plugin compatibility path. A plugin with Atlassian-Plugin-Key and no legacy declarations is already transformerless, with or without Spring-Context. The skill independently runs the documented AMPS conversion workflow.
---

# Transformerless plugin conversion

Platform 9 no longer provides the legacy transformed-plugin compatibility path.
Convert a plugin by replacing runtime-generated wiring with transformerless,
source-owned OSGi configuration.

This capability is portable. It operates on the caller-selected plugin module
and obtains its own transformed artifact by running AMPS. It does not consume a
previously captured JAR, an external migration artifact, or an earlier run's
output. It requires Bash, Maven (or an executable `MAVEN_BIN` override), and
AMPS 9.10.0 or newer with either the selected product plugin or a generic AMPS
plugin configured for the target product.

## Applicability

Inspect authoritative source inputs, not generated files under `target/`:

Scope one Maven plugin module at a time. If a reactor contains several
applicable modules, invoke the helper separately for each module.

1. Locate the module's `pom.xml` and its `src/main/**/atlassian-plugin.xml`.
2. Identify the Maven product goal prefix from caller context or from a directly
   declared product-specific Maven plugin. A generic `amps-maven-plugin`
   requires the caller to supply the product.
3. Resolve the configured AMPS version. The automated `transform` goal requires
   AMPS 9.10.0 or newer. If the project uses an older version, update the owning
   version or property to at least 9.10.0 before invoking the helper. If the
   version cannot be resolved, or is owned by a file outside the permitted edit
   scope, report a blocker. Do not silently invoke a different AMPS version
   while leaving the project configuration unchanged.
4. Classify the module `already-compatible` and stop, without changes, when
   the AMPS plugin declared directly under `build/plugins` defines
   `<Atlassian-Plugin-Key>` in its `<instructions>` and `atlassian-plugin.xml`
   has no `<component>`, `<component-import>`, or `<module-type>`
   declarations. `Spring-Context` is not required for this shape: Spring XML
   under `META-INF/spring/` is read by default, so a missing `Spring-Context`
   header alone is not conversion evidence.
5. Require evidence that conversion is meaningful: legacy `<component>`,
   `<component-import>`, or `<module-type>` declarations, or AMPS
   `<instructions>` that lack `<Atlassian-Plugin-Key>`.

If the target product is ambiguous, the descriptor is missing, no recognized
AMPS plugin is declared directly under `build/plugins` (a limitation of the
AMPS `transform` goal), or the evidence does not establish a supported
conversion, classify the candidate as `ambiguous` or `unsupported` and stop.
Do not guess a product and do not hand-edit a partial conversion.

## Inputs

The caller supplies ordinary project context:

```text
module       path to the Maven plugin module (default: current directory)
product      Maven product goal prefix, such as jira, confluence, bitbucket,
             crowd, or bamboo
profiles     comma-separated Maven activation profiles (required when the
             selected build configuration is profile-scoped)
```

Run the checked-in helper from this directory. `MAVEN_BIN` may override the
Maven executable; otherwise the helper uses `mvn` from `PATH`.

```bash
scripts/apply-amps-transform.sh \
  --module path/to/plugin-module \
  --product jira \
  --profiles platform9,conversion
```

Use the helper after applicability is confirmed. It is the only supported way
for this skill to invoke product lifecycle goals. Do not reproduce its Maven
command manually and do not substitute a hand-written XML or descriptor
conversion when AMPS reports an error.

## Conversion workflow

The helper runs the documented AMPS workflow from the module itself. It uses
the product goal prefix for a product-specific plugin or `amps` for a generic
`amps-maven-plugin`, to which it also passes `-Dproduct=<product>` so the
requested product is hosted rather than the plugin's default. It deliberately uses the AMPS version configured by the
project; applicability preparation above owns any required version upgrade.

```text
mvn --batch-mode --no-transfer-progress \
  <amps-or-product>:run <amps-or-product>:stop <amps-or-product>:transform \
  -Dwait=false -DuseSpringJava=false -DskipAllPrompts=true [-Dproduct=<product>]
```

Every Maven invocation uses batch mode and disables transfer progress; AMPS
invocations also disable prompts. The helper first installs the selected
module's reactor dependencies when a parent reactor is present. That reactor
lookup never discovers or converts other modules. It then starts the selected
product so AMPS can create a fresh transformed artifact, stops it before
conversion, and asks AMPS to generate Spring XML rather than Spring Java
configuration. If the AMPS workflow fails, it makes a best-effort stop call
using the same profiles. It preserves command logs below the module's normal
Maven `target/` directory and returns a failure status. The helper never takes a
JAR from a caller, cache, or earlier migration run. It does not inspect or
summarize the resulting source changes; perform the checks below after it exits.

## Verification

After AMPS succeeds, verify the authoritative source tree:

- the directly declared AMPS plugin's instructions contain both
  `Atlassian-Plugin-Key` and `Spring-Context`;
- at least one generated Spring XML file exists under
  `src/main/resources/META-INF/spring/`;
- `atlassian-plugin.xml` no longer contains `<component>`,
  `<component-import>`, or `<module-type>` declarations; and
- the application diff contains only the expected generated wiring and
  descriptor/POM changes, including an AMPS version upgrade when required.

Inspect the diff and resolve any product-specific dependency or import issue.
Never treat a successful Maven exit alone as proof of conversion: the source
invariants must also hold.

## Reporting

Summarize the target product, supplied profiles, configured AMPS version and any
version upgrade, applicability classification, AMPS command outcome, generated
Spring resources, removed legacy declarations, and any blocker. Distinguish
`already-compatible`, `converted`, `ambiguous`, and `unsupported`; leave
ambiguous or unsupported candidates unchanged.

## References

- [Atlassian transformed-to-transformerless conversion guide](https://developer.atlassian.com/server/framework/atlassian-sdk/transformerless-plugin-conversion/)
- [Atlassian Maven command reference](https://developer.atlassian.com/server/framework/atlassian-sdk/working-with-maven/)
