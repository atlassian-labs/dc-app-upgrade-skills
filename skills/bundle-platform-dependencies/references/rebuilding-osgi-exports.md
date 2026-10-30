# Rebuilding the compact OSGi export snapshots

These files support `bundle-platform-dependencies`, not a whole-application API audit.
They contain externally usable product exports for packages contained in or
referenced by the supported library versions and their conditional companion
libraries in `BundleDependencyModel`. Contained packages support pre-migration
availability checks; external references support post-bundling verification.
The scope comes from JAR bytecode and declared reflective requirements, not from
an example application or runtime fixture.

## Inputs

- An installed migration catalog, Maven, and the selected product's runtime JDK
  (Java 25 for the currently published snapshots).
- The exact product WAR or exploded webapp, with its version metadata intact.
- Its container library directory when APIs are supplied outside the WAR.
  Use the container selected by that product build, as listed below.
- A local Maven repository containing the versions in `BundleDependencyModel`,
  including `conditionalDependencies`. The extractor reads these JARs without
  resolving or downloading dependencies. It fails with the missing GAV if one
  is absent. Resolve that artifact using your normal Maven settings, for example:

```bash
mvn org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get \
  -Dartifact=GROUP_ID:ARTIFACT_ID:VERSION -Dtransitive=false
```

The current product artifacts are:

| Product | Maven artifact | Source version | Tomcat libraries | Output filename |
|---|---|---|---|---|
| Jira | `com.atlassian.jira:atlassian-jira-webapp:war` | the Jira 12 early-access build | read from the archive (see below) | `jira/12.0.0.json` |
| Confluence | `com.atlassian.confluence:confluence-webapp:war` | `11.0.0` | `11.0.25` | `confluence/11.0.0.json` |
| RefApp | `com.atlassian.refapp:atlassian-refapp:war` | `9.0.6` | `11.0.15` | `refapp/9.0.6.json` |

