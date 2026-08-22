# reference-content-placement — sizing a reference off what it draws, not off its artboard

Evidence for the placement change in
[`png-resample.mjs`](../../../../scripts/design-artifacts/png-resample.mjs) and
[`emit-design-references.mjs`](../../../../scripts/design-artifacts/emit-design-references.mjs), and
for the gate in [`reference-scale.mjs`](../../../../scripts/design-artifacts/reference-scale.mjs).

Raised as [m3-catalog#180](https://github.com/yschimke/m3-catalog/issues/180).

## The problem

A Figma component reference is exported at the Compose renderer's density and centred on the
sticker's canvas without enlargement — its pixels already describe the component at the renderer's
scale, so scaling it up to fill a padded canvas would republish the design at a size its author
never drew. An oversized export is still reduced, so it stays representable rather than being
clipped.

"Oversized" was measured on the export's **whole raster**. A Figma export spans the node's
`absoluteBoundingBox`, and the Material 3 kit draws that box larger than the component: the XSmall
button is a 32dp button inside a 48dp touch-target frame, and every icon button, checkbox, switch
and radio is 48dp around something smaller. At 2.625 the XSmall button arrives as an 84px button in
a 126px raster; the sticker canvas is 84px, because the render draws the button and nothing else.
126 > 84, so the whole raster was reduced by 0.667 — and the button with it.

The measurement in m3-catalog#180, reproduced here over all 1340 published ids: **536** have a
reference, and **121** of those draw their component at a uniform 0.47–0.91 of the render, 108 of
them exactly centred in the render's canvas. The ratios are exactly the padding ratios — every `xs`
and `xs-square` cell across four button and four toggle-button emphases lands on 0.667, which is
32/48.

Nothing noticed, and the score could not: `design-reference-score.mjs` drives the viewer's own
`format-compare.js`, which crops both sides to their content box and redraws them into one shared
box before scoring. That normalisation is what makes the lane robust to a design tool's padding —
and it is exactly what makes a size difference invisible to it. So the `PNG ↔ Figma` percentage for
those 121 cells was a statement about the shape and none at all about the size.

## What changed

The **drawn** pixels decide. `alphaBounds` finds the content box of the export; `placeRgba` centres
*that* on the canvas and reduces only when *it* overflows. Transparent margin that would otherwise
force a reduction is cropped away instead — an empty row carries nothing the comparison can read,
and shrinking the artwork to keep it costs the comparison the one thing it can.

The returned `box` still describes where the whole source landed, so the annotation layer keeps
mapping design-frame coordinates onto the artwork; after a crop it simply starts outside the canvas.

## Before / after

The kit node geometry is read from the Figma REST metadata, the *before* and *render* columns are
the PNGs `m3.preview.coo.ee` publishes today, and the *after* column is the same pipeline code with
the change, replayed over a source raster rebuilt from the published reference — so it is a pixel
softer than a fresh export from Figma will be, and its **geometry is exact**.

![Reference placement, before and after](reference-placement-before-after.png)

| cell | canvas | before | after | render |
| --- | --- | --- | --- | --- |
| `button-filled__ideal__xs` | 219x84 | 145x56 | 218x84 | 219x84 |
| `iconbutton-filled__ideal__xs-narrow` | 95x105 | 57x65 | 74x84 | 74x84 |
| `radiobutton-selected__ideal__disabled` | 63x63 | 27x27 | 53x53 | 53x53 |
| `shape-materialshapes__ideal__fan` | 252x252 | 200x200 | 252x252 | 252x252 |
| `checkbox-checked__ideal__disabled` | 63x63 | 25x25 | 47x47 | 55x53 |

The replay is also the proof that the diagnosis is right rather than plausible: run the same rebuilt
source through the **old** placement and it reproduces what the catalog published — exactly on
`button-filled__ideal__xs` (145x56@(37,14)), `shape-materialshapes__ideal__fan` (200x200@(26,26)),
`radiobutton-selected__ideal__disabled` and `splitbutton-filled__ideal__xl`, and within a pixel on
the checkbox, whose drawn box had to be estimated from the node tree rather than measured.

Four of the five land on the render exactly. The checkbox does not, and that is the point of
separating the two readings: 0.47 was the pipeline, and the ~0.87 that remains is the kit and
Compose disagreeing about a checkbox's size — a parity finding, which is what the lane exists to
report.

## The gate

`reference-scale.mjs` compares each published reference's content box with its sticker's and reports
a **uniform** rescale outside tolerance — a difference in *proportion* being what the scorer's
`geometry` already reports.

A uniform ratio does not by itself say whose defect it is, and it must not: the checkbox above is
0.87 on one axis and 0.85 on the other, well inside the uniform band, and it is a genuine parity
finding. Failing a publish on it would be the opposite of what this lane is for. What separates the
two is not the ratio but whether the pipeline scaled the artwork on the way in — which the emitter
knows for a fact from the placement it just performed, so it passes that in:

- **rescaled by this export** ⇒ the published picture is not the size the design was drawn at. A
  defect here, reported through `warnFor` so a primary is a warning `--strict` fails on and a
  secondary stays a note — one variant cell must not cost a catalog its publish.
- **published at its own density and still a different size** ⇒ the kit and the render disagree. A
  note, and a finding about the component.

All 107 cells in the issue are the first kind; the checkbox residue is the second.

One place the check cannot speak, and says so rather than inventing an answer: a catalog publishing
with `--reference-backdrop`. The reference's box is measured before the backdrop goes on, but the
sticker's cannot be — a `showBackground` preview is opaque edge to edge, so its alpha bounds the
watch face and never the component. Those pairs are counted in the tally as out of scope instead of
comparing 1:1 by construction.

`splitbutton-filled__ideal__xl` is the case that shows the uniform test working. Its reference is
371x126 against a 1049x105 render — but the kit node is 400x136 with no empty margin anywhere, so
the placement has nothing to crop and this change does not move it. Width ratio 0.35 against height
ratio 1.2 is not uniform, so the gate leaves it to `geometry`: the kit's XL split button is 136dp
tall and the render's is 40dp, which is a defect in the component, not in the export.

One note on the arithmetic, because it is easy to get wrong and invisible when you do: **nothing
about the placement is estimated from `scale`.** The raster is resampled first and the content's box
is then read off the result, and the offset and the fits-or-not clamp both come from that. Two
different bugs came from estimating it instead, each costing a row or column with no sign anywhere:
centring on the unrounded factor published a 300-unit vector fitted to 151px as 150, and a `ceil` on
a fractional edge reported a content box that really occupied 25 rows as 26, read that as "does not
fit", skipped the clamp meant to protect it and cropped it. The measurement is windowed to the rect
the caller nominated, so naming a sub-rect of a raster that draws more than one thing still means
what it says.
