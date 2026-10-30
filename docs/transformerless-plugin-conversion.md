---
skill: transformerless-plugin-conversion
skill-sha256: 9c136a666cb9
---

# Convert a plugin to transformerless

Platform 9 no longer provides the legacy transformed-plugin compatibility path,
in which the runtime generates a plugin's OSGi wiring. A plugin that still
relies on it has to move that wiring into source: OSGi headers in the AMPS
configuration and Spring XML under `META-INF/spring/`. This guide covers the
conversion with the AMPS `transform` goal. The [last section](#use-the-skill)
describes the `transformerless-plugin-conversion` skill, which runs the same
workflow.

## Is my code affected?

Look at the source inputs of one plugin module, not at files under `target/`:

- `src/main/**/atlassian-plugin.xml` declares `<component>`,
  `<component-import>` or `<module-type>` elements, or
- the AMPS plugin declared directly under `build/plugins` has `<instructions>`
  without `<Atlassian-Plugin-Key>`.

```bash
grep -rnE '<(component|component-import|module-type)\b' src/main --include=atlassian-plugin.xml
grep -n 'Atlassian-Plugin-Key' pom.xml
```

The module is already transformerless, and you can skip it, when the AMPS plugin
declared directly under `build/plugins` defines `<Atlassian-Plugin-Key>` in its
`<instructions>` and `atlassian-plugin.xml` has none of the three element types
above. A missing `Spring-Context` header alone is not a reason to convert:
Spring XML under `META-INF/spring/` is read by default.

## Before you start

- AMPS **9.10.0 or newer**. The `transform` goal requires it. If the project
  uses an older version, raise the version (or the property that owns it)
  first.
- The module must declare a recognized AMPS plugin directly under
  `build/plugins`: a product plugin such as `jira-maven-plugin` or
  `confluence-maven-plugin`, or the generic `amps-maven-plugin`. A generic
  plugin needs you to say which product to host (`-Dproduct=<product>`).
- Maven, and the Maven profiles your build needs if its AMPS configuration is
  profile-scoped.
- Convert one module at a time. In a reactor with several applicable modules,
  repeat the steps per module.

## Migrate

1. Install the module's reactor dependencies if the module has a parent
   reactor:

   ```bash
   mvn -DskipTests -DskipITs -pl <module> -am install
   ```

2. From the module directory, start the product so AMPS produces a fresh
   transformed artifact, stop it, then run the transform:

   ```bash
   mvn --batch-mode <amps-or-product>:run <amps-or-product>:stop <amps-or-product>:transform \
       -Dwait=false -DuseSpringJava=false -DskipAllPrompts=true
   ```

   Use the product goal prefix (`jira`, `confluence`, `bitbucket`, `crowd`,
   `bamboo`) for a product plugin. For `amps-maven-plugin` use `amps` and add
   `-Dproduct=<product>`. Add `-P<profiles>` when needed. `-DuseSpringJava=false`
   asks AMPS for Spring XML rather than Spring Java configuration. Run the
   AMPS version the project configures; do not swap it on the command line.
3. Review the changes AMPS made, then fix any product-specific dependency or
   import issue. Expect:

   | Area | Result |
   |---|---|
   | AMPS `<instructions>` | `Atlassian-Plugin-Key` and `Spring-Context` present |
   | `src/main/resources/META-INF/spring/` | at least one generated Spring XML file |
   | `atlassian-plugin.xml` | no `<component>`, `<component-import>` or `<module-type>` |
   | POM | AMPS version raised, if it had to be |

Do not hand-write a partial conversion when AMPS reports an error; fix the cause
and rerun. If the product is ambiguous or the descriptor is missing, stop and
resolve that first.

## Verify

A successful Maven exit alone is not proof. Check the source tree:

1. The directly declared AMPS plugin's instructions contain both
   `Atlassian-Plugin-Key` and `Spring-Context`.
2. At least one Spring XML file exists:

   ```bash
   ls src/main/resources/META-INF/spring/
   ```

3. This search must come back empty:

   ```bash
   grep -rnE '<(component|component-import|module-type)\b' src/main --include=atlassian-plugin.xml
   ```

4. The diff contains only the generated wiring and the descriptor and POM
   changes, including any AMPS version raise.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| The `transform` goal is unavailable or fails on an older AMPS | The goal requires AMPS 9.10.0 or newer. Raise the version in the file that owns it. |
| AMPS starts the wrong product | A generic `amps-maven-plugin` hosts its configured default. Pass `-Dproduct=<product>`. |
| Nothing is converted for the module | No recognized AMPS plugin is declared directly under `build/plugins`, which the `transform` goal requires. |
| The conversion fails partway | Stop the product (`<prefix>:stop`) with the same profiles, then read the AMPS output. |

## Use the skill

Ask your agent to "convert this plugin to transformerless". The
`transformerless-plugin-conversion` skill:

1. Inspects the module's `pom.xml` and `atlassian-plugin.xml`, not `target/`,
   identifies the product goal prefix (from your context or a directly declared
   product plugin) and resolves the AMPS version. It raises the version to 9.10.0
   when that is within its edit scope, and otherwise reports a blocker.
2. Classifies the module. It stops without changes for `already-compatible`
   (the shape described above). It needs evidence that conversion is meaningful,
   and stops without guessing when the product is ambiguous, the descriptor is
   missing, or no recognized AMPS plugin is declared.
3. Runs `scripts/apply-amps-transform.sh --module <path> --product <product>
   [--profiles ...]`. The helper installs the reactor dependencies when a parent
   reactor exists, runs the AMPS `run`, `stop` and `transform` goals above in one
   invocation, and makes a best-effort `stop` if the workflow fails. Logs are
   written below the module's `target/transformerless-plugin-conversion/`
   directory.
4. Verifies the source invariants listed above, then reports the product,
   profiles, AMPS version, classification (`already-compatible`, `converted`,
   `ambiguous` or `unsupported`), generated Spring resources and any blocker.

It converts only through the helper and always generates a fresh artifact; it
never uses a JAR from a cache or earlier run. It leaves ambiguous and
unsupported modules unchanged. You review the diff and resolve product-specific
dependency or import issues.
