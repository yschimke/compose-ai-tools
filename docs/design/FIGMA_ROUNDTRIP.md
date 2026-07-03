# Figma roundtrip — pushing rendered variants and editable layers into Figma

The design catalogs already travel **code → importable bundle → designer pulls
into Figma** ([DESIGN_CATALOGS.md](DESIGN_CATALOGS.md)). This doc covers the
*programmatic* import leg: an agent session with the official Figma MCP server
pushes rendered variants — and editable layer reconstructions — straight onto a
Figma canvas, no manual branch pull. It documents the workflow proven end to
end against `samples/design-catalog-m3` (`TextFieldSticker`); the live proof
sits in the Figma file *Compose M3 Catalog — Roundtrip Proof*.

The render is authoritative throughout: pixels come from `compose-preview`,
structure and text metrics come from its data products. Figma is a *view* of
the code, never the source of truth (the same stance as the catalogs).

Evidence from the proven run:

![variant matrix contact sheet](figma-roundtrip-matrix-evidence.png)

*The 8-cell `render-matrix` contact sheet (uiMode × fontScale × locale) — note
the 1.5× cells catching real overflow: the field's value text wraps and clips
in the fixed sticker bounds.*

![variant sheet arranged in Figma](figma-roundtrip-canvas-evidence.png)

*The same cells pushed through `upload_assets` and arranged into a captioned
auto-layout sheet on the Figma canvas.*

![editable reconstruction beside ground truth](figma-roundtrip-recon-evidence.png)

*Leg 3: left is the rendered PNG, right is native Figma layers (frames,
rectangles, text) built from `layout.json` bounds + `semantics.json`
typography/colors — same geometry, same tokens, fully editable.*

## Leg 1 — render the variant matrix

One command produces every variant a designer wants on canvas plus per-cell
files for the importer:

```sh
compose-preview render-matrix \
  --module samples:design-catalog-m3 \
  --id com.example.designcatalogm3.CatalogPreviewsKt.TextFieldSticker_Light \
  --ui-mode light,dark --locale en,ar --font-scale 1.0,1.5 \
  --contact-sheet --cells-dir --json
```

- `--cells-dir` writes one PNG per cell (`en--light--1.0x.png`, …) and echoes
  each path as `pngPath` in the JSON summary — these are the files the import
  step uploads. The contact sheet is the human/PR-comment artifact. The
  directory is cleared of stale `.png` files first, so a re-run with narrowed
  axes (or a failed cell) never leaves an earlier variant behind for a globbing
  importer to pick up.
- Bounded at 24 cells; axes follow `render_matrix` (MCP) semantics exactly.

For the editable-layer leg, also extract the data products:

```sh
compose-preview bundle pack --module samples:design-catalog-m3 \
  --id <preview-id> --with-semantics -o /tmp/sticker.png
compose-preview bundle extract /tmp/sticker.png -o /tmp/sticker-bundle
# → previews/<id>.layout.json  (layout-inspector tree: exact px bounds per node)
# → previews/<id>.semantics.json (text, typography in sp, colors, tokens)
```

## Leg 2 — push PNG variants onto the canvas

With the Figma MCP server connected (agent session):

1. `create_new_file` (or target an existing file key).
2. `upload_assets` with `count = <cells>` → POST each cell PNG
   (multipart, `file=` keeps the filename as the layer name) → each response
   returns `placedOnNodeId`.
3. One or two `use_figma` scripts arrange the placed frames into a titled
   auto-layout grid with per-cell captions (uiMode row × fontScale/locale
   columns worked well) and set frame names from the cell labels.
4. `get_screenshot` on the container to verify the sheet visually.

Notes from the proven run:

- The upload URLs live on `mcp.figma.com`; the sandbox's network allowlist must
  include it or every POST fails at CONNECT with a 403 (see the environment's
  network policy — this is separate from enabling the Figma MCP connector).
- `use_figma` can fall back to `figma.createImage(bytes)` with base64-embedded
  PNGs (≈5 KB per sticker cell) when raw HTTPS egress is blocked entirely.

## Leg 3 — editable Figma layers from the data products

`layout.json` + `semantics.json` carry enough to rebuild a sticker as **native
Figma nodes** (frames, rectangles, text) rather than a flat image:

| Figma property | Source |
| --- | --- |
| Node bounds (x/y/w/h, px) | `layout.json` per-node `bounds` (already in render px) |
| Text content | `semantics.json` `layoutText` / `editableText` / `label` |
| Font size / line height / letter spacing | `semantics.json` `typography` (sp) × the preview `density` (px = sp × density; density is in `previews.json` params, 2.625 for the default device) |
| Text color | `semantics.json` `textColor.foreground` |
| Container / background fills | `semantics.json` `tokens` where present; otherwise sample the authoritative PNG at a point inside the node's bounds |
| Font family | Compose `sans-serif` → Roboto (available in Figma); keep the substitution explicit in the layer description |

Build the reconstruction in one `use_figma` script: a fixed-size frame at the
render's px size, children placed at absolute `layout.json` bounds, text nodes
with the density-scaled typography. Place it beside a frame filled with the
uploaded render (`fills = [{type:'IMAGE', imageHash}]` from the upload
response) so the designer — and `get_screenshot` — can compare ground truth vs
editable copy directly.

Known limits (state them in the Figma file rather than papering over):

- Canvas-drawn content (shadows, ripples, arbitrary `Canvas` draws) is not in
  the tree — it stays image-only.
- Text metrics are near-exact, not glyph-exact: Roboto ≈ sans-serif, and
  Compose's baseline layout differs subtly from Figma's.
- The reconstruction is one variant; it does not (yet) bind Figma variables.
  `figma-variables.json` from the catalog export is the intended token source —
  applying it via `figma.variables.createVariableCollection` in `use_figma` is
  the natural next step, then binding reconstruction fills to those variables.

## Iteration loop

The roundtrip composes with the existing PR machinery: the agent generates or
edits catalog code on a branch, `compose-preview.yml` posts the before/after
diff, and once merged the `design-artifacts` workflow refreshes the delivery
branches. The Figma push is the last mile — re-run legs 1–2 against the merged
`main` (or a PR head for design review) and re-upload; `upload_assets` with a
`nodeId` swaps the fill on the existing frame in place, so a variant sheet can
be refreshed without re-arranging the canvas.
