---
name: check-public-api-readiness
description: Check every package an app imports against the target product's public API allowlist, and report which are public, internal, deprecated or absent. Use to find Platform 9 export withdrawals an app depends on beyond the individually documented libraries, or before shipping an app against a new product version.
---

# Check an app's imports against the product's public API

**Scope:** Actual plugin/bundle artifacts and target-product evidence. Plain
libraries are not applicable to this audit; an absent manifest is not a clean
public-API result and does not dismiss source-level findings.

Platform 8 published almost everything on the product classpath to apps: the package scanner took
broad patterns such as `org.apache.*`, then subtracted a short exclude list. Platform 9 inverted
that into an explicit allowlist. A package absent from the allowlist is unreachable from an app even
though its classes sit on the product's classpath, and no deprecation cycle precedes the change.

The individually documented library removals are the enumerated part of that inversion. This skill
covers the rest: any package an app imports that quietly failed to make the list.

Each product keeps its own allowlist, so an app that works on one product says nothing about
another. Read the allowlist from the product build the app targets rather than from any summary.

## When to use

- An app fails to resolve on Platform 9 and the cause is not one of the documented libraries.
- Before shipping an app against a new product version, to find withdrawals in advance.
- To scope the work: which of an app's imports need attention, and which are fine.

Not for diagnosing a specific known library. Use that library's own skill, which carries the
remediation this one does not.

## Step 1 — Build the app

```bash
mvn -q clean package
```

The scan reads the built bundle's `Import-Package` header, not the source tree. That is the correct
input: bnd has already worked out which packages the bundle genuinely needs, including packages
reached only from an embedded library, and excluding packages the bundle carries itself. A source
grep both misses the first and over-reports the second.

## Step 2 — Get the product

Unpack the product build the app targets. A full install is not required; any directory containing
the product's jars is enough, and the scan searches recursively. The scan works in `$TMPDIR`, so
point it at a writable directory under a sandbox.

## Step 3 — Run the scan

```bash
./scripts/check-public-api.sh \
  --bundle target/my-app-1.0.0.jar \
  --product-dir /path/to/unpacked/product
```

Add `--format tsv` for `package<TAB>verdict<TAB>resolution`, one line per import, to diff across
product versions or feed another tool.

Exit status is 1 when the bundle has a mandatory import of an internal package, which is the case
that stops the bundle resolving.

## Step 4 — Read the verdicts

| Verdict | Meaning | Action |
|---|---|---|
| `public` | named in the allowlist and not excluded | none |
| `deprecated` | published now, already marked for withdrawal | plan the move before it becomes internal |
| `internal` | on the product classpath, not published to apps | an import cannot wire; bundle the library or stop using it |
| `unknown` | in none of the product's export sets | supplied by another bundle, by the JDK, or absent |

Read the verdict together with the resolution:

- **`internal` or `unknown`, mandatory** — the bundle does not resolve at all, so the app does not
  install and no feature of it works. Fix these first.
- **`internal` or `unknown`, optional** — the bundle resolves and the app installs, then fails at
  the first call into that package. One feature breaks and the rest keep working.

`unknown` is not a problem on its own. Most JDK packages land there, as does anything an app gets
from another plugin. Treat it as "this scan cannot speak for it" rather than as a finding.

## How much to trust the result

Measured against a diagnostic app on an early Confluence 11 build, comparing verdicts with what the
running product actually did for 19 imports: **15 verdicts matched, 4 over-reported.** All four
disagreements were in the same direction — the scan called a package internal and the app could
still use it. No package the scan called public or otherwise safe turned out to fail.

So the asymmetry is the useful part. A `public` verdict has held up. An `internal` verdict means
*investigate*, not *confirmed broken*: it is accurate about the product's own allowlist, and the
allowlist is not the only thing that can publish a package.

The four over-reports were `org.apache.xerces.parsers`, `org.apache.xalan.processor`,
`org.apache.xml.serializer` and `org.apache.xpath` — each absent from Confluence's allowlist while
still reachable, and each with a sibling package that genuinely was not. Something other than the
system bundle publishes them, which is exactly the gap below.

## What this scan cannot see

- **A package exported by another bundle.** The allowlist governs what the product publishes from
  its own classpath. A bundled plugin can export a package on its own, and an app can wire to that.
  This is the source of the over-reports above.
- **Whether the classes are really there.** A package can match the scanner's include patterns and
  still not exist in any jar that was scanned. The scan reports what the configuration permits, not
  what the runtime found.
- **Bamboo.** Bamboo ships no allowlist resource: its set is the platform default compiled into
  `atlassian-plugins-osgi`, plus `com.atlassian.bamboo.*`. The scan reports this and stops rather
  than guessing. Check a Bamboo app against a running instance.
- **Anything not reached through OSGi.** Reflection, `ServiceLoader` and resource lookups do not
  appear in `Import-Package`, so they are outside this scan entirely.

For a definitive answer on a single package, install the app on the product and call the code.

## Supported products

| Product | Allowlist read from | Also read |
|---|---|---|
| Confluence | `public-api.yaml` in `com.atlassian.confluence.confluence-*.jar` | `package-exports.yaml` |
| Jira | `osgi/public-packages.txt` in `jira-core-*.jar`, or loose in an unpacked webapp's `WEB-INF/classes/osgi/` (Jira 12) | `osgi/public-packages-excludes.txt`, `osgi/deprecated-packages.txt`, `osgi/package-includes.txt`, `osgi/package-excludes.txt` |
| Bitbucket | `package-public-includes.properties` in `bitbucket-platform-*.jar` | `package-public-excludes.properties`, `package-includes.properties`, `package-excludes.properties` |
| Crowd | `com/atlassian/crowd/public-api.yaml` in `crowd-server-*.jar` | `com/atlassian/crowd/package-exports.yaml` |
| Bamboo | not shipped as a resource | — |

Every product decides a package the same way — public if it matches the allowlist and matches no
exclude — so only the extraction differs.

## Notes

- **A trailing `*` matches any depth; an entry without one matches exactly that package.** So
  `org.apache.*` covers `org.apache.commons.io`, while a bare `org.apache.xml` covers only
  `org.apache.xml` and not `org.apache.xml.serialize`. That is why a list carries both
  `org.apache.xml` and `org.apache.xml.*` when it means the package and its descendants. No shipped
  list uses a wildcard anywhere but the final segment.
- **Per-product lists differ in granularity, not just content.** Confluence's allowlist runs to
  about 1100 entries and Jira's to about 120, so Jira leans on broad wildcards where Confluence
  enumerates. A package can be public on one and internal on the other for that reason alone.
- **An optional import is not a fix, only a downgrade.** Marking an import optional converts a
  bundle that will not install into an app that installs and fails later. That is the right trade
  for a feature an app can live without and the wrong one for a dependency it cannot.
- **Re-run per product and per version.** The allowlist is part of the product build. A result from
  one milestone does not carry to the next, which is the point of reading it from the build rather
  than from a snapshot.
