# Data products: one producer, two senses of the word

## Single producer

The **daemon is the single producer of *structured* data products** — accessibility
(ATF) findings, semantics trees, theme tokens, layout, recomposition, runtime
permissions. They're
registered in `DataProductRegistry` and travel as JSON over the daemon protocol.
Consumers reach them three ways, all backed by the same daemon:

- the VS Code chip toggle (`data/subscribe`),
- `compose-preview a11y` and the MCP server (`get_preview_data`), which spin up or
  talk to a daemon,
- `compose-preview bundle pack --with-semantics` and `compose-preview render --format
  svg`, which drive a short-lived daemon (`DaemonSemanticsFetcher`) to bake the
  `compose/figma-svg` (and semantics) sidecars, then carry them into the bundle or land
  them as loose `.svg` files,
- on-disk history (`compose-preview history`), which reads what the daemon archived.

The standalone `composePreviewRenderAll` Gradle Test task is the **lean PNG path**:
it renders screenshots (and secondary rendered artefacts — see below) but produces
**no** structured data products. ATF / semantics never run there. When a CLI command
needs structured data it spins up a short-lived daemon (as `compose-preview a11y`
does) rather than teaching the Test task to produce it. That split is deliberate:
it keeps the cold render path lean and free of the daemon's dependency surface.

### Seeding an environment is not producing a data product

The rule above is about *reading analysis out of* a render, not about *pushing state
into* one. Those are separate directions, and the static lane is allowed the second
one: `@PermissionPreview`, `@AmbientPreview`, `@GestureHintPreview`, `@FocusedPreview`
and friends all seed a connector's controller before composition so the render lands
on the branch the author asked for. A seeded environment changes the *pixels*, which
is exactly what the lean PNG path exists to produce.

`compose/permissions` is the worked example of the split, because its connector has
two legs that reach the lanes differently — and that asymmetry is deliberate:

| Leg | Daemon | Static `composePreviewRenderAll` |
| --- | --- | --- |
| **Grants** — `PermissionsController.set(...)` → `ShadowApplication.grantPermissions/denyPermissions`, so `ContextCompat.checkSelfPermission` flips the rendered branch | ✅ `renderNow.overrides.permissions` | ✅ `@PermissionPreview(grants = [...])` |
| **Queries** — `ShadowContextWrapperPermissionTracker` records which permissions the screen asked about, surfaced as the `queried` list | ✅ registered by `SandboxHoldingRunner` | ❌ **by design** |

Grants are seeding: they decide what gets drawn, so the static lane needs them and has
them (issue #3676, `PermissionPreviewPixelTest`). Queries are analysis: the `queried`
list is only ever read back through `PermissionsDataProductRegistry` over
`data/fetch?kind=compose/permissions`, which the standalone Test task has no protocol
to answer on. Registering the tracker shadow in
`GenerateRobolectricPropertiesTask`'s generated `robolectric.properties` would collect
a list nothing in that lane can read, and would route *every* static-lane
`ContextWrapper.checkPermission` through the connector's grant map — including previews
carrying no `@PermissionPreview` at all, whose controller state is empty. That is cost
and blast radius for no consumer, so the tracker stays daemon-only (issue #3698).

The general shape: **if a connector leg changes what the render draws, it belongs in
both lanes; if it only produces a payload someone fetches, it stays daemon-only.**

## Two senses of "data product"

The term is overloaded. These are genuinely different things:

1. **Structured data products** *(daemon)* — JSON analysis over the daemon protocol:
   `a11y/atf`, `compose/semantics`, `compose/theme`, layout, recomposition. Produced
   by the daemon's `DataProductRegistry`. Not images.
2. **Rendered artefacts** *(render manifest)* — secondary *images* a preview emits
   beyond its primary screenshot, each `kind`-tagged with an output PNG and a render
   cost. Modeled by `RenderPreviewArtifact`; the manifest field is
   `RenderPreviewEntry.dataProducts`. Produced by the render path (daemon or
   standalone).

The manifest type was historically named `RenderPreviewDataProduct`, which read like
sense (1). It is now `RenderPreviewArtifact`. The Kotlin class name isn't serialized,
so the rename is wire-neutral; the field name stays `dataProducts` for back-compat.

3. **Foreign reference data** *(consumed, never produced here)* — structured design
   data that some **other** tool emits and we only read. Today that is
   [`PageBackdropManifest`](../api/preview-data-api/src/main/kotlin/ee/schimke/composeai/designparity/PageBackdrop.kt):
   a design page imported from Figma with every component instance on it linked back
   to the code that implements it, produced by design-parity's
   `@design-parity/page-backdrop` and published to npm on its own cadence.

   It is neither of the other two — not the daemon's JSON analysis of a preview, and
   not a secondary image a render emitted. It comes from outside, and **nothing in
   this repo should ever produce one**. Our role is consumer: we hold a hand-written
   Kotlin mirror pinned to the producer's fixture by `PageBackdropParseTest`, and we
   use its `previewId`s to render components ourselves at the size the design places
   them — which is the whole reason to consume the data rather than the baked HTML
   viewer the producer also ships.

## Don't regress this

- **Don't teach the renderer or the Gradle plugin to produce structured (sense-1)
  data products.** That's the daemon's job. Keeping it there is what lets the cold
  render path stay lean and dependency-light — see the renderer-compatibility notes
  in [RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md).
- **Don't add `ShadowContextWrapperPermissionTracker` to the generated
  `robolectric.properties`.** It looks like a one-line parity fix and isn't — see the
  table above. `GenerateRobolectricPropertiesTaskTest` pins its absence so the "fix"
  can't land by accident; if the static lane ever *should* emit permission queries,
  that needs a sidecar-writing path and a deliberate reversal of the single-producer
  rule, not just the extra `instrumentedPackages` entry.
- **Don't teach anything here to produce sense-3 data.** A page backdrop needs a
  Figma read API and a design→code correspondence layer; both are design-parity's
  job, and importing that domain here would duplicate it. If the shape is
  inconvenient, change it upstream — the schema lives in that repo on purpose, since
  we are one consumer of it among several.
