# Developer guides

One guide per migration skill, written for developers. The skills in
[`../skills`](../skills) are instructions for AI agents; these pages explain the
same migrations so you can do them by hand or understand what a skill does.

Each guide covers how to tell whether your code is affected, what to prepare,
the migration steps with before and after examples, how to verify the result,
common failures, and how the matching skill works.

## Start here

| Guide | What it covers |
|---|---|
| [`migrate-dc-app`](migrate-dc-app.md) | Orchestrates a Data Center app migration: assesses your modules, proposes the applicable skills and applies approved changes in dependency order. |

## Java and platform upgrades

| Guide | What it covers |
|---|---|
| [`java-25-readiness`](java-25-readiness.md) | Java 25 readiness blockers in plugins and libraries. |
| [`spring7-jakarta11-migrate`](spring7-jakarta11-migrate.md) | Spring 5/6 and `javax` to Spring 7 and Jakarta EE 11. |
| [`httpclient-to-5-migrate`](httpclient-to-5-migrate.md) | HttpClient 3 and 4 to HttpClient 5. |
| [`jackson-3-migrate`](jackson-3-migrate.md) | Jackson 2 to Jackson 3. |
| [`commons-lang-migrate`](commons-lang-migrate.md) | Move off the repackaged `gzipfilter` Commons Lang namespace. |

## Platform APIs and packaging

| Guide | What it covers |
|---|---|
| [`analytics-api-migrate`](analytics-api-migrate.md) | Move off the analytics API, which Platform 9 holds internal. |
| [`crowd-internal-api-migrate`](crowd-internal-api-migrate.md) | Move off internal Crowd packages onto the public Crowd API. |
| [`serviceloader-bootdelegation-readiness`](serviceloader-bootdelegation-readiness.md) | Assess JAXP, JAXB and `ServiceLoader` discovery that relies on boot delegation. |
| [`check-public-api-readiness`](check-public-api-readiness.md) | Check imported packages against the target product's public API. |
| [`bundle-platform-dependencies`](bundle-platform-dependencies.md) | Discover and bundle platform libraries your app uses but does not ship. |
| [`transformerless-plugin-conversion`](transformerless-plugin-conversion.md) | Convert a plugin that needs AMPS runtime transformation into a transformerless OSGi plugin. |

## Frontend

| Guide | What it covers |
|---|---|
| [`react19-upgrade`](react19-upgrade.md) | Upgrade to React 19. |
| [`typescript-6-upgrade`](typescript-6-upgrade.md) | Migrate TypeScript 5.x projects to TypeScript 6. |
| [`upgrade-to-jquery-4`](upgrade-to-jquery-4.md) | Detect and migrate jQuery 4 incompatibilities. |
| [`atlaskit-upgrade`](atlaskit-upgrade.md) | Upgrade `@atlaskit` packages and migrate legacy buttons. |
| [`compiled-react-setup`](compiled-react-setup.md) | Verify and fix `@compiled/react` bundler and Babel setup. |
