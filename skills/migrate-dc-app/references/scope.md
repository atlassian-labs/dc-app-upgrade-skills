# Module scope and packaging ownership

## Inventory and dispatch

A component/reactor request covers its declared Maven modules recursively under
active profiles, plus requested frontend workspaces. A plugin request covers
that plugin and its actual embedded modules, not unrelated siblings. Respect
explicit file/module restrictions; build-only dependencies are not edit targets.

Record each module's path, actual packaging, source/resources, build configuration,
and per-capability evidence and disposition. A parent POM does not establish its
children's packaging. Assess all requested modules, including libraries no plugin
ships. Unassessed modules prevent a claim of complete migration.

Determine packaging owners and embedding/shading from descriptors and build
configuration. A Maven dependency does not prove embedding. Do not invent owners,
wrappers, source relocations, or `embedded_modules` entries for independent libraries.

| Work | Scope and validation |
|---|---|
| Source/API/dependency, JVM, frontend migrations | Every affected requested module, including independent libraries. Follow each leaf's supported execution unit. |
| Bundling and transformerless conversion | Each real plugin owner and its actual embedded modules. |
| Manifest/public-export audits and installation | Actual plugin/bundle artifact and resolved product target. An absent library manifest is not a clean audit. |
| Shared parent/toolchain changes | Authorized declaring file, edited once; record and validate all affected modules. |

Dispatch single-module leaves separately and packaging leaves once per owner.
Independent libraries receive source-migration packets, never fake embedding.
Keep edits sequential; migrate shared source once, then refresh consumer evidence.
A relevant but unsupported module stays visible with its effect on completion;
the orchestrator does not expand a leaf's implementation support.

## Targets and verification

A Platform 9 goal suffices for product-independent library/API work. Resolve
runtime Java separately when needed; compiler release is not runtime evidence.

For product-dependent checks, prefer explicit caller targets, then effective
AMPS configuration and active profiles. Generic AMPS identifies no product;
a configured baseline version is not the requested upgrade. Accept per-owner
target maps and explicitly authorized RefApp hosts where the check supports them.
Do not default to RefApp or claim it proves another product's compatibility.

Resolve missing exact targets under the interaction/on-blocker policy before
product-dependent edits/checks. Continue read-only source discovery across the
requested scope; library-only work needs no invented product blocker.

Build/test affected modules with required reactor dependencies. Keep library
validation separate from plugin packaging/runtime results. A shared build does
not replace per-module assessment or prove that a product supplies dependencies.
