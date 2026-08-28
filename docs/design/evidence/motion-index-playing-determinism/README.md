# `serve-motion-index-motion-index-playing` determinism

`before-run-1.png`, `before-run-2.png` and `before-run-3.png` are three renders of the **same
commit** (`ca7196ce11`), produced back-to-back by

```
cd preview-server/preview-harness
HARNESS_THEME=light npx playwright test -c playwright.config.mjs pages-snapshot.spec.mjs \
  -g "snapshot · serve-motion-index"
```

Eight consecutive runs of that command produced **three** distinct images:

| md5 | runs (of 8) |
| --- | --- |
| `1b248b0f4338f251ca75377c4b26f164` | 4 — `before-run-2.png` |
| `e100b488007e2274fe8e06457a523911` | 2 — `before-run-1.png` |
| `5f4b4fbb53175900c058aabce8f9d87c` | 2 — `before-run-3.png` |

The captures are 1024×1877. The differences are tiny and always in the same four places — one
19-row band per motion card, at the cards' switch knobs:

| pair | differing pixels | bands (rows × columns) |
| --- | --- | --- |
| run-1 vs run-2 | 2125 (0.11%) | 426–444 ×[456,723], 851–869, 1192–1210, 1578–1596 ×[456,486] |
| run-1 vs run-3 | 1251 | 851–869, 1192–1210, 1578–1596 ×[456,486] |
| run-2 vs run-3 | 874 | 426–444 ×[456,723] |

## What moved, and why

The state presses "Play all", which swaps every card's `src` from its poster still to its
recording; the harness serves `fixtures/pages/_motion-placeholder.apng` for all four. That stub is
**14 frames at 40 ms with `acTL` plays=1** — 560 ms of playback, then it rests on its last frame.
Resting is what makes a four-card shot reproducible, and the spec's comment said so.

Nothing waited for it. The pre-shot hold was

```js
i.complete && i.naturalWidth > 0 && /\.apng/.test(i.getAttribute("src") || "")
```

which is satisfied when the APNG has **decoded**, not when it has finished playing. The shutter
therefore opened somewhere inside a 560 ms window, on whichever frame each card happened to be
showing — and because the four `src` swaps land at slightly different moments, the cards drift
independently. That is exactly the run-1/run-2/run-3 pattern above: sometimes the first card
differs, sometimes the other three, sometimes all four.

Downstream, the visual-diff bot reported this capture as "changed" on pull requests that touch
none of it — [#4689](https://github.com/yschimke/compose-ai-tools/pull/4689), a documentation
rename, was flagged with 2141 differing pixels across those same four bands.

## The fix

Hold for rest, without hard-coding the stub's duration: poll the grid's pixels and require two
consecutive reads, spaced wider than one 40 ms frame, to agree. A still-playing card cannot
produce the same bytes twice at that spacing; a rested one always does. Bounded, like the decode
wait beside it.

`after.png` is what the fix produces. **Eight** consecutive runs were byte-identical
(`md5 f433b9a29cf5c9adbbff2bc7d5ec1d85`), and the fixture's other two captures
(`serve-motion-index` and `serve-motion-index-motion-index-dark-take`, both themes) are unchanged
— only the two `motion-index-playing` captures move, which is the rebaseline this fix is.
