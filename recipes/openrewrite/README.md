# Migration Recipes

OpenRewrite recipes that mechanically fix confirmed Jira and Confluence breaking changes.

Recipes are invoked lazily by the migration skill that owns their discovery
and scope rules. The repackaged Commons Lang recipe is executed by
[`../../skills/commons-lang-migrate`](../../skills/commons-lang-migrate)
only after executable legacy usage is confirmed; this project is not built
unconditionally.

## Verified against

Real Maven Central metadata at authoring time (not assumed from memory - see inline pom comments
for how each was checked):
- `org.openrewrite:rewrite-bom:8.87.3`
- `org.openrewrite:rewrite-java-25:8.87.3` (also confirmed `-17` and `-21` variants exist, in case
  the repo-wide JDK pin ever changes again)
- `org.openrewrite.maven:rewrite-maven-plugin:6.44.0` (for consumers; not used by this module's own build)
- `commons-lang:commons-lang:2.6` (the real fix target for Cluster A)

## Running the tests

```bash
mvn clean test
```
