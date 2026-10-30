---
skill: crowd-internal-api-migrate
skill-sha256: 5e9ac0310aef
---

# Move off the internal Crowd API

Platform 9 holds `crowd-common`, `crowd-persistence` and `embedded-crowd-spi`
internal. Their packages are on the application classpath but are not API: a
product may stop putting any of them on the plugin classpath in any release.
Jira 12 already has. The migration is to reach the same information through
the public Crowd API, or stop reaching it. This guide covers the manual
migration. The [last section](#use-the-skill) describes the
`crowd-internal-api-migrate` skill.

## Is my code affected?

Your code is affected if production code in a plugin or library imports any of
`com.atlassian.crowd.common`, `.crypto`, `.dao`, `.directory`, `.model` or
`.embedded.spi`. Two searches: the first finds imports, the second finds
reflective or descriptor references that an import scan misses.

```bash
grep -rn --include="*.java" --include="*.kt" -E \
  "^import\s+com\.atlassian\.crowd\.(common|crypto|dao|directory|model|embedded\.spi)\." .

grep -rn --include="*.java" --include="*.kt" --include="*.xml" -E \
  "com\.atlassian\.crowd\.(common|crypto|dao|directory|model|embedded\.spi)\." . \
  | grep -v "^[^:]*:[0-9]*:\s*import "
```

You can skip the migration when nothing matches. A `com.atlassian.crowd` prefix
alone is not evidence of a problem: imports of `com.atlassian.crowd.embedded.api`
or other public Crowd artifacts need no change. `crowd-api`, `crowd-events`,
`crowd-integration-api` and `embedded-crowd-api` stay in the platform public API.

One thing the search cannot find: a `static final String` is inlined into its
caller at compile time, so an app reading such a constant from an internal Crowd
class has no runtime dependency and no import to find. That call keeps working
after the class is gone.

## Before you start

- Record which packages appear. The remediation differs per package, and one
  app commonly uses several.
- Check each call site against the table in step 1. Where there is a public
  replacement, migrate it. Where there is none, for example a DAO used to reach
  data the public API does not expose, or a custom directory implementation, you
  need a design decision rather than a substitution.
- Do not plan to bundle the artifacts. They are Atlassian's persistence and SPI
  layers (Hibernate entities, DAOs and the directory implementations behind
  them), bound to the running product's schema. A shipped copy gives you types
  that cannot interoperate with the product's, and a second set of entity
  mappings against the same tables.

## Migrate

### 1. Pick the public replacement

| Internal surface | Public replacement |
|---|---|
| `com.atlassian.crowd.embedded.spi.UserDao`, `GroupDao`, `MembershipDao` | `com.atlassian.crowd.embedded.api.CrowdService` for lookups, `CrowdDirectoryService` for directory configuration |
| `com.atlassian.crowd.model.user.InternalUser` and other entities | `com.atlassian.crowd.embedded.api.User`, or the product's own user type |
| `com.atlassian.crowd.directory.*` directory implementations | `CrowdDirectoryService`; configure directories rather than constructing them |
| `com.atlassian.crowd.dao.*` | `CrowdService` and `CrowdDirectoryService`; there is no public DAO layer by design |
| `com.atlassian.crowd.common.util.*` | JDK equivalents, such as `java.util.Base64`, or the app's own masking helper |
| `com.atlassian.crowd.crypto.*` | the app's own encryption, or `com.atlassian.secrets` where the product provides it |

### 2. Migrate one call site at a time

A lookup through the SPI becomes a lookup through `CrowdService`:

```java
// Before: com.atlassian.crowd.embedded.spi.UserDao
User user = userDao.findByName(directoryId, username);

// After: com.atlassian.crowd.embedded.api.CrowdService
User user = crowdService.getUser(username);
```

`CrowdService` resolves across configured directories rather than within one,
so an app that passed a `directoryId` is making a narrower query than
`CrowdService` offers. Where that matters, use `CrowdDirectoryService` to
enumerate directories and keep the narrowing explicit. Do not assume the two
are equivalent.

For `com.atlassian.crowd.common.util`, replace the call rather than the
dependency:

```java
// Before
String encoded = Base64Util.urlSafeEncoderWithoutPadding().encodeToString(bytes);

// After
String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
```

### 3. Keep the artifacts out of the POM

Do not add `crowd-common`, `crowd-persistence` or `embedded-crowd-spi` to the
POM at compile scope.

### Cases that need your judgment

- Internal packages with no public equivalent (see Before you start). Work out
  what the app needs the data for before choosing a replacement.
- Call sites that pass a `directoryId` (step 2).

## Verify

1. The app compiles against the public API only. The count must be 0:

   ```bash
   grep -rn --include="*.java" --include="*.kt" -E \
     "^import\s+com\.atlassian\.crowd\.(common|crypto|dao|directory|model|embedded\.spi)\." . \
     | wc -l
   mvn -q clean package
   ```

2. For plugins, confirm the built bundle imports no internal Crowd package. A
   plain JAR has no bundle-import audit.

   ```bash
   unzip -p target/*.jar META-INF/MANIFEST.MF \
     | tr -d '\r' | sed -e ':a' -e '$!N;s/\n //;ta' -e 'P;D' \
     | grep '^Import-Package' \
     | grep -oE "com\.atlassian\.crowd\.(common|crypto|dao|directory|model|embedded\.spi)[^,;]*"
   ```

No output is the passing result. A match means a call site remains, or a type is
still referenced from a signature the compiler kept. Bundle and host-service
checks need an actual deployment; for a library, source, dependency and
build/test checks are what you can run.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `NoClassDefFoundError` for `com.atlassian.crowd.common.util`, `.crypto` or `.embedded.spi` on Jira 12 | Jira 12 withdraws these. Migrate the call site (steps 1 and 2). |
| `com.atlassian.crowd.embedded.spi` fails on Confluence | No Confluence version publishes it. |
| App works on one product but not another | Availability is per package, not per artifact, and varies by product. Jira 12 keeps `com.atlassian.crowd.directory` and `com.atlassian.crowd.model.user`; Confluence 11 keeps every surface it previously exported. A surface that resolves on one product says nothing about another, or the next build of the same one. |
| A package that still resolves | It is still internal. The two that survive on Jira 12 have the widest known marketplace usage, which makes them the most likely to break later rather than the safest to keep. |
| Clean import search but a Crowd constant is still read | Inlined constants are invisible to the search (see affected check). Review such reads by hand. |

## Use the skill

Ask your agent to "move this app off the internal Crowd API". The
`crowd-internal-api-migrate` skill applies to plugin and independent-library
production code.

1. Sorts the app into a case: no internal imports, or only public Crowd imports
   (nothing to do, it stops); internal imports with a public replacement in the
   table (it migrates the call sites); internal imports with no public
   equivalent (it reports what the app uses and what it appears to need it for,
   and stops without inventing a replacement).
2. Detects with the two searches above and records which packages appear.
3. Migrates one call site at a time, checking each against that case analysis,
   and does not add the internal artifacts to the POM.
4. Verifies with the zero-count import search, `mvn -q clean package`, and, for
   plugins, the manifest `Import-Package` check.

It leaves to you the design decisions for call sites that have no public
replacement.
