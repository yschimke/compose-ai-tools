# rc-embedded-player — component chrome drawn twice

Evidence for the fix that stops the **embedded** player (`RcPlayer`) from drawing a component's
background/border chrome twice — which stroked a spurious inner outline hugging the content of any
padded, bordered component (most visibly `OutlinedCardRemote`).

## The bug

`RcPlayerComponent` built a component's modifier with
`componentModifiers.toModifier(component.getDrawContentOperationsListReflection())`. `toModifier`
already draws that `drawOpsList` — its `DrawContentOperation` branch (or its fallback for a component
with no explicit content marker) wraps the chrome ops in a `drawWithContent`. Immediately afterwards,
`RcPlayerComponent` executed the **same** `drawOpsList` a second time in its own `drawWithContent`.

Because the two `drawWithContent` wrappers sit at different points in the modifier chain, they render
the chrome at different sizes. For a component with content padding — an outlined card — the second
pass landed *inside* the padding and stroked the card's outline a second time at the content bounds,
producing a small pill outline around the word "Card" on top of the correct card-bounds outline.
Components without padding drew both passes at the same size, so the duplication was invisible — which
is why the latent double-draw only surfaced once a padded, bordered component (the outlined card) hit
it. Confirmed by instrumenting the draw stream: the outlined card's border path (`DrawPath`, stroke)
is emitted at both `640×134` (the card) and `544×38` (the content row).

## The fix

Remove the second execution in `RcPlayerComponent`; `toModifier` is the single owner of the chrome
draw. One-line-of-intent change, no behavior added.

## Before / after

Rasterized through the embedded player, flattened onto the mid-grey the `rc-compare` page diffs
against. Mismatch vs the baked reference (pixelmatch-style, threshold 32):

![outlined card before/after](outlined-card-before-after.png)

| preview | before | after |
|---|---|---|
| `OutlinedCardRemote` | 0.90% | **0.05%** |
| `CardRemote` | 0.60% | **0.05%** |
| `AppCardRemote` | 3.06% | **0.86%** |
| `TitleCardRemote` | 2.46% | **1.06%** |

Non-padded components are unchanged (the two passes coincided): `FilledRemoteButton`,
`NamedLabelRemoteButton`, `ButtonGroupRemote` all render byte-identically before and after.

![app card before/after](app-card-before-after.png)

## Known, unrelated

The outlined card's rounded left/right end-caps read very faint — a 1px light-`outline`-token stroke
anti-aliased along the curve, sitting at the sticker's edge. This is **pre-existing and identical in
the baked reference**, independent of this fix.

## Regenerating

The `remote-m3` catalog now lives in yschimke/wear-m3-catalog as `:remote-catalog`
(compose-ai-tools#4588). The harness itself is unchanged and still runs here.

It wants a directory of `<id>.rc` plus a `manifest.json`. Two ways to get one — the
second needs no Gradle and no second checkout, because the delivery branch carries the documents
inside `bundle.png` rather than as loose files:

```sh
# (a) render the catalog, in a yschimke/wear-m3-catalog checkout:
./gradlew :remote-catalog:composePreviewRenderAll
#     → remote-catalog/build/compose-previews/renders/<id>.rc (no manifest.json — see (b))

# (b) or stage them straight from the published bundle, entirely inside this checkout:
git fetch https://github.com/yschimke/wear-m3-catalog.git design-artifacts/remote-m3
git show FETCH_HEAD:bundle/bundle.png > /tmp/remote-m3.png
node scripts/design-artifacts/rc-compare.mjs \
  --bundle /tmp/remote-m3.png \
  --player cli/serve/src/main/resources/rc-player/bundle.js \
  --out /tmp/rc-out --stage-embedded /tmp/rc-in
#     → /tmp/rc-in/<id>.rc + /tmp/rc-in/manifest.json, which is what -Prc.embedded.input reads
```

Then run the harness here:

```
./gradlew :third-party-rc-embedded-player:testDebugUnitTest \
  --tests 'ee.schimke.composeai.rcembedded.RcEmbeddedRenderHarness' \
  -Prc.embedded.input=<dir with <id>.rc + manifest.json> -Prc.embedded.output=<out>
```
