---
name: serviceloader-bootdelegation-readiness
description: Assesses JAXP, JAXB, and ServiceLoader discovery in Data Center plugins and libraries, including discovery performed inside dependencies. Use when source, provider metadata, dependency evidence, or runtime traces indicate service discovery may rely on the thread-context classloader or OSGi boot delegation.
metadata:
  version: 0.0.1
---

# Service discovery readiness

Platform 9 removes `META-INF.services` from the OSGi boot-delegation list, so a plugin bundle sees only the providers it ships or imports. Assess direct and library-owned discovery paths that depend on `META-INF/services` visibility or the thread-context classloader. Do not infer runtime compatibility from compilation, imports, or a clean direct-call scan.

## Scope

Plugins and independent libraries. Classify each path against the intended classloader and acceptable default provider before editing. OSGi remedies apply only to bundle deployments; provider metadata that is valid on a flat classpath is preserved. Source repairs need no product target; host-provider and runtime claims do.

## Applicability

The app is in scope when its main source or packaged resources:

- call a no-argument `newInstance()` on `DocumentBuilderFactory`, `SAXParserFactory`, `TransformerFactory`, `XMLInputFactory`, `XMLOutputFactory`, `XPathFactory`, `DatatypeFactory` or `SchemaFactory`
- call `JAXBContext.newInstance(...)` or one-argument `ServiceLoader.load(...)`
- ship a `META-INF/services/` provider file
- depend on a library whose source, bytecode, metadata, or runtime trace shows discovery on an app call path

## Detection

```bash
# factory discovery through the thread-context classloader
grep -rnE '(DocumentBuilderFactory|SAXParserFactory|TransformerFactory|XMLInputFactory|XMLOutputFactory|XPathFactory|DatatypeFactory|SchemaFactory)\.newInstance\s*\(\s*\)' \
    --include='*.java' --include='*.kt' . | grep -v '/src/test/'

# JAXB context discovery, and ServiceLoader without an explicit classloader
grep -rnE 'JAXBContext\.newInstance\s*\(|ServiceLoader\.load\s*\(\s*[A-Za-z0-9_.]+\.class\s*\)' \
    --include='*.java' --include='*.kt' . | grep -v '/src/test/'

# provider files the app ships, and what they name
find . -path '*/META-INF/services/*' -type f -not -path '*/target/*' \
    -exec sh -c 'echo "== $1"; cat "$1"' _ {} \;
```

These cover direct calls and shipped providers only; they do not show that dependencies never perform discovery. When a dependency or runtime trace suggests indirect discovery, inspect that dependency's relevant source, bytecode, metadata, or call path. Do not scan every transitive dependency by default.

## Evidence to record

For each path: location or caller, direct or library-owned discovery operation, dependency and version (library-owned paths), intended provider and classloader context, whether the app requires the provider or can operate without it, empty-result behavior and any acceptable fallback, evidence source, and the runtime check that can exercise it. Establish optionality from the affected operation's contract and required behavior; an empty iterator or successful startup alone does not establish it. Keep source and dependency evidence distinct from runtime results.

Statuses use the [`assess` result vocabulary](../migrate-dc-app/references/contracts.md). Use `uncertain` when a suspected path's operation, provider, context, or empty-result/fallback behavior needed for the decision cannot be established. Use `not-applicable` only after inspecting direct calls and every evidence-indicated dependency path, naming what was inspected. Use `already-compatible` only for an evidence-backed no-edit decision, and record caller-delegated runtime checks separately as `deferred` with owner and reason.

Stop without edits when no actionable discovery path is found. Otherwise load [references/migration.md](references/migration.md) for the supported changes, verification, and report.
