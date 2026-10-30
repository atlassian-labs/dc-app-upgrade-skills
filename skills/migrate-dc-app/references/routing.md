# Migration routing inventory

Use this inventory after reading only each available entrypoint's `name` and
`description`. Inspect concrete source, build, descriptor, and scanner
signals before loading a leaf skill. A framework or dependency being present
is an inspection signal, not proof that migration is required.

Apply [scope.md](scope.md) to every requested module. The scope column below
routes assessment; each leaf defines its supported edits and prerequisites.
Keep source, dependency, and runtime evidence distinct.

| Family and capability | Scope | Select when inspection finds | Skip or classify as already compatible when |
|---|---|---|---|
| Transformerless plugin conversion, `transformerless-plugin-conversion` | Plugins | An authoritative AMPS descriptor/configuration lacks either `Atlassian-Plugin-Key` or `Spring-Context`, establishing the legacy transformed-plugin path | Both headers are present, or the plugin already has `Atlassian-Plugin-Key`, its Spring XML under `META-INF/spring/`, and no legacy `<component>`/`<component-import>`/`<module-type>` declarations (`already-compatible` even without `Spring-Context`); ambiguous product/configuration is `uncertain`, not a clean skip |
| Repackaged Commons Lang, `commons-lang-migrate` | Plugins and libraries | Executable application Java imports or fully qualifies `com.atlassian.gzipfilter.org.apache.commons.lang` | Only comments, strings, tests, or protected expectations mention it |
| Platform dependency bundling, `bundle-platform-dependencies` | Plugins | Supported platform library usage; read the leaf entrypoint to assess product-specific applicability | Follow the leaf assessment; load execution references only when applicable |
| Spring/Jakarta, `spring7-jakarta11-migrate` | Plugins and libraries | Actionable legacy `javax.*` EE imports, versioned Spring XML XSD, `default-autowire="autodetect"`, Spring 7 removed API usage (e.g. `ListenableFuture`), a demonstrably incompatible existing authoritative `Import-Package`, or caller-authorized target container configuration | No actionable signal remains; generic Spring, OSGi, or AMPS presence alone is not enough |
| Jackson 3, `jackson-3-migrate` | Plugins and libraries | `com.fasterxml.jackson` dependencies/imports or Jackson 2 provider descriptors in application code | No Jackson 2 source or dependency signal |
| HttpClient 5, `httpclient-to-5-migrate` | Plugins and libraries | `org.apache.commons.httpclient` or `org.apache.http` imports/dependencies | No legacy namespace/dependency; all usage is already HttpClient 5 |
| Java 25, `java-25-readiness` | Plugins and libraries | Known runtime/build blocker such as old ByteBuddy, `java.security.manager=allow`, old Spotless/AMPS, or incompatible Kotlin/Scala toolchain; also a build that fails with `class file has wrong version 69.0` or `UnsupportedClassVersionError`, which is a JDK selection below 25 rather than a finding of the migration whose step hit it | Runtime-first checks find no known blocker; Java source alone is not enough, and a passing build does not clear a project-owned build plugin or dependency below the skill's version thresholds |
| Service discovery, `serviceloader-bootdelegation-readiness` | Plugins and libraries | A direct JAXP/JAXB factory call, one-argument `ServiceLoader.load`, a shipped `META-INF/services` provider, or evidence implicating a library-owned discovery path (for example relevant dependency source/bytecode/metadata or a runtime call trace) | No direct signal and no evidence-indicated indirect path after bounded inspection, or an evidence-backed no-change decision (runtime-check dispositions: [contracts](contracts.md#assess-result)) |
| Analytics API, `analytics-api-migrate` | Plugins and libraries | `com.atlassian.analytics.*`, `AnalyticsConfigService`, or `AnalyticsUploadService` in application code | No application use, or only public unrelated analytics APIs are used |
| Internal Crowd API, `crowd-internal-api-migrate` | Plugins and libraries | Main Java imports internal `com.atlassian.crowd` `common`, `crypto`, `dao`, `directory`, `model`, or `embedded.spi` packages | Usage is confined to public `crowd.embedded.api` and public Crowd artifacts, or tests |
| JNDI location hardening, no current migration capability | Plugins and libraries | Application code programmatically configures a mail-server or datasource JNDI location outside `java:`, `jdbc/`, or `mail/` | The app uses the product's already-configured mail or datasource, or every configured location has a compliant prefix; report an actionable signal as `unsupported` until a dedicated capability exists |
| Version pins shadowing the platform BOM, no current migration capability | Plugins and libraries | A project property or `dependencyManagement` entry pins an artifact the imported platform BOM also manages (for example an OAuth2, SAL or Atlassian library version left at its pre-Platform-9 value), so the BOM bump does not reach it | The pin matches the target BOM, or the BOM does not manage the artifact; report an actionable pin as `unsupported` with both versions |
| jQuery 4, `upgrade-to-jquery-4` | Frontend modules | Frontend jQuery sources/dependencies warrant the skill's own detection; apply only confirmed incompatible calls in scope. Raw scanner hits are candidates pending the skill's confirmation, not `applicable` evidence | No jQuery signal, or inspected usage is compatible *and* the declared `jquery` and `@types/jquery` are on the target product's jQuery major with an `@atlassian/aui` whose peer range admits it; generic references and supported deprecations do not justify edits |
| React 19, `react19-upgrade` | Frontend modules | React sources/dependencies warrant the skill's assessment; apply supported own-code findings or confirmed target dependency/peer mismatches, accounting for host versus package runtime ownership | No React signal, or source and target evidence support no changes; a clean source scan alone does not establish dependency readiness |
| Atlaskit, `atlaskit-upgrade` | Frontend modules | A package declares `@atlaskit/*` (the skill's own applicability check) and has an outdated `@atlaskit/*` package, a legacy or deprecated `@atlaskit/button` import, or a missing or unregistered theme exclusion plugin | No `@atlaskit/*` dependency, or the skill's checks find nothing actionable |
| Compiled React setup, `compiled-react-setup` | Frontend modules | A package depends on `@compiled/react` or `@atlaskit/*` (the skill's own applicability check) and its Compiled extraction, Babel scoping, or CSS registration is missing or incompatible | Compiled setup is already valid for the project build |
| TypeScript 6, `typescript-6-upgrade` | Frontend modules | Target frontend toolchain uses TypeScript APIs or configuration incompatible with TypeScript 6 | No TypeScript project or readiness check is clean |
| Public API readiness, `check-public-api-readiness` | Plugins | Assess imports against a new target product build, or investigate imports not covered by known families | Run the scan when the bundle and product distribution are available; otherwise record it as deferred, never clean |

If a concrete signal has no available capability, classify it as
`unsupported` and preserve it in `skipped_candidates`. Do not invent a
migration. If a scanner is unavailable or fails, classify the result as
`uncertain` or `blocked`, never as `not-applicable`.

An AI-guided skill is an available migration even without an executable recipe.
Apply its supported transformations directly; absence of a helper alone is not
an unsupported finding. Conversely, when a skill requires a guarded helper,
its failure is a blocker, not permission to recreate the recipe manually.

The catalog addresses the documented Platform 9 migration families. A request
for another target does not authorize applying this entire catalog: verify the
selected skill's target assumptions and report uncovered work. For frontend
compatibility claims, check the target's official Atlassian ecosystem guidance.

Availability is evaluated against the exact target product and version. A
package may be public on one product and internal on another, and a package
that remains resolvable can still be an internal API that needs attention.
Run `check-public-api-readiness` against the target product's own build when
that artifact is available; do not carry a clean result from another product
or milestone forward.

## JNDI configuration finding

The former guide's JNDI check has no source-transform capability. Platform 9
sanitizes locations used by `lookup`, `doLookup`, `lookupLink`, and `rename`:
`java:` (for example `java:comp/env/...`), `jdbc/`, and `mail/` are the allowed
prefixes. Custom names and `ldap:` or `rmi:` URLs can raise
`JndiIllegalArgumentException`. This was observed on the mail server path
(`SMTPMailServerImpl`); verify datasource enforcement for the exact target.

Inspect locations configured programmatically by the app and report a required
configuration change with evidence. Apps using the platform's already-configured
mail server or datasource are unaffected. Do not invent a bundling or
`Import-Package` fix, or silently replace a location without knowing its binding.

## Recommended order

Use this order unless preflight identifies a real dependency that changes it.
Backend and runtime work precede frontend work. Recheck applicability after
each completed step because one migration can supply or remove another
dependency (for example, ROME can supply JDOM 2).

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

A target BOM bump can leave POMs unresolvable (for example, once the BOM stops
managing Jackson 2). Bundling and transformerless conversion need a resolvable,
buildable module, so run the POM-swapping skills (`spring7-jakarta11-migrate`,
`jackson-3-migrate`, `httpclient-to-5-migrate`) before them in that case, and
record the reason in the plan.

The list is an ordering policy, not an unconditional execution list. Select
only capabilities in the caller's roster whose evidence is actionable for the
resolved target and approved scope.
