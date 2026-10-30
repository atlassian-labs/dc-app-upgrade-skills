---
name: spring7-jakarta11-migrate
description: Migrate Java Maven plugin or library code from Spring 5/6 and javax to Spring 7 and Jakarta EE 11 for Platform 9. Apply plugin manifest and container changes only to real, approved packaging owners.
---

Migrate requested Java Maven modules for **Platform 9**
(Jira 12 / Confluence 11), which moves from **Spring 5/6 → Spring 7**,
**`javax.*` → `jakarta.*`** (Jakarta EE 11), and **Tomcat 9/10 → Tomcat 11**.

**Scope:** Plugin and independent-library source, Spring XML, and dependencies.
Library API migration needs no product target. OSGi, transformed-plugin, AMPS,
and container checks apply only to actual, authorized deployment configuration
and retain their product-target requirements.

Use this skill when the user asks to:
- "migrate to Spring 7" / "Jakarta 11" / "Platform 9 backend"
- "fix javax to jakarta"
- "make my plugin work on Jira 12 / Confluence 11"

## Step 1 — Route only concrete incompatibilities

Inspect application/main sources and authoritative build/configuration inputs
before selecting this skill. The following are **inspection signals only**;
none proves that a migration is needed:
- Spring dependencies, annotations, or XML being present,
- an OSGi `Import-Package` declaration,
- an AMPS plugin or integration/E2E test configuration,
- a platform/BOM version, or
- generated output under `target/`, including a generated manifest.

Select this skill only for a concrete, in-scope incompatibility:
- application/main source or resource files use one of the Jakarta EE
  `javax.*` packages listed under `Jakarta EE javax.* packages` below;
- application Spring XML contains a versioned Spring XSD or
  `default-autowire="autodetect"`;
- application/main source uses a Spring API removed in Spring 7 (see
  `Removed Spring 7 APIs` below);
- plugin main source, or a library embedded in the plugin, uses JSR-305
  nullness annotations from `javax.annotation` and a target product does not
  publish that package to apps (see the nullability section of
  `references/migration.md`);
- an **existing authoritative** `Import-Package` block can be safely amended
  for a known incompatible package, or for a `jakarta.*` version range that
  excludes the API version the target platform manages, without changing its
  other clauses, wildcard/default fallback, or resolution semantics; or
- an AMPS configuration explicitly included by the caller's scope needs the
  target container configuration described in the AMPS tests section of
  [`references/migration.md`](references/migration.md).

Do not select the skill for generic Spring/AMPS presence, test infrastructure
alone, or a stale `target/` manifest. Use these outcome classifications:
- `selected` — at least one actionable incompatibility is in scope;
- `already-compatible` — the relevant actionable checks are clean;
- `no-actionable-signal` — only broad presence signals were found;
- `protected` — an issue exists but caller scope forbids the change; and
- `ambiguous` — the evidence or safe remediation is unclear.
Leave skipped candidates unchanged.

The caller's scope controls whether build, AMPS, or test configuration may be
changed. Do not modify AMPS/test infrastructure merely because it exists.

> **For actual plugins:** Platform 9 removes the legacy
> "transformed plugin" (v1 / non-OSGi) compatibility layer. If the app is still a
> v1 plugin it will **not load** on P9 and must first be converted to a v2 (OSGi)
> plugin. That conversion is a separate, larger task that gates everything below —
> scope it first.

## Detection reference

### Jakarta EE `javax.*` packages

| Before (`javax.*`) | After (`jakarta.*`) |
|---|---|
| `javax.servlet` | `jakarta.servlet` |
| `javax.inject` | `jakarta.inject` |
| `javax.ws.rs` | `jakarta.ws.rs` |
| `javax.mail` | `jakarta.mail` |
| `javax.xml.bind` | `jakarta.xml.bind` |
| `javax.validation` | `jakarta.validation` |
| `javax.annotation.PostConstruct`, `PreDestroy`, `Resource`, `javax.annotation.security.*` (JSR-250) | `jakarta.annotation.*` |

JSR-250 annotations are runtime-retained, so like JSR-305 they make bnd import
`javax.annotation`; move them to `jakarta.annotation` (public on Platform 9).

**Do NOT blindly rename every `javax.*`.** Leave these as-is:
- `javax.annotation.concurrent.*` (`@Immutable`, `@GuardedBy`) — no Jakarta
  equivalent exists; they are class-retained, so bnd generates no import.
- `javax.annotation.Nullable` / `javax.annotation.Nonnull` — migrate to
  **JSpecify**, not Jakarta (see the nullability section of
  `references/migration.md`).
- Core JDK `javax.*` that is not part of Jakarta EE (e.g. `javax.crypto`,
  `javax.net`, `javax.sql`, `javax.naming`).

### Removed Spring 7 APIs

Spring 7 removes the `ListenableFuture` family (deprecated since Spring 6.0)
from `org.springframework.util.concurrent`, and `AsyncListenableTaskExecutor`
/ `submitListenable` from `org.springframework.core.task`. The package is still
exported by the platform, so the plugin loads on Platform 9 and fails only when
the code runs, with `NoClassDefFoundError`.

```bash
grep -rnE --exclude-dir=target --exclude-dir=build --exclude-dir=.git --include='*.java' --include='*.kt' --include='*.groovy' \
  'org\.springframework\.(util\.concurrent\.|core\.task\.(\*|AsyncListenableTaskExecutor))|submitListenable' src/main
```

Every match is a candidate: a confirmed hit is executable code using a removed
type, not a comment, string, or a surviving type such as `FutureUtils`.

## Migration

When Step 1 classifies the module as `selected`, load
[`references/migration.md`](references/migration.md) and follow its migration
and verification guidance, then report through Step 2. For any other
classification, leave the module unchanged and report through Step 2. Do not
duplicate that procedure here.

## Step 2 — Summary

Report to the user:
1. Whether the module was in scope (and if a v1→v2 conversion is required first).
2. Which `javax.*` imports were renamed to `jakarta.*` (and which were
   deliberately left as-is).
3. Spring XML changes (versionless XSD, removed autodetect).
4. OSGi `Import-Package` exclusions added, and `jakarta.*` version ranges updated.
5. Nullability annotations moved to JSpecify (with any manual TYPE_USE fixes),
   or deferred, with the `javax.annotation` verdict that permits it.
6. Which AMPS configs had a hardcoded `containerId` removed so AMPS resolves
   it, and the removed value.
7. compile / test / verify results, candidates left unchanged as `ambiguous` or
   `protected` (with file and line), and any remaining manual follow-ups.

## Related Platform 9 backend skills
- `jackson-3-migrate` — `com.fasterxml.jackson` → `tools.jackson`.
- `httpclient-to-5-migrate` — `org.apache.commons.httpclient` / `org.apache.http` → `org.apache.hc`.
- `java-25-readiness` — assess/prepare the module for Java 25.
