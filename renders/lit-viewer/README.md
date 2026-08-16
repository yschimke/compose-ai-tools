# The viewer, unchanged: all 240 page captures byte-identical

Committed evidence for `viewer.js` → generated from `cli/serve-web/src/viewer.ts`.

This is the last of the hand-written `assets/*.js` files, and the port is meant
to change **nothing** a visitor can see. It is checked rather than asserted: the
full page-snapshot harness was run on `origin/main` (`63dd154e6`) and on this
branch, and every one of the **240** captured PNGs compares byte-identical.

```
identical: 240   differing: 0
```

That covers all 57 page fixtures in both themes plus the runtime states the
committed HTML cannot express — which is the part that matters here, because
those states are produced *by the ported code*: `serve-viewer-connecting` is
`openStream()` setting `data-pending` while the socket comes up, and the
exploded, wasm, spec, motion, source and rc-player captures each enter a lane
through the mode machine this file owns.

| file | what it is |
| --- | --- |
| `serve-viewer.light.png`, `serve-viewer.dark.png` | the viewer at rest, both themes |
| `serve-viewer-connecting.light.png` | the live lane's activation badge — a runtime state, drawn by the ported `openStream()` |
| `serve-viewer-wasm.light.png` | the in-browser Wasm lane mounted over the snapshot's box |
| `serve-viewer-exploded.light.png` | the 3D view, whose knobs and URL params round-trip through the ported query builder |

## Reproducing it

```
rm -rf vscode-extension/preview-harness/out
HARNESS_CHROMIUM=/opt/pw-browsers/chromium-1194/chrome-linux/chrome \
  npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot
```

Run it on both refs and `cmp` the two `out/` directories. Note that `out/`
accumulates across runs rather than being cleared, so the `rm -rf` is load-bearing
— without it a stale capture from an earlier run compares equal to itself.

## Why "identical" is the whole claim

A port that renders the same pixels has not proved it is correct — only that it
has not broken what these fixtures exercise. What backs the rest is elsewhere:
the 783 tests in `cli/serve-web`, the DOM-free rules the viewer now imports
directly (each with its own test file under `src/viewer/`), and the type check
over all 3,151 lines, which is new — the hand-written file had none.
