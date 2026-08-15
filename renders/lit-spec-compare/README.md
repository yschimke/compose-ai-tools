# The spec lane's diff options, unchanged by the port

Committed evidence for `spec-compare.js` → `<cp-spec-compare>`.

**All 234 `pages-snapshot` captures are byte-identical** between `origin/main`
(`9dc5c35`) and this branch — same filenames, same bytes, nothing added or
dropped. The two images here are what those captures hold, not a before/after:
there is no "before" to show, because nothing moved.

| file | what it is |
| --- | --- |
| `diff.light.png` | the `spec-diff` state: the magenta delta map, the readout, and the live verdict on the chip |
| `slider.light.png` | the `spec-slider` state: one frame wiped between spec and render, seam in the diff map's magenta |

Both are worth reading for one detail each:

- In **Diff**, the chip says `Figma 90.3%` and the readout says
  `90.3% match · 92.15% pixels differ`. Those are the same measurement reported
  twice on purpose — and they agree, which is the whole point of moving the
  verdict onto the chip. The chip's *published* label is `Figma 91.2%`; what is
  showing is the live score of the frame actually on the stage. Leaving the lane
  puts the published one back.
- In **Slider**, the seam is drawn in the delta map's magenta so the two
  comparison surfaces read as one instrument, and it stays inside the frame at
  both extremes — `spec/wipe.ts` clamps a two-pixel seam that the naive
  arithmetic hangs off either edge.

Also note `90.3% match` beside `92.15% pixels differ`: those answer different
questions, which is why the readout carries both. A high structural match with
most pixels differing is a uniform shift — here, a theme.

## What the port changed that a capture cannot show

Three sources want to pick the lane's view: the address bar, the design-spec
chip, and the visitor. They are **not equal**, and the ordering was three
booleans in a closure:

- an explicit choice latches and never clears;
- a named view in the URL *is* an explicit choice — it is what someone picked
  before sharing, or where Back is returning them;
- so the chip's request is only ever a default, spent the moment it is used.

Getting any of that backwards gives a view that silently changes under the
reader. `spec/views.ts` is a reducer over that, with the case it exists for as a
test: a shared `?specView=triptych` link must not be overwritten by the chip
sitting on the same page.

`spec/sameOrigin.ts` came out alongside — the guard on every URL reaching
`drawImage`, now tried against `javascript:`, `data:`, `file:`, a
protocol-relative host and a lookalike domain rather than trusted to a comment.

```
cd cli/serve-web && npm run verify   # 349 passing
./gradlew :cli:test --tests '*ServeWeb*' && ./gradlew ktfmtCheck
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
# 124 passed, both refs
```
