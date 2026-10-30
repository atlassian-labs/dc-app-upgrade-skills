# Orchestrator workflow

Use this workflow directly or through an automated caller. Resolve paths,
permissions, and repository instructions from caller context; discover prerequisites
from selected source. Follow [scope.md](scope.md) for module and target rules.

## Invocation context

Resolve these inputs before preflight:

| Input | Requirement |
|---|---|
| `target.platform` | Platform goal from the request or authoritative context; may accompany a product target. |
| `target.product` | Product for product-dependent work: `jira`, `confluence`, or an explicitly selected `refapp` host. |
| `target.version` | Exact requested product build when needed, not the Platform/BOM version. Library-only work may omit product/version. |
| `target.java` | Target runtime Java major version when required by a selected capability or validation check; resolve from caller context or the selected product runtime, independently of the tooling JVM. |
| `scope.module` | The requested plugin, library, frontend module, or component/reactor root. Inventory its requested child modules before capability selection. |
| `scope.embedded_modules` | Sibling modules whose classes are embedded or shaded into that artifact. Discover them from authoritative build configuration when the caller has not supplied scope; include them in the proposed plan. An explicit caller scope remains binding. |
| `capabilities` | Optional allowlist. If present, it is authoritative; never select a capability outside it. The orchestrator and generic `migration-worker` are infrastructure, not migration candidates. |
| `state_dir` | Durable writable directory outside build output. Use the caller's directory, defaulting to workspace-level `.migration-state/`; never place it under `target/` or another build-cleaned directory. |
| `validation` | Caller-provided command and product/profile restrictions. Shared build/artifact checks belong to the orchestrator; runtime and other checks may be delegated or optional. Leaf skills supply acceptance criteria within those boundaries. |
| `interaction` | `guided` by default, or explicit `non-interactive`. |
| `execution` | `stepwise` by default, or explicit `bulk`. |
| `delegation` | `preferred` by default; `required` uses workers for migration edits; `disabled` executes in-process without subagents. Discovery and shared validation stay with the orchestrator. |
| `plan_approval` | Required in guided mode; in automation it must be an explicit `authorized` value. |
| `on_blocker` | `ask` in guided mode; automated callers should use `stop`. |
| `git_actions` | `none` by default. Any commit, push, or PR operation requires separate explicit authorization. |

After scope/goal discovery, resolve missing required targets under `on_blocker`:
ask in guided mode, stop in non-interactive mode. Continue read-only source
discovery, recording unavailable distribution checks without claiming target
compatibility. Discover validation commands from the project.

When `delegation: required`, stop with a blocker if the worker or delegation
tool is unavailable. Reading worker instructions is not a worker invocation.

## Phase 1: preflight

Preflight is read-only with respect to application source and build files. It
may run the project's existing scanners, dependency inspection, or a build
when a selected capability requires an artifact; put generated evidence in the
state directory or an existing generated-output directory and list it in the
report. Preserve unrelated user changes.

1. Record the migration goal, full requested module inventory, packaging owners
   and real embedding relationships, per-owner targets, capability allowlist,
   repository state, and validation limits in `state_dir/preflight.json`.
2. Enumerate installed leaf skill and specialized worker entrypoints, excluding
   the orchestrator and generic `migration-worker`. Read frontmatter
   first; load instruction bodies only for candidates selected by evidence.
   The orchestrator performs discovery, assessment, and read-only audits itself,
   including when delegation is required. Do not dispatch assessment-only workers.
   Assessment executes only the discovery portion of a leaf skill, never its
   apply phase before plan approval. Reuse scanner output in the apply phase
   if its source inputs have not changed.
3. Inspect application source, build files, descriptors, frontend source, and
   built artifacts as appropriate in every requested module, including independent
   libraries, plus actual in-scope embedded modules. A signal in a
   comment, test-only source, diagnostic string, or protected expectation is
   not by itself an application migration candidate.
4. For every capability in the authoritative roster, record per-module applicability
   and an overall disposition as one of:
   `applicable`, `already-compatible`, `not-applicable`, `uncertain`,
   `unsupported`, `deferred`, or `blocked`. Use `deferred` only for validation
   the caller delegates or makes optional, not for unresolved source work.
   Include concrete paths, declarations, scanner output, and version evidence.
   Resolve known stop conditions from the skill before dispatch; do not send a
   worker merely to rediscover an unsupported candidate.
