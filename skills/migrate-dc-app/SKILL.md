---
name: migrate-dc-app
description: Orchestrate a Data Center app or component migration to Platform 9 or an exact product target. Assess requested modules, propose applicable skills, and apply approved changes in dependency order.
---

# Migrate a Data Center app or component

Own the migration conversation and coordinate specialized capabilities. A known
single transformation can use its leaf directly.

Read progressively:

- [scope.md](references/scope.md) and [workflow.md](references/workflow.md) before
  resolving scope, targets, interaction, execution, and state.
- [routing.md](references/routing.md) during preflight for applicability and order.
- [contracts.md](references/contracts.md) for worker packets, resume state, and
  the existing six-array report adapter.

Resolve the requested modules and migration goal first. Product-independent
library work can target Platform 9; product-dependent checks require exact targets.
Assess every requested module, keeping source scope separate from plugin embedding.

The orchestrator owns read-only discovery, preflight, audits, and shared
build/artifact verification. Classify every available capability with evidence,
then present scope, order, dependencies, validation, and blockers before edits.
Load applicable leaves and required references progressively, preserving their
standalone behavior, execution units, readiness checks, and bounded recovery rules.
At each shared checkpoint, run configured non-mutating formatter and analyzer
checks, even when they are not bound to the main build lifecycle. Never use
mutating fix/apply goals as validation. Follow [workflow.md](references/workflow.md)
for check discovery, commands, and evidence requirements.

Include configured check-only formatters/analyzers at shared checkpoints, even
when unbound to the build lifecycle, per [workflow.md](references/workflow.md).

Execute approved steps sequentially. With delegation `preferred`, use fresh
workers for substantial steps when available; `required` needs a fresh worker for
each implementation/correction and blocks if unavailable; `disabled` runs in-process.
Keep durable plan, fingerprints, results, and review state outside build output.
Recheck each step's signal and follow the workflow's bounded residual correction
loop. New capabilities, scope expansion, and unresolved design decisions require
approval. Distinguish required checks from deferred/optional checks; never claim
unrun validation passed.

Default to guided, stepwise execution: obtain preflight approval and show each
incremental diff and verification result at its review checkpoint. Explicit bulk
execution follows the workflow contract. Automation requires bounded scope,
authorized plan execution, and `on_blocker: stop`; an environment variable alone
is not user authorization.

After an accepted, independently valid step, suggest a PR. Commits, pushes, and
PR operations require separate explicit authorization. Produce the six-array
result; callers must not need to parse worker logs or prose.
