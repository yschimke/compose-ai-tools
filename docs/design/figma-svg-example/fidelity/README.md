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

**Composite card — ~95%.** A themed `Surface` with a title, body text, and a coloured box. The
surface fill and box match exactly; the diff shows only faint residual on the text where the SVG
baseline drifts by more than a pixel — a genuine, but small, structural signal to drive the text
baseline/alignment fix from.

![card fidelity](card.png)

The score is a per-pixel agreement fraction over a common opaque background. It's a **structural**
metric: a per-channel colour tolerance absorbs antialiasing, and a **spatial tolerance** (a ±1px
neighbourhood match) absorbs the sub-pixel baseline/edge drift that is unavoidable between the render
and its SVG re-rasterisation — so the score flags real drift (missing shape, wrong fill, text off by
more than a pixel) rather than being dominated by 1px text jitter. The `rasterizer` field in the
sidecar records whether Chromium (text-inclusive) or the Skia fallback (shapes only) produced the
score.
