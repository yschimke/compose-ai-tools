# CMP/Wasm parity — the flake was a blank render, not a moved pixel ([#3558](https://github.com/yschimke/compose-ai-tools/issues/3558))

The advisory `CMP/Wasm Parity` check produced **three different verdicts across six consecutive CI
runs** of a PR that changed only `.github/` files, and two more across a second such PR. Nothing in
either diff can reach a renderer, a sample, a catalog or any Kotlin source, and the interesting runs
all compared against the same `design-artifacts/remote-m3` tip — so every one of them scored
identical input.

The moving rows never drifted. They **flipped between two exact values**, repeatable to two decimals
across weeks and different runners:

```
VariableWidthRemote     0.19%  ↔  2.45%
VariableWeightRemote    0.12%  ↔  1.98%
TypographyRemote        0.46%  ↔  0.79%
TypefaceSpecimenRemote  1.08%  ↔  2.21%
TextRemoteButton        0.11%  ↔  0.26%
```

## What the two states are

Not two draws of the same text. **One of them is not a draw at all.** Opening the captured PNG for
`VariableWidthRemote` in the 2.45% state, the entire 640×480 image is one colour — the compare
harness's grey background, 307,200 identical pixels, no ink anywhere:

```
$ node -e '…histogram of rc-cmp-wasm/…VariableWidthRemote….png…'
[ [ '128,128,128,255', 307200 ] ]
```

Five documents came back like that, and they are exactly the corpus's text-only documents. Every
other row had ink. So the "regression" the check reported was the CMP/Wasm player drawing **nothing**
and being scored on it: a document that is nothing but white text on transparent, missing all of its
text, differs from its baked reference by a completely stable 2.45%.

That is why the numbers were bistable rather than noisy, and why the two states were the same to two
decimals every time. There is nothing analogue about "drew it" versus "didn't".

## Why the capture accepted it

`rc-settle.mjs` screenshots until the pixels hold still for 500 ms, which was introduced (#3466)
precisely so the lane would stop capturing half-resolved text. A quiet window says the page stopped
changing. It does not say the page ever **started**: a blank frame is blank in 500 ms too, so it
converges on the first comparison. Instrumenting the loop over the failing rows:

```
settle: done 533ms shots=31 changes=0     TypographyRemote      → blank
settle: done 531ms shots=27 changes=0     VariableWidthRemote   → blank
```

Zero changes. The loop was not racing a redraw and losing; there was no redraw to race.

## Why the player drew nothing

It is the **document swap**, not the document. Three experiments on one machine, same corpus, same
player build:

| Run | `VariableWidth` | `VariableWeight` | `Typography` | `TypefaceSpecimen` |
|---|---|---|---|---|
| full corpus, forward order | 2.45% (blank) | 1.98% (blank) | 0.79% (blank) | 2.21% (blank) |
| those four documents *alone* | **0.19%** | **0.12%** | **0.46%** | **1.08%** |
| full corpus, order reversed | 0.19% | 0.12% | **0.79% (blank)** | **2.21% (blank)** |

Rendered on their own they are correct. Rendered late in a sequence of swaps they lose their text.
Reverse the corpus and a *different* band loses it — in reverse order `TruncatedTextRemote`, which
was fine forwards, goes blank instead. The failure follows position in the run, not the document,
which is precisely why CI produced a different verdict per run and why a slower runner produced a
different one again.

It is also **not a race the capture can wait out**. Holding the settle loop open for the full 5 s
timeout leaves the row blank:

```
the player drew nothing in 5014 ms while the baked reference has ink
```

while the same document, navigated to instead of swapped in, renders correctly in ~600 ms.

## The fix

Two changes, one for each half of the failure.

**1. `rc-compare.mjs` navigates for every document.** #3445 replaced the navigation with
`window.rcPlayerLoad` for speed (warm time-to-`ready` 819 ms → 107 ms), and
`rc-cmp-wasm-document-swap.test.mjs` pinned a swapped render as byte-identical to a navigated one.
That equivalence is true for the two documents it checks and false across a corpus, which is the gap
the table above measures. The player keeps `rcPlayerLoad` and keeps its guard; the driver stops
depending on it. Extending that test to a corpus-length sequence is what would let the driver go
back to swapping.

**2. `rc-settle.mjs` takes an `expectation` the converged frame must satisfy.** The lane's is "there
is ink here", asked only of documents whose baked reference has ink — a claim built from data the
driver already holds, not a sleep tuned to one machine. A frame that is still blank at the timeout
fails the row rather than scoring it, so a missing render can only ever be reported as a missing
render. This is what makes the first change enforceable instead of merely true today: if the swap
path is ever restored, or navigation stops being enough, the lane says so instead of publishing a
number.

## Verification — the double render the issue asked for

Two consecutive full runs of the fixed driver against the published `design-artifacts/remote-m3`
bundle, same commit, same machine:

```
$ diff -rq final-1/rc-cmp-wasm final-2/rc-cmp-wasm
$ echo $?
0
```

**All 27 CMP/Wasm PNGs byte-identical**, no row's number differing between runs, `27/27 rendered,
0 failed` — and every previously-flipping row on its correct value (0.19 / 0.12 / 0.46 / 1.08 /
0.11), which is the state the baked reference agrees with.

Before the fix, the same corpus on the same machine produced four to five blank rows on every run,
and which rows went blank moved with the order.

Cost of the navigation, measured end to end on the full comparison: **74 s → 88 s** (+19%). The
CMP/Wasm lane is one of several the run drives, so the per-document cost is the ~0.5 s the #3445
note already quotes.

Unit coverage, which does not need a browser and therefore cannot skip, is in
[`rc-settle.test.mjs`](../../../../scripts/design-artifacts/rc-settle.test.mjs): a converged frame
the expectation rejects is not settlement, an expectation that never holds gives up at the timeout
with `settled: false` rather than blocking, and a call with no expectation behaves exactly as before.

## What this does *not* fix

The issue's other observation stands untouched: `design-artifacts/remote-m3` is regenerated roughly
hourly, so a PR spanning a regeneration is compared against a different baseline than the run before
it — the check answers "has parity moved since whenever the baseline last ran" rather than "did this
PR move parity". The evidence in the issue already rules that out as the cause of the flips above,
but pinning the baseline per PR is worth doing on its own.

Nor does it explain *why* a swapped-in document loses its text — only that it does, reproducibly,
and that waiting does not recover it. That belongs to the player and is the reason the driver's
change is framed as declining to depend on the swap rather than as removing it.