5. Follow each applicable skill's execution unit and linked execution instructions
   when planning its handoff and validation checkpoints. Load those details only
   after applicability is established, or when its assessment instructions require
   them to resolve uncertainty. Do not assume every skill operates per file or
   split a skill-defined group into separate workers. Discovery and user approval
   precede any implementation dispatch.
6. Order applicable work using [routing.md](routing.md), retaining only real
   prerequisites. Record independent steps separately even when they happen to
   touch similar files.
7. Produce a user-facing preflight report with the target, evidence, proposed
   order, per-module scope/owners, dependencies, validation commands, unresolved findings,
   and the expected review checkpoints.

During this inspection, identify configured formatting and static-analysis
checks that cover the source being migrated. Inspect the module and parent
build configuration, project scripts, and CI commands; record the exact
non-mutating check command, its scope, and whether the shared build already
runs it. Examples include Spotless and Checkstyle Maven goals, other configured
Maven formatter or analysis check goals, and frontend `format:check` or `lint`
scripts in the selected or an in-scope embedded module. Do not infer tools from
dependency names alone or guess commands. Inspect frontend script definitions
before running them, and run only scripts whose commands are demonstrably
check-only (no `--fix`, `--write`, apply, or equivalent mutating option). Do not
run formatter apply/fix goals. Where practical, capture the check's
pre-migration result to distinguish existing failures from regressions; a
failing required check remains visible either way.

In guided mode, ask the user to approve the plan, adjust its scope, choose
stepwise or bulk execution, or stop. Approval covers only the named target,
capabilities, modules, any explicit file restrictions, and validation scope.
It includes missed call sites covered by those same boundaries, not just the
initial list of matches. It does not authorize git writes, additional
capabilities, expanded scope, or unresolved design decisions.

### Validation ownership

Record each check's owner and requirement during preflight:

- **Required now:** run it before declaring this phase successful. Missing
  prerequisites or a failed check are blockers.
- **Delegated:** the caller or another phase owns it. Record `deferred`, its
  owner, and the reason it was not run. Its absence is not a migration blocker.
- **Optional:** run it when permitted inputs are available; otherwise record
  `deferred` with the missing input. Do not claim a clean result.

In bulk mode, the orchestrator owns shared compile/test/package and artifact
checks at the end of each related migration group and after corrections that
invalidate them. Workers perform focused source/configuration checks only.
Task packets supply scope, evidence, and validation restrictions; they must not
reassign shared validation to workers. Leaf verification requirements become
checkpoint acceptance criteria, not instructions for another worker build.
A transformation's own helper-required build remains allowed. If a worker needs
an additional build to make a decision, return the specific need to the
orchestrator rather than starting shared validation. In stepwise mode the
orchestrator finishes these checks before offering a verified step for review.
With delegation disabled, it applies the migration and runs the same checkpoints
itself. Standalone leaf skills retain their own validation sequence.

Use a clean build at each shared checkpoint (for Maven, scoped reactor
`clean package` with tests enabled and required upstream modules included).
This prevents stale extracted dependencies or module descriptors from influencing
later compilation. Include required upstream modules' generated output in the
preflight validation scope; this does not authorize source edits in those modules.
Inspect the project's clean configuration so it removes only generated output
within that approved build scope. Preserve comparison artifacts,
scanner reports, and trackers in the state directory before cleaning; update
their recorded paths and regenerate build prerequisites as needed. If caller restrictions prevent a clean checkpoint,
report the conflict rather than deleting files manually or skipping tests.

Checkpoint builds may pin the target JDK or tooling explicitly. Before handoff,
also run the project's own validation command once as a developer would, in
the default environment, without overriding `JAVA_HOME`, toolchains or
`PATH`. A build that is green only with a forced JDK is not verified: route the
JDK selection to `java-25-readiness` or report it.

At each shared checkpoint, run applicable configured formatting/static-analysis
checks, including those unbound to the build lifecycle. Build/CI-required checks
are required validation; classify others during preflight. Reuse checks already
run by the shared build. Otherwise invoke specific check goals (`spotless:check`,
`checkstyle:check` when configured), rather than `verify` or broader lifecycles
that could start product/integration tests. Use inspected check-only frontend
scripts for in-scope modules. Never validate with mutating goals such as
`spotless:apply`; if no clear non-mutating command exists, record the limitation
without claiming validation. Retain commands/results as checkpoint evidence.
For failures, use the bounded correction flow only within authorized module/source
scope; unrelated or pre-existing failures remain blockers, not scope expansion.

