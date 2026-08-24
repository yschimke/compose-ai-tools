# The acceptance band, on a real evaluation

What a focused comparison says once its catalog has accepted something. Three numbers and a row per
acceptance — and the pictures below are the actual `known-differences.js` bundle running the actual
engine, not a mock of what it would look like.

![the acceptance band, light theme](band-light.png)

![the acceptance band, dark theme](band-dark.png)

## What is in the picture

The synthetic catalog behind it has **two** acceptances over one comparison, which is the case a
single aggregate verdict cannot express:

| Acceptance | Recorded | This render | Status |
| --- | --- | --- | --- |
| `m3-iconbutton-tonal-glyph` | the glyph, drawn red | still red | `valid` — accepted |
| `m3-iconbutton-tonal-badge` | the badge, drawn blue | now green | `invalidated: candidate-changed` |

So the band shows one quiet row and one loud one. The loud row is the point of the whole model: a
known difference that has stopped matching what was recorded suppresses **nothing**, and says so,
rather than going on hiding a region nobody has looked at since.

The three numbers are `raw` (the pair as though nothing had been accepted — never hidden), then
`unaccepted`, then the accepted region's own match. None is the difference of the others: `accepted`
is a regional measurement on the same scale, and reading `unaccepted` against `raw` is what gives a
signed "what did acceptance buy".

**The result line above the band and `raw` disagree, deliberately.** Acceptance is measured with the
portable kernel — an exact area average both engines can reproduce — while the result line still
comes from the browser's own `drawImage` filter, which no offline engine can. The band says so in as
many words. The two converge when the versioned rebaseline
([D3](../../parity-batches/00-decisions.md#d3--the-score-rebaseline-is-versioned-and-when)) makes the
portable path the live scorer, which is a change of its own with regenerated baselines and a release
note.

## How these were made

```
node build-band-harness.mjs   # writes band.html into the scratchpad: synthetic catalog + real bundle
node shoot-band.mjs           # screenshots it in both themes
```

`build-band-harness.mjs` generates the four rasters, resolves the canonical plane with the same
`resolvePlane` the page uses, hashes the artifacts, and writes a page carrying the committed
`serve.css` and the committed `known-differences.js` with a `fetch` stub in front of them. Nothing
about the engine is stubbed — the numbers in the picture were computed by the bundle that ships.

A live server would have done as well and costs far more to stand up here: the band only renders on a
catalog that has published a `parity/known-differences.json`, and no catalog has yet, because the
publish path shipped in the same batch as the thing that reads it.
