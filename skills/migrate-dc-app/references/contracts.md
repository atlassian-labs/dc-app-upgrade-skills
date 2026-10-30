# Capability and report contracts

Leaf skills remain independently usable. The orchestrator provides context to
make them efficient, but a leaf skill must discover missing evidence when it
is invoked directly. The contract is deliberately small so a fresh worker can
execute one migration without carrying the full orchestration context.

## Worker input

Pass a worker a bounded packet containing:

- the migration goal (for example Platform 9), plus exact product/version and
  runtime Java only when required by this invocation's capability/checks;
- the canonical absolute workspace path, selected module(s) and their packaging
  shape, and, for plugin work, packaging owner and actual embedded module paths;
- the approved capability name and its entrypoint path;
- for deterministic helpers, the complete invocation as an argument array, including
  the selected skill's resolved inputs and output paths, plus any required
  environment overrides; do not make the
  worker reconstruct flags from prose;
- the specific preflight evidence and applicability status;
- allowed files, protected paths, focused checks, and validation restrictions;
  shared build/artifact checks remain orchestrator-owned and cannot be reassigned
  by the packet;
- optional reusable evidence: command, target/profile, relevant input fingerprints,
  and absolute artifact paths; identify comparison baselines separately from
  current validation results;
- applicable repository instructions and the requirement to use fresh context;
- the state/result path and the required report adapter fields;
- the instruction to make no commits, pushes, PRs, or edits outside scope.

Do not pass the whole migration catalog, the user's full conversation, prior
worker transcripts, or unrelated findings. A worker may read the selected
skill's directly linked references and checked-in helper scripts as required
by that skill.

Before dispatch, resolve module, entrypoint, helper, state/result, and output
paths to absolute paths, resolving relative skill references from their containing
file and application paths from the approved workspace. Resolve existing symlinks
and check write targets against the allowed scope. Use absolute module arguments
where supported. If a helper requires a relative module argument, derive it from
the approved absolute module and invoke it from the verified workspace; confirm
that it resolves to that module. Keep output redirections absolute and report
`changed_files` as workspace-relative paths.

Follow the selected skill's execution unit, prerequisite checks, and handoff
requirements. Preserve its approved plan, reusable evidence, invocation arguments
and environment in step state; pass them unchanged to the worker. Run any
skill-required readiness check before dispatch. Changed prerequisites return to
the discovery owner for reassessment within the approved scope.

Dispatch only approved migration work. The worker may recheck its selected
signal after preceding edits; catalog discovery, assessment-only work, and
read-only audits belong to the orchestrator. Caller validation restrictions override
example commands. Workers return questions and blockers to the orchestrator,
which owns user interaction. Route leaf-requested temporary summaries to the
caller-approved evidence directory instead of creating unrelated project files.

## `assess` result

Represent assessment as an object with these fields (Markdown is acceptable
for a human report, but persisted state should be machine-readable):

```json
{
  "capability": "jackson-3-migrate",
  "status": "applicable",
  "evidence": ["plugin/pom.xml: dependency com.fasterxml.jackson.core:jackson-databind"],
  "scope": ["plugin"],
  "dependencies": [],
  "validation": ["mvn --batch-mode --no-transfer-progress -pl plugin compile"],
  "risks": []
}
```

`status` is one of `applicable`, `already-compatible`, `not-applicable`,
`uncertain`, `unsupported`, `deferred`, or `blocked`. `deferred` is for a
caller-delegated or optional validation check, not an unassessed migration.
`uncertain` means evidence or a
required prerequisite is incomplete; it is not permission to edit. `blocked`
must explain what is missing. `dependencies` names only real prerequisites,
not merely a preferred order. Keep migration disposition separate from runtime
verification: an evidence-backed `already-compatible` no-edit decision does not
prove runtime compatibility. Record caller-delegated runtime checks as `deferred`
with the owner and reason; they do not block handoff by themselves, but must not be
reported as passed.

## `apply` and `verify` result

An applied step returns the assessment plus:

