# Launcher contract

Set the installed directory containing `SKILL.md`
to `resolved_skill_directory`, then
invoke its launcher from the selected application workspace. Keep the
application workspace and module paths absolute after resolving symlinks.

The launcher exposes analyze, approve, check, apply, verify, and exports commands. Discovery inputs and the `analyze` invocation
are documented in [the skill entrypoint](../SKILL.md#determine-applicability).
Read this contract when proceeding to migration. The first selected module owns
AMPS packaging and import instructions. Additional selected modules must belong
to that plugin; use separate plans for separate plugin artifacts.

`analyze` accepts `--target-java-version` and an optional `--export-snapshot`.
Pass the target Java major explicitly; the tooling JVM can differ. Schema 2 product-aware
plans record `exportEvidence` (source, target and snapshot digest) and
`dependencyClassifications` (`needs-bundling`, `provided-by-target`,
`already-bundled`, or `uncertain`). Keep the caller-owned snapshot alongside the
plan. Missing or insufficient evidence is handled through
[the preflight extraction workflow](exports.md#obtain-missing-preflight-evidence).

`check` and `apply` consume the approved plan and use `--workspace DIR`, `--plan FILE`,
and `--output DIR`. Neither rediscovers families or modules from discovery
flags.

```bash
bash "$resolved_skill_directory/scripts/bundle-platform-dependencies.sh" \
  check --workspace "$WORKSPACE" --maven "$MAVEN_BIN" \
  --plan "$PLAN_JSON" --output "$OUTPUT_DIR"

bash "$resolved_skill_directory/scripts/bundle-platform-dependencies.sh" \
  apply --workspace "$WORKSPACE" --maven "$MAVEN_BIN" \
  --plan "$PLAN_JSON" --output "$OUTPUT_DIR"

bash "$resolved_skill_directory/scripts/bundle-platform-dependencies.sh" \
  verify --workspace "$WORKSPACE" --maven "$MAVEN_BIN" \
  --plan "$PLAN_JSON" --artifact "$ARTIFACT_JAR" --output "$RESULT_JSON" \
  --target-java-version "$TARGET_JAVA_MAJOR" [--export-snapshot "$EXPORT_SNAPSHOT"] \
  [--baseline "$BASELINE_JAR"] [--provided-packages "$PROVIDED_PACKAGES"]
```

Use the same resolved Maven executable and Java environment across phases,
including worker invocations. The launcher locates recipes relative to the skill
and reuses compiled tooling in `$WORKSPACE/.migration-state/bundling-tool`, keeping
the installation read-only. `--maven` overrides `MAVEN_BIN` or `mvn` on PATH.
The launcher is the source of truth for
other optional flags, output locations, and exit statuses. It must validate the
workspace and module scope before edits, reject a stale plan, and return a
machine-readable result suitable for the six-array adapter. `analyze` always
assesses all supported families and rejects `--family`. It writes the plan to
`--plan` when supplied, otherwise to `<output>/bundle-plan.json`; `apply`
requires `--plan` and writes its result to `<output>/bundle-result.json`.

## Approval after complete discovery

If all proposed supported work is approved, use the discovery plan directly.
If the user explicitly approves only a subset, preserve that full plan and run:

```bash
bash "$resolved_skill_directory/scripts/bundle-platform-dependencies.sh" \
  approve --workspace "$WORKSPACE" --maven "$MAVEN_BIN" \
  --plan "$FULL_PLAN_JSON" --approve-family gson --approve-family rome \
  --output "$APPROVAL_OUTPUT_DIR"
```

Use the resulting `$APPROVAL_OUTPUT_DIR/bundle-plan.json` for `check`, `apply`,
and `verify`. `--approve-family` accepts repeated or comma-separated IDs, only on
`approve`; it is never a discovery filter. Approval requires a fresh full-catalog
plan and does not overwrite it. It rechecks source and Maven evidence to calculate
the approved dependency closure, including companions shared with declined
families. It preserves the recorded target Java and snapshot context; an explicit
`--export-snapshot` or `--target-java-version` must match that context. Changed
export evidence requires full discovery again before subset approval. Do not manually filter operations or verification artifacts. Review the
resulting plan before applying; unresolved findings in that plan still block edits.

Keep the original full findings and the user's decision in the report. Declined
families are remaining migration work, not already compatible. A family declined
as independent work may still supply a companion required by an approved family;
if the user forbids that dependency entirely, report the conflict instead of
applying the subset. A passing subset verification does not establish full-app
compatibility. Unsupported package findings never authorize silently declining
an otherwise supported family.

`verify` requires an exact built plugin JAR and a plan containing
`verificationArtifacts`. It checks the actual bundle class path, including nested
JARs, rejects forbidden prefixes, and uses JDK `jdeps` to select mandatory imports
referenced by the planned bundled library classes. It also checks app-used
packages recorded as `provided-by-target`, including actual built import
constraints. An optional import does not hide an unavailable app-used package;
a package neither contained nor imported is a blocker. Unrelated application
imports are reported as out of scope. It checks the selected requirements against the export
snapshot selected for the plan's product/version. Pass `--target-java-version`
explicitly; it otherwise uses the plan's target Java when recorded, then the
tooling JVM for legacy plans. The verifier reuses the plan's caller-owned snapshot
unless an explicit `--export-snapshot` supplies refreshed evidence.
Use a JDK capable of reading the packaged library class files; the target runtime
JDK is recommended for bytecode inspection.
Use `--export-snapshot` to supply refreshed product evidence. See
[exports.md](exports.md) for normalized version selection, approximation handling,
and the `exports` extraction command.

A baseline JAR is optional comparison evidence; it is not a prerequisite for
verification or proof of target compatibility. Snapshot-backed checks must still
validate import constraints even if the baseline contains the same package.
`--provided-packages` is a legacy name-only evidence input, not a substitute for
versioned target exports. Never populate it from the plugin's import list.
Exit status 2 means verification needs attention: distinguish concrete packaging
blockers, unmatched imports, and missing/provisional export evidence in the report.
A passing static check does not replace required target-runtime validation.

Legacy schema 1 plans must be regenerated with `analyze` before approval or application.

`check` validates the plan's schema, workspace, operation scope, input fingerprint,
export evidence freshness, and unresolved findings without running Maven discovery or editing application files.
It writes `bundle-check.json` with status `ready` (exit 0), `stale-plan`, or
`unresolved` (exit 2); stale/unresolved plans have zero recipe invocations and
pending family outcomes. Invalid schema, workspace, or operation scope produces
an error (exit 3) without a new result file. Other launcher failures are also
nonzero exits, not readiness; never consume an older result as current success.
It does not overwrite `bundle-result.json`. Run it immediately before dispatch
using the same resolved launch arguments as apply. Follow the refresh procedure
below for stale inputs; never edit a recorded fingerprint to bypass the guard.
`apply` repeats these checks at mutation time.

## Refresh and recovery

Retain the original full `analyze` invocation and approved plan. If readiness
reports stale application or export evidence after preceding changes, the discovery
owner preserves the old plan and reruns full discovery with the original target,
Java, profiles and plugin/embedded modules into a new plan and evidence directory.
Do not reuse stale effective-POM evidence. If approval was a subset, derive a new
subset plan using the recorded user decision.

Compare refreshed operations with the approved target, families, module/file scope
and explicit version or dependency constraints. Companions prescribed by approved
families stay in scope. An in-scope refresh needs no new approval; new scope,
unresolved findings or a design decision require a plan change under the caller's
blocker policy. Record both plan paths and the refresh reason, then run `check`
again. Pass the successful checked arguments and environment to application.

If a delegated worker returns a stale plan, allow one refresh and redispatch within
that step, then stop if inputs become stale again. Unknown failures or possible
partial edits require assessment before redispatch. Discovery and shared validation
remain with the orchestrator; standalone use follows the same rules in-process.

`analyze` and `approve` do not edit application files. `apply` is the only phase that edits application files,
and it runs one composite recipe for the plan. `verify` checks the exact built
artifact against the plan; source, POM, and dependency-resolution checks belong
to `analyze`, `apply`, or the orchestrator. Record any checks deferred to the
orchestrator explicitly.

An orchestrated worker receives a current approved plan and the complete apply
argument vector. Run one successful composite transformation, followed by focused
verification. A failed launch before transformation is not a recipe invocation.
Allow at most one corrected retry for a catalog/Maven/JDK prerequisite error when
the supplied context establishes the correction and the application diff proves
no files changed. Do not repeat an unchanged command. A stale plan returns to the
discovery owner for refresh; workers do not rediscover it. Unresolved findings,
scope conflicts, unknown failures, or possible partial edits stop for assessment. The orchestrator owns shared
compile, package, and runtime checks. A standalone invocation must perform its
own required validation or report the missing validation input explicitly.