Reuse one checkpoint build for all applicable artifact checks. Do not run
separate compile, test, and package commands when the chosen lifecycle already
covers them. Capture any required baseline artifact once before the group and
retain it outside build output. Reuse a baseline only when its provenance matches
the pre-group inputs; otherwise produce it with the same clean build procedure
or record that no valid baseline is available. Record the command, target/profile, relevant
input fingerprints (including parent/module POMs, source, and build configuration),
and artifact path with reusable evidence; reuse only while those inputs match.
Resolve each required main plugin or library artifact from build configuration;
run bundle/import checks only for actual packaging owners, not plain JARs.
Never use a `target/*.jar` glob that can include test/source classifiers. Pass its absolute
path for baseline and checkpoint inspection, and resolve ambiguity before use.
Earlier artifacts are comparison baselines, not validation of later edits. Evidence is an optional optimization, never a prerequisite for
a standalone capability. Re-run dependency/effective-POM inspection only when
changed configuration or an unresolved question makes it necessary.

At each shared checkpoint, run the selected skills' required artifact checks and
evidence-refresh procedures against the current build. Record resolved commands,
input provenance, results and validation ownership in step state. A worker's
focused checks do not replace these acceptance criteria. Keep affected steps
unverified until required checkpoint checks pass; report unavailable evidence
according to the recorded validation ownership.
If a checkpoint fails, inspect the approved owning skill for a supported remedy
before declaring a terminal blocker. Dispatch that correction within the existing
scope and bounded correction budget, then rerun the affected checks. This allows
a changed-input retry, never an unchanged retry or suppressed failed check.
An unrelated failure or unsupported remedy remains a blocker.

Caller-prohibited runtime checks are not failed checks. Keep validation limits
visible in the final report without blocking handoff to their owner. Never
relabel a failed required check as optional or deferred to obtain success.
If an optional audit identifies a confirmed incompatibility within the requested
scope, assess and address it or report a blocker; optional execution does not
make a discovered defect safe. Missing information needed to choose a correct
source transformation remains a blocker regardless of audit ownership.

## Phase 2: execution

Before each step, write its approved input and status to
`state_dir/steps/<ordinal>-<capability>.json`. A resumable run must skip only a
step whose state records successful apply and verify results for the same
target, scope, and capability content fingerprint, and whose recorded output
files still match their content fingerprints. Record the initial working-tree
changes, the before/after fingerprints of each step's files, its execution
status, and its user review status. Re-assess changed inputs before resuming;
never blindly replay a partially applied step. An applied but unreviewed step
resumes at its review checkpoint in guided mode, not at the next migration.

Dispatch each approved migration implementation to a fresh worker when delegation
is required, including residual corrections; otherwise dispatch substantial
migration steps when delegation is preferred and the host supports subagents.
Use the execution unit and readiness checks defined by the selected skill.
Pass its required handoff context and references as described in
[contracts.md](contracts.md); keep discovery refresh and shared validation in the
orchestrator.
When delegation is disabled, execute the approved steps in-process without workers. Discovery-only and audit-only
capabilities run in the orchestrator context. On Rovo, use
`invoke_subagents` (or `invoke_subagent` on older runtimes) with exactly one
`migration-worker` per call, defined in
[migration-worker.md](../../../subagents/migration-worker.md), registered by
the host in its subagent directory before startup. The worker receives only
the selected leaf skill (or specialized worker entrypoint), its needed
references, the approved scope, relevant preflight evidence, validation rules,
applicable repository instructions, and the contract in [contracts.md](contracts.md).
Use a fresh context rather than forking the full conversation. Do not pass the complete
conversation, the complete catalog, or prior worker logs. The worker writes a
compact result to its step state and returns the same result in its response.
Wait for each worker, review its result and diff, and persist its invocation
identifier when exposed by the host before starting the next. Keep detailed
implementation output in workers and retain compact results in the orchestrator.
Resolve task paths as specified in the worker contract before dispatch; worker
tools do not inherit the orchestrator's working directory.
For small deterministic work or hosts without subagents, in-process execution
is allowed only when delegation is not required.

When a skill reports stale prerequisites or a recoverable failure, follow its
refresh and bounded recovery instructions. Reassess changed evidence and compare
resulting operations with the approved scope before redispatch. Scope expansion
or an unresolved design decision requires a plan change under `on_blocker`;
unknown failures or possible partial edits require assessment before retrying.

Each step follows:

1. `assess`: confirm the signal still applies after earlier edits. If it is
   already compatible, record that result and do not edit.
2. `apply`: make supported changes only within approved scope under
   [scope.md](scope.md); do not widen it for unrelated build failures.
