---
skill: migrate-dc-app
skill-sha256: eab4715747d8
---

# Migrate a Data Center app to Platform 9

Moving an app or component to Platform 9, or to a newer product version, is
not one change. It is a set of separate migrations (Java, Spring, Jackson,
HttpClient, frontend libraries and more), and each one applies only if your
code shows the matching signal. This guide is the starting point: it shows how
to work out which migrations you need and in what order. The
[last section](#use-the-skill) describes the `migrate-dc-app` skill, which does
that assessment for you and applies the approved changes by running the other
skills in dependency order.

## Is my code affected?

Any app or component that moves to Platform 9 or to a newer product version is
in scope for the assessment. That does not mean every migration applies. A
framework or dependency being present is only a signal to inspect, not proof
that you have to migrate.

Assess every module you own, not only the plugin:

- A plugin request covers the plugin and the modules that are actually embedded
  or shaded into it. A Maven dependency alone does not prove embedding; check
  the descriptors and build configuration.
- A component or reactor request covers its Maven modules (recursively, under
  the active profiles) and any frontend workspaces you include.
- Independent libraries that no plugin ships are still in scope for source,
  API and dependency migrations. Bundling and transformerless conversion apply
  only to real plugin owners and their embedded modules.
- A parent POM does not tell you the packaging of its children. Check each
  module.

Signals that a comment, a test-only source, a diagnostic string or a protected
expectation mentions something are not application migration candidates by
themselves.

You can skip a migration when its signal is absent or when the code is already
compatible. Do not treat a failed or missing scanner as a clean result: that is
"uncertain", not "not applicable".

## Before you start

- **Goal and target.** Decide whether the goal is Platform 9 in general or a
  specific product build. Product-independent library and API work only needs
  Platform 9. Product-dependent checks (for example public API readiness) need
  an exact product (`jira` or `confluence`) and an exact version, not the
  Platform or BOM version. If you do not supply them, they are taken from your
  explicit request first, then from the effective AMPS configuration and active
  profiles. A generic AMPS setup names no product, and a configured baseline
  version is not the upgrade you asked for. Do not default to RefApp, and do
  not treat a RefApp result as proof for another product.
- **Runtime Java.** Resolve the target runtime Java separately when a check
  needs it. The compiler release is not evidence of the runtime.
- **Ownership.** Know which modules are plugins, which are embedded into them
  and which are plain libraries (see above).
- **Validation.** Find the commands your project already uses to build and test,
  and any formatter or static-analysis check goals (for example Spotless or
  Checkstyle check goals, or frontend `format:check` and `lint` scripts). Only
  check-only commands are used for validation; a goal that rewrites files
  (such as `spotless:apply`) is never validation.
- **A clean working tree is not required**, but you should know what you have
  already changed so it is not mixed up with migration edits.

## Migrate

### 1. Find which migrations apply

Inspect source, build files, descriptors and frontend sources in every
in-scope module, then match what you find against this table.

| If you find | You need | Applies to |
|---|---|---|
| A plugin descriptor or AMPS configuration missing `Atlassian-Plugin-Key` or `Spring-Context`, or legacy `<component>`, `<component-import>` or `<module-type>` declarations | [`transformerless-plugin-conversion`](transformerless-plugin-conversion.md) ([guide](transformerless-plugin-conversion.md)) | Plugins |
| Application Java importing or fully qualifying `com.atlassian.gzipfilter.org.apache.commons.lang` | [`commons-lang-migrate`](commons-lang-migrate.md) ([guide](commons-lang-migrate.md)) | Plugins, libraries |
| Supported platform library usage that must be bundled (the skill decides if it is product-specific) | [`bundle-platform-dependencies`](bundle-platform-dependencies.md) ([guide](bundle-platform-dependencies.md)) | Plugins |
| `javax.*` EE imports, versioned Spring XML XSDs, `default-autowire="autodetect"`, removed Spring 7 APIs such as `ListenableFuture`, or an incompatible existing `Import-Package` | [`spring7-jakarta11-migrate`](spring7-jakarta11-migrate.md) ([guide](spring7-jakarta11-migrate.md)) | Plugins, libraries |
| `com.fasterxml.jackson` dependencies or imports, or Jackson 2 provider descriptors | [`jackson-3-migrate`](jackson-3-migrate.md) ([guide](jackson-3-migrate.md)) | Plugins, libraries |
| `org.apache.commons.httpclient` or `org.apache.http` imports or dependencies | [`httpclient-to-5-migrate`](httpclient-to-5-migrate.md) ([guide](httpclient-to-5-migrate.md)) | Plugins, libraries |
| Old ByteBuddy, `java.security.manager=allow`, old Spotless or AMPS, an incompatible Kotlin or Scala toolchain, or a build failing with `class file has wrong version 69.0` or `UnsupportedClassVersionError` | [`java-25-readiness`](java-25-readiness.md) ([guide](java-25-readiness.md)) | Plugins, libraries |
| A direct JAXP or JAXB factory call, one-argument `ServiceLoader.load`, a shipped `META-INF/services` provider, or evidence of a library-owned discovery path | [`serviceloader-bootdelegation-readiness`](serviceloader-bootdelegation-readiness.md) ([guide](serviceloader-bootdelegation-readiness.md)) | Plugins, libraries |
| `com.atlassian.analytics.*`, `AnalyticsConfigService` or `AnalyticsUploadService` in application code | [`analytics-api-migrate`](analytics-api-migrate.md) ([guide](analytics-api-migrate.md)) | Plugins, libraries |
| Main Java importing internal `com.atlassian.crowd` packages (`common`, `crypto`, `dao`, `directory`, `model`, `embedded.spi`) | [`crowd-internal-api-migrate`](crowd-internal-api-migrate.md) ([guide](crowd-internal-api-migrate.md)) | Plugins, libraries |
| Frontend jQuery sources or dependencies (confirmed incompatible calls, or `jquery`, `@types/jquery` and `@atlassian/aui` not aligned with the target's jQuery major) | [`upgrade-to-jquery-4`](upgrade-to-jquery-4.md) ([guide](upgrade-to-jquery-4.md)) | Frontend modules |
| React sources or dependencies, with own-code findings or a React or peer mismatch against the target | [`react19-upgrade`](react19-upgrade.md) ([guide](react19-upgrade.md)) | Frontend modules |
| `@atlaskit/*` packages that are outdated, a legacy or deprecated `@atlaskit/button` import, or a missing or unregistered theme exclusion plugin | [`atlaskit-upgrade`](atlaskit-upgrade.md) ([guide](atlaskit-upgrade.md)) | Frontend modules |
| `@compiled/react` or `@atlaskit/*` with Compiled extraction, Babel scoping or CSS registration missing or incompatible | [`compiled-react-setup`](compiled-react-setup.md) ([guide](compiled-react-setup.md)) | Frontend modules |
| TypeScript APIs or configuration incompatible with TypeScript 6 | [`typescript-6-upgrade`](typescript-6-upgrade.md) ([guide](typescript-6-upgrade.md)) | Frontend modules |
| Imports to check against a new target product build, or imports not covered by the families above | [`check-public-api-readiness`](check-public-api-readiness.md) ([guide](check-public-api-readiness.md)) | Plugins |

Two findings have no migration skill today. Both are recorded as unsupported
rather than fixed for you:

- **JNDI locations.** Platform 9 only accepts `java:` (for example
  `java:comp/env/...`), `jdbc/` and `mail/` prefixes for names passed to
  `lookup`, `doLookup`, `lookupLink` and `rename`. Custom names and `ldap:` or
  `rmi:` URLs can raise `JndiIllegalArgumentException`. This applies only if
  your code configures a mail server or datasource location programmatically;
  apps that use the product's already-configured mail server or datasource are
  unaffected. The enforcement was observed on the mail server path, so verify
  datasource behaviour for your exact target. Do not invent a bundling or
  `Import-Package` fix, and do not replace a location without knowing its
  binding.
- **Version pins shadowing the platform BOM.** A project property or
  `dependencyManagement` entry pins an artifact that the imported platform BOM
  also manages (for example an OAuth2, SAL or Atlassian library left at its
  pre-Platform 9 version), so the BOM bump does not reach it. No skill handles
  this yet: the orchestrator reports an actionable pin as unsupported, with both
  versions. By hand, align the pin with the target BOM or remove it.

Availability depends on the exact target. A package can be public on one
product and internal on another, and a package that still resolves can be an
internal API. Run `check-public-api-readiness` against the target product's own
build, and do not reuse a clean result from another product or milestone.

### 2. Apply them in this order

Use this order unless you find a real dependency that changes it. Backend and
runtime work comes before frontend work.

1. `java-25-readiness`
2. `transformerless-plugin-conversion`
3. `commons-lang-migrate`
4. `bundle-platform-dependencies`
5. `spring7-jakarta11-migrate`
6. `jackson-3-migrate`
7. `httpclient-to-5-migrate`
8. `serviceloader-bootdelegation-readiness`
9. `analytics-api-migrate`
10. `crowd-internal-api-migrate`
11. `upgrade-to-jquery-4`
12. `react19-upgrade`
13. `atlaskit-upgrade`
14. `compiled-react-setup`
15. `typescript-6-upgrade`
16. `check-public-api-readiness` (final audit)

The list is an ordering policy, not a checklist: do only the migrations whose
signal you found in step 1.

- **A BOM bump can leave POMs unresolvable.** For example, once the target BOM
  stops managing Jackson 2, a versionless Jackson 2 dependency makes the POM
  invalid. Bundling and transformerless conversion need a module that resolves
  and builds, so in that case do the POM-swapping migrations
  (`spring7-jakarta11-migrate`, `jackson-3-migrate`,
  `httpclient-to-5-migrate`) first.
- **Recheck after each migration.** One migration can supply or remove
  another's dependency (for example, ROME can supply JDOM 2), so an item that
  looked applicable may no longer be.
- **Treat a `class file has wrong version 69.0` or
  `UnsupportedClassVersionError` as a JDK problem.** It means a JDK below 25
  was used. It is not a finding of the migration whose build hit it; handle it
  with `java-25-readiness`.

### 3. Handle shared files once

Edit a shared parent POM or toolchain file once, in its declaring file, and
check every module it affects. Migrate shared source once, then refresh the
evidence for the modules that consume it.

### Cases that need judgment

- A signal with no migration skill (JNDI, version pins): fix by hand with
  evidence, see above.
- A module no skill supports: keep it on your list and note how it affects
  completion. Do not stretch another migration to cover it.
- A request for a target other than the documented Platform 9 families does not
  mean you should apply every migration. Check each migration's target
  assumptions and list the work it does not cover. For frontend compatibility
  claims, check the target's official Atlassian ecosystem guidance.
- A migration that needs a helper script and cannot run it is blocked; do not
  recreate its recipe by hand.

## Verify

1. Build and test with a clean build of the affected modules, including the
   upstream modules they need. For Maven that is a scoped reactor
   `clean package` with tests enabled. Clean only generated output inside that
   scope, and keep any scanner reports you want to compare first.
2. Run the check-only formatter and analyzer commands your project configures
   (for example `spotless:check` or `checkstyle:check`, or frontend
   `format:check` and `lint` scripts), including ones not bound to the build
   lifecycle. Never use a fixing goal as validation. A check that fails before
   your changes is still a failing check; compare against the pre-migration
   result to separate old failures from regressions.
3. Run the project's own build once as you normally would, without overriding
   `JAVA_HOME`, toolchains or `PATH`. A build that only passes with a forced
   JDK is not verified; see [`java-25-readiness`](java-25-readiness.md).
4. Re-scan for each migration's original signal, and for every plugin owner
   check the actual bundle artifact against the target. A missing manifest in a
   library is not a clean audit.
5. Account for every requested module. Unassessed modules mean the migration is
   not complete.
6. To claim the app works on a product, start each exact target product with
   the migrated plugins installed and run your smoke or integration tests
   against it, from a clean integration-test working directory (a reused
   product home keeps previously installed plugins).

A passing compile and unit-test run proves the selected migrations verified. It
is not evidence that your plugin resolves, enables or serves requests on the
target product, and a clean result for one product or milestone says nothing
about another. A step whose full build fails only because another approved
migration is still pending is not independently verified: finish the related
steps, then run the project-level checks.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Build fails with `class file has wrong version 69.0` or `UnsupportedClassVersionError` | A JDK below 25 is selected. Use [`java-25-readiness`](java-25-readiness.md) rather than treating it as a failure of the migration that hit it. |
| POMs no longer resolve after the BOM bump | The new BOM no longer manages an artifact such as Jackson 2. Run the POM-swapping migrations ([`spring7-jakarta11-migrate`](spring7-jakarta11-migrate.md), [`jackson-3-migrate`](jackson-3-migrate.md), [`httpclient-to-5-migrate`](httpclient-to-5-migrate.md)) before bundling and transformerless conversion. |
| A dependency keeps its old version after the BOM bump | A property or `dependencyManagement` entry pins it. Align the pin with the target BOM, or remove it. No skill does this for you. |
| `JndiIllegalArgumentException` on a mail or datasource location | The location has a prefix other than `java:`, `jdbc/` or `mail/`. Change the configured location after checking what it is bound to. |
| A migration that looked applicable has nothing left to do | An earlier migration supplied or removed its dependency. Recheck its signal; if it is gone, it is already compatible. |
| An import compiles but is internal on the target | Availability is per product and version. Run [`check-public-api-readiness`](check-public-api-readiness.md) against the target product's build. |
| Frontend module receives React or jQuery from the host | You do not own the runtime version. Verify the host's version and exports instead of changing dependencies. See [`react19-upgrade`](react19-upgrade.md) and [`upgrade-to-jquery-4`](upgrade-to-jquery-4.md). |

## Use the skill

Ask your agent to migrate your app or component to Platform 9, or to a named
product and version, for example "migrate this plugin to Platform 9 for
Jira". You can name the modules, restrict files, or limit which skills it may
use; a list of allowed skills is authoritative. If you already know you need
one transformation, invoke that skill directly instead. The `migrate-dc-app`
skill:

1. Resolves what you asked for: the modules, the goal, and, where a check
   needs it, the exact product and version (it asks if missing) and the runtime
   Java. It carries on with read-only discovery while a target is unresolved,
   and does not claim target compatibility without it.
2. Inspects every requested module, including libraries and embedded modules,
   and classifies each migration as `applicable`, `already-compatible`,
   `not-applicable`, `uncertain`, `unsupported`, `deferred` or `blocked`, with
   evidence such as paths, declarations, scanner output and versions. Preflight
   does not edit your source or build files. It uses the project's existing
   scanners and may run a build if a selected migration needs an artifact. It
   also finds your check-only format and analysis commands.
3. Presents the plan: target, evidence, order, per-module scope and owners,
   dependencies, validation commands, unresolved findings and review points.
   You approve it, adjust its scope, choose stepwise or bulk execution, or
   stop. Approval covers only the named target, skills, modules, file
   restrictions and validation. It does not cover git writes, extra skills,
   wider scope or open design decisions.
4. Applies the approved steps one at a time in the order above. A step is
   assessed again first, so one that has become compatible is not edited. By
   default it uses a fresh worker for each substantial step (the
   `migration-worker` subagent), or works in-process if delegation is disabled
   or the host has no subagents; a configuration can require workers, and
   then it blocks if none are available. A worker gets only its skill, scope,
   evidence and validation limits, runs focused checks and leaves shared
   build and artifact checks to the orchestrator.
5. After each step, the default is guided and stepwise. It shows the diff for
   that step alone and the verification result, then asks whether to accept
   and continue, request a correction, switch the rest to bulk, or stop.
   In bulk mode it runs the approved plan and does the shared checks at the end
   of each related group. Unattended runs need an authorized plan and a
   stop-on-blocker policy; an environment variable alone is not authorization.
6. Verifies with a clean, scoped build and the check-only format and analysis
   commands described above. After the last step it scans every module for
   residuals and judges each match with the skill that owns it. For confirmed
   missed work inside the approved scope, it runs at most two correction
   rounds, then blocks if actionable work remains.

It keeps the plan, per-step results, before and after file digests and your
review decisions in a state directory outside build output (by default
`.migration-state/` in the workspace), plus a final summary. This lets a run
resume: it skips a step only if its recorded files still match and the signal
is gone, and an applied step you have not reviewed resumes at its review. It
never resets your own edits.

The result is one JSON object with six arrays: `selected_skills`,
`skipped_skills`, `changed_files`, `migrated_apis`, `skipped_candidates` and
`blockers`. Unsupported findings (such as JNDI and BOM-shadowing pins) are in
`skipped_candidates`, not `blockers`, unless they stop a selected migration
from completing. An empty `blockers` array means no blocking issue was seen in
that phase. It does not mean runtime validation ran.

What it deliberately leaves to you:

- **Git.** No commits, pushes or pull requests without separate explicit
  authorization. After an accepted step that is valid on its own, it suggests a
  PR. When steps depend on each other it suggests a combined PR, or stacked PRs
  if you ask for them.
- **Added skills, wider scope and open design decisions.** These need your
  approval as a plan change; a skill being available is not approval.
- **Runtime verification.** Starting the target product with your plugins
  installed and running your integration tests is tracked as deferred when it
  is not run, and is never reported as passed.
- **Work with no skill.** JNDI locations and version pins are reported with
  evidence for you to fix.
