# Catalog mode opens on the baked snapshot

Catalog mode used to tick `#cp-wasm-toggle` on load, so every component page opened on the
in-browser CMP Wasm app instead of the snapshot the catalog publishes. It came up blank
(issue #4091, reported against <https://preview.coo.ee/compose-m3/>).

| File | Page | State |
| --- | --- | --- |
| `before-catalog-wasm-blank.png` | `/compose-m3/p/button-filled__ideal__keyboard-focus__dark?chrome=catalog` | Before — auto-enabled Wasm, blank stage, URL rewritten to `&mode=wasm` |
| `after-catalog-snapshot.png` | same URL, with this change | After — the baked snapshot, clean URL |
| `dev-mode-wasm-lane.png` | `…?chrome=dev&mode=wasm` | The Wasm lane entered the gated way, for contrast — it renders, and is still reachable |

Captured in headless Chromium at 1400×1000 against the live public deployment, waiting 35s per
page so nothing here is a load-in-progress frame. The "after" shot proxies that deployment with
the rebuilt `viewer.js` and the rewritten inline tab script from this change, so it is the real
catalog data with only the changed assets swapped in.

## What the "before" shot is showing

The app itself was fine — it booted, initialized WebGL, and posted its `cp-wasm-ready` first-frame
signal. It was rendering into a **104×20** iframe parked mid-stage:

```
IFRAME [{ src: "/wasm/compose-m3/?id=button-filled&uiMode=dark#…", w: 103.56, h: 20 }]
STAGE  { img: { src: "", nw: 0, nh: 0, w: 104, h: 20, vis: "hidden" } }
```

`positionOverlay` sizes the Wasm iframe to the snapshot `<img>`'s box, and that `<img>` never got a
`src`: entering an interactive lane cancels the in-flight snapshot, and the auto-enable ran at parse
time, ahead of the gate `viewer.js` puts on a bookmarked `?mode=` for exactly this reason. A
src-less `<img>` still reports a box — the browser's alt-text placeholder, ~104×20 — so the overlay
had a plausible-looking rect to copy and no pixels behind it. The same page in Dev mode, where the
lane is entered only after the snapshot lands, sizes the iframe to the render's 351×210 and draws.

Both halves are fixed: the auto-enable is gone (the snapshot is the default rendering, not a
fallback), and `positionOverlay` now requires a *decoded* snapshot — `naturalWidth > 0`, not merely
a non-empty rect — before mirroring its box, falling back to the stage's content box otherwise.
