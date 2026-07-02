# Roboto (vendored)

`Roboto-Regular.ttf` + `Roboto-Medium.ttf` — the two weights Material 3's type scale uses —
extracted from `org.robolectric:nativeruntime-dist-compat:1.0.19` (`fonts/` inside the jar,
Maven Central), i.e. **the exact font files the Android snapshot renderer rasterizes with**
under Robolectric's native graphics. Using the same bytes is what makes the in-browser Wasm
tier's text wrap, truncate, and measure identically to the baked catalog PNGs; classic
Roboto 2.x and CMP's bundled default both differ measurably (see PR history).

Fetched by the app **by URL** at startup (`Main.kt` → `loadRobotoFamily()`, default base
`./fonts/`, overridable via `?fontsBase=`); self-hosted beside the app so the bundle stays
offline-clean behind an egress proxy. A fetch failure degrades to the CMP bundled font.

License: Apache 2.0 (Roboto, Google) — see [LICENSE.txt](LICENSE.txt).