For Jira, use the [public early-access download](https://www.atlassian.com/software/jira/download-eap).
Read the source version from the archive's webapp `pom.properties` and the Tomcat
version from its bundled `catalina.jar`. The snapshot uses every `lib/*.jar` from
that standalone archive, including the product's additional container libraries,
and records the archive's container library directory as its provenance.

For Confluence and RefApp, resolve the table's Tomcat version using ZIP coordinates
`org.apache.tomcat:apache-tomcat:VERSION-atlassian-hosted:zip:windows-x64`;
extract its `lib/*.jar` files without changing filenames.

Product artifacts may require access to the relevant Atlassian Maven repository.
The extractor verifies the version inside the distribution and requires its JVM
Java major to match `--target-java-version`.

Distribution extraction supports Jira, Confluence, and RefApp. Supporting a new
product requires an adapter for its distribution metadata and export declarations,
validated against that product's exact distribution. A compatible caller-supplied
snapshot can be consumed without such an extraction adapter; do not infer exports
from the mere presence of library JARs in a different product.

## Generate a replacement

Set `resolved_skill_directory` to this installed skill and use absolute paths for
a writable working directory,
Maven executable, local Maven repository, product distribution, and container
libraries. Select the product's runtime JDK before running the launcher.

```bash
PRODUCT=confluence
TARGET_VERSION=11.0.0
TARGET_JAVA_MAJOR=25
NORMALIZED_VERSION=${TARGET_VERSION%%-*}
OUTPUT="$WORKSPACE/$PRODUCT-$NORMALIZED_VERSION.json"

bash "$resolved_skill_directory/scripts/bundle-platform-dependencies.sh" \
  exports --workspace "$WORKSPACE" --maven "$MAVEN_BIN" \
  --product "$PRODUCT" --product-version "$TARGET_VERSION" \
  --target-java-version "$TARGET_JAVA_MAJOR" \
  --distribution "$PRODUCT_DISTRIBUTION" --container-libs "$CONTAINER_LIB_DIRECTORY" \
  --library-repository "$MAVEN_REPOSITORY" --output "$OUTPUT"
```

Repeat with each selected product's version, distribution, and container. No plugin build, baseline JAR,
application source, or orchestrator is required to rebuild the published catalog.

The extractor collects contained class packages and uses JDK `jdeps` to derive external package references from every
catalog library and conditional companion, excluding references within the same
JAR. It uses effective multi-release classes and omits Java module descriptors
so absent Java modules do not prevent OSGi analysis. It also includes the declared
reflective requirements in `BundleDependencyModel.reflectivePackageReferences`
(currently Gson's `sun.misc.Unsafe` lookup). It intersects that scope with product exports,
removes internal-only providers, and writes schema 2. It preserves package versions
when known and matching attributes
or directives when present. It omits repeated extraction paths, per-package provenance,
visibility markers, bundle symbolic names/versions, duplicate versions, empty fields,
and `uses` diagnostics.
Checksums and library coordinates/versions remain in snapshot-level provenance.

`coveredPackages` records every package queried, including ones without an eligible
export. Do not remove that coverage information: an absent covered export means no
usable provider was found; an unlisted requirement needs refreshed evidence. Do not
add a package to `exports` merely because a library references it.

## Review and publish the data

1. Check source version, Java major, distribution/container checksums, and library
   versions in the generated metadata. No machine-specific absolute paths should
   be present.
2. Confirm that unrelated packages such as `com.atlassian.applinks.application.fecru`
   are absent and that exports have no internal access markers. Missing package
   versions must remain unknown, not inferred from a containing JAR's version.
3. Run the recipe tests using JDK 21 or newer:

   ```bash
   "$MAVEN_BIN" --batch-mode --no-transfer-progress \
     -f "$resolved_skill_directory/../../recipes/openrewrite/pom.xml" test
   ```

4. Verify a packaged migrated application with its bundling plan and the candidate
   snapshot via `verify --export-snapshot "$OUTPUT" --target-java-version 25`.
   Inspect checked imports, out-of-scope imports, and evidence gaps. Run the
   applicable product runtime validation before treating a migration as complete.
5. Replace `recipes/openrewrite/src/main/resources/osgi-exports/$PRODUCT/$NORMALIZED_VERSION.json`
   with the reviewed output. Ensure `osgi-exports/index.json` contains that relative
   path once. Keep one file per normalized version; qualifiers belong in metadata.

Rebuild all products when changing supported library versions or companion rules.
Keep declared reflective requirements aligned with the supported libraries.
Bytecode discovery alone does not cover reflective class names assembled at runtime or
prove that optional integrations are exercised; static checks do not replace an
OSGi resolver or runtime validation.

## Refresh while migrating an application

An application may preserve a different library version and introduce references
outside the published scope. The verifier derives requirements from the actual
selected library classes and reports uncovered requirements as evidence gaps.
Run `exports --verification-report "$VERIFICATION_REPORT"` instead of
`--library-repository` to query exactly those required imports (including app-used
packages deliberately left to the product by preflight) against the
actual target distribution, then rerun verification. The report must come from the
current built artifact and contain `verificationScope: selected-bundled-library-requirements`.
This selects what to inspect; it does not supply proof that a provider exists.
Keep this application-specific snapshot in caller-owned storage rather than replacing
the published all-family snapshot. See the skill's
[export workflow](exports.md).

## Imports constrained to a provider bundle

The generator intentionally omits `bundleSymbolicName` and `bundleVersion` from
both published and refreshed snapshots. Ordinary package imports do not need them.
An import specifying `bundle-symbolic-name` or `bundle-version` therefore produces
an evidence gap. Repeating the standard extraction will not resolve that gap.

If such an import is encountered, obtain the exporting bundle's identity and
version from the exact target runtime's resolved wiring or registered bundle
manifest. For a normal bundle, inspect `Bundle-SymbolicName`, `Bundle-Version`
and the relevant `Export-Package` clause in its `META-INF/MANIFEST.MF`.
Host/system exports require evidence of the actual framework provider; the JAR
containing a class is not necessarily its OSGi provider.

In a caller-owned copy of the snapshot, add `bundleSymbolicName` and/or
`bundleVersion` to the relevant export record using those observed values, and
record the evidence source in snapshot provenance. Rerun `verify --export-snapshot`
with that copy. The verifier still supports these optional fields and validates
the constraint when they are supplied. Do not infer them from product versions,
artifact filenames, or the requested import, and keep provider fields out of the
published minimal snapshots. If provider evidence is unavailable, leave the gap
unresolved.
