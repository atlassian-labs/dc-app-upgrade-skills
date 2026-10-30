# Target OSGi export evidence

Read this when preflight needs target evidence or when performing artifact verification. The plugin's manifest
describes what it imports; the target product snapshot describes available exports
within the bundling capability's scope.
Building the app refreshes only the former. A baseline app build is optional.

Verification checks mandatory imports referenced by the selected bundled library
classes on the plugin's actual bundle class path, plus app-used packages that
preflight classified as target-provided. The verification report retains those
packages in `targetProvidedPackages`; its scoped import list includes their actual
built import constraints for refresh. It reports unrelated application
imports in `outOfScopeImports`; those belong to a broader compatibility audit.
Discovery includes conditional companion libraries and known reflective
requirements declared by the catalog (such as Gson's `sun.misc`). It cannot infer
reflective class names assembled at runtime or prove optional code paths execute.

## Bundled snapshots

The recipe resources contain `osgi-exports/<product>/<normalized-version>.json`,
indexed by `osgi-exports/index.json`. There is at most one snapshot per product
and normalized version. Normalization drops everything from the first hyphen:
`11.0.0-<qualifier>` and `11.0.0` both map to `confluence/11.0.0.json`.
Keep the actual `sourceVersion`, Java major, extraction time, and provenance in
the JSON; do not relabel prerelease evidence as release evidence.

Verification selects the matching normalized version first, otherwise the nearest
snapshot for the same product. It never substitutes another
product. Only an exact source version and target Java match qualify as exact
evidence. A nearest-version, different qualifier, or different Java match is
provisional, even when every import appears in the snapshot. Report the selected
source and evidence gaps; do not call provisional verification passed.

Schema 2 snapshots contain only relevant, externally usable exports. Their
`coveredPackages` list records the packages examined, including packages with no
usable provider. A requirement outside that list is an evidence gap, not proof
that the product does not export it. Refresh for that requirement. A covered
package without an export is unavailable in the selected evidence.

The published scope includes packages contained in the library versions and conditional
companions in `BundleDependencyModel`, plus their external and declared reflective
references. It is not derived from a particular application's imports.
Snapshots retain exported package versions and relevant attributes/directives.
Bundle symbolic names and bundle versions are omitted. Imports explicitly constrained
to a bundle require additional provider evidence; ordinary snapshot regeneration
does not supply it. See the maintainer instructions below for obtaining that evidence. A package name alone is not proof that a constrained import
can resolve. Containment/forbidden-class checks still run independently. Export
matching is a static check, not a complete OSGi resolver or runtime validation.

## Obtain missing preflight evidence

Preflight uses the target snapshot to distinguish dependencies that need bundling
from packages the target provides. An unavailable, approximate, or insufficiently
covered snapshot cannot justify declaring a dependency provided. Keep those
findings uncertain until exact evidence is available. Preserve intentionally
bundled dependencies; product exports are not an instruction to unbundle them.
For a Java mismatch, first check the resolved target runtime against caller and
project configuration. Correct a mistaken target argument before generating new
evidence; do not regenerate a snapshot merely to match the tooling JVM.

For Jira, Confluence, and RefApp, locate an exact target WAR or exploded webapp in caller
context or the configured local Maven repository. If absent, resolve the exact
product artifact through the caller's normal Maven repository configuration:
Jira uses `com.atlassian.jira:atlassian-jira-webapp:VERSION:war`; Confluence uses
`com.atlassian.confluence:confluence-webapp:VERSION:war`; RefApp uses
`com.atlassian.refapp:atlassian-refapp:VERSION:war`. Respect caller network
and provisioning restrictions. If access is unavailable, request the distribution
location or repository access. Extraction needs no running product or app build.

Generate into a caller-owned evidence directory, for example
`$WORKSPACE/.migration-state/osgi-exports/$PRODUCT/$NORMALIZED_VERSION.json`.
Use the target runtime JDK (set `JAVA_HOME`; the launcher prefers it over the
`java` on `PATH`) and the local Maven repository containing all catalog
libraries and conditional companions. Resolve missing library artifacts with the
caller's Maven settings as described in the maintainer guide below.

```bash
bash "$resolved_skill_directory/scripts/bundle-platform-dependencies.sh" \
  exports --workspace "$WORKSPACE" --maven "$MAVEN_BIN" \
  --product "$PRODUCT" --product-version "$TARGET_VERSION" \
  --target-java-version "$TARGET_JAVA_MAJOR" --distribution "$PRODUCT_DIRECTORY" \
  --container-libs "$TARGET_CONTAINER_LIB_DIRECTORY" \
  --library-repository "$MAVEN_REPOSITORY" --output "$EXPORT_SNAPSHOT"
```

Rerun the original full `analyze` invocation with
`--export-snapshot "$EXPORT_SNAPSHOT" --target-java-version "$TARGET_JAVA_MAJOR"`.
Keep the generated snapshot with the plan and preserve its provenance. Review
the new findings before approving application. Do not repeatedly regenerate from
unchanged inputs if coverage or version constraints remain unresolved.

Automatic distribution extraction supports Jira, Confluence, and RefApp.
RefApp extraction executes the trusted distribution's default export configuration;
customized runtimes require separately observed evidence.
For Bitbucket, Bamboo, or Crowd, accept an externally generated compatible snapshot
with exact target metadata and coverage, or report that an extraction adapter and
target evidence are needed. Do not run a supported product's extractor on another
product and interpret empty results as missing exports.

## Refresh after packaging

Use an available exact target product distribution as evidence. Read its version
metadata rather than inferring the version from the requested filename. Do not
derive provider evidence from the plugin's imports, Maven dependency names, or
an unrelated runtime. Observe caller restrictions on product provisioning.

After running `verify` on the current built artifact, generate a fresh snapshot
into the caller's writable evidence directory. Use that invocation's report even
when it returns `needs-attention`: its scoped mandatory imports define what to
look up, and the distribution independently proves whether providers exist.
Do not use a stale report after a failed invocation. Use the target runtime JDK:

```bash
bash "$resolved_skill_directory/scripts/bundle-platform-dependencies.sh" \
  exports --workspace "$WORKSPACE" --maven "$MAVEN_BIN" \
  --product "$PRODUCT" --product-version "$TARGET_VERSION" \
  --target-java-version "$TARGET_JAVA_MAJOR" --distribution "$PRODUCT_DIRECTORY" \
  --container-libs "$TARGET_CONTAINER_LIB_DIRECTORY" \
  --verification-report "$VERIFICATION_REPORT" --output "$EXPORT_SNAPSHOT"
```

The optional `--container-libs` directory must belong to the selected product's
container (for example its Tomcat `lib` directory). Include it when extracting
from a WAR: container APIs such as Servlet are not normally inside the WAR.
The extractor records the container library checksum and applies the product's
export policy; it does not treat every container class as a public export.
Unknown host package versions remain unknown, so constrained imports can still
require further evidence. JDK exports are derived using the target JDK.

After building the migrated app, rerun verification against that exact JAR with
`--export-snapshot "$EXPORT_SNAPSHOT" --target-java-version "$TARGET_JAVA_MAJOR"`.
An explicit snapshot replaces bundled evidence for this invocation. Keep both
verification results and extraction provenance. Do not modify an installed catalog
during an app migration; a catalog maintainer can replace the single normalized
version file after reviewing newly generated evidence.

At the post-build checkpoint, refresh whenever the exact target distribution is
available, even when the bundled snapshot matches. Otherwise refresh when
evidence is approximate or known to be stale, and investigate
unmatched imports with fresh exact-target evidence when available. Do not rely
only on failures to detect staleness: a stale snapshot can wrongly accept a removed
export. If exact evidence cannot be obtained, report the verification gap and
required follow-up. Refreshing evidence alone must not trigger a second recipe
application; only a confirmed, in-scope migration correction justifies new edits.

The orchestrator owns extraction and shared build/artifact checkpoints in delegated
runs. Standalone use follows the same procedure without requiring an orchestrator.

Catalog maintainers rebuild all supported families with `--library-repository`
instead of `--verification-report`. See
[rebuilding the published snapshots](rebuilding-osgi-exports.md)
for prerequisites, commands, and validation.