```json
{
  "changed_files": ["plugin/src/main/java/example/Mapper.java"],
  "migrated_apis": ["com.fasterxml.jackson -> tools.jackson"],
  "skipped_candidates": [],
  "validation": ["compile passed", "residual Jackson 2 scan clean"],
  "blockers": []
}
```

Keep capability-specific result details in step state and adapt them using the
selected skill's reporting instructions. Report completed changes from execution
evidence; planned operations are not applied changes.

`changed_files` must contain workspace-relative source/build paths under the
approved modules/actual embedded scope; parent files need edit authorization.
Exclude generated reports, dependency
caches, `node_modules`, and files outside the application scope. A worker must
re-open changed files and check the diff before returning. `verify` must say
whether the original signal is gone, whether validation actually ran, and
which limitations remain.

Record deferred checks in step/final state with the check name, owner, reason,
and whether it remains required later. Do not add the absence of an explicitly
delegated or optional check to `blockers`. Failures of required checks and
confirmed unresolved incompatibilities remain blockers.

## Durable step state

Persist these fields alongside the result for every step. They are required
for automatic resume; if an older or incomplete record lacks them, reassess
the working tree instead of assuming the step is complete.

```json
{
  "state_version": 1,
  "capability": "jackson-3-migrate",
  "capability_fingerprint": "sha256 of the skill and referenced migration assets",
  "target": {"product": "jira", "version": "caller-resolved exact version"},
  "scope": ["plugin"],
  "phase": "applied",
  "verification": "passed",
  "review": "pending",
  "before": {"plugin/pom.xml": "sha256 before this step"},
  "after": {"plugin/pom.xml": "sha256 after this step"},
  "result": {}
}
```

Use actual digests, not the illustrative strings above. Include relevant
assessment inputs and all changed files; represent a nonexistent file with
`null`. Keep original file contents or an incremental patch in the state
directory to reconstruct the step's diff without a commit. Exclude generated
dependency/build directories from capability fingerprints. `phase` is
`pending`, `applying`, `applied`, `skipped`, or `blocked`; `verification` is
`pending`, `passed`, `deferred`, or `failed`; `review` is `pending`, `accepted`,
`changes-requested`, or `not-required` (explicit bulk authorization).

Record pre-existing user changes and the approved plan in `preflight.json`.
Include the module inventory, per-module outcomes, packaging relationships, and
target resolution. Library-only steps record the platform goal without unused
product/version fields; scope names the assessed/edited modules.
Never mark a step verified merely because its changes were applied. Before a
resume skips an accepted step, verify its recorded digests and reassess its
signal. Never reset or overwrite user edits to restore a stored fingerprint.

## Consolidated report adapter

The existing callers consume exactly one JSON object with these six required
array fields, each containing strings:

```json
{
  "selected_skills": [],
  "skipped_skills": [],
  "changed_files": [],
  "migrated_apis": [],
  "skipped_candidates": [],
  "blockers": []
}
```

Populate it as follows:

- `selected_skills`: exact frontmatter names of selected discovery skills and
  workers, in execution order;
- `skipped_skills`: every unselected capability in the authoritative roster,
  formatted as `<exact-name>: <reason>`; include `deferred` and the reason for
  an audit skill whose execution the caller delegates or makes optional;
- `changed_files`: all application files changed by this run, including partial
  changes from blocked steps, with duplicates removed; exclude pre-existing
  user changes unless a migration also changed that file;
- `migrated_apis`: concise descriptions of completed changes;
- `skipped_candidates`: unsupported, uncertain, protected, already-compatible,
  or otherwise intentionally untouched candidates with reasons. Also summarize
  deferred validation here so the six-array report exposes its limitations;
- `blockers`: unresolved selected migration, required-current-validation, target,
  approval, or scope issues. Exclude the absence of caller-delegated or optional
  checks and findings without a capability unless they block selected work or
  required validation. An empty array means no blocking issue was observed in this phase,
  not that runtime validation was performed.

Keep all six fields as string arrays. Qualify differing skip/blocker outcomes by
module; selection for one module does not settle another's applicability.
Completion requires assessment of the full requested scope.

Keep richer evidence, diffs, command output, and resume metadata in the
preflight and step state files. Do not make the six-array adapter depend on
prose in a worker's final response.
