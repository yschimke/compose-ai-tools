# Data products: one producer, two senses of the word

## Single producer

The **daemon is the single producer of *structured* data products** — accessibility
(ATF) findings, semantics trees, theme tokens, layout, recomposition. They're
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

## Don't regress this

- **Don't teach the renderer or the Gradle plugin to produce structured (sense-1)
  data products.** That's the daemon's job. Keeping it there is what lets the cold
  render path stay lean and dependency-light — see the renderer-compatibility notes
  in [RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md).
