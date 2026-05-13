# Exposing data-extension output in the VS Code preview panel

Working design for surfacing every data extension's output in the
"Compose Preview" webview through one consistent shell, plus a per-kind
catalogue of how each extension should present its data.

## Goals

- One presentation shell for every kind: **overlay + legend** on the
  preview image, plus **a table** in a dedicated tab below the
  toolbar. No bespoke UI per extension when the standard shell fits.
- When the user toggles a kind on, the table appears immediately. No
  expand / scroll. The table is **above** any not-yet-enabled filter
  affordances.
- Existing CLI / MCP-side rendered PNGs (e.g. `a11y/overlay`) stay
  available, but as a secondary artefact — the locally-painted overlay
  is the primary surface.
- JSON is reachable per-tab via **Copy JSON** but never the default view.

## Bundles (the default toggle is a cluster, not a kind)

The user wants to see "a11y" or "theming", not a checklist of
`a11y/atf` + `a11y/hierarchy` + `a11y/touchTargets`. Each cluster
exposes one **bundle toggle** that turns on a sensible default
combination, and an expander that reveals the individual kinds for
power users.

| Bundle | Default ON | Default OFF (in expander) |
|---|---|---|
| **A11y** | `a11y/atf` (findings), `a11y/hierarchy` (locally-painted overlay + legend) | `a11y/touchTargets`, `a11y/overlay` (daemon-rendered PNG — only useful when reproducing CI output) |
| **Theming** | `compose/theme` | `compose/wallpaper` (it's an *input* override; only useful when actively tuning dynamic color) |
| **Text / i18n** | `text/strings`, `fonts/used` | `i18n/translations` (heavier — Android-only, requires `res` scan), pseudolocale re-render toggle |
| **Resources** | `resources/used` | — |
| **Inspection** | `compose/semantics` (cheap projection) | `layout/inspector` (heavier — full hierarchy), `uia/hierarchy` (Android-only) |
| **Performance** | — (all kinds are at least medium cost) | `compose/recomposition`, `render/trace`, `render/composeAiTrace` |
| **Display** | — | `displayfilter/<id>` per filter |
| **History** | `history/diff/regions` | — |
| **Watch / Wear** | — | `compose/ambient` |
| **Errors** | always implicit | `test/failure` postmortem fetch |

Bundle UX rules:

1. **Chip bar in the panel toolbar lists bundles, not kinds.** Toggling
   the A11y chip turns on the default-ON kinds within the bundle.
2. **One tab per *active* bundle**, not per-kind. The tab body
   chooses how to lay out the union of enabled kinds (e.g. the A11y
   tab shows hierarchy table + findings inline rather than two
   separate tabs).
3. **Expander inside the tab** ("Configure…") reveals checkboxes for
   each kind in the bundle. Toggling a checkbox subscribes /
   unsubscribes immediately. The default-ON kinds remain ON until
   the user unchecks them.
4. **Disabled kind from another bundle stays in the "…More" tab,** so
   the chip bar doesn't grow with seldom-used toggles like
   `a11y/overlay` PNG.
5. **MRU follows the bundle.** Bundle ON ordering is what drives tab
   placement; per-kind toggling inside an already-open bundle doesn't
   reorder tabs.

This keeps the common case ("show me a11y") one click and one tab,
while the rare case ("I only want the daemon overlay PNG to compare
to CI") is still reachable via the expander or the More tab.

## UX shell

```
┌──────────────────────────────────────────────────────────────────┐
│ [filter chips: function ▾  group ▾]  [layout ▾]  […more filters]│  ← row 1
├──────────────────────────────────────────────────────────────────┤
│  ┌ Data tabs ───────────────────────────────────────────────┐    │
│  │  Fonts │ Strings │ Theme │ Recomposition │ ...           │    │  ← row 2
│  └────────────────────────────────────────────────────────── ┘    │
│  ┌──── table for the active data tab ──────────── [Copy JSON]┐    │  ← row 3
│  │ ...                                                       │    │
│  └───────────────────────────────────────────────────────────┘    │
├──────────────────────────────────────────────────────────────────┤
│  [previews grid, each card paints overlay + legend for active tab]│
└──────────────────────────────────────────────────────────────────┘
```

Decisions:

1. **Filter chip bar stays as row 1.** Function / group / layout
   selectors remain top-of-panel.
2. **Bundle chips are the shortcuts.** Each bundle chip is a toggle —
   pressing it on opens its tab; pressing it again (or closing the tab)
   turns the bundle off and returns the panel to the plain-preview
   default state. The chip + tab are two ends of the same toggle, not
   two independent controls.
3. **Tabs are dismissible.** Every data tab has a close (`×`)
   affordance in the tab header. Closing the last data tab returns the
   panel to "no inspector" — the preview grid is visible
   unobstructed, no surface left dangling like today's hierarchy
   legend with no dismiss path. The `…More` tab is the only
   non-closable tab.
4. **"No inspector" is the resting state.** With no bundle chips
   pressed, no tab row is shown. The card overlays (`BoxOverlay`,
   legends) are also cleared. This is the single source of truth for
   "I want to look at the preview without any data surface on top of
   it."
5. **Secondary filters collapse into a "More" tab.** Anything that's
   not in the chip bar today and isn't yet enabled is hidden behind a
   single `…` tab so disabled rows don't push enabled data below the
   fold. Once enabled, a kind earns a tab and moves to the front.
6. **Each enabled data extension owns one tab.** Two enabled = two
   tabs, in the order they were enabled. Tab order is sticky per
   scope (re-uses the MRU machinery in `focusInspector.ts`).
7. **Active tab body is a table, not a `<details>`.** No
   click-to-expand for the default view — the data the user just
   asked for must be on screen.
8. **Last tab is always `…More` / settings.** Stable position so the
   user always knows where to find the extra filters and disabled
   kinds.
9. **Overlay + legend live on the card,** unchanged from the current
   focus-mode inspector — but available outside focus mode too, on
   whichever previews are currently selected.
10. **JSON is a secondary action.** Each tab has a `Copy JSON` button
    in the top-right of the table; that's the only path. No raw-JSON
    default rendering anywhere.

### Chip ↔ tab ↔ overlay state machine

For each bundle:

| Chip | Tab | Card overlay/legend |
|---|---|---|
| OFF (default) | not shown | not painted |
| ON | shown; user can switch between active tabs | painted on focused/selected cards |
| OFF via chip re-press | tab removed; if it was active, the next-most-recent active tab takes over (or no tab row if it was the last) | cleared |
| OFF via tab `×` | identical to chip re-press | cleared |

This is the **missing affordance** in today's panel: once a kind's
overlay paints, the only way to dismiss it is to scroll back to the
focus-inspector chip row and toggle it off — easy to miss. The tab's
own `×` + the chip's toggle-off behaviour are redundant on purpose so
the dismissal path is always visible from wherever the user's eye lands.

### Standardising overlay + legend

Today `focusPresentation.ts` already defines the four surfaces a
presenter can contribute to (overlay / legend / report / error). The
proposal is:

- Drop "report" as a primary surface; promote it into the **table tab**
  surface managed by the panel shell.
- Keep **overlay** (tinted box on the image) and **legend** (sidecar
  list correlated by `data-overlay-id` / `data-legend-id`) as the
  in-card surfaces. These are what `legendArrow.ts` already wires up.
- Provide one shared `BoxOverlay` primitive that takes
  `{ id, bounds, level?, color? }[]` and a matching `Legend` primitive
  that takes `{ id, label, detail?, level? }[]`. Presenters that fit
  the bounds-on-image model use these instead of hand-rolling DOM.
  Presenters that don't (e.g. fonts, theme tokens, ambient state) skip
  the overlay surface and only contribute a table.

This is what most of the new per-kind issues need: their data already
has bounds (`text/strings`, `resources/used`, `layout/inspector`,
`compose/semantics`, `a11y/touchTargets`, `uia/hierarchy`,
`history/diff/regions`) so they can ride the shared `BoxOverlay`
without each writing their own DOM.

## Per-extension presentation notes

Each entry: **wire kind** · primary table columns · overlay support ·
external links · cluster.

### `a11y/atf` and `a11y/hierarchy` and `a11y/touchTargets`
Cluster: **A11y** (#1010 covers hierarchy legend).

- Table: `node`, `role`, `states`, `findings level`, `message`.
- Overlay: one tinted box per node, level-coloured (error / warning /
  info). Hover correlates with legend row.
- Secondary: `a11y/overlay` PNG, shown as a *separate row* on the
  card's options panel rather than mixed into the table. Only shown
  when the daemon has it.

### `compose/theme`
Cluster: **Theming**.

- Table tabs: `Colors` / `Typography` / `Shapes`.
  - Colors: swatch · token name · hex · consumers (nodeIds).
  - Typography: token name · family · size · weight · style ·
    line-height · letter-spacing.
  - Shapes: token name · resolved value.
- Overlay: tint the boxes of nodes in `consumers[].nodeId` when a
  token row is hovered (uses bounds from `compose/semantics` or
  `layout/inspector` if available; otherwise legend-only).
- Copy JSON.

### `compose/wallpaper`
Cluster: **Theming**.

- Table: `seedColor` swatch · `isDark` · `paletteStyle` ·
  `contrastLevel` · then the derived colour scheme as a swatch grid
  identical to `compose/theme`'s Colors tab.
- No overlay (it's an input override, not a per-node thing).

### `fonts/used`
Cluster: **Text / i18n**.

- Table columns: `requestedFamily`, `resolvedFamily`, `weight`,
  `style`, `sourceFile`, `fallback chain`, `consumers`.
- **Reconstruct the request.** Show enough so a reader can recreate
  the `Font(...)` call: family + weight + style + source file +
  fallback chain are mandatory.
- **Google Fonts preview.** When `requestedFamily` resolves on
  `fonts.google.com`, render a tiny preview rendered with the
  resolved family + weight in the table cell, and link the family
  name to `https://fonts.google.com/specimen/<family>`. URL-encode the
  family. No network call from the webview — preview uses the local
  resolved font; the link is `target=_blank`-style (VS Code
  `openExternal`).
- Overlay: tint consumer nodes; legend rows match.

### `text/strings` + `i18n/translations`
Cluster: **Text / i18n**.

- One tab for "Drawn text" (`text/strings` payload):
  table columns `text`, `localeTag`, `fontScale`, `fontSize`,
  `foreground`, `background`, `overflow`, `nodeId`. Overlay each row
  by `boundsInScreen`. Sortable by `truncated` / `didOverflowWidth` /
  `didOverflowHeight` so the user lands on truncation bugs.
- One tab for "Translations" (`i18n/translations` payload):
  table columns `rendered`, `resourceName`, `sourceFile`,
  `supported locales` (count), `untranslated locales` (count + chip).
- **Link to resource file.** `sourceFile` and `resourceName` open the
  underlying `values*/strings.xml` via `vscode.openTextDocument` + a
  search jump to the `<string name="...">` tag.
- Locale picker in the header lets the user preview translations
  inline without re-rendering.

### `resources/used`
Cluster: **Resources**.

- Table: `resourceType`, `resourceName`, `packageName`, `resolvedValue`,
  `resolvedFile`, `consumers`.
- **Link to file.** `resolvedFile` opens the resource file directly;
  for `string`/`color`/`dimen`/`drawable` types we resolve to the
  best-match `values*/<file>.xml` and jump to the resource by name.
- Overlay: tint consumer nodes when bounds are available (joined via
  `compose/semantics` or `layout/inspector`).

### `layout/inspector` + `compose/semantics`
Cluster: **Inspection**.

- Tree (collapsible) instead of a flat table — bounds, modifiers,
  source refs. Each node row has an overlay handle.
- Click a node row → tint its box on the preview + highlight ancestors
  faintly (matches Android Studio's Layout Inspector flow).
- Source-link column opens the file:line of the composable when the
  daemon provides it.

### `compose/recomposition`
Cluster: **Performance** (already tracked in #1046).

- Table: top-N nodes sorted by `count`, with `mode` (delta vs snapshot)
  + `inputSeq` chip. Heat-map overlay deferred until the daemon ships
  source coords (v2).

### `render/trace` and `render/composeAiTrace`
Cluster: **Performance** (`render/trace` tracked in #1047).

- `render/trace`: horizontal bar chart of phases in the table tab,
  plus a metrics key/value sub-table.
- `render/composeAiTrace`: link to download/open in
  [`ui.perfetto.dev`](https://ui.perfetto.dev) (Perfetto-importable
  JSON). The table itself is a phase summary.

### `compose/ambient`
Cluster: **Watch / Wear**.

- Single-row table (one preview = one ambient state). Columns:
  `state`, `burnInProtectionRequired`, `deviceHasLowBitAmbient`,
  `updateTimeMillis`. Badge in the legend showing the current state.

### `displayfilter/<id>`
Cluster: **Display**.

- This isn't a typical extension: it produces post-process PNGs
  rather than structured data. Table lists the active filters with a
  thumbnail per filter. Clicking a row swaps the card preview to that
  filtered render. No overlay.

### `history/diff/regions`
Cluster: **History / Diffs**.

- Table: `baselineHistoryId`, then one row per region with
  `bounds`, `pixelCount`, `avgDelta`. Overlay paints each region as a
  tinted box — colour ramped by `avgDelta` so small drift fades.

### `uia/hierarchy`
Cluster: **Inspection / Automation**.

- Table: `text`, `contentDescription`, `testTag`, `role`, supported
  actions chips, `boundsInScreen`. Each row → tinted box overlay.
- Copy-as-selector action on each row (builds the `By.testTag(...)` /
  `By.text(...)` snippet inline).

### `test/failure`
Cluster: **Errors**.

- Special-case: the panel banner already surfaces render errors. The
  data table is the postmortem bundle — phase, error type/message,
  stack frames. No overlay.

### Pseudolocale (`en-XA`, `ar-XB`)
Cluster: **Text / i18n**.

- Not a data product per the catalogue (no kind), but it's a toggle
  the panel should expose alongside the strings/i18n tab — a chip
  that re-renders the preview with the pseudolocale applied. Table
  view reuses `text/strings`.

## Cluster summary

Each cluster matches a **bundle** above, and gets its own tracking
issue. Cluster A is the framework prerequisite for everything but the
simplest swatch-only presenters.

| Cluster | Bundle | Kinds | Existing issues |
|---|---|---|---|
| A | Panel shell — bundle chips, tabs, table, Copy JSON, shared overlay/legend primitive, "More" tab | (framework) | — |
| B | A11y | `a11y/atf`, `a11y/hierarchy`, `a11y/touchTargets`, `a11y/overlay` | #1010 |
| C | Theming | `compose/theme`, `compose/wallpaper` | — |
| D | Text / i18n | `fonts/used`, `text/strings`, `i18n/translations`, pseudolocale | — |
| E | Resources | `resources/used` | — |
| F | Inspection | `layout/inspector`, `compose/semantics`, `uia/hierarchy` | — |
| G | Performance | `compose/recomposition`, `render/trace`, `render/composeAiTrace` | #1046, #1047 |
| H | Misc — Display / Ambient / History-diff / Errors | `displayfilter/*`, `compose/ambient`, `history/diff/regions`, `test/failure` | — |

## Open questions

1. **Multi-preview selection.** The current panel renders many cards
   simultaneously. Does a kind's tab show the union across all
   visible previews, or scope to the focused preview only? Default:
   focused preview; fall back to "all visible" when no card is
   focused.
2. **Persistence.** Per-scope MRU of which kinds had tabs open
   (re-use the focus-inspector MRU keying), so re-opening a panel
   re-restores the layout.
3. **Tab overflow.** If the user enables 6+ kinds, do tabs scroll
   horizontally or collapse to a `…` overflow menu? Lean toward
   horizontal scroll + a "Pin" affordance.
4. **Copy JSON shape.** Whole-payload (scoped to the preview the user
   is viewing) vs the table-as-rendered (post-sort, post-filter).
   Default: whole-payload, since agents are the dominant Copy JSON
   audience and they want the wire shape.
