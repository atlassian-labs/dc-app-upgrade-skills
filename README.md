# dc-app-upgrade-skills

A collection of agent skills that help migrate and upgrade Atlassian Data Center
(DC) apps. Each skill packages step-by-step guidance an AI coding agent can follow
to perform a specific upgrade task safely and consistently.

## Available skills

| Skill | Description |
| --- | --- |
| [`jackson-3-migrate`](skills/jackson-3-migrate/SKILL.md) | Migrates a Java Maven module from Jackson 2 (`com.fasterxml.jackson`) to Jackson 3 (`tools.jackson`) for Platform 9. |

## Repository layout

```
skills/
  <skill-name>/
    SKILL.md    # skill definition and workflow
```

Each skill lives in its own directory under `skills/` and is defined by a
`SKILL.md` file containing YAML front matter (`name`, `description`) followed by
the workflow and reference material.

## Usage

These skills are designed to be discovered and activated by an AI coding agent.
Point your agent at this repository (or install the skills into your agent's skill
directory) and invoke a skill by name — for example, ask to "migrate to Jackson 3"
to trigger the `jackson-3-migrate` skill.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines
and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE.txt](LICENSE.txt) for
details.
