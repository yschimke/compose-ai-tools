# `spec:` device strings lost `orientation=portrait` and `parent=` — issue #3547

`DeviceDimensions.resolve` parses `@Preview(device = "spec:…")`. It honoured `orientation=landscape`
(via `maxOf`/`minOf`) but had no portrait branch, and never read `parent=` at all. So the two terms
Studio's device picker writes when you pick a device and rotate it were the two terms that did
nothing.

Both images per pair are `:samples:cmp:composePreviewRenderAll` output for the fixtures added to
[`PreviewModeMatrix.kt`](../../samples/cmp/src/main/kotlin/com/example/samplecmp/PreviewModeMatrix.kt),
rendered on the same tree with only `DeviceDimensions.kt` differing.

| file | `device =` | frame |
| --- | --- | --- |
| `rotated-before.png` | `spec:width=1280dp,height=800dp,dpi=240,orientation=portrait` | 1920×1200 — landscape, the request dropped |
| `rotated-after.png` | same | 1200×1920 — 800×1280dp @1.5×, what Studio renders |
| `parent-before.png` | `spec:parent=small_phone,orientation=landscape` | 2100×1050 — the 400×800dp default, rotated; the picked device is gone |
| `parent-after.png` | same | 1280×720 — Small Phone's 360×640dp @2.0×, rotated |

The first string is not hypothetical: it is verbatim what AndroidX's `@PreviewScreenSizes` uses for
its "Tablet" entry, so that multipreview rendered its Tablet and Tablet-Landscape screens as the
same landscape pixels.

`PreviewModeMatrixTest` pins both sizes in `:samples:android` and `:samples:cmp`, so a regression
fails a test rather than quietly reshaping the pixels.
