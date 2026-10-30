# Apply and verify platform dependency bundling

Read this after discovery identifies supported operations, or when executing a
caller-supplied approved plan. Use the original plan and resolved invocation
context; do not repeat discovery in an application worker. If prerequisites are
missing or invalidated, return them to the discovery owner. In standalone use,
this skill owns discovery and required validation.

Resolve the launcher from the skill directory. It owns the analyzer and composite
OpenRewrite recipe; do not reproduce its Maven commands or hand-edit POMs when
it reports a supported transformation failure. Read [cli.md](cli.md) for the
check, apply, verify, and bounded recovery contract before execution.

1. **Review and authorize.** Review the plan's exact modules, dependencies,
   versions, exclusions, embedding changes, evidence, and unresolved findings.
   Present the full discovery results. If the user approves a subset, derive its
   plan with `approve` as described in [cli.md](cli.md); retain the original
   discovery plan and report declined families as remaining migration work.
   Do not apply an unresolved or stale approved plan. In an orchestrated run, the
   orchestrator supplies the approved plan and performs discovery in its own
   context. Run `check` immediately before application or worker dispatch; refresh
   stale discovery within the approved scope as described in [cli.md](cli.md).
2. **Apply the group.** Invoke the launcher with the approved plan and selected
   families recorded in that plan. It runs one configurable composite OpenRewrite
   recipe for the group, preserving unrelated POM content and making no Java source edits.
   A later full analysis should plan no further edits for the approved work;
   declined families may still require changes. This does not require worker-side
   rediscovery. Never split a grouped plan into one hand-edited invocation per library. Carry the resolved
   Maven arguments and Java environment into every phase. The bounded pre-transformation
   recovery rules in [cli.md](cli.md) apply to launcher failures.
3. **Verify.** Reopen the changed POMs, inspect the application-only diff, and
   check residual source/configuration signals for the selected families.
   `REMOVE_IMPORT_EXCLUSION` removes a negated `!package` selector. A positive
   `package.*;resolution:=optional` clause is an optional import, not an exclusion;
   its presence is not a failed removal and does not prove that embedded classes
   are missing. Preserve it unless an explicit supported operation changes it.
   Use artifact verification to check actual containment and mandatory imports.
   In standalone use, resolve and run the build command from caller context or the
   project's build instructions and any configured non-mutating POM format check
   (for example `sortpom:verify`): the recipe keeps scope groups and Maven's
   dependency element order, not every sorter configuration. Then run the
   launcher's `verify` on the produced artifact for contained classes and mandatory OSGi imports against the target
   export evidence described in [exports.md](exports.md). A pre-migration build
   is not required when target exports are available. In delegated
   use, the worker performs focused source/POM checks and reports shared validation
   as deferred; the orchestrator owns the clean build and launcher `verify`.
   A successful POM edit or package build alone does not establish that a
   dependency shipped or that the plugin resolves. If required standalone
   validation inputs are unavailable, report the missing inputs and unrun checks;
   do not claim the migration is verified.

## Delegation and shared checkpoints

In an orchestrated run, the approved group is one implementation step, with one
fresh worker when delegation is enabled. Do not create one worker per library
family. With delegation disabled, execute the same workflow in-process. Standalone
use requires no worker or orchestrator; the invoking agent owns all phases.

Before dispatch, the discovery owner records the original `analyze` argument
array, approved plan, target Java, profiles, snapshot path/digest and reusable
evidence. Run `check` with the resolved arguments and environment that application
will use; require exit zero and a current `ready` result. Hand the worker:

- the approved plan and readiness result paths;
- the complete `apply` argument array, preserving checked arguments and changing
  only the subcommand;
- an explicit environment map including `JAVA_HOME` and `PATH`, with the resolved
  Maven executable in the argument array;
- absolute launcher output and worker result paths, allowed files and validation
  restrictions;
- this execution reference and [cli.md](cli.md), with discovery refresh and shared
  build/artifact validation explicitly assigned to the orchestrator.

The worker applies one successful composite transformation, performs focused
source/POM checks and returns the result. It does not rediscover the catalog or
run shared builds. The discovery owner handles stale plans and bounded redispatch
under [cli.md](cli.md#refresh-and-recovery).

The orchestrator must load [exports.md](exports.md) when planning the shared
artifact checkpoint, retain its resolved verifier invocation and evidence paths,
and run it against the exact plugin JAR after the shared clean build. Refresh
exact-target exports when the distribution is available as described there,
including verification of app-used packages left to the product. Record result
paths and provenance; keep the step unverified until its required checks pass.
Caller-delegated checks remain explicitly deferred. In standalone use, perform
these checks directly under the caller's restrictions.

## Scope and reporting

Scope source and build-file edits to the selected plugin and caller-declared
embedded modules. Identify dependency-declaring modules separately from the
module that packages the plugin. Preserve existing versions where policy
allows; add a version only when the plan's family rule requires one. Preserve
unrelated dependency declarations, exclusions, profiles, and OSGi clauses.

When a family is already compatible, report it without editing. Skip an entire
family only when it has no supported operations or the caller excludes it.
Unsupported package evidence in `dependencyEvidence` is a package-level skipped
candidate; retain supported operations for that family. Entries in `unresolved`
block application and must not be removed by silently narrowing the family scope.
A launcher failure remaining after the permitted correction or a confirmed
residual selected signal is a blocker.

Standalone results report the selected families, changed files, unsupported
candidates, checks performed, deferred checks, and unresolved blockers. No
orchestrator installation is required. When delegating, use exactly one worker
for the group. In an orchestrated run, adapt per-family results to the caller's
report contract. Keep one step-level `changed_files` set and composite result, with each
family's status, evidence, skipped candidates and blockers in detailed state.
Add `bundle-platform-dependencies` to `selected_skills` once the
group is approved; do not add the retired per-family names.

When adapting the Java output to the six-array report, use
`bundle-result.changedFiles` for `changed_files` and `familyOutcomes` for family
statuses. Report completed operations in `migrated_apis` only after a successful
apply, using the plan's `operations` to explain them. Planned files are not changed
files. On failure before transformation, selected families and their operations
remain pending; report the launch error once as the blocker. A dependency marked
missing in discovery but covered by `ADD_DEPENDENCY` is a planned addition, not a
skipped or uncertain candidate (including Jaxen and `rome-utils`). Do not infer
unsupported status from pre-transformation dependency absence.
Map unsupported package evidence and already-compatible or intentionally
untouched families to `skipped_candidates`, preserving the family and reason.
Map every entry in `unresolved` to `blockers`; the launcher will not apply
such a plan. Unsupported non-blocking package findings are recorded separately
in `dependencyEvidence`. Artifact verifier `blockers` and
`requirementsToReview` are blockers when artifact validation is required.
Keep snapshot approximation or missing target evidence distinct from a confirmed
packaging defect. Do not report provisional evidence as completed verification.
Record checks that the worker did not run, such as the shared compile, package,
or runtime checkpoint, as deferred validation in `skipped_candidates` with its
owner and reason. Keep the grouped skill name in `selected_skills` and do not
recreate one report entry per library.

Do not commit, push, open a pull request, or modify files outside the approved
scope.
