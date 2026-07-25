# Remote Compose player — DP density behavior for padding

Evidence for the vendored player fix that honors the `DOC_DENSITY_BEHAVIOR`
header property (key 27). Each strip is **baked AndroidX reference | player
BEFORE | player AFTER**, rendered from the density‑2.0 `design-catalog-remote-m3`
`.rc` documents (the Wear‑aligned `dpi=320` catalog).

Before the fix, padding authored in dp was replayed unscaled, so buttons and
cards rendered with half‑scale padding (cramped, narrow). After the fix the
player multiplies padding by the document density under DP behavior — matching
AndroidX `PaddingModifierOperation` — so the padded geometry lines up with the
baked reference.

remote‑m3 mean pixel mismatch (pixelmatch threshold 0.1) improves 2.70% → 1.52%;
padding‑heavy previews improve most:

| preview | before | after |
| --- | --- | --- |
| TitleCardRemote | 8.88% | 1.91% |
| AppCardRemote | 7.86% | 5.16% |
| ButtonGroupRemote | 6.96% | 3.13% |
| FilledRemoteButton | 3.25% | 0.87% |

Density‑1 / LEGACY documents take no scaling path and are unaffected.