3. `verify`: run only the checks assigned to this worker, inspect the application-only
   diff, and rescan the changed signal. Record changed files, migrated APIs,
   skipped candidates, validation evidence, deferred checkpoint checks, and blockers.
4. Persist the result atomically before moving to the next step. Do not rely
   on a final chat response as durable state.

In guided stepwise mode, show the incremental diff summary and verification
result, then ask whether to accept and continue, request a correction, switch
the remaining approved steps to bulk, or stop. Capture the diff relative to
the start of this step, including new files, so previous migrations and the
developer's existing changes are not attributed to it. After an accepted step,
offer a PR if it is independently valid. When it depends on other steps, explain
the dependency and suggest a combined PR or explicitly requested stacked PRs.
Do not create commits just to establish checkpoints.

A failed or uncertain step is a
blocker; do not silently continue past a required dependency. Do not repeat a
deterministically failing command. Any retry for an infrastructure stall must
be bounded and recorded.

A full build may be blocked by another known, approved migration still pending.
If the failure is attributable to that pending work and this step's focused
checks pass, record full validation as deferred to the dependency group's end;
do not call the step independently verified or PR-ready. An unexpected failure
or a failed focused check is still a blocker. Run the project-level checks once
the related steps complete, and record any caller-prohibited checks as not run.

In bulk mode, execute only the approved ordered plan. `non-interactive` means
the caller has already supplied the plan authorization; it does not authorize
new capabilities, scope, dependencies, or guessed target versions. On an
unexpected failure, a protected-file conflict, a required decision, or an
required additional capability outside the approved plan, record a blocker and obey
`on_blocker`. A missed call site within an approved capability and scope is
covered by the residual correction loop below.

## Phase 3: residual verification and completion

After the last step, scan every requested module against the roster
for residuals and assesses each match using its owning skill before deciding
it needs a change. Dispatch workers only for confirmed, approved corrections.
For example, a zero-argument JAXP factory call is an inspection signal; a no-change
assessment requires evidence that the default provider behavior is acceptable. For
service discovery, compilation, imports, or an explicit loader do not prove runtime
compatibility; record target runtime checks separately as described in the
[`assess` result contract](contracts.md#assess-result). Record the evidence for
leaving a match unchanged rather than requiring an empty text search.

For confirmed missed work, allow at most two corrective rounds in the run:

1. Confirm the capability is already approved and the change stays within the
   same target, modules, explicit file restrictions, and supported behavior.
   Dependencies already prescribed by that approved skill are included only
   when build-file edits are in scope and no additional capability or design
   decision is required.
2. Run the owning skill sequentially on those candidates, update its step
   evidence, and perform focused verification. In guided stepwise mode, show
   the incremental correction at the normal review checkpoint. Bulk mode does
   not require another approval for these in-scope corrections.
3. Rescan the affected families and any families whose inputs changed. Rerun
   affected downstream checks and relevant module validation; earlier results
   do not verify changed inputs. Respect the recorded validation ownership.
   Stop
   with a blocker if a correction makes no progress, fails verification, or
   leaves confirmed actionable work after the second round. Do not repeat a
   deterministically failing command without a relevant correction.

A new capability, work outside approved boundaries, or an unresolved design
decision remains a plan change: ask in guided mode and stop with a blocker in
non-interactive mode. An available capability is not automatically an approved
one. Classify unsupported or uncertain signals honestly; do not invent a
transformation to make the report green.

Account for all requested modules and unresolved work before claiming completion.
Report “selected migrations verified” separately from “fully validated on the target
product”, which requires runtime evidence. Caller-deferred checks remain explicitly deferred at handoff.
When runtime checks are permitted, the minimum runtime evidence is one start of
each exact target product with the migrated plugins installed, plus the
project's smoke or integration tests against it, from a clean integration-test
working directory (a reused product home keeps previously installed plugins).
Compile and unit-test success alone does not show that the plugins resolve,
enable, or serve requests on the target.

On completion or any blocking exit, write `state_dir/final.json` and the
caller's required consolidated report. Include partially changed files even
when a worker failed, and distinguish applied, pending, deferred, and blocked
steps. Findings without an available capability belong in `skipped_candidates`,
not `blockers`, unless they prevent a selected migration or required check from
completing. Catalog coverage does not imply full target compatibility.
The final report must contain the six arrays in [contracts.md](contracts.md),
including empty arrays. In guided mode, after the user accepts the complete
result, suggest a PR when the changes form a coherent review unit. Keep git
actions as a separate authorization step.
