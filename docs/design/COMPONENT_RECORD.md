# The component record — one description of a component, three surfaces

Status: **plan** (2026-09). Root-cause analysis + phased proposal for the preview
overrides format, the catalog data model, and the UI builder. No code yet; every
number below is measured from the checkouts named in [§1](#1-what-is-actually-wrong).

> **Thesis.** There is no *component* in this system. There are **previews**
> (renders), **catalog entries** (publishing identities glued onto preview
> functions), and — in the UI builder — a **hand-transcribed capability table**
> that shares nothing but a name with the catalog it claims to describe. Every
> problem below is a consequence: overrides are written into composable bodies
> because there is nowhere else to declare them; code generation is a *text
> subtraction* because there is no call site to print; the builder cannot
> generate source it trusts because its components are three parallel `when`
> statements rather than real composables.
>
> The fix is one record, derived rather than authored, with a **mechanical**
> conformance ladder deciding which surface each component reaches.

Related: [UI_BUILDER.md](UI_BUILDER.md) (the 2026-07 product proposal this
supersedes on architecture), [USAGE_SNIPPET_CORPUS.md](USAGE_SNIPPET_CORPUS.md)
(the measurement that started this), [DESIGN_CATALOGS.md](DESIGN_CATALOGS.md),
[PSI_PARSE_SPIKE.md](PSI_PARSE_SPIKE.md).

---

## 1. What is actually wrong

Measured over four checkouts, pinned so the numbers below can be reproduced and a changed
corpus can be told apart from a changed analyzer:

| Checkout | Revision |
|---|---|
| `yschimke/compose-ai-tools` | `bcacbf96a363af8250dcf65799252f2943fb1898` (`main`) |
| [`yschimke/m3-catalog`](https://github.com/yschimke/m3-catalog) | `3e6ac3a482d9090a3e5c00a754255d05ca0d16de` |
| [`yschimke/wear-m3-catalog`](https://github.com/yschimke/wear-m3-catalog) (incl. `:remote-catalog`, published as `remote-m3`) | `5a9a588c526dd46c538f405dd4139405827a5d8f` |
| [`joreilly/Confetti`](https://github.com/joreilly/Confetti) — a stand-in for a typical app | `f000306138d1ef7b1b5957f3ce7c015799d72bd6` |

Every count in this document is from those exact revisions. Phase 0 re-measures and
re-pins them.

### 1.1 Knobs are declared in the wrong place

`previewOverride*(key, default)` is a **call inside a composable body**. Three
things follow, and all of them are load-bearing:

* **The source is littered.** m3-catalog's 91 preview functions carry **321**
  scaffold/knob call sites (`catalogText` ×92, `counted` ×60, `catalogChoice` ×54,
  `catalogButtonSize` ×18, `catalogEnabled` ×13, …) — ~3.5 per preview. The
  smallest honest reading of `Button/Filled` is 25 lines, of which two are about
  `Button`.
* **The knob set is only knowable by rendering.** `ControllerPreviewOverrideHost`
  *records* each declaration as the lookup executes
  ([`PreviewOverrideHost.kt`](../../data/preview-overrides/runtime/src/main/kotlin/ee/schimke/composeai/overrides/PreviewOverrideHost.kt)).
  Nothing static can enumerate what a component exposes; a knob behind an `if`
  is invisible until that branch runs.
* **It is non-exhaustive by construction.** A parameter becomes editable only when
  a human writes a knob call for it. "Exhaustively enable overrides for
  everything" is unreachable from this shape — it is a per-parameter manual edit,
  forever.

Worse, m3-catalog's knobs are mostly *ambient*: `catalogButtonSize()`,
`catalogEnabled()`, `catalogExpressive()` take no key at all and are read several
call levels down inside private helpers. The knob's identity is not visible at
the call site, so neither a reader nor a tool can say what a component exposes.

### 1.2 Generation is subtraction, and subtraction cannot be made correct

[`PlaygroundSourceCleaner`](https://github.com/yschimke/compose-preview-server/blob/main/server/src/main/kotlin/ee/schimke/composeai/cli/serve/PlaygroundSourceCleaner.kt)
is 1,988 lines that *remove* scaffolding from preview source to recover "the plain
Compose a developer would write". It is a careful, well-tested piece of work and
the approach is still wrong, because the corpus says so
([USAGE_SNIPPET_CORPUS.md](USAGE_SNIPPET_CORPUS.md)):

| Catalog | Snippets that compile |
|---|---|
| m3-catalog | 4 / 10 (first run), 6 causes taxonomised |
| meshcore-mobile | 0 / 10, and *structurally* unfixable by rules |

Two findings from that document are the whole argument:

* **"Residue is not a proxy for compiles."** `NumberBadge` reported *zero*
  residue and did not compile. A subtractive pass can only report the scaffolding
  it was told about; the leak worth catching is the helper nobody declared.
* **The known gap that cannot be closed by rules.** Substituting
  `previewOverride*(key, default)` with `default` is right for the default render
  and *wrong for every `@OverrideVariant` cell* — the `off` variant of a toggle
  emits `true`, because that is what the source says. m3-catalog has **1,745**
  `@OverrideVariant` cells and wear-m3-catalog **669**. The snippet is honest
  about one render out of 2,414.

The rules file is the maintenance surface: 25 entries for m3-catalog, plus
`scaffoldSources` naming cross-module files by repo-relative path, plus `EXPAND`,
a rule kind meaning *inline this helper's body and, if it is a `when` over a
string literal, keep one branch*. That is an interpreter for a bespoke language,
written to undo something the author never needed to write.

### 1.3 Previews are not components, and nothing records the difference

m3-catalog, counted:

| | count |
|---|---|
| `@CatalogComponent` entries | 59 |
| `@Composable` preview functions | 91 |
| `@OverrideVariant` cells | 1,745 |
| distinct `androidx.compose.material3.*` symbols imported | **148** |
| local multipreview annotation classes | 57 |

59 catalog entries stand in for 148 library symbols, fanned across 1,745 variant
cells. The *component* — `Button(onClick, modifier, enabled, shape, colors,
elevation, border, contentPadding, interactionSource, content)` — is never
written down anywhere. `PreviewInfo.targets` gets closest: discovery already
walks the preview's bytecode for a project-local `@Composable` call and reads the
target's parameters out of its `@kotlin.Metadata`
([`PreviewData.kt`](../../gradle-plugin/preview-discovery/src/main/kotlin/ee/schimke/composeai/discovery/PreviewData.kt),
`PreviewTarget` / `TargetParameter`), and
[`apply-component-parameters.mjs`](../../scripts/design-artifacts/apply-component-parameters.mjs)
already stamps that signature onto the published catalog for Figma Code Connect.
It stops at name/type/`hasDefault` — no KDoc, no default *expressions*, no enum
constants, no call-site argument bindings, one target maximum.

### 1.4 The UI builder describes a different catalog than it renders

This is the finding that explains "our ui-builder components are beyond my
understanding".

The builder's one and only capability catalog is
`docs/design/fixtures/ui-builder/jetcaster-discover-capabilities-v1.json` — 25
components **hand-transcribed from one screen of `android/compose-samples`** —
copied at build time into a resource named `m3-catalog-v1.json`
([`ui-builder-runtime/build.gradle.kts`](https://github.com/yschimke/compose-preview-server/blob/main/ui-builder-runtime/build.gradle.kts)).
It has no relationship of any kind to `yschimke/m3-catalog`. Two different things
are called *m3-catalog* on the same host.

That table is then restated twice more, by hand:

* [`UiBuilderRenderer.kt`](https://github.com/yschimke/compose-preview-server/blob/main/ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderRenderer.kt) — 1,554 lines, a `when (componentId)` that *re-implements* each component in Compose;
* [`CapabilityComposeCodeExporter.kt`](https://github.com/yschimke/compose-preview-server/blob/main/ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/CapabilityComposeCodeExporter.kt) — 1,232 lines, a `when (componentId)` that *prints* Kotlin for each.

They already disagree. Diffing the three tables today:

```
renderer implements, catalog does not declare:
  m3/center-aligned-top-app-bar, m3/list-item, m3/primary-tab-row, m3/tab, shape/colour-dot
exporter prints, catalog does not declare:  (the same five)
catalog declares, exporter cannot print:    remote-compose/document
```

Nothing detects that. And because each component is a hand-written `when` branch
rather than the real composable, the answers to the natural questions are
uncomfortable:

* **"Do slots really work?"** `SlotCapability` (name, cardinality, ordered,
  `acceptedRoles`, `acceptedTraits`) is validated against the JSON, and then the
  renderer's branch decides what a slot *actually does*. The two can differ and
  nothing compares them. A slot is not derived from a `@Composable` lambda
  parameter of a real function — there is no real function.
* **"Nesting remote composables is a mess."** `remote-compose/document` embeds a
  whole Remote Compose player as a node, and its slots are *document-authored*
  names registered as custom-component configs
  ([UI_BUILDER_REMOTE_COMPOSE.md](https://github.com/yschimke/compose-preview-server/blob/main/docs/design/UI_BUILDER_REMOTE_COMPOSE.md)),
  reached through the `DynamicSlots` trait, which the catalog parser *infers*
  from a trait string. It is a second component model wearing the first one's
  clothes. It is also the one node the Compose exporter cannot print at all.

### 1.5 Generated source is not verified, and says so

The builder's Compose export is `RevisionPinnedComposeExportExecutor`, and every
artifact it returns carries a diagnostic:

```
ALMOST_COMPILING_PROJECTION —
  "Catalog symbols and the complete typed document are preserved;
   project-specific state and event adapters may require edits."
```

That is the honest state of "we don't really know how to generate source from the
ui-builder": nothing ever compiles the output. Meanwhile
[`PlaygroundCompileService`](https://github.com/yschimke/compose-preview-server/blob/main/server/src/main/kotlin/ee/schimke/composeai/cli/serve/PlaygroundCompileService.kt)
— on the same server, in the same process — *already* stages Kotlin, compiles it
against a catalog's resolved classpath, discovers the previews in it, renders a
first frame and mints a live session. The oracle exists and the exporter does not
call it.

### 1.6 A typical app is a different shape again — and that is good news

Confetti, 54 `@Preview`s, zero override calls:

```kotlin
@MobilePreviews
@Composable
internal fun SpeakerItemViewLoadedPreview() {
    SpeakerItemView(speaker = johnOreillySpeaker, navigateToSpeaker = {})
}
```

This is *already* everything the plan needs: a clean zero-argument preview, one
project composable call, a named fixture in `preview/MockData.kt`, and a call
site whose argument expressions are the binding. Discovery's existing inference
(`SINGLE_PROJECT_COMPOSABLE_CALL` + `NAME_MATCH` + `CROSS_FILE`) resolves it
without any repo change.

It is also the case that must **not** reach the UI builder:
`speaker: SpeakerDetails` is not constructible from a JSON wire protocol. That is
the heavyweight opt-in, and §4 makes it a check rather than a policy.

---

## 2. The proposal in one paragraph

Introduce **one component record**, derived from Kotlin metadata + a parse of the
source, published as a data product beside `previews.json`. Move the override
surface out of composable bodies and into **the preview function's own parameter
list**, where Kotlin already supplies names, types, defaults, nullability, enum
constants and KDoc — making a preview's knobs statically enumerable and its body
clean Compose, while the *exhaustive* editable surface comes from the record's own
reading of the target's signature (§4). Generate code by **printing a call site** from the record,
never by subtracting scaffolding from source. Derive the UI builder's capability
catalog from the same record, and render builder nodes by **invoking the real
composable** through the `ComposableMethod` path the daemon already uses. Gate
every surface on a **mechanical conformance ladder** whose top rung requires a
document → Kotlin → compile → render → pixel-compare round trip to pass.

---

## 3. The record

A new `components.json`, emitted by the Gradle plugin beside `previews.json` and
carried in the bundle (`components.json` + per-component sidecars, the same
convention `previews/<id>.overrides.json` already uses).

```jsonc
{
  "schemaVersion": 1,                          // see §3.1 — a published data product needs one
  "componentId": "Button/Filled",              // catalog identity, when published — may be absent
  "canonicalId": "samples:cmp/androidx.compose.material3.Button(String,Boolean)",
  "symbol": {
    // Three names, deliberately. `jvmOwner` is the reflection handle — for a top-level
    // function that is the synthetic file facade — `callable` is the source-level FQN
    // that generated Kotlin imports, and `descriptor` is what actually disambiguates.
    // Deriving `code.imports` from the facade would print
    // `import androidx.compose.material3.ButtonKt`, which does not resolve.
    "jvmOwner": "androidx.compose.material3.ButtonKt",
    "callable": "androidx.compose.material3.Button",
    "name": "Button",
    "jvmName": "Button",                       // differs from `name` under value-class mangling
    "descriptor": "(Lkotlin/jvm/functions/Function0;…)V",
    "origin": "library",                       // "project" | "library" | "generated"
    "sourceFile": null,                        // availability only — NOT a proxy for origin
    "library": "androidx.compose.material3:material3:1.5.0",
    "docs": "sources-jar"                      // where kdoc/defaults below came from; see §3
  },
  "kdoc": { "summary": "…", "markdown": "…" },
  "parameters": [
    { "name": "onClick",  "type": "() -> Unit",  "typeFqn": "kotlin.Function0",
      "hasDefault": false, "kdoc": "called when this button is clicked" },
    { "name": "enabled",  "type": "Boolean",     "hasDefault": true,
      "default": "true", "kdoc": "controls the enabled state…" },
    { "name": "shape",    "type": "Shape",       "hasDefault": true,
      "default": "ButtonDefaults.shape", "constructible": false },
    { "name": "content",  "type": "@Composable RowScope.() -> Unit",
      "composableSlot": true, "receiverScope": "androidx.compose.foundation.layout.RowScope",
      "hasDefault": false }
  ],
  "slots": [
    { "name": "content", "scope": "androidx.compose.foundation.layout.RowScope",
      "required": true,                        // the *lambda* must be supplied…
      "childCardinality": {"min":0,"max":null}, // …but it may emit any number of children
      "childPolicy": "unknown" }               // acceptedRoles/Traits are NOT derivable; see §3
  ],
  "bindings": [                                  // one per preview that renders it
    { "previewId": "…FilledButton", "arguments": {
        "onClick": {"kind":"literal","source":"{}"},
        "enabled": {"kind":"parameter","name":"enabled"},
        // A raw source fragment is not a standalone call site: `label` here is the
        // WRAPPER's parameter, neither a target parameter nor a fixture, so printing
        // this verbatim emits an unresolved identifier. Every binding therefore
        // records the free variables its expression closes over, and generation
        // either substitutes their selected values or declares them above the call.
        "content": {"kind":"lambda","source":"{ Text(label) }",
                    "freeVariables":[{"name":"label","from":"previewParameter"}]} },
      "renders": ["…FilledButton_Light", "…FilledButton_Dark"] } ],
  "fixtures": [ ],                               // referenced non-literal values + their source
  "conformance": { "tier": 3, "provenAt": "…", "checks": ["compiles","renders","roundTrips"] }
}
```

### 3.1 Identity, versioning and origin — three things the first draft left implicit

* **`schemaVersion` is mandatory.** `components.json` is meant to persist in bundles and
  be read by the browser, the builder and MCP clients, so an old bundle and a detached
  consumer must be able to tell an additive record from an incompatible future one.
  `docs/API_STABILITY.md` records the same lesson for `previews.json`. Evolution rules
  travel with it: additive fields bump nothing, a removal or a changed meaning bumps the
  major, and a reader refuses a major it does not know rather than guessing.
* **`componentId` cannot be the identity.** It comes from `@CatalogComponent`, which an
  ordinary application preview does not carry — so it is absent for exactly the
  typical-app records Phase 1 promises to publish, and several null ids would collide as
  a key. The record therefore carries a `canonicalId` (module + callable + descriptor)
  that is always present, with the catalog id as an optional alias. Bindings,
  per-component sidecars, MCP lookups and persisted builder references all address the
  canonical one.
* **A name is not a reflection handle.** Overloads need a descriptor, and a composable
  with value-class parameters gets a mangled JVM method name that differs from its Kotlin
  callable name. This repository already knows it: `findDefaultedComposableMethod`'s KDoc
  in `:renderer-android` explains that a manifest entry carries "only the class and the
  function NAME — discovery records no JVM descriptor", refuses to guess between two
  fully-defaulted overloads, and names the fix as "threading the descriptor from
  discovery through the manifest". The record is where that lands. (The desktop renderer
  had the narrower version of the same bug — a defaulted preview it could not resolve at
  all — fixed in #4993.)

**Where each field comes from**, most-trusted first:

1. **Kotlin metadata off the compiled classpath** — already read today
   (`ComposableSignature` → `TargetParameter`). Gives parameter names, types,
   *whether* a default exists, `@Composable`-lambda-ness, and works for **library**
   symbols, which is what m3-catalog previews actually demonstrate.

   **But nothing currently *names* a library target, and that blocks the primary
   corpus.** Reading a signature presupposes knowing which symbol to read.
   `PreviewTargetInference` cannot supply it for a library component: it drops every
   candidate whose owner matches `WRAPPER_FQN_PREFIXES` — which lists
   `androidx.compose.material3.` explicitly, alongside `material.`, `foundation.`,
   `ui.`, `animation.` and the Wear equivalents — and then keeps only owners that are
   `in projectClassFqns`. Both filters are right for the job that inference was built
   for ("which of *my* composables does this preview render?") and both make
   `androidx.compose.material3.Button` unreachable, which is exactly the component
   m3-catalog's 59 entries exist to show.

   `:usage-source-psi` cannot close it either: it is deliberately parse-only, so it
   sees the call `Button(...)` as a name and cannot resolve it to a coordinate.

   So Phase 1 needs a **second, explicitly dependency-facing inference path** that
   keeps the bytecode invocation's exact owner and descriptor rather than filtering it
   out — a different question from the existing one, and one that should stay a
   separate signal so a consumer can tell "this preview renders my `HomeScreen`" from
   "this sticker demonstrates the library's `Button`". Until it exists, the record and
   the API panel cover project-local targets (Confetti's shape) and **not** the
   library-wrapping catalogs, which is the opposite of what the phase order above
   implies.
2. **A parse of the source** — [`:usage-source-psi`](../../usage-source-psi) already
   exists, already parses Kotlin with no classpath and no resolution at ~3 ms/file,
   and already runs inside an isolated classloader off the CLI's classpath. It
   supplies exactly the three things metadata cannot: **KDoc**, **default value
   expressions**, and **call-site argument expressions**. Extending its
   `analyze(String): String` output from `calls` + `declarations` to include
   parameter lists with KDoc and defaults is the single cheapest high-value change
   in this plan.

   **Where that source comes from is a real problem for the primary corpus, and the
   plan has to name it.** `analyze` only sees source text it is handed. For a
   project-local target (Confetti's `SpeakerItemView`) the file is in the checkout.
   For m3-catalog — whose components are *library* symbols like
   `androidx.compose.material3.Button` — there is no source in the checkout at all,
   which is exactly the case this plan leans on hardest. Three options, in order of
   preference:

   * **resolve the `-sources.jar`** for the declaring coordinate through the same
     Gradle resolution the bundle already does for `manifest.classpath`, and parse
     the entry the metadata's file facade names. This is the only path that yields
     real KDoc and real default *expressions* for a library component;
   * fall back to a **curated overlay** for coordinates with no published sources;
   * otherwise **degrade explicitly** — emit the parameter with `hasDefault: true`,
     `default: null`, `kdoc: null` and a `docs: "unavailable"` marker on the symbol,
     so a consumer can tell "no default" from "default not recovered". Silently
     omitting the field is the one outcome to avoid.

   Phase 0 must report the sources-jar availability rate across the corpora; if it is
   low for `material3`, the API panel's promise shrinks to types-and-defaults-exist
   for library components and the overlay becomes load-bearing rather than optional.
3. **Runtime observation during the existing render** — what the override
   controller does today. Kept only as a *cross-check* that the static record
   matches what composed, never as the source of truth.

**Slots are derived — but only their existence, not their content policy.** A
parameter whose type is `@Composable (Scope.) -> Unit` *is* a slot, and whether the
lambda must be supplied follows from its nullability and default. Two things do
**not** follow, and conflating them would reject valid documents:

* **Child cardinality is not lambda optionality.** `Button`'s `content` lambda is
  required, and it may legally emit zero children or five. A required lambda is
  `required: true` with `childCardinality {min: 0, max: null}` — not `min 1, max 1`.
  Where a real bound exists it comes from the component's own contract (a
  `LazyColumn` item slot, a `Scaffold`'s `topBar`), which is authored or measured,
  not inferred from the signature.
* **`acceptedRoles` / `acceptedTraits` are not recoverable from a receiver scope.**
  `RowScope` says what the *child* may call on its modifier; it says nothing about
  which catalog components belong there. So the record carries
  `childPolicy: "unknown"` by default, and the builder either permits any
  Tier-3 component or takes an explicit policy the catalog authored. The receiver
  scope is still worth recording — it is what tells the generator that a child using
  `Modifier.weight` compiles here and not elsewhere.

With those two separated, the UI builder's `SlotCapability` is a projection of the
parameter list plus an explicit content policy, rather than a second hand-written
table that can silently disagree with the signature.

---

## 4. Overrides become parameters

The better format is the one Kotlin already has.

```kotlin
@CatalogComponent(id = "Button/Filled", caption = "Highest emphasis; the primary action.")
@CatalogModes
@Composable
fun FilledButton(
  /** The button's label. */
  label: String = "Filled",
  /** Whether the button accepts input. */
  enabled: Boolean = true,
  size: ButtonSize = ButtonSize.Small,
) {
  Button(onClick = {}, enabled = enabled) { Text(label) }
}
```

| Property | `previewOverride*` today | parameter |
|---|---|---|
| Enumerable without rendering | no | **yes** |
| Statically enumerable | no (recorded at composition) | **yes** |
| Types | 8 hand-rolled kinds | **the Kotlin type system** |
| Closed value sets | `previewOverrideChoice` + an options list | **an enum's constants** |
| Docs | none | **KDoc `@param`** |
| Default | an argument | **the default expression, in source** |
| Snippet | body must be rewritten to be readable | **the body already is the snippet** |
| Variant cell | `@OverrideVariant(booleans=["enabled=false"])` | `enabled = false` — a real argument |

**What "exhaustive" does and does not mean here — the claim needs narrowing.**
Parameters make a preview's knobs *statically enumerable*; they do **not** by
themselves make them exhaustive over the component's API. The example above exposes
`label`, `enabled` and `size`, while `Button` also takes `shape`, `colors`,
`elevation`, `border`, `contentPadding` and `interactionSource` — deciding which to
thread into the wrapper is the same per-parameter manual work `previewOverride*`
demanded. So the honest split is:

* **the preview's parameters are a curated seed set** — what the catalog author chose
  to fan variants over, and what the render is baked from;
* **the record's `parameters` list is the exhaustive one**, because it is read off
  the *target's* signature rather than the wrapper's. The API panel, the generated
  call site and the UI-builder property list can offer every parameter `Button` has,
  whether or not a preview ever threaded it.

That is where "exhaustively enable overrides for everything" actually comes from, and
it costs nothing extra: the record already knows the full signature. What the two
lists being different buys is a **mechanical parity check** — for each component,
report target parameters with no wrapper knob. Today that gap is invisible; as a
reported delta it becomes a finite, closeable list per component instead of a
standing unknown. Tier 3 can require the delta to be empty or explicitly waived.

**Seeding is *not* free, and the earlier draft of this plan was wrong to say so.** Three facts about the existing seam, two helpful and one not:

* **The all-defaults preview shape is already first-class.**
  `PreviewDiscovery.hasUnsupportedPreviewParameters` admits "no parameters, exactly
  one `@PreviewParameter` value, **or a default for every parameter**", verified
  against metadata by `allParametersHaveDefaults`; the renderer then invokes with no
  args and `ComposableMethod` fills every one from Kotlin's synthetic `$default`
  bridge. So a fully-defaulted knob preview is discovered and rendered *today*, with
  no change at all. That is the floor this format stands on.
* **Reflective `@Composable` lambda arguments work — for the *unscoped* shape only.**
  `InvokeWithOptionalWrapper` hands the preview body to a wrapper as exactly such an
  argument, which is what makes builder slot-filling feasible at all. It proves less than
  the earlier draft claimed: that lambda is a zero-receiver `@Composable () -> Unit`,
  while the record's primary slot shape is `@Composable RowScope.() -> Unit` — a
  different compiled arity that must be handed the scope instance. A receiver-scoped slot
  therefore needs a receiver-aware adapter (or a generated invocation path), with its own
  conformance coverage, rather than being assumed to ride the wrapper mechanism.

  Treat the whole reflective seam as *proven only where a test exercises it*. #4993 is the
  cautionary case: the plan asserted "nothing new is needed at the render seam", and
  building it surfaced two real defects — a defaulted preview the desktop renderer could
  not resolve, and `ComposableMethod.invoke` forwarding a null into a primitive parameter
  — neither visible from reading the code.
* **But nothing seeds a *subset* of arguments.** `PreviewParameterSupport.resolve`
  returns `emptyList()` for a non-`@PreviewParameter` preview — it invokes the
  all-defaults path, it does not bind chosen values. Seeding `enabled = false` while
  letting `label` default means invoking the `$default` bridge with a computed mask
  (bits cleared for supplied parameters), which is new plumbing. And
  `@PreviewParameter` composes badly with it: `hasUnsupportedPreviewParameters`
  returns `userParameters.size != 1` when a provider is present, so adding even one
  defaulted knob to a provider-backed preview makes discovery **skip it silently**
  with a warning.

So Phase 2 owns three concrete pieces of work, not zero: a wire shape for "here are
the arguments"; a `$default`-mask invoker that merges *provider values*, *named
override values* and *defaults* in that precedence (rather than replacing the
resolver's argument list, which would drop the selected provider row); and a
discovery change to admit `@PreviewParameter` alongside otherwise-defaulted
parameters. Plus the JSON→value mapping for the constructible set (`String`,
numerics, `Boolean`, `Color`, `Dp`, enums, no-op lambdas, and `@Composable` slot
lambdas built from child nodes).

**What this costs.** Direct knob call sites are few — 55 in m3-catalog, 167 in
wear-m3-catalog, 116 here — and a codemod can rewrite them from the PSI offsets
the analyzer already reports. The real cost is m3-catalog's **ambient** knobs
(`catalogButtonSize()` read inside `ButtonFrame`, three levels down): those become
either explicit parameters threaded through the helpers, or `CompositionLocal`s
with a declared default recorded in the component record. That is a genuine
refactor of ~18 helper functions and it is the honest price of the change; it is
also the same refactor that makes the source readable. `previewOverride*` stays,
deprecated, so no catalog is forced to move before its tier requires it.

---

## 5. Three surfaces, one record

### 5.1 Preview browser — *explain the API*

Pixels unchanged. Gains an **API panel** that is derived, not authored: the
parameter table with types, defaults, KDoc, enum values and the slot list; the
fixtures a preview bound; the library coordinate. This alone delivers "learn the
component API including params and kdocs" and "explain the API to the user, and
the meaning and defaults" — for humans in the panel and for agents as
`components.json` over the existing MCP surface.

### 5.2 Playground — *print, don't strip*

The Source panel stops being a rewrite and becomes a **projection**: emit the call
site from `symbol` + `parameters` + the chosen `binding`, append the fixtures it
references, prune imports to what is used. `PlaygroundSourceCleaner` then serves
only un-migrated catalogs, and its residue list becomes a *migration checklist*
rather than a permanent ceiling. Editing a value is changing an argument and
re-invoking — no recompile. Structural edits still go through the existing
compile path.

The known gap of §1.2 closes for free: an `@OverrideVariant` cell is an argument
set, so the snippet for the `off` variant says `checked = false` because that is
what was invoked.

### 5.3 UI builder — *invoke the real composable*

* **The capability catalog is generated** from `components.json`.
  `ComponentCapability` maps 1:1: `componentId` ← catalog id; `properties` ←
  non-slot parameters (`jsonType` from the Kotlin type, `allowedValues` from enum
  constants, `PropertyEditorControl` derived rather than hard-coded in
  `EDITOR_OVERRIDES`); `slots` ← `@Composable` lambda parameters; `code.symbol` /
  `code.imports` ← the FQN. The three-way drift of §1.4 cannot recur because there
  is one table.
* **Rendering is invocation.** On the daemon/server path a node renders by
  resolving its `ComposableMethod` from the catalog's bundle classpath and
  invoking it with arguments built from the node's properties and
  `@Composable` lambdas built from its slot children. This is the "ComposableMethod
  invoker" idea, and the machinery is already load-bearing in `RenderEngine`.
* **The Wasm tier is the real constraint, and must be stated.** The in-browser
  renderer cannot reflectively invoke an arbitrary jar, so it needs a compiled-in
  component set. The answer is not to keep hand-writing `UiBuilderRenderer.kt`: it
  is to **generate** that `when` from the record, check it in, and have CI fail on
  a diff. Same for the exporters. Two tiers — daemon-backed (any opted-in catalog,
  authoritative) and browser-only (the generated set) — with the record as the
  single input to both.

  **Membership of the browser set is itself a gate.** An Android- or JVM-only catalog
  can pass every Tier 3 check on the daemon and still break the Wasm build the moment
  its symbols are generated into common source, because they have no Wasm actual. So a
  record enters the generated set only after a Wasm compile/conformance check; anything
  that fails stays daemon-only rather than failing the browser build.
* **Export goes through the oracle.** A Compose export is not returned until it has
  been compiled and rendered and pixel-compared against the builder's own render
  of that revision: either the round trip passes, or the export returns the failure.

  That does **not** retire `ALMOST_COMPILING_PROJECTION`'s warning wholesale, and the
  earlier draft was wrong to say it deletes it. A compile plus a single-frame pixel
  comparison passes happily with `onClick = {}` — the export can be visually perfect and
  behaviourally inert, which is precisely what the existing diagnostic warns about
  ("project-specific state and event adapters may require edits"). So either model and
  test interactions and state as part of the oracle, or keep a diagnostic that states the
  narrower guarantee the oracle actually earns: *this compiles and its first frame
  matches*. Replacing a vague warning with a precise one is the improvement; removing it
  is an overstatement.
* **Remote Compose keeps its own lane.** `remote-compose/document` is a nested
  *player*, not a composable, and its document-authored slot names are a different
  model. Do not force it into the component record: keep it as an explicitly
  typed embed node with its named-value boundary, exclude it from the Compose
  exporter (as it already is), and let `remote-m3` reach Tier 2 through a Remote
  Compose *creation-DSL* generator rather than the Compose one.

---

## 6. The heavyweight opt-in: a mechanical conformance ladder

Each rung is earned by a machine check, not a declaration. A typical project
cannot contribute to the UI builder because it *fails a test*, not because it
forgot a flag.

| Tier | Name | What proves it | Unlocks |
|---|---|---|---|
| **0** | Rendered | a `@Preview` renders | preview browser |
| **1** | Attributed | a target was inferred at `HIGH` confidence and its signature read | API panel, doc links, `components.json` entry |
| **2** | Reproducible | the **printed** snippet compiles against a *consumer* classpath and renders **pixel-identical** to the baked capture | playground **Run**, source panel's strong claim |
| **3** | Authorable | Tier 2, **plus** per component: every parameter constructible-or-defaulted from the wire; every slot a `@Composable` lambda; **argument-deterministic** (two invocations with the same arguments are pixel-identical); **parent-agnostic** (composes correctly outside its own preview frame); and the full **document → Kotlin → compile → render → pixel-compare** round trip passes | UI-builder catalog |

Tiers 0 and 1 are automatic — Confetti gets Tier 1 today with no repo change.
Tier 2 requires either a migrated catalog or a `compose-usage.json`. Tier 3
additionally requires the module to opt in under the plugin's existing extension —
`composePreview { uiBuilder { enabled = true } }`, since `ComposePreviewDsl.EXTENSION_NAME`
is `composePreview` and there is no `composeAi` extension to nest under — **and** to run
the generated conformance suite in its own CI **and** to publish the resulting record in
its bundle. `SpeakerItemView(speaker: SpeakerDetails, …)` fails
Tier 3 at the first check, automatically, which is the correct answer.

**Falsifiable success criterion for the gate: if Confetti reaches Tier 3 for any
component, the gate is too loose.**

---

## 7. The oracle

One harness, used by every tier above Tier 1:

```
component record + argument binding + the preview's frame context
  → print Kotlin                                        (§5.2)
  → compile against a consumer classpath                PlaygroundCompileService / :tools:usage-compile-check
  → discover + render                                   the daemon
  → pixel-compare to the reference capture              the known-difference machinery
```

**The frame context is not optional, and leaving it out would make Tier 2
unreachable rather than strict.** A baked capture is never the bare call: m3-catalog's
goes through `Sticker` (a `MaterialTheme` over the baseline scheme),
`:samples:design-catalog-m3`'s through `CatalogSticker` (a transparent surface plus
16 dp of padding), the wear sheet's through `WearSticker`, and several through a
`ButtonFrame` that pins the container height. Those wrappers supply theme, sizing and
framing that a printed call site does not, so the snippet could be argument-perfect,
compile cleanly, and still never match the reference pixel for pixel.

So the record carries the preview's **frame**: the wrapper chain the capture composed
through, each entry classified as *reproducible* (a stock `MaterialTheme` the snippet
can print, which `compose-usage.json`'s `RENAME`/`MATERIAL3_SYSTEM_THEME` rules
already do today) or *catalog-private* (`CatalogSticker`'s padding — reproducible in
the harness, but not something a consumer snippet should carry). Tier 2 then compares
under the frame, and the *published* snippet keeps only the reproducible half. Where a
frame cannot be reproduced at all, the comparison falls back to a consistently
isolated component region rather than the full canvas.

Run over three deliberately different corpora:

| Corpus | Shape | What it tests | Expected outcome |
|---|---|---|---|
| **m3-catalog** | annotation-first, wraps library components, 1,745 variant cells | the migration, and whether printing beats stripping | Tier 3 for the stateless component set |
| **wear-m3-catalog / remote-m3** | delegating wrappers; Remote Compose documents, not composables | the limits of the model | `:catalog` Tier 2–3; `remote-m3` Tier 2 via the RC generator, embed-only in the builder |
| **Confetti** | a typical app: clean previews, fixture data, app screens | that Tier 1 is free and Tier 3 is closed | Tier 1 everywhere, Tier 2 where fixtures are plain top-level vals, **Tier 3 nowhere** |

Publish the ratios the way [USAGE_SNIPPET_CORPUS.md](USAGE_SNIPPET_CORPUS.md)
publishes its own — the point of that document is that the compiler is the only
honest signal, and this plan is built on taking it seriously.

---

## 8. Phases

Ordered so that the cheapest measurement decides the expensive work.

**Phase 0 — measure (small, no product change).** Extend `:usage-source-psi` to
report parameter lists, KDoc and default expressions, plus call-site argument
expressions. Run it over the four pinned corpora and publish: how many previews have
exactly one inferable target; for how many is the full signature recoverable; the
binding taxonomy (literal / fixture reference / unrepresentable); the
**`-sources.jar` availability rate** for the library coordinates the catalogs depend
on (§3 — this decides whether the API panel can promise KDoc for library components
at all); and, per component, the **parity delta** between the wrapper's parameters and
the target's (§4). Every later
phase is sized by these numbers, and Phase 0 can be wrong cheaply.

**Phase 1 — the record.** `components.json` as a data product (bundle sidecar +
manifest pointer, `schemaVersion` from the start), built from metadata + PSI. Preview
browser API panel. Delivers "detect components from previews", "learn the API",
"explain the API" with no source change anywhere — but **for project-local targets
first**. The library-wrapping catalogs (m3-catalog, wear-m3) need the
dependency-facing inference path of §3 before their components can be named at all, so
that is Phase 1's first piece of work rather than an afterthought: the corpus the plan
leans on hardest is the one the existing inference deliberately filters out.

**Phase 2 — parameters as the override format.** The parameter convention; the
argument wire shape; the `$default`-mask invoker merging provider values, named
overrides and defaults; discovery support for `@PreviewParameter` alongside
otherwise-defaulted parameters (§4 — without it, adding a knob to a provider-backed
preview silently drops it); `@OverrideVariant` lowering to arguments; the codemod;
deprecation of `previewOverride*`. Migrate `:samples:design-catalog-m3`
first (this repo's own, and the delegating shape that broke the Source panel),
then m3-catalog's ambient helpers.

**Phase 3 — print, and gate.** Snippet generation by projection; Tier 2 as a CI
gate over the corpora; retire the cleaner for migrated catalogs.

> **Started.** `ComponentSnippets.callSite` prints the *unbound* call site — the
> component's own API, with required arguments filled from a placeholder table and
> defaulted ones omitted — from `symbol` + `parameters` alone. It has no argument
> binding yet (that is Phase 2's wire shape) and no compile gate yet (the second
> half of this phase), so what it currently earns is narrower than "the snippet is
> right": it is *this call site type-checks*. The discipline that makes even that
> claim honest is that it **refuses** rather than guesses — see below.
>
> Three record fields exist only to make refusal possible, and are worth naming
> because each closes a way the generator could otherwise emit plausible source
> that does not build:
>
> * `ComponentRecord.signatureKnown` — `parameters` degrades an unreadable
>   `@kotlin.Metadata` to an empty list, which reads exactly like a genuinely
>   parameterless composable. Printing `Button()` from the first case is a
>   compile error; printing it from the second is correct. Nothing else in the
>   record tells them apart.
> * `ComponentSymbol.receiver` — `AnimatedVisibility` is declared on `ColumnScope`,
>   so it resolves only inside a `Column`. Without the receiver the generator
>   cannot tell a top-level composable from a scoped one, and would print an
>   unresolved reference for every scoped component in the corpus.
> * the `…Kt`-facade evidence already in `symbol.callable` — an unwrapped callable
>   is proof the symbol is a top-level function, and therefore importable and
>   callable on its own. A member of a class needs an instance the generator has
>   no way to obtain.
>
> What is still refused, and is the honest measure of how far this reaches: any
> required parameter whose type has no unambiguous literal (`ImageVector`,
> `Shape`, a domain type), any callback of arity two or more, any callback with a
> non-`Unit` return. Widening the placeholder table is the obvious next increment
> and should be paid for by the compile gate, not by confidence.
>
> **The gate is in, and it paid for itself on the first run.**
> `ComponentCallSiteCompileFunctionalTest` builds a real Compose Multiplatform
> project, discovers its `androidx.compose.material3` components, prints their call
> sites, writes them into that project and compiles them — so the Kotlin compiler,
> not an argument in a code review, decides whether a snippet is real. Its
> load-bearing assertion is the vacuity guard: a generator that refused everything
> would emit an empty file that compiles perfectly, so the test names components it
> insists were emitted.
>
> That guard failed immediately, on `Text`. Kotlin mangles the JVM name of any
> function whose signature mentions a value class, so `androidx.compose.material3.Text`
> compiles to `TextKt."Text-Nvy7gAk"` — its `fontSize`, `color` and `overflow`
> parameters are `TextUnit`, `Color` and `TextOverflow`. `isComponentLibraryTarget`
> rejects a name that is not a usable Kotlin import, and a mangled name is not, so
> **every Material 3 component whose signature mentions `Color`, `Dp` or `TextUnit`
> was being dropped from the record**. A preview whose only call was `Text` inferred
> no component at all. `Button` and `Card` take no value-class parameters, which is
> exactly why the hand-written unit tests were green and stayed green.
>
> The same mangling drops the project's *own* composables from `targets`:
> `AppTile(padding: Dp)` compiles to `AppTile-<hash>`, so its preview reports
> `targets = []` for a composable the preview does nothing but render. That half was
> left alone at first, on two arguments that did not survive being checked.
>
> The first was that `DiscoveryFunctionalTest` pins the rejection with a
> purpose-built `@JvmInline` fixture, so someone had decided it. The assertion
> arrived whole in `feat(mcp): add remote UI builder tools` (#4929) — a feature
> commit — pinning what the code did at the time. Someone noticed enough to extend
> the test's *name*; nothing designed the behaviour.
>
> The second was that a `targets` entry has a single name which consumers may be
> using as a JVM lookup key, so demangling it would break them. Across both
> repositories `PreviewInfo.targets` has exactly **one** non-test consumer,
> `ComponentRecords.from`, which folds it into this record. Nothing reflects on it.
> The consumer being deferred to did not exist.
>
> What was true is the shape underneath: `ComponentSymbol` already separates
> `callable` (the source-level name an import needs) from `jvmOwner` (the reflection
> handle) — its own KDoc opens *"three ways, because one name cannot serve all three
> readers"* — and `PreviewTarget` had one name for all of them. **So the fix is not
> to choose which name to publish but to stop making it a choice**: `functionName` is
> the source name, `jvmName` is the JVM name, and `descriptor` is what actually says
> *which* method is meant, since two overloads mentioning no value class share both
> names exactly. Discovery already had the descriptor — the bytecode walk matches
> call sites by name **and** descriptor — and was discarding it at the last step;
> `ComponentSymbol.descriptor` had been declared and left null since v1 waiting for
> it.
>
> Scoring was reading the mangled name too. `nameMatches` compared `ScreenPreview`
> against `Screen-<hash>`, so the clearest naming convention a preview can follow was
> the one case where the `NAME_MATCH` signal could never fire — a scoring bug hiding
> behind the naming bug, invisible while the target was being dropped anyway.
>
> One thing this does **not** fix: overloads still share a canonical id and merge
> into a single record. Recording a descriptor does not un-merge them, and putting
> the id on a descriptor basis would rewrite every id in the file for a case no
> consumer has asked to resolve. `ComponentSymbol.descriptor` is therefore null when
> merged targets disagreed, rather than naming whichever one the manifest listed
> first — a null descriptor with `signatureKnown` true is how a collision announces
> itself.
>
> Cost worth stating rather than hiding: `infer` now reads `@kotlin.Metadata` once
> per surviving candidate instead of once per preview, because the source name has to
> be in hand before any name-based filter can run. That is the shape `inferComponents`
> already had, the parse is `SKIP_CODE`, and candidate counts after filtering are
> small — but it is more class-file reads than before and has not been measured on a
> large corpus.
>
> The lesson generalises past this bug: a corpus you chose is a corpus that agrees
> with you. Both fixtures I picked by hand happened to avoid the one construct that
> breaks the path, and only compiling something real found it.
>
> **The call site is persisted, not recomputed.** `ComponentRecord.code` carries what
> `ComponentSnippets` printed — either a `call` plus its `imports`, or a `refusedReason`.
> That is a deliberate choice about where the rule lives. The consumer this record
> exists for (compose-preview-server's Compose exporter and playground) depends on
> published compose-ai-tools artifacts but **not** on `preview-discovery`, so a
> consumer-side call site would mean either a new dependency on a module that drags
> ClassGraph, ASM and kotlin-metadata in to produce one string, or a second
> implementation of the three rules that make a refusal sound — `signatureKnown`,
> `symbol.receiver`, and the `…Kt`-facade evidence in `symbol.callable`. A second
> implementation of a rule this exacting is how two sides of a contract start
> disagreeing, which is the drift §1.4 already records once.
>
> `codeFor` is a projection of `callSite`, never a parallel path, so the persisted answer
> and the in-process one cannot differ: there is one decision.
>
> The compile gate was moved onto the persisted field for the same reason. It now reads
> `record.code` off the written `components.json` instead of calling `ComponentSnippets`
> itself, so what the Kotlin compiler accepts is the exact bytes a consumer receives — a
> generator that worked in-process while the record persisted something else would have
> passed the old test and failed every consumer.
>
> **From a call site to a screen.** `ComponentSnippets` prints one component with
> placeholders — `Text(text = "")` — which proves a component is *reachable* and
> renders nothing anyone designed. `ScreenGenerator` adds the half a UI builder needs:
> the values its user set, and components nested into each other's slots.
>
> A node generates **only when its record carries an emitted `code`**. That single
> check inherits every protection listed below without restating any of it — public,
> no uninferable type parameters, no overload collision, a signature actually read, an
> importable callable. `code.call` is the licence to call; the argument list is rebuilt
> from `parameters` with the document's values, and `placeholderFor` plus the
> qualified-type check are *shared* with `ComponentSnippets` rather than copied,
> because "is this really `kotlin.String`?" is the question already got wrong twice.
>
> `ScreenDocument` is deliberately not the builder's own document model — it is the
> narrow projection the generator needs, so the generator is testable without an
> editor's undo stack and collaboration protocol. Projecting onto it is the builder's
> job, and the interesting decisions are not in that projection.
>
> `ScreenGeneratorCompileFunctionalTest` closes the loop: a real project, real
> discovery, and a screen assembled from the discovered catalog that the Kotlin
> compiler accepts. The exporter this path replaces asserted *balanced braces* on its
> output.
>
> Building it immediately caught a defect in the opt-in markers below, and the first
> fix for it was wrong in an instructive way. Generated screens told consumers to
> `@OptIn(InternalComposeApi::class)` in order to place a `Card`. The cause is that
> ClassGraph's `annotationInfo` is the **transitive closure of meta-annotations**, not
> the annotations written on the element: `Card` carries `@Composable`,
> `@ComposableInferredTarget` and `@FunctionKeyMeta`, and the closure of those three
> drags in `InternalComposeApi`, `ComposeCompilerApi` and `kotlin.RequiresOptIn`
> itself. Denylisting those names silenced the noise and *also* silenced a component an
> author had deliberately marked `@InternalComposeApi`, whose callers really must opt
> in. Reading `directOnly()` at both levels asks the actual Kotlin question — this
> element, this marker — so a compiler-stamped annotation drops out because it is not
> itself `@RequiresOptIn`, while an author's `@ExperimentalMaterial3Api` survives.
> `ComposableSignatureTest` reproduces both halves on local fixtures shaped like the
> Compose ones, and the functional gate asserts stock Material 3 needs no opt-in at
> all.
>
> What is **not** done: `compose-preview-server`'s exporter still runs off an authored
> `CapabilityCatalog` and a hand-written 29-entry `EMITTER_IDS` allowlist. Moving it
> onto this path needs `ScreenGenerator` in a published artifact — that repo consumes
> released jars and does not depend on `preview-discovery` — so the wiring is blocked
> on a release rather than on a design question. Layout primitives (`Column`, `Row`,
> `Box`) are also absent, because inference scopes library components to
> `material3`/`material`/`wear`; the prototype nests inside `Card`'s `ColumnScope`
> content slot instead, and widening that is a separate decision about what counts as
> a component.
>
> **Six ways the compile claim was wider than the record could justify.** Persisting
> the call site drew a review pass over the guarantee itself, and every one of these
> was real. They share a root cause worth naming: `callSite` was deciding from a
> *rendered* view of the signature, which is the same trap `nullable` was introduced
> for one level down.
>
> | What broke | Now |
> | --- | --- |
> | ``fun `when`(`is`: String)`` printed `when(is = "")` and an import ending `.when` | hard keywords backtick-escaped in the call and every import segment |
> | merged overloads emitted `Chip()`, which resolves to neither | `overloadsCollided` refuses — the signal was already computed and discarded |
> | a `@RequiresOptIn` component compiled in its preview and not in a generated wrapper | `code.requiredOptIns` travels with the call; the wrapper applies them |
> | a `private` composable was advertised as importable | visibility read from metadata; non-public refuses |
> | `fun <T> Picker(items: List<T> = emptyList())` emitted `Picker()` | a declaration with type parameters refuses |
> | `com.example.String` got `""`, because it renders exactly like `kotlin.String` | placeholders match `TargetParameter.typeFqn`, never the spelling |
>
> The opt-in one is the only one not answered by refusing. Refusing would drop most of
> Material 3 over something a caller fixes with one annotation on the wrapper it
> already has to write, so the call is emitted and the markers travel beside it — and
> the compile gate generates `@OptIn(…)` wrappers, so that half of the contract is
> compiler-checked rather than asserted.
>
> Two of these defaulted permissively on purpose. `callableFromAnotherFile` defaults
> to true and `typeFqn` falls back to the rendered spelling, because a record written
> before those fields existed carries neither, and reading "not recorded" as "private"
> or "not a Kotlin scalar" would silently retract call sites that were already being
> published.
>
> **Measured reach.** Over a 28-component Material 3 surface (buttons, cards, chips,
> toggles, progress, scaffolding, list and navigation items, dialogs), **26 emit a
> call site and 2 refuse** — the two being `TextField` / `OutlinedTextField`, whose
> required `state: TextFieldState` genuinely has no literal to write. So the
> placeholder table is not the bottleneck it looked like from the outside, and
> widening it further would buy very little: what is left needs *values*, which is
> Phase 2's argument binding, not more literals.
>
> The first run of that measurement read 25/30 with five refusals, three of which —
> `Checkbox`, `RadioButton`, `Switch` — turned out to share one cause: material3
> declares their callbacks `((Boolean) -> Unit)?`, and no lambda-shaped rule accepts
> a nullable function type. Fixing that also surfaced a trap worth recording, because
> the obvious fix is wrong: a rendered type ending in `?` does **not** mean the
> parameter is nullable. `(Int) -> String?` is a non-null callback returning a
> nullable value, and answering `null` for it emits source that does not compile.
> Nullability is now carried structurally on `TargetParameter.nullable` rather than
> read off the spelling — and `renderType` separately parenthesises nullable function
> types, because `(Boolean) -> Unit?` was being handed to every consumer as the
> rendering of `((Boolean) -> Unit)?`.
>
> Caveat on the number: that surface is hand-picked, so it measures the *table*, not
> a catalog. The corpus ratios §7 asks for still have to come from m3-catalog,
> wear-m3 and Confetti.

**Phase 4 — the builder on the record.** Generate the capability catalog;
generate `UiBuilderRenderer` / `CapabilityComposeCodeExporter` for the Wasm tier
and CI-diff them; reflective invocation on the daemon tier; export through the
oracle; delete `ALMOST_COMPILING_PROJECTION`.

**Phase 5 — Tier 3.** The conformance suite, the `uiBuilder` opt-in, and the
Confetti negative control.

### Do first, independent of all of the above

* **Fix the three-way drift** listed in §1.4 — five component ids implemented and
  printed but never declared, one declared but unprintable — and add the
  catalog↔renderer↔exporter diff as a test. It is a real bug today.
* **Rename the builder's `m3-catalog-v1.json`.** It is a Jetcaster transcription;
  calling it `m3-catalog` while a different, unrelated `m3-catalog` is served on
  the same host is most of why the builder is hard to reason about.

---

## 9. What this deliberately does not do

* **It does not make the UI builder a general Compose editor.** Tier 3 is narrow
  on purpose: stateless, argument-deterministic, parent-agnostic components with
  constructible parameters. Most real app screens are none of those things, and
  the honest answer for them is the preview browser and the playground.
* **It does not delete `previewOverride*`.** Deprecated, still supported;
  catalogs migrate when a tier they want requires it.
* **It does not unify Remote Compose with Compose.** A Remote document is a
  different runtime with a different composition model; §5.3 keeps it embedded
  and separately generated rather than pretending otherwise.
* **It does not assume the inference is right.** Every derived field carries its
  provenance (`TargetSignal`s already do this), and a catalog can override any of
  it — the same "spec always wins over the annotation" rule
  [`@CatalogComponent`](../../api/preview-annotations/src/commonMain/kotlin/ee/schimke/composeai/preview/CatalogComponent.kt)
  already uses.
