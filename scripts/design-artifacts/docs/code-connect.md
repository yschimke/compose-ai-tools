# Figma Code Connect from the design-catalog

The design-catalog export already pushes editable, layered Figma vectors (`figma/<slug>.svg`) onto
each `design-artifacts/<system>` branch. **Code Connect** is the complementary binding: it makes
Figma's Dev Mode (and the Figma MCP that grounds design-to-code agents) show *your* Compose component
for a selected design node, instead of an auto-generated snippet.

This repo emits the mapping; a designer imports the catalog; you publish. Publishing the records
requires a Figma **Org/Enterprise** plan with a **Dev/Full** seat — but *emitting* and *resolving* the
mapping are plan-agnostic, so the manifest ships on every branch and you can dry-run resolution on any
plan.

## What gets emitted

`generate-design-catalog.mjs` writes `code-connect.json` at the bundle root, next to `catalog.json`
and the `figma/` vectors. One entry per catalog component:

```jsonc
{
  "system": "meshcore-mobile",
  "title": "MeshCore Mobile",
  "label": "Compose",
  "source": { "repo": "yschimke/meshcore-mobile", "ref": "main", "module": ":app" },
  "generatedAt": "…",
  "mappings": [
    {
      "componentId": "DeviceSummaryCard/Populated",     // catalog id
      "figmaLayerName": "DeviceSummaryCard/Populated",   // the Figma frame name = the join key
      "componentName": "DeviceSummaryCard",              // the real component (not the @Preview)
      "source": "https://github.com/…/blob/main/…/DeviceSummaryCard.kt",
      "label": "Compose",
      "confidence": "HIGH",                              // how componentName/source were resolved
      "previewName": "DeviceSummaryCardPopulatedPreview", // the @Preview that rendered the sticker
      "figmaSvg": "figma/devicesummarycard-populated.svg"
    }
  ]
}
```

The one thing it can't carry is the Figma **node id** — that only exists once the catalog is imported
into a file. So the manifest keys on `figmaLayerName` (= `componentId`, the name the design-parity
importer gives each frame) and the node id is resolved at publish time.

### Which composable does it point at?

Code Connect's value is showing how to *use the real component* (`DeviceSummaryCard(state = …)`), not
the zero-arg `@Preview` wrapper that nobody calls. `componentName`/`source` resolve to the real
component by priority, and each mapping's `confidence` records which source won:

1. **`explicit`** — a `component` (plus optional `import` / `source`) authored on the catalog-spec
   entry. Deterministic; use it wherever inference is uncertain:
   ```jsonc
   { "componentId": "Btn/Primary", "preview": "BtnPrimaryPreview",
     "component": "PrimaryButton", "import": "import com.x.ui.PrimaryButton",
     "source": "src/commonMain/kotlin/com/x/ui/Button.kt" }
   ```
2. **`HIGH` / `MEDIUM` / `LOW`** — discovery's inferred `PreviewTarget` (`PreviewInfo.targets`, carried
   in the bundle's `previews.json`), i.e. the composable the preview's bytecode actually calls, with
   its inference confidence.
3. **`preview-fallback`** — the `@Preview` function itself, when neither above is available. Still a
   valid mapping; review it before publishing.

`previewName` always records the `@Preview` that rendered the sticker, so the trace back to the render
survives even when the mapping points at the underlying component.

### Real call site (a template, not a bare mapping)

When the emitted component IS the inferred target, the mapping also carries a real call site built
from the component's **actual Kotlin parameters** — recovered from its `@kotlin.Metadata` (see
`ComposableSignature`), which yields the source signature with names/types/defaults and none of the
synthetic `Composer`/`changed` params the bytecode carries:

```jsonc
{
  "componentName": "DeviceSummaryCard",
  "codeSnippet": "DeviceSummaryCard(\n    state = TODO(\"DeviceState\"),\n    content = { },\n)",
  "imports": ["import ee.schimke.meshcore.components.ui.DeviceSummaryCard"],
  "parameters": [ { "name": "state", "type": "DeviceState", "hasDefault": false }, … ]
}
```

