# The parity page's visual-difference band, and the shot that was covering nothing

Committed evidence for `parity.js` → `<cp-parity-lanes>` + `<cp-parity-scores>`.

## The band now settles before the shot is taken

| file | what it is |
| --- | --- |
| `band-before.png` | `main`: `Checking 1 mapped comparison(s)…`, captured mid-scan |
| `band-after.png` | the settled sentence — `All 1 mapped component is at least 90% structural match.` |

This is the whole gap the port closed on the capture side. The band is
asynchronous — fetch, decode and score every mapped render/reference pair — and
`pages-snapshot` shot it before the first pair had been measured, on both refs.
An in-flight line looks the same whether the scan works, scores everything
wrong, or has been deleted, so nothing about the result was ever diffed. The
default `serve-parity` shot now waits for the status line to stop starting with
"Check", the same way `serve-format-compare` already waited for its scores.

## …and a finding is captured too

| file | what it is |
| --- | --- |
| `findings.light.png` / `findings.dark.png` | the `visual-findings` state: the issues table, worst-first, with the drift suffix and the red score |

The committed fixture's one mapped pair scores clean, so the default shot above
holds the "everything matches" sentence and nothing else — the table, its order,
the `61.4% · 5.2% proportion drift` result and the red cell would still be
diffed by nothing. The new `serve-parity` · `visual-findings` state stubs
`window.ComposePreviewCompare` with a fixed low score and replaces the element,
which re-runs its scan the way a reload would. The metric itself is not stubbed
away from coverage: it has its own spec in
`preview-harness/format-compare-scorer.spec.mjs`.

## What the port changed that a capture cannot show

`<cp-parity-scores>` owns the whole band now; the server emits the tag and
nothing else. Two things went away rather than moved:

- **The hand-rolled escaping.** `parity.js` built the issues table with
  `innerHTML` and an `esc()` that set `textContent` and read `innerHTML` back —
  which neutralises `<`, `>` and `&` but **not** `"` — while its output was
  interpolated straight into `href="…"`. A Lit binding cannot be broken out of.
- **The false promise.** A page with JavaScript off was left showing
  `Checking 40 mapped comparison(s)…` forever. An element that renders nothing
  until it has something to say leaves nothing behind.

The summary sentence also stopped double-counting. `parity.js` pushed
unscorable pairs onto the same list as the differences and then quoted its whole
length as "require review", so three missing renders read as *"3 of 40 are
unavailable; 3 require review"* — the same three components, said twice, as
though six things were wrong. `parity/findings.ts` counts them separately, and
`findings.test.ts` pins both sentences.

```
cd cli/serve-web && npm run verify          # 244 passing
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
# 124 passed
```
