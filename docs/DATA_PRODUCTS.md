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

### A narrowed request narrows the production, and the report it writes is partial

Structured production fans out **per preview** — each `data/fetch` is its own daemon
render — so it is the dominant cost of a command like `compose-preview a11y`, not a
rounding error on top of the Gradle render. `--id` / `--filter` therefore has to reach
it: on a 66-preview module, `a11y --filter Foo` fetches ATF for the one preview it is
going to print, not for all 66 (issue #3742). `ReportCommand` resolves the request
against each module's discovery manifest and hands its `produceAdditionalDataProducts`
hook a per-module `DataProductRequest` carrying exactly the ids to fan out over;
modules the request doesn't touch never open a session at all.

The narrowing is computed from the **request**, not from the render's `renderedIds`.
That set is `null` both when there was no request and when the request happened to
select every preview (the render declines to narrow, because a filtered
`composePreviewRender` isn't build-cacheable), so it cannot answer "what did the user
ask about".

The request is resolved in the **daemon's** id space, which is a second thing worth
getting right. `PreviewIndex.byId` resolves against the `previews.json` the plugin
wrote, so a preview the CLI synthesised through `PreviewPermutationsCli` — the
`Foo_dark` a `--permutations accessibility` run shows you — is not addressable: ask
for it and the daemon answers "unknown preview". So the hook is handed the
**unexpanded** manifests (`PreviewResultBuilder.readAllManifests`, not `Command`'s
expanding wrapper), matching is done against the *expanded* ids because that is what
the user saw, and the *unexpanded* id is what gets fetched. `--id Foo_dark` therefore
fetches `Foo`, and `--filter Foo` fetches it once instead of once per permutation.
Getting this backwards is not a rounding error: on a narrowed run the synthetic id is
the only fetch, so it fails the whole command.

The consequence to keep in mind when adding another per-preview hook: the sidecars
(`accessibility.json` and friends) are **per-module** reports, so a narrowed run
produces a partial one, and a partial report is dangerous in a specific way — every
consumer reads "module has a report, but this preview isn't in it" as *checks ran and
found nothing*. Publishing a module-wide report covering three of sixty-six previews
would therefore certify sixty-three previews ATF never looked at. Two mechanisms keep
that honest, and a new hook needs both:

1. **Merge, don't clobber.** Entries on disk for previews outside this run's ids are
   carried forward and the fetched ones replace — the same bargain the `.cli-state.json`
   carry-forward strikes for previews a narrowed render skipped (issue #3730). Only an
   unnarrowed run rewrites wholesale, which keeps it the one thing that evicts an entry
   for a preview that no longer exists. A carried-forward `status = "atf-unavailable"`
   rides along with the entries it belongs to rather than being cleared by a later
   narrowed success.
2. **Declare what's still uncovered.** When the merged entries *still* don't cover the
   module, the report is stamped `partial: true`, and consumers invert the default
   reading: `A11yReportRenderer` withholds the `dataExtensions["a11y"]` carrier for an
   unlisted preview (so `a11yEntry()` is `null` — the established "checks didn't run"
   signal) and `.github/actions/lib/a11y-report.py` drops it from the findings table,
   the same way it already drops Wear Tiles rather than listing them as checked.

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

3. **Imported design data** *(produced by a repo's own import, republished here)* —
   structured design data cached out of the design tool. Today that is
   [`DesignPagesManifest`](../api/preview-data-api/src/main/kotlin/ee/schimke/composeai/designpages/DesignPages.kt):
   a whole page of a design file cached as SVG, with the node id of every component on
   it linked back to the code that implements it.

   It is neither of the other two — not the daemon's JSON analysis of a preview, and
   not a secondary image a render emitted. **Nothing in this repo talks to the design
   tool to make one**: a catalog repo imports its own pages on the design file's
   cadence and commits `design/pages/`, and this repo's role is to re-key the preview
   ids onto the published catalog (`scripts/design-artifacts/emit-design-pages.mjs`)
   and serve it. That split is what lets a fork, a token-less run and an offline
   republish all produce the same pages.

   It used to be genuinely *foreign* — version 1 was design-parity's
   `@design-parity/page-backdrop`, a composed screen as a flat PNG, mirrored here and
   pinned to the producer's fixture. That was retired: a raster has nothing
   addressable in it, and the whole point of this data is that the server can find a
   node in the export, hide the design's own drawing of it, and put our render in its
   place. Both ends of the contract now live here, so `DesignPagesParseTest` quotes
   the wire format directly rather than vendoring someone else's sample.

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
