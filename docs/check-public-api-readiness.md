---
skill: check-public-api-readiness
skill-sha256: f3a7bcafd942
---

# Check an app's imports against the product's public API

Platform 8 published almost everything on the product classpath to apps: the
package scanner took broad patterns such as `org.apache.*` and subtracted a
short exclude list. Platform 9 inverts that into an explicit allowlist. A
package missing from the allowlist is unreachable from an app even though its
classes sit on the product's classpath, and no deprecation cycle precedes the
change. The individually documented library removals are the enumerated part;
this check finds any other import that quietly failed to make the list. It is
an assessment, not a code migration. This guide covers running it by hand. The
[last section](#use-the-skill) describes the `check-public-api-readiness`
skill.

## Is my code affected?

Run it for an actual plugin or bundle artifact when:

- an app fails to resolve on Platform 9 and the cause is not one of the
  documented libraries;
- you are about to ship an app against a new product version and want to find
  withdrawals in advance;
- you want to scope which imports need attention and which are fine.

It does not apply to plain libraries. An absent manifest is not a clean result,
and it does not dismiss source-level findings. For a specific known library,
use that library's own skill, which carries the remediation this check does not.

## Before you start

- Each product keeps its own allowlist, so a result for one product says
  nothing about another. Read the allowlist from the product build and version
  you target, not from a summary.
- Unpack that product build. A full install is not required: any directory
  containing the product's jars is enough, and the scan searches it recursively.
- The scan needs `bash`, `unzip` and a writable temp directory (it uses
  `$TMPDIR` if set).

Supported products and where the allowlist is read from:

| Product | Allowlist read from | Also read |
|---|---|---|
| Confluence | `public-api.yaml` in `com.atlassian.confluence.confluence-*.jar` | `package-exports.yaml` |
| Jira | `osgi/public-packages.txt` in `jira-core-*.jar`, or loose in an unpacked webapp's `WEB-INF/classes/osgi/` (Jira 12) | `osgi/public-packages-excludes.txt`, `osgi/deprecated-packages.txt`, `osgi/package-includes.txt`, `osgi/package-excludes.txt` |
| Bitbucket | `package-public-includes.properties` in `bitbucket-platform-*.jar` | `package-public-excludes.properties`, `package-includes.properties`, `package-excludes.properties` |
| Crowd | `com/atlassian/crowd/public-api.yaml` in `crowd-server-*.jar` | `com/atlassian/crowd/package-exports.yaml` |
| Bamboo | not shipped as a resource | none |

Bamboo ships no allowlist resource: its set is the platform default compiled
into `atlassian-plugins-osgi`, plus `com.atlassian.bamboo.*`. Check a Bamboo
app against a running instance.

## Migrate

There is no code change to make until you have the findings. The steps below
produce them, then turn them into work.

### 1. Build the app

```bash
mvn -q clean package
```

Read the built bundle's `Import-Package` header, not the source tree. By then
bnd has worked out which packages the bundle needs, including packages reached
only from an embedded library, and excluding packages the bundle carries
itself. A source grep misses the first and over-reports the second.

### 2. Decide each import

A package is public when it matches the allowlist and matches no exclude. A
trailing `*` matches any depth, so `org.apache.*` covers `org.apache.commons.io`.
An entry without one matches exactly that package: a bare `org.apache.xml`
covers only `org.apache.xml`, not `org.apache.xml.serialize`. Jira's list
(about 120 entries) leans on broad wildcards where Confluence's (about 1100)
enumerates, so a package can be public on one product and internal on another
for that reason alone.

You can do this by hand with the extracted lists. The script does exactly this
for every import, see [Use the skill](#use-the-skill).

### 3. Act on the verdicts

| Verdict | Meaning | What to do |
|---|---|---|
| `public` | in the allowlist and not excluded | nothing |
| `deprecated` | published now, already marked for withdrawal | plan the move before it becomes internal |
| `internal` | on the product classpath, not published to apps | an import cannot wire: bundle the library or stop using it |
| `unknown` | in none of the product's export sets | supplied by another bundle, by the JDK, or absent; not a finding by itself |

Read the verdict with the resolution:

- **`internal` or `unknown`, mandatory.** The bundle does not resolve, so the app
  does not install. Fix these first.
- **`internal` or `unknown`, optional.** The app installs, then fails at the
  first call into that package. One feature breaks and the rest keep working.

Marking an import optional is a downgrade, not a fix: it turns a bundle that
will not install into an app that installs and fails later. That suits a feature
the app can live without and is wrong for a dependency it cannot.

### Cases that need your judgment

- **`internal` verdicts.** Treat as "investigate", not "confirmed broken" (see
  below). For a definitive answer on one package, install the app on the product
  and call the code.
- **`unknown` verdicts.** Most JDK packages land here, as does anything the app
  gets from another plugin. It means this scan cannot speak for it.
- **Bundle or drop.** Decide per library. When a package belongs to a library
  that has its own skill, use that skill (for example
  [`bundle-platform-dependencies`](bundle-platform-dependencies.md)).

## Verify

1. Re-run the scan after each fix, against the same product build. A bundle
   with no mandatory import of an internal package prints
   `No mandatory import of an internal package.` and exits 0. Exit status 1
   means a mandatory internal import remains. A bad or missing argument, or no
   supported allowlist, exits 2.
2. Install the app on the target product and exercise it. This is the only
   definitive check, because of what the scan cannot see:
   - **A package exported by another bundle.** The allowlist governs what the
     product publishes from its own classpath. A bundled plugin can export a
     package itself and an app can wire to it.
   - **Whether classes are really present.** A package can match the include
     patterns and still not exist in any scanned jar.
   - **Anything not reached through OSGi.** Reflection, `ServiceLoader` and
     resource lookups do not appear in `Import-Package`.
3. Re-run per product and per version. The allowlist is part of the product
   build, and a result from one milestone does not carry to the next.

How far to trust a result: in a measurement on an early Confluence 11 build against
a diagnostic app, 15 of 19 verdicts matched what the running product did. The
four that differed were all over-reports, where the scan called a package
internal and the app could still use it (`org.apache.xerces.parsers`,
`org.apache.xalan.processor`, `org.apache.xml.serializer`, `org.apache.xpath`).
No package the scan called public or otherwise safe failed. A `public` verdict
has held up; an `internal` verdict means investigate.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `no Import-Package header in <bundle>` | The jar is not an OSGi bundle, or the build did not produce the manifest. Run the build (step 1) and point at the bundle. |
| `no supported product allowlist found under <dir>` | The directory has no matching product jar or Jira `osgi` files. Point `--product-dir` at the unpacked product. For Bamboo, test against a running instance. |
| `cannot create a temporary directory` | The default temp directory is not writable. Set `TMPDIR` to a writable directory. |
| `internal` package but the app works | The package may be exported by another bundle. This is the known source of over-reports. Confirm on a running product. |
| Many `unknown` entries | Expected for JDK packages and packages from other plugins. |
| Bundle installs but a feature fails at its first call | An optional import of an `internal` package. Bundle the library or stop using it. |
| A package is public on Confluence but internal on Jira | Lists differ in granularity as well as content. Check each target on its own build. |

## Use the skill

Ask your agent to "check this app's imports against the Platform 9 public API
for <product> <version>". The `check-public-api-readiness` skill needs the built
bundle and an unpacked product build.

1. Builds the app with `mvn -q clean package` and reads the bundle's
   `Import-Package` header (unfolding wrapped manifest lines, and noting which
   imports are optional).
2. Runs `scripts/check-public-api.sh --bundle <jar> --product-dir <dir>`, which
   finds the product's allowlist (Confluence, Jira, Bitbucket or Crowd), applies
   the allowlist, exclude and deprecated lists, and assigns one verdict per
   import. `--format tsv` emits `package<TAB>verdict<TAB>resolution` for diffing
   across product versions. The default text output groups by verdict.
3. Reports the verdicts with their resolution, internal first. The script exits
   1 only when a mandatory import is of an internal package.

It changes no code. It leaves to you the decisions on each finding (bundle,
remove, or accept an optional import), confirmation of `internal` verdicts on a
running product, and any Bamboo app.
