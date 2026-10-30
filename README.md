# dc-app-upgrade-skills

Agent skills that help migrate and upgrade Atlassian Data Center (DC) apps. Each
skill packages step-by-step guidance an AI coding agent can follow to perform one
upgrade task safely and consistently, and each has a developer guide so you can
do the same migration by hand.

[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE.txt)

> This is the initial version of the skills. More skills for Jira 12 and
> Confluence 11 will be published in the upcoming weeks.

## Quick start

1. **Install the skills** (see [Install](#install)).
2. Open your app repository in an AI coding agent.
3. Ask:

   > Migrate this app to Jira 12 / Confluence 11.

   Or name the orchestrator directly: *use the `migrate-dc-app` skill*.

The orchestrator resolves the exact target, inspects your app and presents an
evidence-backed plan before it edits anything. By default it pauses for your
review after each migration; you can approve bulk execution instead. Commits,
pushes and pull requests always need your separate permission. Individual skills
also work on their own, for example *"migrate to Jackson 3"*.

## Install

The skills follow the common `SKILL.md` format. Pick one option.

### Option 1: copy the catalog

Copy `skills/`, `subagents/` and `recipes/` from this repository into a folder
your agent reads, **keeping them as siblings**:

```
<agent-folder>/
  skills/        # one folder per skill
  subagents/     # migration-worker, used by the orchestrator
  recipes/       # OpenRewrite recipes
```

`.agents/` is a shared folder that many agents read skills from, including Rovo
Dev and Codex. It is not universal: Claude Code reads only `.claude/`. Use the
folder your agent documents:

| Agent | `<agent-folder>` in your app repository (or in `~` for all projects) |
|---|---|
| Rovo Dev | `.rovodev/` or `.agents/` |
| Codex | `.agents/` |
| Claude Code | `.claude/` |
| Other agents | The folder your agent documents for skills. Many also read `.agents/`; keep the three directories side by side |

The shared `.agents/` convention might cover `skills/` only. If you use it, make
sure your agent also finds the `migration-worker` subagent, or use the agent's own folder.

The sibling layout matters. `commons-lang-migrate` and `bundle-platform-dependencies`
run scripts that look for `recipes/openrewrite` next to `skills/`, and the
orchestrator expects the other skills and the `migration-worker` subagent to be
installed with it.

### Option 2: the `skills` CLI

This installs skill folders. It does not install `recipes/` or `subagents/`, so
use Option 1 or 3 for `migrate-dc-app`, `commons-lang-migrate` and
`bundle-platform-dependencies`. The remaining skills are self-contained.

For individual skills you can use the [`skills`](https://github.com/vercel-labs/skills) CLI:

```bash
npx skills add atlassian-labs/dc-app-upgrade-skills                      # install the skills
npx skills add atlassian-labs/dc-app-upgrade-skills --skill jackson-3-migrate -a claude-code
npx skills add atlassian-labs/dc-app-upgrade-skills -g                   # global instead of project-wide
npx skills update                                                        # update installed skills
```

### Option 3: the install script

`install.sh` does Option 1 for you. It copies `skills/`, `subagents/` and `recipes/`
side by side into an agent folder:

```bash
./install.sh /path/to/your-app    # into that project
./install.sh                      # globally, into your home directory
./install.sh --dry-run <dir>      # show what would be copied, change nothing
```

It installs into every folder that already exists among `.claude/`, `.rovodev/`
and `.rovo/` in the target (or in `~` when no target is given), and into `.agents/`
if none of them exist. Claude Code reads only `.claude/`, so create it first if you
use Claude Code. Re-running the script updates the catalog's own skills in place.
It leaves your other skills alone, and it does not delete skills that a newer
catalog has removed.

## Choose an AI model

The skills are multi-step, evidence-driven workflows: they read many files, run
builds and scanners, and verify their own work. Use a current, highly capable
coding model with strong tool use and a large context window. A weaker model is
more likely to skip verification steps, misread a build error or make an
unsafe edit. A stronger model reduces that risk but does not remove it.

Whatever you use:

- **Review every diff** before you accept it, and run your own tests.
- **Migrate on a branch** with a clean working tree so changes are easy to inspect or discard.
- **Check your organisation's policy:** the agent sends code and build output to its model provider.

## Skills

Each skill has a developer guide that explains the migration, how to check
whether you are affected, how to verify the result, and how the skill works.
Start with [`migrate-dc-app`](docs/migrate-dc-app.md); it routes to the others.

| # | Skill | Area |
|---|---|---|
| 1 | [`migrate-dc-app`](docs/migrate-dc-app.md) | Orchestrator |
| 2 | [`java-25-readiness`](docs/java-25-readiness.md) | Java and platform upgrades |
| 3 | [`spring7-jakarta11-migrate`](docs/spring7-jakarta11-migrate.md) | Java and platform upgrades |
| 4 | [`httpclient-to-5-migrate`](docs/httpclient-to-5-migrate.md) | Java and platform upgrades |
| 5 | [`jackson-3-migrate`](docs/jackson-3-migrate.md) | Java and platform upgrades |
| 6 | [`commons-lang-migrate`](docs/commons-lang-migrate.md) | Java and platform upgrades |
| 7 | [`analytics-api-migrate`](docs/analytics-api-migrate.md) | Platform APIs and packaging |
| 8 | [`crowd-internal-api-migrate`](docs/crowd-internal-api-migrate.md) | Platform APIs and packaging |
| 9 | [`serviceloader-bootdelegation-readiness`](docs/serviceloader-bootdelegation-readiness.md) | Platform APIs and packaging |
| 10 | [`check-public-api-readiness`](docs/check-public-api-readiness.md) | Platform APIs and packaging |
| 11 | [`bundle-platform-dependencies`](docs/bundle-platform-dependencies.md) | Platform APIs and packaging |
| 12 | [`transformerless-plugin-conversion`](docs/transformerless-plugin-conversion.md) | Platform APIs and packaging |
| 13 | [`react19-upgrade`](docs/react19-upgrade.md) | Frontend |
| 14 | [`typescript-6-upgrade`](docs/typescript-6-upgrade.md) | Frontend |
| 15 | [`upgrade-to-jquery-4`](docs/upgrade-to-jquery-4.md) | Frontend |
| 16 | [`atlaskit-upgrade`](docs/atlaskit-upgrade.md) | Frontend |
| 17 | [`compiled-react-setup`](docs/compiled-react-setup.md) | Frontend |

## Repository layout

```
skills/<skill-name>/SKILL.md   # skill definition for the agent (plus references and scripts)
subagents/                     # generic migration worker used by the orchestrator
recipes/openrewrite/           # deterministic OpenRewrite recipes used by some skills
docs/                          # developer guides, one per skill
```

## Limitations

- These skills assist a migration; they do not guarantee one. Treat every change
  as a proposal and verify it with your own build and tests.
- Coverage is partial. Skills only handle the findings they were built for, and
  report anything else for manual review.
- Skills were developed and validated with Rovo Dev. They use the common `SKILL.md`
  format, but behaviour in other agents can differ; for example, the orchestrator
  runs its steps in-process where the host has no subagent support.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines,
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations, and open an
issue to report a problem or propose a skill.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE.txt](LICENSE.txt).
