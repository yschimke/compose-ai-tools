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

## Weekly delivery branches

The [`design-artifacts`](../../.github/workflows/design-artifacts.yml) workflow
runs every Monday (and on demand via `workflow_dispatch`): it renders each
catalog module with `compose-preview bundle pack --with-semantics`, runs the
`@design-parity/catalog-export` driver
(`scripts/generate-design-catalog.mjs`), and force-pushes the importable bundle
to a clean **`design-artifacts/<system>`** branch — `design-artifacts/compose-m3`,
`design-artifacts/wear-m3`, … — that a designer pulls into Figma / Stitch /
Claude Design. The branch holds only the generated bundle (`catalog.json`,
`tokens.dtcg.json`, `figma-variables.json`, `images/` PNGs, and `figma/` — the
per-sticker layered **`compose/figma-svg`** vectors), regenerated from the code
each week so it never drifts. Each component ships both the raster PNG (in
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

The spec shape is described by
[`scripts/design-artifacts/catalog.spec.schema.json`](../../scripts/design-artifacts/catalog.spec.schema.json)
(referenced via `$schema` in each sample spec for editor validation).

## Adding a component

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