Only **required** parameters (no default) form the minimal call; a function-typed slot renders as
`name = { }`, everything else as `name = TODO("Type")` — valid, copyable Kotlin (`TODO()` returns
`Nothing`, assignable anywhere) with the type as the hint for the developer/agent to replace.
At publish time `publish-code-connect.mjs` wraps `codeSnippet` into a `figma.code` parserless
`template` (with `imports` in `templateDataJson`), so Dev Mode shows the real call rather than just the
component name. A component with no required params, or one whose signature couldn't be read, degrades
to a bare `Foo()` — still valid.

### Binding parameters to Figma variant properties

When the target Figma component is a real **component set** with variant properties, the publisher
binds them to the matching parameters — a param whose name matches a Figma property gets a live
`figma.properties.*` interpolation instead of a `TODO`:

```kotlin
DeviceSummaryCard(
    state = ${figma.properties.enum('State', { 'Loading': 'DeviceState.Loading', 'Populated': 'DeviceState.Populated' })},
    title = ${figma.properties.string('Title')},
)
```

`VARIANT` → `figma.properties.enum` (options mapped best-effort to `Type.Option`, for you to confirm),
`BOOLEAN` → `figma.properties.boolean`, `TEXT` → `figma.properties.string`. Matching is by normalized
name; a param with no matching property keeps its `TODO`. Bound property names are recorded in
`templateDataJson.props`.

**Important:** variant properties live on Figma **component sets**. The code-led rendered catalog is
plain frames with none, so binding is a no-op there — it activates when you publish against an actual
Figma design system whose components carry variants (e.g. a hand-built or generated `Button` set). This
is the same boundary Figma's own Code Connect draws: prop mapping is a component-set concept, and the
final design-prop → code-value mapping is expected to be reviewed/completed by hand.

Join sources (all already in the pipeline):

- `componentId` → the Figma frame name (from the catalog spec / importer).
- `componentId` → `@Preview` function (`fnByComponentId`, from `catalog.spec.json`).
- `previewName` → inferred target composable + its source file (`targetsByFunction`, from the bundle).
- `--source-repo` / `--source-ref` / `--source-module` → the GitHub `source` URL, pointing at the
  resolved component's file when known, otherwise the module dir.

## Publishing (Org/Enterprise)

1. **Import** the catalog into a Figma design file. The frames must be named by `componentId`
   (the design-parity catalog import does this; it is the same board the public preview server shows).
2. **Resolve** layer names → node ids and build the send payload:

   ```
   FIGMA_TOKEN=<pat> node scripts/design-artifacts/publish-code-connect.mjs \
     --manifest <branch>/code-connect.json \
     --file 'https://www.figma.com/design/<fileKey>/…' \
     --out send-mappings.json
   ```

   The token only needs **read** access (the walk uses `GET /v1/files/:key`), so this step runs on any
   plan. Unresolved names (a component not on the board) and ambiguous names (the same component twice)
   are reported; the rest are bound.

3. **Publish** the resolved mappings. `send-mappings.json` is the exact argument object for Figma's
   `send_code_connect_mappings` MCP tool — hand it to an agent connected to the Figma MCP, or feed the
   same mappings to `figma connect`. This is the step gated to an Org/Enterprise Dev/Full seat.

## Reviewing before publish

Sort review by `confidence`: `explicit` mappings are author-pinned and need no scrutiny; `HIGH` is
usually safe; `MEDIUM`/`LOW`/`preview-fallback` are where to look — either pin an explicit `component`
on the spec entry or retarget before publishing. `get_code_connect_suggestions` is a good cross-check.
Node-id resolution is deterministic and re-runnable, so re-resolving after the board changes is cheap.

## Why this pairs with the render pipeline

Code Connect's own weak spot is drift: when a design changes, its mapping silently rots and it has no
way to notice. This repo *does* — it re-renders every component and diffs it. So the same branch that
carries the Code Connect mapping also carries the rendered PNG + the `compare.html` PNG-vs-SVG score,
which is the drift signal Code Connect can't produce on its own.
