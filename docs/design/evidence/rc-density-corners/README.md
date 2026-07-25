# Remote Compose player — DP density behavior for corners, spacing, offset, border

Follow-up to the padding fix (#2768). AndroidX scales several dp-typed values by
the document density under **DP** density behavior, not just padding. The vendored
player replayed these unscaled, so at density 2.0 (the Wear-aligned remote-m3
catalog) they came out half-size.

Each strip is **baked AndroidX reference | player BEFORE (main, padding fix only)
| player AFTER (this change)**. Before, the corner radius is visibly too tight;
after, it matches the baked reference.

| op fixed | effect |
| --- | --- |
| `RoundedClipRectModifierOperation` | corner radii (button/card corners) |
| `Row` / `ColumnLayout` | `spacedBy` spacing between children |
| `OffsetModifierOperation` | offset x/y |
| `BorderModifierOperation` | border width + corner radius |

remote-m3 mean pixel mismatch (pixelmatch 0.1): 1.52% → 1.26%. Corner-heavy
previews improve most:

| preview | before | after |
| --- | --- | --- |
| AppCardRemote | 5.16% | 2.83% |
| TitleCardRemote | 1.91% | 0.77% |
| FilledRemoteButton | 0.87% | 0.50% |
| CardRemote | 0.73% | 0.43% |

Offset/Border mirror AndroidX for parity but aren't exercised by the current
catalog (outlined components fill via `CanvasOperations`), so they're not
pixel-verified here. `LEGACY` / density-1 documents take no scaling path.
