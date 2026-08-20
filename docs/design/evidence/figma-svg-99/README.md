# figma-svg fidelity: every wear-m3-catalog row above 99%

Before/after evidence for the four `compose/figma-svg` export fixes measured against the published
[wear-m3-catalog compare page](https://preview.coo.ee/wear-m3-catalog/compare?format=svg).

Each strip is **render (reference) | SVG before | SVG after**, all three drawn on the same fixed
backdrop the compare page scores against. "Before" is the SVG the deployed catalog serves today;
"after" is the SVG this branch's renderer produces from the same capture.

| Strip | What it shows |
| --- | --- |
| `materialshapes-boom.png` | A `MaterialShapes` star exported as a plain square, because the generic-outline sampler could not run on Android at all. |
| `placeholder-card.png` | The placeholder block built from the card's padded *content* rect instead of the container it dresses. |
| `linearprogressindicator-min.png` | A round-capped 24px track squashed to 16px with elliptical caps by a non-uniform fit. |
| `checkboxbutton-split.png` | A split button's outer pill lost, because the shape lived in a lambda-form `graphicsLayer { }` and the clip that carried it was collapsed away. |
| `slider-low.png` | The slider container's pill lost with the clip-only wrapper that held it. |
| `alertdialog-192dp.png` | A 126x108 pill turned -45 degrees drawn as the 166px circle its bounding box describes. |

Reproduce with `scripts/compare-audit.mjs` (`mirror` the catalog, then `run --format svg`), which
drives the real compare page and scrapes the score the shipped scorer settles on.
