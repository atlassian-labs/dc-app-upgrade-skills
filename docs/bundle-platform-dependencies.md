---
skill: bundle-platform-dependencies
skill-sha256: bd1103f385d7
---

# Bundle platform libraries your app uses

On Platform 9, an app that uses certain third-party libraries can no longer rely
on the product to supply them. The app has to ship those libraries itself:
declare them at compile scope, embed them in the plugin bundle, and fix the
OSGi imports so they resolve from the bundle. This guide covers the manual
migration for the libraries the skill supports. The [last section](#use-the-skill)
describes the `bundle-platform-dependencies` skill, which discovers the usage,
plans the POM edits and checks the built artifact.

## Is my code affected?

This applies to a plugin that packages itself (an AMPS-built app, or an embedded
module that the plugin ships). Plain libraries are not applicable; assess their
source migrations separately.

1. **Source.** Search executable main sources for the package prefixes below.
   Test sources and build-generated sources are not relevant.

   ```bash
   git grep -nE 'org\.apache\.commons\.(io|codec|text|validator|collections)|org\.json|org\.jsoup|org\.jdom2|com\.rometools|org\.apache\.(xerces|xalan|xpath|xml\.(serialize|serializer|dtm|utils|res)|bcel|regexp|wml|html\.dom)|com\.google\.gson|net\.oauth|org\.w3c\.tidy' \
     -- 'src/main/*.java' 'src/main/*.kt' 'src/main/*.scala' 'src/main/*.groovy'
   ```

   A match in a comment or a string, or a similarly named local type, is not usage.
2. **Packaging.** Check whether the matching artifact is declared at
   `compile` scope in your effective POM and embedded in the bundle. Dependency
   presence alone does not prove work is needed, and an app that already bundles
   a library on purpose should stay as it is.
3. **Target.** A package the target product itself exports may not need bundling.
   That needs exact evidence for your product and version (see Before you start).
   One exported package does not prove a whole family is provided.

Supported libraries and the versions the skill adds by default:

| Family | Packages | Artifact (default version) |
|---|---|---|
| commons-io | `org.apache.commons.io` | `commons-io:commons-io` 2.22.0 |
| commons-codec | `org.apache.commons.codec` | `commons-codec:commons-codec` 1.22.1 |
| commons-text | `org.apache.commons.text` | `org.apache.commons:commons-text` 1.15.0 (excludes `commons-lang3`) |
| org-json | `org.json` | `org.json:json` 20240303 |
| jsoup | `org.jsoup` | `org.jsoup:jsoup` 1.23.2 |
| jdom2 | `org.jdom2` | `org.jdom:jdom2` 2.0.6.1; adds `jaxen:jaxen` 1.2.0 when `org.jdom2.xpath` is used |
| rome | `com.rometools.rome`, `com.rometools.utils` | `rome` and `rome-utils` 2.1.0 (exclude `slf4j-api`) plus `jdom2`; `jaxen` for `org.jdom2.xpath` |
| commons-validator | `org.apache.commons.validator` | `commons-validator` 1.11.0; for XML-based use also `commons-beanutils` 1.11.0, `commons-digester` 2.1, `commons-logging` 1.3.5, `commons-collections` 3.2.2 |
| xerces-xalan | `org.apache.xerces`, `org.apache.xalan`, `org.apache.xpath` and related | `xerces:xercesImpl` 2.12.2 (excludes `xml-apis`), `xalan:xalan` and `xalan:serializer` 2.7.3 |
| commons-collections3 | `org.apache.commons.collections` | `commons-collections:commons-collections` 3.2.2 |
| gson | `com.google.gson` | `com.google.code.gson:gson` 2.14.0 (excludes `error_prone_annotations`) |
| net-oauth | `net.oauth` | `net.oauth.core:oauth` 20100601-atlassian-5 |
| jtidy | `org.w3c.tidy` | `jtidy:jtidy` r8-20050104 |

These defaults follow the Platform 9 declarations; they are not a
latest-version policy. Keep an existing version where your policy allows.

Not supported: `org.apache.html.dom` (Xerces HTML DOM) has no supported
bundling repair. Other Xerces and Xalan packages in the same app are still
migrated. jsoup `re2j` selector usage needs the compatible `re2j` dependency
confirmed and bundled by hand.

## Before you start

- The target product and exact version (Jira, Confluence or RefApp have bundled
  evidence; Bitbucket, Bamboo and Crowd need a compatible export snapshot from
  you), and the target runtime Java major. A compiler release level is not a
  runtime selection.
- Which module owns plugin packaging. Embedded modules whose code it ships
  belong in the same migration; a separate plugin artifact is a separate
  migration.
- Active Maven profiles, since dependencies owned by a profile or inherited
  AMPS instructions are hard to assess.
- The skill's tooling needs JDK 21 or newer and Maven (see the last section).
- Your POM sorting rules, if any (for example `sortpom:verify`).

## Migrate

Make these POM edits for each library in use. A later `Import-Package` change
only matters if you pin imports yourself.

1. **Declare the dependency at compile scope.** Add it if missing; if it is
   present at another scope, change the scope to `compile`. Add the exclusions
   from the table. Keep scope grouping and element order your POM already uses.

   ```xml
   <!-- Before -->
   <dependency>
       <groupId>com.google.code.gson</groupId>
       <artifactId>gson</artifactId>
       <scope>provided</scope>
   </dependency>

   <!-- After -->
   <dependency>
       <groupId>com.google.code.gson</groupId>
       <artifactId>gson</artifactId>
       <version>2.14.0</version>
       <scope>compile</scope>
       <exclusions>
           <exclusion>
               <groupId>com.google.errorprone</groupId>
               <artifactId>error_prone_annotations</artifactId>
           </exclusion>
       </exclusions>
   </dependency>
   ```

   Add a version only when you do not already manage it.
2. **Embed it.** If the AMPS plugin has `Embedded-Dependencies` instructions,
   append the new artifact IDs (skip if `*` is already listed). A complex
   selector needs a manual decision.
3. **Fix `Import-Package`.** If you pin `Import-Package`:
   - remove a negated `!package` selector for a package you now bundle;
   - add optional imports for packages the library may reference but does not
     need:

   | Family | Optional imports |
   |---|---|
   | commons-io | `sun.nio.ch` |
   | jdom2, rome | `org.jaxen.*`; with `org.jdom2.xpath` use `org.jdom.*`, `org.dom4j.*`, `nu.xom.*` |
   | jsoup | `com.google.re2j.*`, `org.jspecify.annotations.*` |
   | gson | `com.google.errorprone.annotations.*` |
   | commons-validator (only `routines`) | `org.apache.commons.digester.*`, `beanutils.*`, `collections.*`, `logging.*` |
   | xerces-xalan | `org.apache.xml.resolver*`, `sun.io` (Xerces); `org.apache.xerces.parsers`, `org.apache.xml.serialize` (Xalan without Xerces) |
   | jtidy | `org.apache.tools.ant.*` |

   If `Import-Package` is absent, a plan that needs optional imports creates
   `<Import-Package>*</Import-Package>` and appends the optional clauses. A
   positive `package.*;resolution:=optional` clause is an optional import, not
   an exclusion; leave it.
4. **Allow bundling.** For a newly bundled library add its `groupId:artifactId`
   to the `banningExcludes` of the AMPS plugin configuration, so the bundling
   check accepts it.

Cases that need judgment: a profile-owned dependency, inherited AMPS
instructions, an `Embedded-Dependencies` selector more complex than a list, and
`Import-Package` containing XML comments or mixed content. Scripts, custom
source roots, dynamic loading and reflection need a manual check; absence of a
match proves nothing for them. For Kotlin, Scala and Groovy, confirm that a
matched name really refers to the library.

## Verify

1. Reopen the changed POMs and check the diff touches only the selected
   dependencies, exclusions, `Embedded-Dependencies`, `Import-Package` and
   `banningExcludes`.
2. Run your normal clean build and any non-mutating POM format check.
3. Inspect the built plugin JAR. Check that the library classes are in the bundle
   (including nested JARs), that classes forbidden by an exclusion are not
   (for example `javax/xml/` when `xml-apis` is excluded), and that the
   manifest's mandatory `Import-Package` entries for the library's own
   dependencies are exported by the target product:

   ```bash
   unzip -p target/<plugin>.jar META-INF/MANIFEST.MF
   jar tf target/<plugin>.jar | grep -E 'com/google/gson|BOOT-INF|\.jar$'
   ```
4. Compare mandatory imports against the target product's exported OSGi
   packages. A package name alone does not prove an import resolves: check its
   version constraints and any bundle-constrained import. A package in neither
   the bundle nor the imports is a defect; an optional import does not hide an
   unavailable package your code uses.
5. Run the app on the target product. A successful POM edit or package build
   does not prove the library shipped or that the plugin resolves.

Static export matching is not a full OSGi resolver, so target-runtime
validation is still needed.

## Troubleshooting

| Symptom | Cause or fix |
|---|---|
| Plugin does not resolve: missing mandatory import for a library package | The package is neither bundled nor exported by the target. Check that it is embedded and not excluded by a `!package` selector |
| Import for a package the target does export fails on version | Exported version outside the import range; compare with the target's exports |
| Verification reports provisional evidence | The snapshot is for a nearest version, another qualifier or another Java major, not an exact match |
| A required package is "not covered" by the snapshot | An evidence gap, not proof the product lacks it. Refresh the snapshot for the exact target |
| Constrained import cannot be confirmed | Bundle-constrained imports need provider evidence beyond a package name |
| Gson runtime failure about `sun.misc` | Gson reflects on `sun.misc.Unsafe`, which bytecode analysis cannot see; check the manifest imports it |
| `org.apache.html.dom` classes missing | No supported bundling repair; handle by hand |

## Use the skill

Ask your agent to "bundle platform dependencies" for your plugin. The
`bundle-platform-dependencies` skill has no `--family` option: discovery always
assesses every supported family above for the selected modules. Its launcher
(`scripts/bundle-platform-dependencies.sh`) needs JDK 21 or newer (from
`JAVA_HOME`, otherwise `java` on `PATH`) and Maven, uses the OpenRewrite recipes
that ship alongside the skill, and caches compiled tooling under
`.migration-state/bundling-tool`. The target Java is passed explicitly with
`--target-java-version`, since it can differ from the tooling JDK.

**Analyze.** It asks for the target product if missing, then reads executable
main-source usage (Java, and an approximate scan of Kotlin, Scala and Groovy
that ignores comments and literals, without compiling), the effective Maven
dependencies, the packaging configuration and the target product's OSGi exports.
It writes evidence and a plan with the operations listed above. Each dependency
is classified as needing bundling, provided by the target, already bundled or
uncertain. Target evidence comes from a bundled snapshot (Jira 12.0.0,
Confluence 11.0.0, RefApp 9.0.6); for other versions it can extract a snapshot
from a product distribution (`exports` command), or accept one you supply.
A discovery failure, incomplete scan or unresolved finding is reported as
uncertain or blocked, never as not applicable. Unsupported findings (such as
`org.apache.html.dom`) are reported without cancelling the supported work.
Analysis makes no edits.

**Approve and apply.** You review the full findings and approve all of them or
decline specific families (`approve --approve-family`); a declined family stays
as remaining work, and a companion needed by an approved family stays in. `check`
confirms the plan is current and is not stale or unresolved, then `apply` runs
one composite OpenRewrite recipe that edits POMs only, preserving unrelated
content, and no Java sources. An unresolved or stale plan is not applied.

**Verify.** `verify` inspects the built plugin JAR against the plan: contained
classes, forbidden classes, mandatory imports referenced by the bundled library
classes (selected with `jdeps`), and the imports for packages classified as
provided by the target, all checked against the export snapshot. Exit status 2
means something needs attention. It refreshes export evidence from the exact
target distribution when one is available.

It does not commit or push, edit files outside the plugin and declared embedded
modules, or prove runtime compatibility. Unsupported packages, scripts and
dynamic loading, and running the app on the target product are left to you.
