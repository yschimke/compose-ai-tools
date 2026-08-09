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
(`scripts/generate-design-catalog.mjs`), and publishes the importable bundle
to a **`design-artifacts/<system>`** branch — `design-artifacts/compose-m3`,
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

`tokens.dtcg.json` has a second consumer beside the design tools: the preview
server reads it back and paints that system's own pages in its own colours —
see [the catalog palette](../public-preview-server.md#the-page-wears-the-catalogs-own-palette).
Publishing a catalog with a new brand colour therefore re-themes its pages too,
with nothing to change on the server.

### Per-theme token sets

`tokens.dtcg.json` is the **system** token set: the one resolved theme the stickers were rendered
under. A module that declares `@ThemeCatalog` / `@WearThemeCatalog` providers ships more than one,
and each is published beside it as `themes/<provider-fqn>.dtcg.json`, listed in `catalog.json`:

```jsonc
"tokensFile": "tokens.dtcg.json",          // the system's own tokens
"themes": [
  { "id": "com.example.WearCoralThemeCatalog", "name": "Coral", "group": "Wear",
    "tokensFile": "themes/com.example.wearcoralthemecatalog.dtcg.json" }
]
```

Nothing new is rendered for these. Each theme's specimen sheet already resolved its own
`MaterialTheme` — colours **and** typeface, since a theme is free to swap the type scale — and the
renderer writes them into the bundle as a theme-tagged `previews/<id>.catalog.json` sidecar
(issue #2179). The export driver reads them back per theme
([`catalog-themes.mjs`](../../scripts/design-artifacts/catalog-themes.mjs) over design-parity's
`themeTokenSetsFromBundle`) and publishes one file each.

Two details are load-bearing:

- **A theme's id is its provider FQN**, because that is what a preview server addresses it by
  (`?theme=theme:<providerFqn>`) — so a published token set can be joined to the theme a page is
  showing. A theme whose FQN can't be resolved from `previews.json` is **not published**: an entry
  keyed on anything else is data no consumer can attach to a theme, and the driver warns rather than
  shipping it silently.
- **`dark` is left unset.** Nothing in the pipeline declares whether a theme is dark — the
  annotation carries a name and a group — and the field exists as a declaration precisely so it
  doesn't become a luminance guess made at an arbitrary layer. A consumer needing the mode reads the
  theme's own `surface` out of its tokens.

A folded section does **not** bring its themes with it. `themes/` is catalog-level despite being
nested, so [`merge-catalog-section.mjs`](../../scripts/design-artifacts/merge-catalog-section.mjs)
skips it the way it skips the top-level `tokens.dtcg.json`: the host's `catalog.json` describes the
host's themes, and a borrowed system's theme is one the host cannot render.

Until these existed, the theme sheets' tokens were merged into `tokens.dtcg.json` along with the
system's own. Because M3 role labels repeat across themes, that made a system's published `primary`
whichever sheet the bundle happened to yield last; splitting them fixed the system set as well as
adding the themes.

### Delivery-branch history

Each publish **appends a commit on top of the branch tip** rather than
force-pushing a fresh orphan, so `design-artifacts/<system>` carries a
per-regeneration history — the same shape `compose-preview/main` has always had,
and via the same helper
([`push-branch.sh`](../../.github/actions/apply/lib/push-branch.sh)). That makes
the branch answerable to ordinary git questions:

```bash
git fetch origin design-artifacts/compose-m3
git log --oneline origin/design-artifacts/compose-m3
# when did this sticker last change, and against which source commit?
git log --oneline origin/design-artifacts/compose-m3 -- images/button-filled/
```

Each commit subject names the render date **and the short `main` SHA the bundle
was rendered from** (`chore(design-artifacts): regenerate compose-m3 catalog
(2026-08-06, 1a2b3c4d)`), so a sticker that moved can be traced back to the
source change that moved it.

A run whose output tree is byte-identical to the tip pushes **nothing**
(`SKIP_IF_UNCHANGED=1`), so the weekly cron doesn't accumulate empty commits —
a commit on these branches always means the rendered output actually changed.

The storage cost is modest despite each commit being a full ~60 MB snapshot,
because unchanged PNGs are the same blob and git stores them once.
`compose-preview/main` is the worked example: 770 commits of a ~69 MB tree
occupy ~84 MB of packed objects in total. If a branch ever does need resetting,
a manual force-push still works — nothing in the pipeline depends on the history
being contiguous.

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
      # extra-split-mode: full             # give that module its own live lane (see below)
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

#### Giving `extra-module` its own live lane

An `extra-module` render exists to **override** same-named functions in the primary
render — an Android-only render of a control CMP can't draw, folded into an
otherwise-CMP catalog. Its previews are pixels-only, because the daemon serving the
catalog runs the *primary* bundle and would redraw those functions differently.

That is the wrong answer for a supplement that mostly **adds**. A module whose
`@Preview`s the primary module doesn't carry at all overrides nothing, so there is no
disagreement to protect against — yet those stickers still got no `previewId`, hence
no daemon twin, hence a viewer that told visitors to "Enable a local preview server"
for a catalog that already has one. On meshcore-mobile that was 32 of 70 components:
its whole `Screens` section lives in `:meshcore-components`.

`extra-split-mode: full` fixes that half:

- the supplement is split per preview **with** its re-render classpath;
- the driver aliases only its extra-**only** functions. A function present in both
  bundles is a true override and stays baked-only, exactly as before;
- serve reaches those ids through the per-preview pool — its shared monolithic daemon
  answers `NotFound` for an id it never listed, and `renderDaemon` falls through.

The supplement is split from the **raw** render, not from an externalised copy, and
that is load-bearing: `ServeCatalogStore` rehydrates exactly one `externalResources`
manifest — the primary `liveBundle`'s — and hands that single materialized directory
to every pooled per-preview daemon. Externalising the supplement into the same
content-addressed `bundle/res/` pool would publish blobs nobody materializes for any
resource the primary doesn't also declare, so a supplement carrying its own faces
would yield daemons that start with a missing classpath entry. Self-contained
per-preview bundles cost duplication and owe nothing to the pool.

Requires `publish-live-bundle: true` and `split-per-preview: true`. It costs one
pooled daemon per supplement preview actually opened, plus the per-preview bundle
weight, so leave it off for a supplement that only overrides — it would add weight and
change nothing.

Known gap: `ServeCatalogLiveHost.mergeDeclaredKnobs` grafts knob / focus / gesture
metadata from the **monolithic** daemon's preview list, which by definition can't
contain a supplement-only id. Those previews get their display-axis overrides (size,
device, locale, font scale, orientation) but not author-declared knobs beyond whatever
the catalog's own `previews/<id>.overrides.json` sidecar carries.

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

## Two lanes: what a click does

Every catalog sticker is rendered on **two** surfaces, and they want opposite
things from a pointer:

| Lane | Signal | A click must |
|---|---|---|
| Baked snapshot / one-shot `/render` / the published sticker sheet | `LocalInspectionMode = true` | do **nothing** — a published PNG can't depend on whether something tapped it |
| Held Live Compose session, and the in-browser wasm tier for `compose-m3` | `LocalInspectionMode = false` | visibly change the component |

The split is a single `interactive` flag derived from that signal
(`interactive = !LocalInspectionMode.current`), never a hard-coded constant —
one sticker body serves both lanes. `:samples:design-catalog-m3-shared`'s
`CatalogComponent(id, interactive)` takes it as a parameter (the wasm app passes
`true` directly); the Wear sheet reads it through `catalogInteractive()` in
`CatalogInteractive.kt`.

**No sticker may ship a dead handler.** Components that carry state — switch,
checkbox, radio, filter chip, slider, segmented button, text fields, Wear's
`SwitchButton` / `CheckboxButton` — own it and mutate it on the interactive
lane.

Everything else takes the **click tally** as its default: `counted` /
`wearCounted` / `countedRemote` append `(n)` to the label, `Filled` →
`Filled (1)`. At `n == 0` the tally returns the bare label and a no-op handler,
so the baked capture is byte-identical either way. Prefer it over a bespoke
affordance — it reads the same across all three sheets, and it composes over a
label that is itself bound (remote-m3's named-value button counts on top of its
override rather than replacing it).

M3's cards are the one family that ships *both* a plain and a clickable
overload (Wear's and Remote's take a required `onClick`). The interactive lane
picks the clickable one; the baked lane composes the same plain overload it
always did, so the published capture keeps its exact node tree rather than just
its pixels — otherwise the `a11y/touchTargets` greenlines and the layout
wireframe would gain a clickable node that no longer describes the sticker.

Four kinds of exception, all deliberate:

- **Disabled** stickers stay inert — unresponsiveness is the state they document.
- Wear's `Layout/List` skeleton has empty slots by design, so there is nowhere
  to put a count.
- The **icon buttons** carry no label at all. They read as a favourite toggle
  instead: Wear tints the glyph, remote-m3 tweens the container colour.
- `Card/Slots` stays inert on both lanes because it is a slot **host**: the
  structured-screen builder drops real components into its regions, and a
  card-wide click target over them would swallow the taps meant for those
  children — making the filled card less interactive, not more.

`remote-m3` gets there differently, because a `RemoteDocument` is replayed by a
player rather than recomposed. `hostAction(...)` — what every button on that
sheet used to carry — posts a payload *out* of the document and changes nothing
inside it, so the player had nothing to repaint. `countedRemote` instead pairs a
`rememberMutableRemoteInt` with a `valueChange` action and a label expression
conditional on that counter, so the click is resolved **inside** the document,
with no host round-trip. `hostAction` remains the right tool when a component
genuinely means "tell the host" rather than "change me", and stays in the file
documented as such — but nothing in the catalog uses it any more.

Guarded by `CatalogInteractivityTest` in the shared M3 module (desktop
`runComposeUiTest`) and the Wear module (Robolectric + `createComposeRule`),
each asserting **both** lanes, plus `InteractiveActionCaptureTest` on the remote
sheet, which reads the encoded `.rc` sidecar because the counter branch is
invisible in a static raster by construction.

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

### Seed-kit handles: `reference` (one variant) and `referenceSet` (the family)

`reference` names the **single** kit node this sticker corresponds to — the frame a
[design-parity](https://github.com/yschimke/design-parity) run diffs the render
against. It has to be one variant: point it at a Figma component *set* and the
comparison is against a grid of every variant at once, which says nothing.

`referenceSet` names the **family** that variant belongs to (the component set), and
exists for the opposite direction — matching a whole *screen* back to code. An
instance placed on a screen reports its own variant and its set, and a screen almost
never uses the exact variant a catalog chose to picture, so matching on `reference`
alone misses. Measured on the Material 3 kit: per-variant handles alone linked 3 of 11
instances on a real screen; the misses were a list item and a carousel whose screens
used *sibling* variants of components the catalog already maps.

```kotlin
@CatalogComponent(
  id = "Lists/ListItem",
  reference = "figma:AbCdEf/51964:64241",     // the one variant this sticker renders
  referenceSet = "figma:AbCdEf/51964:63037",  // the family every sibling variant shares
)
```

Two fields rather than one because the two readers want incompatible things: a parity
diff needs the narrowest renderable node, screen matching needs the widest. Both travel
onto `previews.json` as `catalog.reference` / `catalog.referenceSet` and out to the
exported inventory, and both have a same-named spec field that overrides the annotation.
`referenceSet` is optional — omit it and everything behaves exactly as before.

### Breakpoints and multipreviews: `select`, not a split `@Preview`

A multipreview annotation (`@WearPreviewDevices`, a local `@CatalogWearBreakpoints`)
renders one function at several device sizes. The candidate join keys on **function
name**, so all of those renders fold into one spec entry carrying one image per size,
tagged from the spec's `breakpoints`:

```jsonc
"breakpoints": [
  { "size": "smallRound", "device": "id:wearos_small_round", "widthDp": 192 },
  { "size": "largeRound", "device": "id:wearos_large_round", "widthDp": 227 }
]
```

Declare each breakpoint by `device` (the `@Preview(device = …)` id, matched first) as
well as by `widthDp` (the fallback, and what the live-preview bridge scores a
candidate annotation against). The device is the expansion's own identity; a width is
a fingerprint two devices can share, and a device no breakpoint names keeps the
generic Material width class — which makes two distinct renders indistinguishable on
one axis. The export warns when it sees one. A Wear catalog that declares no
`breakpoints` inherits the standard round table (`smallRound` / `largeRound` /
`xlRound` / `smallSquare`, by device id and width).

Folding every size onto one entry is right when the entry means "this component, at
every breakpoint we document". When a catalog wants a **card per breakpoint** — its
own id and caption — use `select` rather than splitting the `@Preview` function in
the module:

```jsonc
{ "componentId": "Home/SmallRound", "preview": "HomeListViewPreview",
  "select": { "size": "smallRound" }, "caption": "Home — small round." },
{ "componentId": "Home/LargeRound", "preview": "HomeListViewPreview",
  "select": { "size": "largeRound" }, "caption": "Home — large round." }
```

Two entries may share one `preview` as long as each selects a different value; the
validator's "these fold into one sticker" warning stands down for that case, and
fires as before when one of the references selects nothing (it would fold in the
renders its sibling selected). A variant may `select` too, and a selection counts as
its distinguishing axis. A selection that matches no render is reported as a missing
render naming what the function *did* produce, so a typo — or an undeclared
breakpoint — reads as itself rather than as a broken `preview`.

Splitting the Kotlin instead costs more than the boilerplate: two `@Preview`
functions delegating to a shared private composable replace *one* multipreview, so
the annotation's other axes go with it (`@WearPreviewFontScales`, whose non-default
scales the export promotes to a `props.fontScale` switcher, is the usual casualty).

#### The annotation form: `@CatalogComponent(perBreakpoint = true)`

An annotation-led inventory says the same thing next to the `@Preview`, so a catalog
that keeps its inventory in code doesn't have to reintroduce `groups` to get a card
per breakpoint:

```kotlin
@CatalogComponent(id = "Layout/List", perBreakpoint = true)
@CatalogWearBreakpoints @Composable fun ListLayout() = FullScreenWear { … }
```

That yields `Layout/List/smallRound`, `Layout/List/largeRound`, … — one per breakpoint
**the function actually rendered at**, each carrying the `select` the export already
understands, so one annotation produces exactly what the hand-written spec entries
would.

It's a flag, not a list of breakpoint names, because the multipreview annotation on the
line below already decides which devices this function renders at. Naming them again in
`@CatalogComponent` would be a second source of truth that can drift from the first —
`sizes = ["smallRound", "largeRound"]` above a `@CatalogWearBreakpoints` that renders
three. So the names come from the renders instead: each `@Preview`'s `device` / width,
resolved through the same matcher that tags the baked stickers. Nothing to keep in sync,
nothing to misspell, and adding a device to the multipreview adds its card for free.

The rules:

- **Flag unset** (the default) is the pre-existing behaviour: every render folds onto
  one component. Every existing annotation catalog stays on this path.
- **One breakpoint rendered** → one card keeping the plain `id`; suffixing there would
  move a published sticker's URL to say what the id already says.
- **Several** → `<id>/<size>` each, in the order `breakpoints` declares (not bundle
  order), so the cards read small→large.
- **None resolved** — an undeclared device, or a catalog with no `breakpoints` table —
  keeps the component whole and the export warns, naming it. The undeclared-device
  warning above usually names the culprit on the same run.
- `@CatalogVariant(of = …)` still names the parent as the annotation spells it, so a
  variant on a fanned-out component attaches to its first breakpoint; name a suffixed id
  explicitly to target one.
- A spec entry still overrides the annotation, `select` included — which is also how you
  document a *subset* of the breakpoints a function renders.

Because the ids depend on what rendered, the build-free pre-flight can't predict them:
it resolves a `display.hero` of `<id>/<breakpoint>` on its parent id, and the exact
check runs at export time against the merged inventory, where every id is known.

Adopting `perBreakpoint` on an already-published catalog **moves those components'
sticker URLs**, since the id is part of the path. It's opt-in for that reason: the
preview server already disambiguates a *colliding* card label on its own
(`Edgebutton · Small Round`), so the fan-out is for when you want authored ids and
captions per breakpoint, not merely readable labels.

### The render timeout scales with the sheet, not the job

`bundle pack`'s own `--timeout` is separate from the job's `timeout-minutes`, and it is the one a
growing catalog hits first: a sheet can sit comfortably inside a 90-minute job and still be killed
by the inner render timeout, which surfaces as a bare `Build timed out after 600s` long before the
publish step it never reached. The reusable workflow exposes it as `render-timeout` (seconds,
default 600) for exactly that reason.

It scales with the number of previews, so a catalog that fans one component out over a variant
matrix outgrows the default well before it outgrows the job — `m3-catalog` crossed it going from 193
previews to 287, which is one component family gaining its size and shape axes. Raise the input
rather than thinning coverage; the other levers are the render priority below (which trades baked
pixels for a live path) and `render-shards` (which divides the work across jobs).

### Sharding the render across parallel jobs (`render-shards`)

Raising `render-timeout` buys one more step and then stops. The render cost is close to linear —
measured over four `m3-catalog` runs on `main`:

| Commit | Previews | Render step | Job total |
| --- | --- | --- | --- |
| `d354560` | 287 | 13.6 min | 17.2 min |
| `323e4a2` | 519 | 22.5 min | 24.0 min |
| `5edc70a` | 607 | 26.7 min | 29.6 min |
| `f35c2e5` | 689 | 27.2 min | 28.9 min |

which fits `render_minutes ≈ 3.7 + 2.15s × previews`. At ~1500 previews that is ~58 min, past a
40-minute `render-timeout`; at ~2400 it passes the 90-minute job timeout, and no number you can put
in `render-timeout` helps.

`render-shards: N` splits the primary render across N jobs:

```
        ┌── shard 1: bundle pack, partition 1 ──┐
 matrix ┼── shard 2: bundle pack, partition 2 ──┼── merge ── generate ── publish
        └── shard N: …                          ┘
```

**Only the marginal 2.15 s divides.** The ~3.7 min of Gradle configure + compile is paid by every
shard in full, which is what caps the useful count:

| Previews | N=1 | N=4 | N=6 | N=8 |
| --- | --- | --- | --- | --- |
| 689 | 29.4 | 15.8 | 13.7 | 12.7 |
| 1000 | 40.5 | 18.6 | 15.6 | 14.1 |
| 1500 | 58.4 | 23.0 | 18.6 | 16.3 |
| 2500 | 94.3 | 32.0 | 24.5 | 20.8 |

(per-shard `1 min setup + 3.7 min compile + ~0.9 min discovery + 2.15s × previews/N`, plus a ~4 min
merge/generate job). **4–6 is the range.** 6→8 at 1500 previews saves 2.3 min for two more runners;
anyone reaching for 16 is buying compile time, not throughput. Sharding does not need to be perfect
— it turns the worst case from impossible into ~25 min.

**How a shard renders only its share.** `bundle pack --exclude-preview-id` leaves the excluded
previews *listed in the bundle*, just without a baked PNG — the same mechanism render-priority
deferral relies on. So each shard emits a structurally identical bundle (same `previews.json`, same
manifest, same re-render classpath) differing only in which `previews/<id>.*` slots are filled.

**How they come back together.** `compose-preview bundle merge <base> <shard>… -o <out>` unions the
per-preview artifacts: the raster, its `.semantics.json` / `.layout.json` / `.fonts.json` /
`.figma.svg` / `.catalog.json` sidecars, the nested `figma-raster/` crops, `ir/<id>.rc` and
`extensions/<id>.json`. Everything else — manifests, cover, `classes/app.jar`, `libs/`, `android/` —
is inherited from the base shard, which is why **`publish-live-bundle` needs no designated shard**:
every shard packs the same module at the same commit, so the classpath is identical in all of them.

`bundle repack` is the wrong tool here and it is worth saying why, because it looks right: repack
swaps re-renders into slots the target *already has*, and only handles `previews/<id>.png` +
`previews/<id>.figma.svg`. Against a shard base every other shard's previews would report "no
matching baked slot" and be skipped, and any that did land would arrive without their semantics
sidecar — which the completeness gate fails.

**Partitioning.** Each shard derives its own partition from its own `compose-preview list --json`,
so there is no serial discover-then-fan-out prefix (that prefix would cost the same full compile
each shard pays anyway). It is deterministic — sort the discovered ids, then round-robin — and
[`shard-preview-ids.mjs`](../../scripts/design-artifacts/shard-preview-ids.mjs) owns the three
decisions:

- **by preview id, never by function name** — one function expands to a 30-cell matrix while its
  neighbour expands to two, and the slowest shard sets the wall clock;
- **round-robin over the sorted list, not contiguous blocks** — ids sort by group, so blocks would
  cluster the template-heavy groups into one shard;
- **whatever the render will not produce is removed before partitioning, then re-applied in every
  shard** — the ids `modePriority` defers, and the ids the pre-flight's positive function-name filter
  drops (an entry-level `priority: "deferred"`). Both are exclusions, so the naive union would let
  them compete with the partition for slots; worse, a shard whose whole share happened to be
  filtered-out ids would report work to do and then exclude every id the name filter kept, and
  `composePreviewRender` refuses a selection that renders nothing. The levers compose — reach for
  deferral first, since it makes the work smaller, and let sharding divide what remains.

Because the shards each plan independently, the merge step cross-checks their uploaded plans
(`shard-preview-ids.mjs --verify`) before unioning: same discovered **set** (compared by digest, not
by count — two runners that saw `["a"]` and `["b"]` are disjoint with a union of the right size),
pairwise disjoint, and a complete cover. A disagreement would otherwise surface as a
completeness-gate failure naming a component, with nothing pointing at the shards.

**The CLI has to be new enough.** The merge runs last, after every shard has spent its twenty
minutes, so a CLI predating `bundle merge` would fail the run having burned the whole fan-out — and
that is the *default* configuration's failure mode, since `cli-source: released` +
`cli-version: catalog` pins the CLI to the applied plugin version. So the matrix job probes
`compose-preview bundle help` for the subcommand before anything expensive starts, and fails with
the pin to raise. (The probe is skipped for `cli-source: build`, where the CLI is built from the same
`driver-ref` checkout the merge step builds from.)

Two things it does not shard: the **extra module** (`extra-module`) renders in the generate job as
before — it is a supplement, small by construction — and a `@PreviewParameter` provider's **rows**,
which travel whole with their parent id. The latter is the most likely source of a straggler shard;
bin-packing from recorded per-preview times is worth building only once one shows up.

The cost is the shard bundles moving through the artifact store — a bundle carrying `classes/` +
`libs/` is hundreds of MB, uploaded once and downloaded once per shard. They upload with
`compression-level: 0` (a bundle is already a zip) and `retention-days: 1`.

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
- **Both backends now honour every axis.** An Android/Robolectric catalog
  (`design-catalog-wear-m3`, `design-catalog-remote-m3`, and the `pocket-casts` catalogs whose
  nine palettes come from a provider) renders through a `RobolectricRenderTask` that reads the
  same `composePreview.*` properties and forwards them into the render JVM as
  `composeai.preview.*`, where the shared `PreviewFilter` applies them — the entry filter and
  the id filters per #2977, and the row labels alongside them, since that renderer expands its
  own `@PreviewParameter` rows. So the measured axis saving lands on the backend it was measured
  on.
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

## Theme specimens (`section: "Themes"` / `@FixedTheme`)

A card whose **subject is a theme** — a colour-role sheet, a typography scale, a
`MeshCore · Light · Orbitron / Space Grotesk / JetBrains Mono` swatch — must not
be re-rendered when a visitor picks a different theme on the browse surface.
Doing so destroys the very thing it documents: the card still says "Light" and
still names Orbitron while drawing dark in the default sans, pixels contradicting
their own label.

`serve` recognises two signals, either of which is enough:

- **`section: "Themes"`** in `catalog.spec.json` — the author's statement of what
  the tab *is*. It exempts every card in that tab at once, which is the right
  granularity for a design system whose Themes tab is nothing but specimens.
  Deliberately keyed on the section rather than a `theme-…` id prefix: the prefix
  is an authoring convention, the section is a declaration.
- **`@FixedTheme`** on the `@Preview` function
  (`ee.schimke.composeai:preview-annotations`) — the per-preview override for a
  specimen with no such tab to speak for it: an ungrouped bundle, a `Foundation`
  section that mixes swatches with components, a plain `compose-preview serve` of
  one module. Discovery matches it by FQN and writes `"fixedTheme": true` into
  `previews.json`; the design-artifacts export lifts it onto the catalog image, so
  the browse surface honours it before any daemon is opened.

Sheets that discovery *synthesises* from `@ThemeCatalog` / `@WearThemeCatalog`
(`themecatalog__<name>`) are theme-fixed by construction — the annotation already
says the sheet's subject is one named theme, so no consumer-side `@FixedTheme` is
needed.

Neither signal disables the theme control: the rest of the catalog still
re-renders, and a specimen simply keeps its baked pixels — the same treatment a
card with no live daemon twin already gets. A catalog made up *only* of specimens
offers no theme chips at all, since there would be nothing for them to redraw.


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
