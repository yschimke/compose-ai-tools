# Design catalogs — code-led sticker sheets per component system

A **design catalog** is a `samples/` module whose `@Preview`s exist to be
exported as an importable **sticker sheet**: every component of a system
(Compose M3, Wear Compose M3, Glimmer, Glance/Wear widgets) rendered in its
primary modes, in two variants (the `ideal` capture and the
`compose/semantics-wireframe` `layout` view), with the system's design tokens
(`compose/theme`) and accessibility findings (`a11y/*`) extracted from the
render.

The renderer here is the source of truth; the importable bundle is assembled by
[`@design-parity/catalog-export`](https://github.com/yschimke/design-parity/tree/main/packages/catalog-export),
and the workflow is documented by the `compose-design-catalog` skill in
[yschimke/skills](https://github.com/yschimke/skills). Published Figma kits are
seed/reference only — a kit/render divergence is a bug in the kit.

## Why a dedicated module per system

`@Preview` discovery is local-module only: the renderer sees previews compiled
into a module, not previews that live inside a library. So each system needs a
module that depends on its library and authors **one `@Preview` per component ×
primary mode**. Encode the modes with a shared multipreview annotation
(`@CatalogModes` → light + dark) and add per-component `@Preview`s for the states
and breakpoints that matter.

## Modules

| Module | System | Status |
| --- | --- | --- |
| `samples/design-catalog-m3` | Compose Material 3 (+ Adaptive, planned) | ✅ template |
| `samples/design-catalog-wear-m3` | Wear Compose M3 | ✅ |
| `samples/design-catalog-remote-m3` | Remote Compose (Wear Compose Remote M3 + `remote-creation-compose`) | ✅ |
| `samples/design-catalog-glimmer` | Glimmer (Android XR) | planned (see `samples/xr-glimmer`) |
| `samples/design-catalog-glance` | Glance app widgets + Wear widgets | planned |

The Remote Compose catalog is the sticker-sheet sibling of the
`samples/remotecompose` demo (which shows the two *ways* to preview Remote
Compose). Each sticker is a real `RemoteDocument` built by `RemotePreview` and
rasterised by the Remote Compose player, and has a single primary mode (the
document carries explicit colours, so there is no light/dark split). That one
mode is **dark**: the colours come from `RemoteMaterialTheme`, the dark-first
Wear Compose Material 3 scheme, so — like the Wear stickers — the captures are
transparent with light content. The spec tags it accordingly (`modes: ["dark"]`
+ `display.surface: "dark"`); leave the surface off and the preview server's
fallback heuristic (a `wear`/`watch` id token, which `remote-m3` doesn't carry)
picks the default white stage and the white `RemoteIcon` / `RemoteText` stickers
disappear into it. It carries the alpha Remote Compose
runtime and `compileSdk 37`, diverging from the rest of the repo; see its
`build.gradle.kts` and `:samples:remotecompose`.

An **app-modelled** catalog — one whose stickers render a real app's own surfaces
rather than a component library's widgets — lives in that app's repo instead of
here (e.g. the Confetti app publishes its own mobile + Wear sheets via the reusable
workflow below); this repo's catalogs are the per-component-system sheets.

Each module carries a `catalog.spec.json` (the Phase-0 inventory: groups,
captions, primary modes, breakpoints, and the seed-kit frame per component).

### Declare a hero (`display.hero`)

`display.hero` names the preview the preview server's front door features on the
system's card — a componentId (`"Template/AppScaffold"`) or a preview-function
name. **Declare one, and make it a screen.** With no declaration the server falls
back to `ServeWeb.representativePreviewId`, which prefers a `Screens`-section
preview and otherwise settles on a canonical filled button; a component-library
catalog has no `Screens` section, so its front door ends up advertising a lone
button — true to the inventory, useless as a shop window. Each catalog here
therefore points its hero at its full-screen scaffold template:
`compose-m3` → `Template/AppScaffold`, `wear-m3` → `Template/TimeText`,
`remote-m3` → `Template/WatchScreen`. A hero that resolves to nothing is silent
(the server just falls through), so `validateSpec` resolves it against the spec's
componentIds, the module's `@CatalogComponent` ids, and its `@Preview` names —
and the `validate-samples` test runs that over every catalog in this repo.

## Delivery branches

The [`design-artifacts`](../../.github/workflows/design-artifacts.yml) workflow
runs **on every merge to `main` that touches a catalog** (`samples/design-catalog-*`,
`samples/cmp-wasm-catalog`) or the export driver (`scripts/design-artifacts/`),
plus every Monday, at the tail of a release, and on demand via
`workflow_dispatch`. A merge-triggered run is scoped by its `changes` job to only
the systems whose inputs moved — the mapping lives in
[`scope-systems.sh`](../../scripts/design-artifacts/scope-systems.sh) and is
guarded by `test-scope-systems.sh` in CI — so a one-catalog change regenerates one
branch rather than paying for every system. Measured over the last 25 successful
runs: a scoped push-triggered render is 8–29 min (median ~14); a full all-systems
`workflow_dispatch` is 31–38 min.

Renderer / plugin / CLI changes are deliberately **not** in that push trigger:
they do change the rendered output, but they're touched by most merges, so the
weekly cron and the release chain absorb that drift instead. Dispatch manually if
a renderer change needs to reach the delivery branches before Monday.

Each run renders the catalog module with `compose-preview bundle pack --with-semantics`, runs the
`@design-parity/catalog-export` driver
(`scripts/generate-design-catalog.mjs`), and force-pushes the importable bundle
to a clean **`design-artifacts/<system>`** branch — `design-artifacts/compose-m3`,
`design-artifacts/wear-m3`, … — that a designer pulls into Figma / Stitch /
Claude Design. The branch holds only the generated bundle (`catalog.json`,
`tokens.dtcg.json`, `figma-variables.json`, `images/` PNGs, and `figma/` — the
per-sticker layered **`compose/figma-svg`** vectors), regenerated from the code
on each catalog change so it never drifts. Each component ships both the raster PNG (in
`images/`) and its editable vector (`figma/<slug>.svg`): import the PNG for a
pixel reference or the SVG for a real editable component — fills, strokes, corner
radii, and text are live layers, not a flattened screenshot. The SVG is the same
layered `compose/figma-svg` export produced per preview; the catalog pipeline
carries it in the bundle (`previews/<id>.figma.svg`) and copies it onto the
branch, exactly as it does the schematic `wireframes/`.

For editable Figma layers the per-sticker `figma/<slug>.svg` is the
`compose/figma-svg` export (see the `FigmaLayeredSvg` KDoc in
`:data-layoutinspector-core` for the emitted layer shape); the design-parity
`figma-plugin` imports it as native component sets. An agent can also push
rendered *variant matrices* onto a Figma canvas via the Figma MCP server for
live review.

## Publishing from another repo (reusable workflow)

A consumer repo that owns a `catalog.spec.json` + a renderable `@Preview` module
(an app modelling its own surfaces, like the Confetti catalog) doesn't need to
copy this job. The reusable
[`design-artifacts-reusable.yml`](../../.github/workflows/design-artifacts-reusable.yml)
(`on: workflow_call`) does the whole pipeline — install CLI → **validate spec** →
render module(s) → generate → force-push `design-artifacts/<system>` — behind one
`uses:` call:

```yaml
# .github/workflows/design-artifacts.yml in the consumer repo
on:
  schedule: [{ cron: '0 6 * * 1' }]
  workflow_dispatch:
jobs:
  publish:
    if: ${{ github.repository == 'you/your-repo' }}   # don't let forks push
    permissions:
      contents: write
    uses: yschimke/compose-ai-tools/.github/workflows/design-artifacts-reusable.yml@main
    with:
      system: your-system         # → design-artifacts/your-system, served at /your-system/
      spec: catalog.spec.json
      module: ':app'
      # extra-module: ':your-components'   # optional, folded in via --extra-renders
      # desktop-render: true               # for a CMP desktop (Skiko) render
    secrets: inherit               # optional; only for buildfetch_ro_token
```

Author the spec with `init-catalog-spec` and check it with `validate-catalog-spec`
(see below) before the first run.

For the two bespoke needs a catalog like MeshCore has, the reusable workflow
exposes generic hooks rather than forcing a copy: `stage-font-globs` stages
bundled faces into fontconfig before rendering (so a desktop render resolves
CJK/Arabic glyphs from the app's fonts), and `fold-artifact` + `fold-bundle` +
`fold-spec` + `fold-section` fold a **caller-produced** bundle in as its own
top-level section after generate. A caller runs its bespoke lane (e.g. re-theming
a sibling catalog) in a prior job that uploads the bundle artifact, then passes it
to the reusable workflow — so the render/generate/publish stays shared while the
bespoke step lives in the caller. A Wasm tier still needs a bespoke workflow.

### Convention: the reference caller drives common features

**meshcore-mobile is the reference consumer of the reusable workflow, and it
drives what "common" means.** When a caller needs a new catalog-pipeline
capability, the rule is: **add it as a generic input to the reusable workflow and
consume it from the caller — never fork a bespoke copy of the pipeline.** That is
how font-staging, section fold-in, two-module validation, and the live-bundle /
per-preview-split lanes each became reusable inputs after MeshCore first needed
them. The test for "generic" is whether it's a capability any catalog could want
(it belongs upstream) versus machinery specific to one repo's internals — a
**Wasm tier**, a **build-from-source CLI to publish HEAD's renderer**, a
**release-runtime Maven-Central gate** — which stays in that repo's own workflow.
The payoff is that every consumer's pipeline can't silently drift from the shared
one; the cost is one small upstream input per new feature, paid by the caller
that introduces it.

## Rendering a catalog

```sh
# --module is the Gradle path (leading colon optional), not the bare name —
# the resolver maps it to a directory (`samples/design-catalog-m3`).
compose-preview show --module samples:design-catalog-m3 \
  --with-extension a11y,theme,semantics,semantics-wireframe --json \
  > /tmp/m3-show.json
```

- `capture` PNGs → the `ideal` variant.
- `compose/semantics-wireframe` → the `layout` (bordered) variant.
- `compose/theme` → the token set; `compose/semantics` → bounds / padding /
  `textOverflow` (maxLines); `a11y/atf` + `a11y/touchTargets` → greenlines.

Feed the result through `@design-parity/catalog-export` to produce the importable
bundle, and commit it to the system's `design-artifacts/<system>` delivery
branch.

## Wireframes

Each component ships an **editable SVG wireframe** (`wireframes/<slug>.svg`,
linked from `index.html`) so a developer can adopt the structure in a vector
tool instead of tracing a screenshot. It is built from the **layout-inspector
tree** (`previews/<id>.layout.json`, carried by `bundle pack --with-semantics`):
a walk of every `LayoutNode`, so it captures the slot containers and each node's
resolved design tokens — background / border colour, per-corner radius, padding —
not just the a11y controls. Where a render carried no tree, the driver falls back
to the older a11y-greenline wireframe (touch-target rects only).

The difference is the slots. For an M3 `SegmentedButton` the a11y wireframe (#2)
can only draw the two radio touch targets; the layout wireframe (#3) draws the
real pill shape, the selected-segment fill, and labels each region with its
resolved token:

![Rendered preview vs semantics wireframe vs layout-inspector wireframe, for
SegmentedButton and AssistChip](layout-wireframe-evidence.png)

## Scaffold templates

Beyond the per-component stickers, each catalog ships a **Scaffold templates**
group: full-screen, pre-built screen skeletons an app copies whole, captured as
a real screenshot (PNG, plus the layered `compose/figma-svg` vector). They sit on
their own full-screen frame rather than the centred component sticker:

- **Compose M3** — `FullScreenM3` places a template on a phone with
  `showSystemUi = true`, so the renderer's `SystemBarsFrame` paints the OS status
  bar (clock, battery) and gesture-pill nav around it; the wrapper reserves
  `SYSTEM_BAR_INSET` top/bottom so the app's own chrome clears that overlay. The
  `Template/AppScaffold` template is a `TopAppBar` + list + FAB — the canonical
  "full screen layout with a status bar", rendered light + dark.
- **Wear M3** — `WearScaffoldTemplate` (an alias of `FullScreenWear`) supplies the
  dark theme *and* the `AppScaffold`, including the curved `TimeText` status strip
  frozen at `10:10` for deterministic renders. A template composes its own
  `ScreenScaffold` under it and must **not** add another `AppScaffold` — nesting a
  second one would draw a second status strip. Every full-screen Wear capture
  carries the strip, templates and stickers alike: a Wear screen without its clock
  isn't the screen an app copies, since the strip reserves the curved top margin
  the content lays out around. (`ScreenScaffold` still hides the strip once a list
  is scrolled away from the top, so a `@ScrollingPreview(END)` capture legitimately
  shows no clock.) Three template variants cover the status-strip archetypes:
  `Template/TimeText` (base list screen),
  `Template/PageIndicator` (horizontal pager + `HorizontalPageIndicator`), and
  `Template/EdgeButton` (list anchored by the screen-hugging `EdgeButton`), each
  captured at every round breakpoint.

## Authoring & validating the spec

`catalog.spec.json` is hand-authored, and each component's `preview` must equal
an **exact `@Preview` function name** in the module — a mistyped or renamed name
renders nothing and only surfaces as a late "missing" entry at the end of the
(long) render. Two build-free helpers in `scripts/design-artifacts/` close that
gap by scanning the module's Kotlin source directly (no Gradle build, no render):

- **Scaffold a starting spec** from the `@Preview` functions a module declares —
  one flat `Components` group, every discovered preview a component to caption and
  regroup:

  ```sh
  node scripts/design-artifacts/init-catalog-spec.mjs \
    --module :app --system meshcore-mobile --title "MeshCore Mobile" \
    --out catalog.spec.json
  ```

- **Validate an existing spec** — resolves every `preview` (component and variant)
  against the discovered functions, suggests a fix for near-miss typos, flags
  structural problems (duplicate `componentId`, folded previews, malformed
  variants), and lists `@Preview`s not yet in the catalog. Exits non-zero on
  errors, so it runs as a pre-flight in `design-artifacts.yml` before the render:

  ```sh
  node scripts/design-artifacts/validate-catalog-spec.mjs --spec catalog.spec.json
  ```

  The module is taken from the spec's `module` field; override with `--module-dir`
  / `--src`, and pass `--preview-annotation <Name>` for a multipreview annotation
  imported from another module. Discovery recognises `@Preview` and any
  `annotation class` meta-annotated with it (`@CatalogModes`, `@CatalogTemplate`,
  …). The authoritative check remains the render + completeness gate; this is the
  fast local/CI pre-flight.

  It also rejects a `preview` that resolves to a **PNG-less** function — one whose
  only capture is an animated GIF or a scroll data product (`@AnimatedPreview`, a
  multi-step `@FocusedPreview(gif = true)`, or `@ScrollingPreview` with only
  `ScrollMode.LONG` / `ScrollMode.GIF`). Those render fine, but the export
  represents every catalog
  entry as a static sticker: `candidatePreviewBundle()` drops anything without
  `previews/<id>.png` from the candidate join, and the completeness gate then
  reports the component missing. Catalogue a static `@Preview` sibling instead and
  let the GIF travel in the bundle as its own artifact. `init-catalog-spec` skips
  these functions when scaffolding, and the validator's discovery line names them
  so you know why they're absent. When there is no static sibling to point at, the
  entry can instead declare `"capture": "none"` — see below.

The spec shape is described by
[`scripts/design-artifacts/catalog.spec.schema.json`](../../scripts/design-artifacts/catalog.spec.schema.json)
(referenced via `$schema` in each sample spec for editor validation).

### Render priority: deferring the long tail to the live server

A catalog that publishes with a live path — `publish-live-bundle: true` (the bundle
carries the classpath to re-render any preview on the serve host) or a buildable
`source` (`live-rerender-source`) — doesn't have to bake *every* sticker in CI. A
spec can mark coverage **deferred**: recorded in `catalog.json` as live-only, not
rasterised, and not counted as a missing render or missing-semantics failure.

```jsonc
{
  "componentId": "Buttons/Row button",
  "preview": "RowButtonLightPreview",          // priority: required (default)
  "variants": [
    { "preview": "RowButtonDisabledPreview", "state": "disabled", "priority": "deferred" }
  ]
}
```

and per axis, which is the bigger lever for a catalog whose previews fan out over
several themes:

```jsonc
"modes": ["light", "dark"],
"modePriority": { "light": "required", "*": "deferred" }
```

- `required` (the default, and what every spec that says nothing gets) — rendered,
  joined, and subject to the strict completeness gate exactly as before.
- `deferred` — recorded in `catalog.json` under a top-level `deferred[]` array with
  its `@Preview` name and the daemon `previewIds` its function produces, so a
  `serve --catalogs --allow-render-trusted` host can produce it on request.

Each `deferred[]` record is **addressable**, which is what makes the on-demand path
real rather than declarative:

- `path` — the `images/…` path the sticker *would* have been written to, derived by
  [`catalog-image-path.mjs`](../../scripts/design-artifacts/catalog-image-path.mjs).
  The serve routes are `previewIdFor(image.path)`, so recording the path (rather than
  having the server re-derive the exporter's naming) keeps one id namespace and means
  flipping an entry between `required` and `deferred` never moves its URL. The export
  re-derives every *baked* image's path on each run and compares: if the naming ever
  drifts, it says so and publishes the records without a `path` (the server then
  skips them) rather than pointing them at routes no sticker will occupy.
- `previewId` — the one daemon preview that renders it. An entry- or variant-level
  deferral names no axes (nothing rendered, so nothing recorded that its function
  produces a light *and* a dark sticker), so the export expands one spec record into
  one record per `@Preview` annotation, recovering each one's theme/size.

`ServeCatalogStore` reads them back: each record joins the catalog-id → daemon-id
alias and is registered as a **live-only preview** — a card in its proper tab, group
and state switcher, whose every render (not just an override-bearing one) routes to
the daemon, since there is no baked PNG to replay. Live-only previews are registered
**only** where a live lane stood up. A session serving baked PNGs only — no
`liveBundle`, an unverified catalog, `--allow-render-trusted` off — omits them and
records a `deferred-not-served` degradation saying how many are hidden, rather than
listing cards whose every request 404s.

Why not just `--allow-incomplete`: that flag is all-or-nothing, so turning it on to
tolerate a known-absent sticker also lets a genuinely broken *required* render
publish unnoticed. Priority keeps the gate strict over the core inventory while
letting a catalog explicitly opt the long tail out.

What each form actually saves:

- **Per axis** (`modePriority`) — **usable now, and now a build-time win too.** Thins
  what is *published*: the deferred palettes are not written to `images/`, not exported
  as `figma/*.svg`, and not carried into the Figma import. Every component stays in
  `catalog.json`'s `components[]` with its untagged primary sticker, so the served
  catalog browses as before with one fewer baked palette. They are also **not rendered
  and not semantics-captured**: the fan-out lives *inside* one `@Preview` function (a
  multipreview member, or one of several `@Preview` annotations), so naming functions
  can't express it and the skip is per preview **id** — `composePreviewRender
  --exclude-preview-id` / `-PcomposePreview.idExclude`, plus `bundle pack
  --exclude-preview-id`, which forwards the patterns to the render *and* skips the same
  ids in the CLI-driven daemon semantics pass. Ids only exist after discovery, so both
  design-artifacts workflows run `compose-preview list --json` first and derive them with
  [`deferred-preview-ids.mjs`](../../scripts/design-artifacts/deferred-preview-ids.mjs);
  that extra Gradle invocation is gated on the spec actually deferring a mode, and its
  compile is shared with the render that follows. Measured against a nine-theme catalog
  this is the bigger lever: deferring every palette beyond the primary drops ~59% of
  renders, versus ~37% for deferring all variants.
- **Per entry** (`priority` on a component or variant) — **usable now**, since
  [#2965](https://github.com/yschimke/compose-ai-tools/issues/2965) gave the deferred
  records a live lane. A wholly-deferred entry has no `images[]` record at all, so it
  reaches a viewer only through that lane: the export writes it to `catalog.json`'s
  top-level `deferred[]` (with the `path` it *would* have baked, so the route id is the
  same either way), and `ServeCatalogStore` decodes those records — aliasing each to its
  daemon `previewId` and registering it with no baked PNG. A session with **no** live
  lane hides them and says why (`deferred-not-served`) rather than showing broken cards.

  It is also the form that saves whole renders, not just published bytes: the pre-flight
  emits the `--preview` patterns for the still-required functions (`--render-filter-out
  <file>`), and both design-artifacts workflows feed them to the render as
  `ORG_GRADLE_PROJECT_composePreview.filter`. A function is only droppable when
  **nothing required points at it** — two entries can name the same `@Preview`. One
  reader — `entryPriority` in
  [`catalog-priority.mjs`](../../scripts/design-artifacts/catalog-priority.mjs) — drives
  every consumer (the join, the variant split, the render filter), so the render set and
  the published set can't disagree about which entries are baked.

  **Deferring a component defers its `variants` with it** (`variantPriority`), whatever
  they say — each gets its own `deferred[]` record so it stays live-routable. A variant's
  sticker is normally *folded onto* its component's images, so once the component is
  deferred there is no `components[]` entry left to fold onto: a variant left at the
  `required` default would be rendered and then neither baked nor recorded anywhere. The
  useful direction — a required component with one deferred variant — works as written.

Caveats worth knowing before reaching for it:

- **A live path is required.** Deferring with neither `--publish-live-bundle` nor
  `--source-module` is refused by the driver (and by the pre-flight, when the
  workflow tells it which applies) — otherwise it isn't a cheaper build, it is
  coverage silently missing from the published sheet.
- **Static consumers see less.** `images/`, `figma/*.svg` and the Figma import carry
  only required entries. That's why the default stays `required` and deferral is
  always explicit. A *baked-only* serve session is a static consumer too: it hides
  the deferred previews (and says so — see `deferred-not-served` above), so the
  deferred sheet is only whole on a host with a live lane.
- **The primary sticker is never deferrable by mode.** Only a render that *names* a
  theme is eligible, so every published component keeps baked pixels. A component
  whose every render is mode-deferred is treated as a misconfiguration and fails the
  gate rather than publishing with no pixels. The render-side filter mirrors that: a
  function whose *every* discovered id resolves to a deferred mode keeps all of them
  (and says so in the log), so a bad `modePriority` surfaces as a gate failure rather
  than as a component silently rendered away.
- **A `@PreviewParameter` mode axis is skipped by label, not by id.** When the palettes come
  from a provider rather than a multipreview, discovery emits ONE entry for the parameterized
  function — it reads bytecode and can't instantiate the provider — and the rows only exist
  once the renderer enumerates them. So there is no id to exclude, and the derivation emits
  the deferred **mode names** instead (`--rows-out` → `bundle pack --exclude-preview-row` →
  `-PcomposePreview.rowExclude`), which the renderer matches case-insensitively against the
  label it puts in `<stem>_<label>.png`. The choice is made **per function**: a deferred mode
  becomes a label when some spec-referenced *parameterized* function has no id carrying it, so
  a catalog whose modes are all visible as ids gets no labels at all, while a mixed one still
  gets the label its provider-backed component needs. Because labels are then matched
  module-wide inside the render, an unrelated parameterized preview whose row is labelled like
  a deferred mode loses that row — the completeness gate then fails the publish for that
  component, which is the loud outcome, and the renderer never empties a preview's rows.
- **An Android/Robolectric catalog gets the publish saving but not the render saving**
  (`design-catalog-wear-m3`, `design-catalog-remote-m3`): that `composePreviewRender` is a
  `Test` task reading the manifest directly and honours none of these filters — the same
  backend gap #2066 left open for the name filter, tracked in #2977. The `--with-semantics`
  saving *does* land there, since the CLI drives that pass.
- **A skipped render is still declared.** Deferred previews stay listed in the bundle's
  `previews.json` — the bundle task carries every selected preview and simply omits the
  PNG for one that didn't render — which is what keeps them addressable on the serve
  host's live lane. `catalog.json`'s `deferred[]` records the mode-deferred coverage from
  that listing, so the declaration doesn't disappear along with the pixels.

## Components with no static sticker (`capture: "none"`)

Not every PNG-less preview announces itself in the source. A composable hosted in
an `AndroidView` (say an HTML-rendering `TextView`) and a horologist
`ScalingLazyColumn` screen are both written as a plain `@Preview` and still land
without a `previews/<id>.png` — so the candidate join drops them and the
completeness gate reports them as **missing renders**, refusing to publish the
whole system. Deleting the entry publishes, but the sticker sheet then silently
under-represents the design system.

Declare the situation instead:

```json
{
  "name": "Screens",
  "section": "Animations",
  "components": [
    {
      "componentId": "Screens/Watch list",
      "preview": "WatchListPreview",
      "capture": "none",
      "caption": "Scrolling watch list (no static frame — ScalingLazyColumn)"
    }
  ]
}
```

`"capture": "none"` (component or variant; absent ⇒ `"static"`) keeps the entry in
the inventory, excludes it from the candidate join like any other PNG-less
preview, and stops it counting as a missing render — the export names it on every
run instead:

```text
[pocketcasts-wear] declared no sticker (capture: "none"), none exported for: Screens/Watch list
```

The value names what the gate checks — this entry exports no sticker — not why.
Only some of these previews are animated (a `ScalingLazyColumn` screen and an
`AndroidView` host are perfectly still), and `"animated"` is deliberately left
unused so a future mode that really does export a GIF can claim it. A mistyped
`capture` is a validation error rather than a silent fall-through to `"static"`,
which would sink the publish on the very entry it was meant to exempt.

Unlike `--allow-incomplete`, this is per-component: every other entry keeps the
strict gate. Use it only where the render genuinely produces no PNG — a spec entry
that *should* have rendered and didn't is exactly what the gate exists to catch.

This is a different axis from **render priority** above, and they answer different
questions. `priority: "deferred"` says *don't bake this one in CI — the live server
can render it on demand*, and needs a live path to be legal. `capture: "none"` says
*nothing can render this to a PNG at all*, live path or not. A deferred entry is
still coverage the sheet can produce; a `"none"` entry is a recorded gap.


1. Author a `@Composable` wrapped in the module's sticker theme, annotated with
   `@CatalogModes` (and extra `@Preview`s for states / breakpoints). Full-screen
   templates use the full-screen frame + multipreview instead — `FullScreenM3` +
   `@CatalogTemplate` (M3) or `WearScaffoldTemplate` + `@CatalogWearBreakpoints`
   (Wear).
2. Add it to `catalog.spec.json` under its group with a caption and, if known,
   the seed-kit frame reference.
3. Validate the spec (`node scripts/design-artifacts/validate-catalog-spec.mjs
   --spec <spec>`) to confirm the `preview` name resolves before rendering.
4. The next render + export picks it up automatically — no harness change.
