# Harness states restored after #3893's viewer changes

Committed evidence for the `pages-snapshot` repair. These three shots produced
**nothing at all** on `main` — each timed out on a control it could no longer
reach — so there is no "before" image to put beside them: the before is a failed
test and an absent PNG.

| file | what it is, and what it could not reach |
| --- | --- |
| `exploded-controls.png` | the Exploded 3D camera sliders (Lean / Spin / Separation / Layers). They live in the Overrides drawer, which #3893 stopped opening by default, so `[data-cp-group="explode"] > summary` resolved inside a `display: none` subtree and never became clickable |
| `cross-product-subtree.png` | the six labelled variant rows — `Default · RTL`, `Pressed · RTL`, `Disabled · RTL`, `Pressed · Default`, `Disabled · Default` — which are the whole point of that shot, since each has to name both coordinates. They used to sit behind `#cp-axes-toggle`; #3893 replaced that fold with the component nav's tree, so the nav is what reveals them now |
| `disclosures-open.png` | the theme bar expanded, with the state axis reached through the nav for the same reason |

Produced by:

```
cd vscode-extension
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```

which goes from **15 failed / 105 passed** on `main` to **1 failed / 119
passed** with this change. The remaining failure, `contract · the fit cap
re-measures when the history strip lands`, is unrelated and also red on `main`:
that fixture builds `.cp-history` at runtime from the fetched manifest and the
strip never lands, which no drawer or nav state affects.
