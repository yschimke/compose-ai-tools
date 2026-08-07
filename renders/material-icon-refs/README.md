# Material icon references in the figma-svg export

Committed evidence for the `compose/figma-svg` Material-icon reference path — a stock
`Icons.*` vector exports as a **named reference** (its canonical fonts.google.com identity plus a
shared `<defs>` entry every placement `<use>`s) instead of an anonymous blob of paths.

| file | what it is |
| --- | --- |
| `compose-figma.svg` | the export itself, from a real render of `MaterialIconRowPreview` |
| `material-icon-row.png` | Compose's own render of that fixture — what the SVG must reproduce |
| `svg-raster.png` | `compose-figma.svg` rasterised in headless Chromium (proves the `<use>` refs resolve) |
| `before-after.png` | inline-paths vs reference, side by side, with the render for comparison |

Regenerate all of it with:

```bash
FIGMA_SVG_DUMP_DIR=<dir> ./gradlew :daemon:android:testDebugUnitTest \
  --tests '*MaterialIconRefE2ETest*' --rerun
```

`MaterialIconRefE2ETest` is the automatic guard: it drives a real Robolectric render, so it fails if
AndroidX ever renames the `VectorComponent.name` field the capture reflects — the one way this
feature could otherwise degrade silently (icons keep exporting, just unnamed). The pixel claim is
checked the same way it was produced: `before-raster` and `after-raster` hash identically, because
the reference changes the document's structure and never its drawing.
