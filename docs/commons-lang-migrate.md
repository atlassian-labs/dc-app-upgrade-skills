---
skill: commons-lang-migrate
skill-sha256: 5020a26f7510
---

# Migrate off the repackaged Commons Lang namespace

Code that imports Commons Lang 2 from the platform-repackaged namespace
`com.atlassian.gzipfilter.org.apache.commons.lang` breaks, because that
namespace is unavailable on the target runtime. The fix is to import the real
`org.apache.commons.lang` classes and declare `commons-lang:commons-lang:2.6`
yourself. This guide covers the manual change. The
[last section](#use-the-skill) describes the `commons-lang-migrate` skill, which
applies it with a deterministic OpenRewrite recipe.

## Is my code affected?

Your module needs migration if its application Java sources import or fully
qualify a type below `com.atlassian.gzipfilter.org.apache.commons.lang`.

```bash
grep -rn 'com\.atlassian\.gzipfilter\.org\.apache\.commons\.lang' src/main/java
```

Only executable code counts. Matches in tests, generated output, comments,
documentation and diagnostic strings are not migration targets, so a module whose
only matches are of that kind can be skipped. If the namespace is absent from
`src/main/java`, the module is already compatible.

## Before you start

No prerequisites beyond a Maven module whose build you can run. The change needs
no target product or version. The `commons-lang:commons-lang:2.6` version is the
one the recipe adds.

## Migrate

### 1. Add the dependency

Declare Commons Lang 2.6 directly in the module's `pom.xml`, with the default
(compile) scope:

```xml
<dependency>
    <groupId>commons-lang</groupId>
    <artifactId>commons-lang</artifactId>
    <version>2.6</version>
</dependency>
```

Keep a single such dependency in the module.

### 2. Rename the package

Replace the repackaged prefix with the real one, for every sub-package. The
rename is a pure prefix change:

| Before | After |
|---|---|
| `com.atlassian.gzipfilter.org.apache.commons.lang.StringUtils` | `org.apache.commons.lang.StringUtils` |
| `com.atlassian.gzipfilter.org.apache.commons.lang.builder.*` | `org.apache.commons.lang.builder.*` |
| `com.atlassian.gzipfilter.org.apache.commons.lang.math.*` | `org.apache.commons.lang.math.*` |

```java
// Before
import com.atlassian.gzipfilter.org.apache.commons.lang.StringUtils;
// After
import org.apache.commons.lang.StringUtils;
```

Do the same for fully qualified references in code, not just `import` lines.

## Verify

1. This search must come back empty for executable code:

   ```bash
   grep -rn 'com\.atlassian\.gzipfilter\.org\.apache\.commons\.lang' src/main/java
   ```

2. The module's `pom.xml` has exactly one `commons-lang:commons-lang:2.6`
   dependency, at compile or default scope.
3. Build and test the module, for example `mvn clean verify`. A passing compile
   shows the renamed classes resolve. Runtime validation on the target product
   is a separate check that the module's own build does not replace.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `package org.apache.commons.lang does not exist` after the rename | The `commons-lang:commons-lang:2.6` dependency is missing from that module. Add it (step 1). |
| A match remains in `src/main/java` after the rename | An import or fully qualified reference was missed, often in a sub-package. Search again and fix it. |
| The helper refuses the module path | See the layout rule in the next section. |

## Use the skill

Ask your agent to "migrate off the repackaged Commons Lang" for a module. The
`commons-lang-migrate` skill:

1. Decides applicability from the module's `src/main/java`. It selects the
   module only when executable code imports or fully qualifies a type below the
   legacy namespace. Tests, comments, documentation and strings are inspection
   evidence only. When the executable namespace is absent it records the module
   as already compatible and changes nothing.
2. Checks layout before running anything. The helper needs a relative
   `reactor/module` path (it contains `/`, both directories are below the
   workspace root, and the module sits directly under its Maven reactor).
   A standalone module at `.` or a reactor at the workspace root is not
   supported and is reported as unsupported, not as compatible.
3. Runs `scripts/apply-openrewrite.sh <reactor/module>` from the repository
   root. The helper builds the bundled recipe catalog once, installs the
   selected module's reactor dependencies (`-pl <module> -am -DskipTests
   install`), then runs only the `MigrateRepackagedCommonsLang` recipe through
   `rewrite-maven-plugin`. The recipe adds the `commons-lang:commons-lang:2.6`
   compile dependency when the legacy namespace is in use, then renames the
   package recursively. A POM with no legacy usage is left unchanged.
4. Inspects the application-only diff, confirms executable references now use
   `org.apache.commons.lang`, and confirms the single compile-scope dependency.

The skill does not fall back to hand-editing when the helper fails; it records
the failure as a blocker. It leaves generated `target/` output untouched and
leaves compilation and runtime validation on the target product to you.
