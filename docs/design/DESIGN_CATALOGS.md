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
| `samples/design-catalog-confetti` | Confetti app (mobile Compose M3) | ✅ |
| `samples/design-catalog-remote-m3` | Remote Compose (Wear Compose Remote M3 + `remote-creation-compose`) | ✅ |
| `samples/design-catalog-glimmer` | Glimmer (Android XR) | planned (see `samples/xr-glimmer`) |
| `samples/design-catalog-glance` | Glance app widgets + Wear widgets | planned |

The Remote Compose catalog is the sticker-sheet sibling of the
`samples/remotecompose` demo (which shows the two *ways* to preview Remote
Compose). Each sticker is a real `RemoteDocument` built by `RemotePreview` and
rasterised by the Remote Compose player, so — unlike the M3/Wear stickers — it
renders on the player's own opaque canvas (`showBackground = true`, no
transparency) and has a single primary mode (the document carries explicit
colours, so there is no light/dark split). It carries the alpha Remote Compose
runtime and `compileSdk 37`, diverging from the rest of the repo; see its
`build.gradle.kts` and `:samples:remotecompose`.

The **Confetti** catalog is the first **app-modelled** sheet rather than a
component-system one: instead of cataloguing a library's widgets, its stickers
reproduce a real app's signature surfaces (Confetti's session cards, bookmark
toggle, speaker rows, day tabs, and schedule scaffold) in standalone `material3`,
and it adds a **Conference themes** group that shows the same components re-branded
from each conference's `themeColor` seed — the phone counterpart of the Wear
catalog's self-contained, Robolectric-rendered shape. It's the template to copy
when onboarding another app's design (via the `init-catalog-spec` flow below).

![Confetti catalog component stickers, the per-conference theme group, and a dark-mode
row](confetti-catalog-stickers.png)

![Confetti schedule scaffold template, light and dark](confetti-catalog-schedule.png)

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
(see below) before the first run. Catalog-specific lanes some systems add here —
compose-m3's re-theme fold-in, a Wasm tier — stay in a bespoke workflow; the
reusable one covers the common single-module (± one extra module) case.

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
- **Wear M3** — `WearScaffoldTemplate` supplies just the dark theme; each template
  composes its own `AppScaffold(timeText = { … })` so the curved `TimeText` status
  strip is part of the capture (unlike the `FullScreenWear` stickers, which drop
  the clock). A frozen `10:10` keeps renders deterministic. Three variants cover
  the status-strip archetypes: `Template/TimeText` (base list screen),
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
