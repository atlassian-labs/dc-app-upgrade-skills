---
name: bundle-platform-dependencies
description: Discover and bundle supported platform libraries that an Atlassian Data Center app uses but does not ship at compile scope. Use during a Platform 9 migration to discover all supported dependencies and apply the approved bundling plan.
---

# Bundle platform dependencies

## Determine applicability

**Scope:** Plugin packaging owners and their actual embedded modules, assessed
per owner. Plain libraries are not applicable to bundling; assess their source
migrations separately. Do not create plugin packaging to make this skill apply.

Use this skill in an ordinary plugin repository to discover and bundle supported
platform libraries used by the application. Resolve the canonical workspace,
plugin module, caller-declared embedded modules, exact target product/version,
and active Maven profiles. The analyzer is authoritative for supported families
and their bundling rules; dependency presence alone does not prove work is needed.

Ask for the target product when it is missing, then resolve its exact version and
runtime Java major from caller context. For an assessment-only request, report
the discovery plan and stop without applying it.

If the caller supplies an approved plan, reuse it and its resolved invocation
context for the applicability decision below instead of repeating discovery.
Confirm its target, profiles, modules, and selected families match the current
request. Require current readiness evidence before accepting a supplied no-op;
return missing evidence or a context mismatch to the discovery owner. The
application phase also checks freshness immediately before edits.

Otherwise, resolve this skill directory and run its read-only analyzer once for
all supported families in the selected modules. It inspects executable main-source usage,
effective Maven dependencies, packaging configuration, and target product exports,
then writes evidence and a structured plan. The launcher requires JDK 21 or newer
and Maven; it uses `JAVA_HOME` when set, otherwise the `java` on `PATH`. Resolve the target runtime Java major independently of the tooling JDK.
Use caller runtime configuration and project build instructions (including
`.java-version` when applicable); a compiler release level is not a runtime
selection. If switching JDKs to launch the tool, retain the resolved target Java
in `--target-java-version` rather than replacing it with the tooling JDK version.

The launcher locates the shared recipes relative to this skill and caches compiled
tooling under `$WORKSPACE/.migration-state/bundling-tool`. The installation may be
read-only. Resolve `MAVEN_BIN` to the Maven executable. Use absolute paths for the
workspace, modules, plan, and evidence; retain the invocation and Java environment
with the plan for application or refresh.

```bash
bash "$resolved_skill_directory/scripts/bundle-platform-dependencies.sh" \
  analyze --workspace "$WORKSPACE" --module "$PLUGIN_MODULE" \
  --maven "$MAVEN_BIN" \
  --target-product "$PRODUCT" --target-version "$TARGET_VERSION" \
  --target-java-version "$TARGET_JAVA_MAJOR" \
  --plan "$PLAN_JSON" --output "$OUTPUT_DIR"
```

Add `--embedded-module DIR` for each embedded module and `--profile ID` for each
active profile. The first `--module` is the plugin packaging owner; use
`--embedded-module` for modules whose code it ships. Analyze separate plugin
packaging owners in separate plans. Add `--export-snapshot FILE` for
caller-owned exact target evidence. When target evidence is missing, approximate,
or does not cover required packages, follow
[exports.md](references/exports.md#obtain-missing-preflight-evidence) to generate a
snapshot from a supported target distribution and rerun discovery. This needs
neither a running product nor a pre-migration app build. Do not infer that an
uncovered package is unavailable or silently accept uncertain findings.
Discovery always assesses all supported
families; `--family` is not accepted. Present the full findings before approval.
The user may approve all supported work or explicitly decline families. Use the
`approve` command in [cli.md](references/cli.md) to derive a subset plan only after
that decision, preserving the full discovery plan and reporting declined work.

Inspect the plan's source/dependency evidence, `operations`, and `unresolved`:

- Discovery failure, incomplete source coverage, or unresolved findings mean
  uncertain or blocked, never not applicable. Report the evidence and missing
  input; do not apply an unresolved plan.
- No supported usage means not applicable. Supported usage with no operations
  means no transformation is needed, subject to the findings below; it does not
  prove runtime compatibility. Report already-compatible configuration separately
  from unsupported or uncertain candidates.
- Supported operations with no unresolved findings mean migration is applicable.
  Unsupported package findings in `dependencyEvidence` do not invalidate supported
  operations in the same family. Keep that family selected and report only the
  unsupported package as skipped. For example, `org.apache.html.dom` does not
  prevent the planned Xerces/Xalan repairs for other packages. Do not rerun
  discovery with a narrower family list just to remove such a finding.
  Unless the request is assessment-only, continue to
  [migration.md](references/migration.md) to review, apply, verify, and report the
  group. Do not load migration instructions for a clean no-op.

Report packages provided by the target separately from dependencies already
bundled by the app. A target-provided decision requires exact target evidence and
compatible package constraints; a single exported package does not establish
that a whole family is provided. Preserve intentional existing bundling.

Always report unsupported evidence, including findings in `dependencyEvidence`,
even when no operation is planned. An unsupported-only result is unsupported,
not a clean no-op; report its candidates and reasons without loading migration
instructions. Discovery parses Java and approximately scans Kotlin (`.kt`), Scala
(`.scala`), and Groovy (`.groovy`) under `src/main/{java,kotlin,scala,groovy}`,
without compiling the app. The shared scanner recognizes imports, aliases,
wildcards, qualified references, and executable string interpolation; it ignores
comments and literal text. Tests and build-generated sources are excluded.
Review non-Java source evidence before applying: confirm that matched names refer
to the library, rather than similarly named local values. Scanner diagnostics
require manual assessment and block automatic application; the scanner does not
validate the full language grammar. If a match is false or scanning is incomplete,
report it and use manual assessment rather than editing the generated plan.
Scripts, custom source roots, dynamic loading, metaprogramming, and references
requiring type resolution need manual inspection before accepting a no-usage
result. Do not treat absence of approximate matches as proof of no dependency use.
Inherited AMPS instructions, ambiguous profile-owned dependencies, complex
embedding selectors, and jsoup re2j usage require manual assessment when reported.

Assessment makes no application edits and reports the selected scope, applicability,
evidence, and blockers.
