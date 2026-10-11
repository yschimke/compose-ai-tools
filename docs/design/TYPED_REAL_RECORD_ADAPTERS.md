# Typed adapters on real composables

Status: design, with the export half spiked (`ExportProjection`). Follows
[`TYPED_UI_BUILDER_CATALOG.md`](TYPED_UI_BUILDER_CATALOG.md).

## Problem

A typed adapter today must target a callable whose parameters ARE the wire properties: `String`,
`Boolean`, `Int`, named exactly as the builder stores them. Real library composables never look like
that — `androidx.wear.compose.material3.Button` takes `colors: ButtonColors`, picks its style by
being a different function (`FilledTonalButton`), and takes `RowScope` slots. So the Mobile and Wear
pilots added catalog-owned wrapper entry points (`WearButton(variant: String, containerColor:
String, …)`) and pointed records, definitions and exported source at them.

That cannot reach production: exported code would call a catalog wrapper instead of the library,
saved designs' records would change identity, and the hand-written export knowledge would simply
move into wrappers. Meanwhile the knowledge production does use is hand-written in the builder:
`WearContentEmitter` is a `when (componentId)` over 25 Wear ids, and `ScreenDocumentProjection`
carries `PROPERTY_PARAMETERS` / `COMPONENT_VARIANTS` / `ENUM_MEMBERS` / `COLOR_TOKENS` tables for
m3. Neither is owned by the catalog it describes.

## Shape

One catalog-owned typed definition per component, on the **real** record, carrying both halves:

1. **Render** — the existing typed registration: a compiled composable call to the real library,
   reading decoded property values. Unchanged in kind; what changes is that codecs decode to the
   real Kotlin types (`Color`, an enum) instead of strings.
2. **Export** — an `ExportProjection`: data stating how the wire properties become the real call.
   Published in the catalog pair; interpreted generically by the builder; printed by the existing
   `ScreenGenerator`.

The two halves are tied by tests, not by sharing code, because the renderer cannot call a
composable reflectively in Wasm: a catalog's verification compiles the exported source of every
template and fixture, renders it, and compares it to the runtime render of the same document.

### Export projection (spiked)

```kotlin
ExportProjection(
  variantProperty = "variant",
  targets = listOf(
    Target(button.canonicalId, mapOf(
      "enabled" to Property("enabled", Codec.Bool, omitWhen = JsonPrimitive(true)),
      "colors" to Call("androidx.wear.compose.material3.ButtonDefaults.buttonColors",
                       "androidx.wear.compose.material3.ButtonColors",
                       mapOf("containerColor" to Property("containerColor", wearColor),
                             "contentColor" to Property("contentColor", wearColor))),
    )),
    Target(filledTonalButton.canonicalId, /* … filledTonalButtonColors … */,
           variants = setOf("filled-tonal")),
  ),
)
```

Lowering a saved `{variant: filled-tonal, enabled: false, containerColor: surface, contentColor:
#336699}` produces `FilledTonalButton(enabled = false, colors =
ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer,
contentColor = Color(0xFF336699)))` through `ScreenGenerator`, imports included
(`ExportProjectionTest`). The vocabulary is deliberately closed:

- **Targets** — a variant property selects the record *and* its parameter bindings, replacing
  `COMPONENT_VARIANTS` and per-variant `when` blocks.
- **Values** — a property through a codec, a fixed `ScreenValue`, or a nested factory `Call` written
  only when one of its arguments is (an unstyled button keeps the library's colours). No templates,
  conditionals or source text: everything is validatable data.
- **Codecs** — text, boolean, whole, float, colour (literal / `transparent` / theme role, with the
  catalog's role renames), enum (wire value → qualified member). Further codecs (dp, sp, shape token,
  icon, Remote state types) are added the same way, each mapping one wire value to one `ScreenValue`.

Structure that is not a call — Wear's shared scroll state, `item { }`, `SurfaceTransformation`
only inside a lazy item — stays in catalog-published `StructuralTemplate`s, which already exist for
exactly this; leaves inside them are projected records.

## Plan

**Tools (this repository)**

1. Publish `ExportProjection` on the wire: an optional `exportProjection` on authored components,
   in compose-preview-contracts' `component-catalog-protocol`, with this interpreter in
   `screen-model` so the builder and catalogs share it.
2. Typed definitions on real records: wire properties instead of parameters, each with a codec that
   both decodes for the renderer and lowers for export; `validateAgainst` checks every projection
   against the discovered records — target parameters exist and match the produced `typeFqn`, factory
   arguments name real factory parameters. `TypedAdapterDiscovery` discovers the `…Defaults`
   factories (object members) the projections call.
3. Release.

Status: 1 is compose-preview-contracts#170 (3.29.0). 2 is in: `wireProperty`, `exportAs`,
`target`/`from`/`call`, the `Float`/`color`/`enumOf` codecs, `WireColor`, projection validation
in `validateAgainst`, variant records shipped excluded from the shelf, and `ExportProjectionJson`
for the published spelling. `policy()` publishes `exportProjection` (contracts 3.29.0).

Factories are discovered, not named: `TypedAdapterDiscovery.discoverFactories` reads every overload
of a `…Defaults` member from metadata as a `FactorySignature` (parameters, defaults, declared return
type), and `call(overloads, …)` takes those rather than strings. Validation resolves each call the
way Kotlin resolves named arguments — some overload must declare every argument written, with the
written type, and default every one left out — and `TypedAdapterCatalog.generate` refuses a factory
its caller did not freshly discover, as it refuses a stale component record.

**compose-ui-builder**

4. Read published projections: `ScreenDocumentProjection` lowers through a component's projection
   when it has one, before its hard-coded tables; a projection-backed `composeSourceExport` strategy
   lets `wear-m3` leave `wear-compose-screen`. Expression allow-list derived from the catalog.
   The interpreter half needs `ExportProjection.properties`, `directParameter` and
   `qualifiedOwners`: a host refuses a property no projection writes, passes a state-bound value
   only to a parameter it reaches unwrapped, and widens its allow-list only by the owners a
   projection prints.
5. Move the typed renderer bridge from `experiments/typed-catalog` into the SDK; release.

**Catalogs** (Wear, then Remote, then Mobile)

6. Typed definitions on the real records with projections; structure as templates; delete the
   wrapper entry points.
7. Gates, together: render parity typed vs production for every offered value (exists for Wear);
   exported source of every template and saved design compiles and renders like the runtime;
   production exporter and projection export equivalent for every committed design.
8. Cut over: production policy, runtime and `composeSourceExport` switch in one change; the hand-
   written builder branches for that catalog are deleted in the builder afterwards.

Remote keeps three surfaces: its editable canvas draws Wear stand-ins (the Wear typed registrations,
reached through the pinned wear-m3-catalog sources), Browser Preview plays real Remote documents and
Android Native/Live stays authoritative. Its projections target the real Remote records, whose state
types (`RemoteBoolean`, `RemoteColor`, `Action`) need their own codecs.
