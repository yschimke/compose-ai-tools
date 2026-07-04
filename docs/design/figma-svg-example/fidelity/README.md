# figma-svg fidelity harness

The fidelity harness scores how faithfully the `compose/figma-svg` export reproduces the real
render. For each preview it rasterises the exported `compose-figma.svg` (headless Chromium — the
engine an imported SVG is interpreted by, so text/gradients/`figma-raster` images all render),
aligns it to the padding-free render PNG, and diffs them into a `render | figma-svg | diff`
composite plus a `compose-figma-fidelity.json` score.

It is opt-in (`-Dcomposeai.figma.fidelity=true`) and runs on the desktop backend (where the renderer
and a browser live). The pure scoring/compositing lives in `FigmaFidelity`; the rasterise + align in
`FigmaSvgFidelity`.

## Examples

**Hybrid raster — 100%.** A screen with an opaque `Image`: the vector surface + the cropped
`figma-raster` image both reproduce exactly, so the export is pixel-faithful.

![hybrid image fidelity](hybrid-image.png)

**Composite card — ~91%.** A themed `Surface` with a title, body text, and a coloured box. The
surface fill and box match; the diff isolates a small **text-baseline drift** (the red ghosting on
the text) — exactly the structural signal used to drive the text baseline/alignment fixes.

![card fidelity](card.png)

The score is a per-pixel agreement fraction over a common opaque background, with a tolerance that
absorbs antialiasing so it flags structural drift (missing shape, wrong fill, misplaced text) rather
than sub-pixel noise. The `rasterizer` field in the sidecar records whether Chromium (text-inclusive)
or the Skia fallback (shapes only) produced the score.
