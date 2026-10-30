---
name: crowd-internal-api-migrate
description: Move an app off the Crowd packages the platform holds internal, onto the public Crowd API. Use when an app imports com.atlassian.crowd.common, .crypto, .dao, .directory, .model or .embedded.spi.
---

# Move off the internal Crowd API

**Scope:** Plugin and independent-library production code; the same public-API
rules apply to both. Libraries use source/dependency and build/test verification;
bundle and host-service checks require actual deployment context.

Platform 9 declares `crowd-common`, `crowd-persistence` and `embedded-crowd-spi` part of the platform's
internal dependency set. Their packages are on the application classpath, but they are not API: a
product may stop putting any of them on the plugin classpath in any release. Jira 12 already has.

There is no bundling path. These artifacts are Atlassian's own persistence and SPI layers — Hibernate
entities, DAOs and the directory implementations behind them — bound to the schema of the running
product. An app that ships its own copy gets types that cannot interoperate with the product's, and a
second set of entity mappings against the same tables.

The migration is therefore always the same shape: reach the same information through the public API, or
stop reaching it.

## Supported alternatives

| Internal surface | Public replacement |
|---|---|
| `com.atlassian.crowd.embedded.spi.UserDao`, `GroupDao`, `MembershipDao` | `com.atlassian.crowd.embedded.api.CrowdService` for lookups, `CrowdDirectoryService` for directory configuration |
| `com.atlassian.crowd.model.user.InternalUser` and other entities | `com.atlassian.crowd.embedded.api.User`, or the product's own user type |
| `com.atlassian.crowd.directory.*` directory implementations | `com.atlassian.crowd.embedded.api.CrowdDirectoryService`; configure directories rather than constructing them |
| `com.atlassian.crowd.dao.*` | `CrowdService` and `CrowdDirectoryService`; there is no public DAO layer by design |
| `com.atlassian.crowd.common.util.*` | JDK equivalents — `java.util.Base64`, the app's own masking helper |
| `com.atlassian.crowd.crypto.*` | the app's own encryption, or `com.atlassian.secrets` where the product provides it |

`crowd-api`, `crowd-events`, `crowd-integration-api` and `embedded-crowd-api` stay in the platform public
API and are unaffected. Nothing in this skill applies to imports from those.

## Case analysis

Determine which case the app is in before changing anything.

1. **No import of the internal packages.** Nothing to do. Report and stop.
2. **Imports only `com.atlassian.crowd.embedded.api` or another public Crowd artifact.** Nothing to do.
   Report and stop; a `com.atlassian.crowd` prefix alone is not evidence of a problem.
3. **Imports an internal package with a public replacement in the table above.** Migrate the call sites.
4. **Imports an internal package with no public equivalent** — a DAO used to reach data the public API
   does not expose, or a custom directory implementation. This needs a design decision rather than a
   substitution: report what the app uses, what it appears to need it for, and stop. Do not invent a
   replacement.

## Detect

Two greps. The first finds imports, the second finds reflective access that an import scan misses.

```bash
grep -rn --include="*.java" --include="*.kt" -E \
  "^import\s+com\.atlassian\.crowd\.(common|crypto|dao|directory|model|embedded\.spi)\." .
```

```bash
grep -rn --include="*.java" --include="*.kt" --include="*.xml" -E \
  "com\.atlassian\.crowd\.(common|crypto|dao|directory|model|embedded\.spi)\." . \
  | grep -v "^[^:]*:[0-9]*:\s*import "
```

Record which packages appear. The remediation differs per package, and one app commonly uses several.

## Migrate

Work one call site at a time, and check each against the case analysis first.

A lookup through the SPI becomes a lookup through `CrowdService`:

```java
// Internal: com.atlassian.crowd.embedded.spi.UserDao
User user = userDao.findByName(directoryId, username);

// Public: com.atlassian.crowd.embedded.api.CrowdService
User user = crowdService.getUser(username);
```

`CrowdService` resolves across configured directories rather than within one, so an app that passed a
`directoryId` is making a narrower query than `CrowdService` offers. Where that distinction matters, use
`CrowdDirectoryService` to enumerate directories and keep the narrowing explicit; do not assume the two
are equivalent.

For `com.atlassian.crowd.common.util`, replace the call rather than the dependency:

```java
// Internal
String encoded = Base64Util.urlSafeEncoderWithoutPadding().encodeToString(bytes);

// JDK
String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
```

Do not add `crowd-common`, `crowd-persistence` or `embedded-crowd-spi` to the POM at compile scope. That
is the bundling path, and for these artifacts it produces an app that loads its own entity and DAO types
alongside the product's.

## Verify

The app compiles against the public API only:

```bash
grep -rn --include="*.java" --include="*.kt" -E \
  "^import\s+com\.atlassian\.crowd\.(common|crypto|dao|directory|model|embedded\.spi)\." . \
  | wc -l          # expect 0
mvn -q clean package
```

For plugins, confirm the built bundle imports no internal Crowd package
(plain JARs have no bundle-import audit):

```bash
unzip -p target/*.jar META-INF/MANIFEST.MF \
  | tr -d '\r' | sed -e ':a' -e '$!N;s/\n //;ta' -e 'P;D' \
  | grep '^Import-Package' \
  | grep -oE "com\.atlassian\.crowd\.(common|crypto|dao|directory|model|embedded\.spi)[^,;]*"
```

No output is the passing result. A match means a call site remains, or a type is still referenced from a
signature the compiler kept.

## Notes

- **Availability is per package, not per artifact, and it varies by product.** Jira 12 withdraws
  `com.atlassian.crowd.common.util`, `com.atlassian.crowd.crypto` and `com.atlassian.crowd.embedded.spi`
  while keeping `com.atlassian.crowd.directory` and `com.atlassian.crowd.model.user` — all three from
  artifacts the platform holds internal, two of them from the same artifact as a withdrawn package.
  Confluence 11 keeps every surface it previously exported, and never exported
  `com.atlassian.crowd.embedded.spi` at all. So a surface still resolving on one product says nothing
  about another, and nothing about the next build of the same one.
- **A surface that still works is still internal.** The two packages that survive on Jira 12 are the ones
  with the widest known marketplace usage, which makes them the most likely to break an app later rather
  than the safest to keep.
- **Where this was verified.** Every surface listed here resolves on Jira 11.3.7. On Jira 12,
  `com.atlassian.crowd.common.util`, `com.atlassian.crowd.crypto` and `com.atlassian.crowd.embedded.spi`
  fail with `NoClassDefFoundError`. An early Confluence 11 build resolves all of them except
  `com.atlassian.crowd.embedded.spi`, which no Confluence version resolves. The per-call-site
  replacements follow the public API's own contracts and were not measured individually.
- **Static constants hide the dependency.** A `static final String` is inlined into its caller at compile
  time, so an app reading one from an internal Crowd class has no runtime dependency on it and no import
  to find. Such a call keeps working after the class is gone, and the grep above will not report it.
