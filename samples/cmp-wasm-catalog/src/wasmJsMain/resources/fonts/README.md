# Roboto (vendored)

`Roboto-Regular.ttf` + `Roboto-Medium.ttf` — the two weights Material 3's type scale uses —
extracted from `org.robolectric:nativeruntime-dist-compat:1.0.19` (`fonts/` inside the jar,
Maven Central), i.e. **the exact font files the Android snapshot renderer rasterizes with**
under Robolectric's native graphics. Using the same bytes is what makes the in-browser Wasm
tier's text wrap, truncate, and measure identically to the baked catalog PNGs; classic
Roboto 2.x and CMP's bundled default both differ measurably (see PR history).

Loading is driven by [`fonts.json`](fonts.json): each `role: "default"` family's files are
fetched **by URL** and become the app's whole M3 type scale (`Main.kt` → `loadCatalogFonts()`,
default base `./fonts/`, overridable via `?fontsBase=`; a base without a manifest falls back to
the fixed Roboto pair). Self-hosted beside the app so the bundle stays offline-clean behind an
egress proxy; on the public server the serve process is the cache — it fetches these files once
from the trusted `design-artifacts` branch and serves them locally. A fetch failure or timeout
degrades to the CMP bundled font.

`index.html` starts the manifest + font fetches at document load, in parallel with the Wasm boot,
and the app consumes those in-flight promises — so fonts add no latency to the first frame. The
prefetch must live in the iframe itself: the sandbox's opaque origin has its own HTTP-cache
partition, so the embedding viewer page cannot warm fonts for it.

The manifest is additive: future roles (named families, generic-family mappings like `serif`)
can be declared per family without breaking older apps, which only consume `role: "default"`.

License: Apache 2.0 (Roboto, Google) — see [LICENSE.txt](LICENSE.txt).
