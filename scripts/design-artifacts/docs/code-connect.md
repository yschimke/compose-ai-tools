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
      "componentName": "DeviceSummaryCardPopulatedPreview", // the @Preview that renders the sticker
      "source": "https://github.com/…/blob/main/app/…/ComponentPreviews.kt",
      "label": "Compose",
      "figmaSvg": "figma/devicesummarycard-populated.svg"
    }
  ]
}
```

The one thing it can't carry is the Figma **node id** — that only exists once the catalog is imported
into a file. So the manifest keys on `figmaLayerName` (= `componentId`, the name the design-parity
importer gives each frame) and the node id is resolved at publish time.

Join sources (all already in the pipeline):

- `componentId` → the Figma frame name (from the catalog spec / importer).
- `componentId` → `@Preview` function (`fnByComponentId`, from `catalog.spec.json`).
- `--source-repo` / `--source-ref` / `--source-module` → the GitHub `source` URL. When the bundle
  carried a per-preview `sourceFile`, the URL points at the exact file; otherwise at the module dir.

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

`componentName`/`source` point at the `@Preview` that renders the sticker. Where a catalog preview is
a thin wrapper around a production component, retarget it to the underlying component before
publishing — `get_code_connect_suggestions` is a good cross-check. Node-id resolution is deterministic
and re-runnable, so re-resolving after the board changes is cheap.

## Why this pairs with the render pipeline

Code Connect's own weak spot is drift: when a design changes, its mapping silently rots and it has no
way to notice. This repo *does* — it re-renders every component and diffs it. So the same branch that
carries the Code Connect mapping also carries the rendered PNG + the `compare.html` PNG-vs-SVG score,
which is the drift signal Code Connect can't produce on its own.
