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

Measured over four checkouts: this repo, [`yschimke/m3-catalog`](https://github.com/yschimke/m3-catalog),
[`yschimke/wear-m3-catalog`](https://github.com/yschimke/wear-m3-catalog) (including
`:remote-catalog`, published as `remote-m3`), and [`joreilly/Confetti`](https://github.com/joreilly/Confetti)
as a stand-in for a typical app.

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
constants and KDoc — making overrides exhaustive by construction and making the
body clean Compose. Generate code by **printing a call site** from the record,
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
  "componentId": "Button/Filled",              // catalog identity, when published
  "symbol": {
    "fqn": "androidx.compose.material3.ButtonKt",
    "name": "Button",
    "sourceFile": null,                        // null ⇒ library, not project-local
    "library": "androidx.compose.material3:material3:1.5.0"
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
  "slots": [ { "name": "content", "cardinality": {"min":1,"max":1}, "scope": "RowScope" } ],
  "bindings": [                                  // one per preview that renders it
    { "previewId": "…FilledButton", "arguments": {
        "onClick": {"kind":"literal","source":"{}"},
        "enabled": {"kind":"parameter","name":"enabled"},
        "content": {"kind":"lambda","source":"{ Text(label) }"} },
      "renders": ["…FilledButton_Light", "…FilledButton_Dark"] } ],
  "fixtures": [ ],                               // referenced non-literal values + their source
  "conformance": { "tier": 3, "provenAt": "…", "checks": ["compiles","renders","roundTrips"] }
}
```

**Where each field comes from**, most-trusted first:

1. **Kotlin metadata off the compiled classpath** — already read today
   (`ComposableSignature` → `TargetParameter`). Gives parameter names, types,
   *whether* a default exists, `@Composable`-lambda-ness, and works for **library**
   symbols, which is what m3-catalog previews actually demonstrate.
2. **A parse of the source** — [`:usage-source-psi`](../../usage-source-psi) already
   exists, already parses Kotlin with no classpath and no resolution at ~3 ms/file,
   and already runs inside an isolated classloader off the CLI's classpath. It
   supplies exactly the three things metadata cannot: **KDoc**, **default value
   expressions**, and **call-site argument expressions**. Extending its
   `analyze(String): String` output from `calls` + `declarations` to include
   parameter lists with KDoc and defaults is the single cheapest high-value change
   in this plan.
3. **Runtime observation during the existing render** — what the override
   controller does today. Kept only as a *cross-check* that the static record
   matches what composed, never as the source of truth.

**Slots are derived, not declared.** A parameter whose type is
`@Composable (Scope.) -> Unit` *is* a slot; its receiver scope *is* its acceptance
rule; nullability and defaults *are* its cardinality. The UI builder's
`SlotCapability` becomes a projection of the parameter list rather than a second
hand-written table that can disagree with it.

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
| Exhaustive | only where hand-wired | **every parameter, by construction** |
| Statically enumerable | no (recorded at composition) | **yes** |
| Types | 8 hand-rolled kinds | **the Kotlin type system** |
| Closed value sets | `previewOverrideChoice` + an options list | **an enum's constants** |
| Docs | none | **KDoc `@param`** |
| Default | an argument | **the default expression, in source** |
| Snippet | body must be rewritten to be readable | **the body already is the snippet** |
| Variant cell | `@OverrideVariant(booleans=["enabled=false"])` | `enabled = false` — a real argument |

**Seeding already works.** The daemon resolves a parameterised preview through
[`PreviewParameterSupport.resolve`](../../daemon/desktop/src/main/kotlin/ee/schimke/composeai/daemon/RenderEngine.kt)
and invokes it as `composableMethod.invoke(currentComposer, null, *args)`. It
*already* passes a `@Composable () -> Unit` as a reflective argument —
`InvokeWithOptionalWrapper` hands the preview body to a wrapper exactly that way.
Seeding an override is building the argument array and letting Kotlin's `$default`
mask cover what the request did not set. Nothing new is needed at the render seam;
what is needed is a wire shape for "here are the arguments" and a mapping from
JSON to the small set of constructible types (`String`, numerics, `Boolean`,
`Color`, `Dp`, enums, no-op lambdas, and `@Composable` slot lambdas built from
child nodes).

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
  a diff. Same for `CapabilityComposeCodeExporter.kt`. Two tiers — daemon-backed
  (any opted-in catalog, authoritative) and browser-only (the generated set) —
  with the record as the single input to both.
* **Export goes through the oracle.** A Compose export is not returned until it has
  been compiled and rendered and pixel-compared against the builder's own render
  of that revision. `ALMOST_COMPILING_PROJECTION` is deleted rather than reworded:
  either the round trip passes, or the export returns the failure.
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
additionally requires the module to opt in (`composeAi { uiBuilder { enabled = true } }`)
**and** to run the generated conformance suite in its own CI **and** to publish the
resulting record in its bundle. `SpeakerItemView(speaker: SpeakerDetails, …)` fails
Tier 3 at the first check, automatically, which is the correct answer.

**Falsifiable success criterion for the gate: if Confetti reaches Tier 3 for any
component, the gate is too loose.**

---

## 7. The oracle

One harness, used by every tier above Tier 1:

```
component record + argument binding
  → print Kotlin                                        (§5.2)
  → compile against a consumer classpath                PlaygroundCompileService / :tools:usage-compile-check
  → discover + render                                   the daemon
  → pixel-compare to the reference capture              the known-difference machinery
```

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
expressions. Run it over the four corpora and publish: how many previews have
exactly one inferable target; for how many is the full signature recoverable; and
the binding taxonomy (literal / fixture reference / unrepresentable). Every later
phase is sized by these numbers, and Phase 0 can be wrong cheaply.

**Phase 1 — the record.** `components.json` as a data product (bundle sidecar +
manifest pointer), built from metadata + PSI. Preview browser API panel.
Delivers "detect components from previews", "learn the API", "explain the API" on
its own, for every catalog *and* for typical apps, with no source change anywhere.

**Phase 2 — parameters as the override format.** The parameter convention; the
daemon's argument-seeding wire shape; `@OverrideVariant` lowering to arguments;
the codemod; deprecation of `previewOverride*`. Migrate `:samples:design-catalog-m3`
first (this repo's own, and the delegating shape that broke the Source panel),
then m3-catalog's ambient helpers.

**Phase 3 — print, and gate.** Snippet generation by projection; Tier 2 as a CI
gate over the corpora; retire the cleaner for migrated catalogs.

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
