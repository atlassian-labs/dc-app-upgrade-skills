---
name: commons-lang-migrate
description: Migrates application code off the legacy com.atlassian.gzipfilter.org.apache.commons.lang namespace by lazily applying the bundled deterministic OpenRewrite recipe. Use this skill whenever Java application sources import or reference that repackaged Commons Lang namespace during a platform migration.
metadata:
  version: 0.0.1
---

# Repackaged Commons Lang migration

The legacy platform-repackaged Commons Lang 2 namespace
`com.atlassian.gzipfilter.org.apache.commons.lang` is unavailable on the target
runtime. Migrate confirmed application usage to the real
`org.apache.commons.lang` package and add the direct
`commons-lang:commons-lang:2.6` dependency with the bundled OpenRewrite recipe.

## Scope decision

Inspect the selected Maven module's application Java sources under
`src/main/java`. Ignore generated output, tests, comments, documentation, and
diagnostic strings when deciding applicability.
**Scope:** Plugins and independent libraries; no product/version required.
Assess requested modules and invoke applicable helpers sequentially.

Check layout before dispatch: the helper requires a relative `reactor/module`
path containing `/`, with both directories below the workspace root and the
module immediately under its Maven reactor. Standalone `.` and workspace-root
reactors are unsupported. Report actionable findings there as `unsupported`,
never compatible; do not pad paths, change workspace boundaries, or bypass the helper.

- Select this skill when executable Java code imports or fully qualifies a type
  below `com.atlassian.gzipfilter.org.apache.commons.lang`.
- Classify it as `already-compatible` when that executable namespace is absent.
  Do not invoke the helper in this case.
- Treat similarly named strings or protected non-application sources as
  inspection evidence only; they are not migration targets.

## Apply the deterministic recipe

Resolve the directory containing this `SKILL.md` to
`resolved_skill_directory`, then invoke its `scripts/apply-openrewrite.sh`
while keeping the selected repository root as the current directory:

```bash
bash "$resolved_skill_directory/scripts/apply-openrewrite.sh" "$PRODUCT_MODULE_PATH"
```

The helper independently verifies the workspace and module scope. Applicability
is established by the semantic check above before the helper is invoked. When
selected, it builds the staged recipe catalog once, installs the selected
module's reactor dependencies, and applies only
`com.atlassian.dc.p9enablement.migrate.MigrateRepackagedCommonsLang`.

Do not reproduce the Maven commands manually, modify the helper or recipe, or
fall back to hand-editing if the helper fails. Record the failure as a blocker
because the deterministic transformation did not complete as designed.

## Inspect the result

After an `APPLIED` result:

1. Inspect the application-only diff under the selected module.
2. Confirm executable imports/references now use `org.apache.commons.lang`.
3. Confirm the selected module's `pom.xml` has one compile/default-scope
   `commons-lang:commons-lang:2.6` dependency.
4. Leave generated recipe and module `target/` output untouched. Runtime
   compilation and validation belong to the caller's validation flow.

Add this skill's name to `selected_skills`. Include only selected-module paths
in `changed_files`, summarize the namespace and dependency transformation in
`migrated_apis`, and put helper failures or residual executable legacy usage in
`blockers`. When the skill is `already-compatible`, add it to `skipped_skills`
with that reason and make no changes.
