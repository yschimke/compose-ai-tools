# Catalog fonts (vendored)

All files are extracted from `org.robolectric:nativeruntime-dist-compat:1.0.19` (`fonts/`
inside the jar, Maven Central), i.e. **the exact font files the Android snapshot renderer
rasterizes with** under Robolectric's native graphics. Using the same bytes is what makes the
in-browser Wasm tier's text wrap, truncate, and measure identically to the baked catalog PNGs;
classic Roboto 2.x and CMP's bundled default both differ measurably (see PR history).

- `Roboto-Regular.ttf` + `Roboto-Medium.ttf` — the two weights Material 3's type scale uses
  (`role: "default"`; applied to the whole M3 `Typography`).
- `NotoSerif-Regular.ttf` + `DroidSansMono.ttf` — what Android's system font table
  (`fonts.xml`) maps the generic `serif` / `monospace` families to (`role: "generic"`;
  consumed by `genericFontFamily(...)` lookups in catalog components — CMP's
  `FontFamily.Resolver` is sealed, so resolver-level interception isn't available to apps).
- `orbitron-400.ttf` + `orbitron-700.ttf` — a downloadable **GoogleFont** (`role: "named"`), the
  faces the `text-branded` specimen resolves via `namedFontFamily("Orbitron")`. Filenames follow
  the `<slug>-<weight>.ttf` scheme the manifest generator expects (`GoogleFontKey.slugify`); the
  export regenerates the `role: "named"` entry from the recorded `Font(GoogleFont("Orbitron"), …)`
  usage. Downloaded from Google Fonts, SIL OFL-1.1 — see [Orbitron-OFL.txt](Orbitron-OFL.txt).

- `google-sans-flex-400.ttf` + `google-sans-flex-700.ttf` — a downloadable **GoogleFont**
  (`role: "named"`), the face `:samples:design-catalog-remote-m3`'s **Google Sans Flex** typeface
  theme names as `google:Google Sans Flex`. This lane is *manifest-only* — it never fetches — so
  without the face vendored here the themed document fails `RcComposeSupport.fontFamilyIssue`'s
  availability check instead of rendering, while the other four player lanes resolve it. Fetched
  from the same CSS2 endpoint as the others (`wght@100..1000`, the 400 and 700 instances of the
  variable file).

  **Licensing — read before forking.** Unlike every other face here, Google Sans Flex is in **no
  license directory** of the [google/fonts](https://github.com/google/fonts) corpus, so its terms
  can't be read off the corpus; the CSS2 endpoint serves it regardless. It is committed because
  the project owner confirmed redistribution is cleared, the same clearance
  [`deploy/image/README.md`](../../../../../../deploy/image/README.md) records for baking it into
  the runtime image. **A fork does not inherit that clearance** — re-check it, or drop this family
  from `fonts.json` and delete the two files. Dropping it only costs the Wasm lane's rendering of
  that one theme.

The committed [`fonts.json`](fonts.json) is the **dev-time default**; the design-catalog export
regenerates it from the per-preview `fonts/used` records (`previews/<id>.fonts.json` in the packed
bundle → `scripts/design-artifacts/render-fonts-manifest.mjs`), so the published manifest tracks
what the catalog's previews actually resolve. The regeneration also **preserves this file's
`role: "default"` and `role: "named"` families** (whose files are still vendored): they are the
catalog's declared **theme-override** typefaces (a Roboto Flex default, a Lobster Two named face),
applied to *clean* previews only via the theme wrapper, so the recorder never sees them and would
otherwise drop them — leaving the published viewer's font-override picks falling back.

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

License: Apache 2.0 (Roboto / Noto Serif / Droid Sans Mono, Google) — see [LICENSE.txt](LICENSE.txt);
Orbitron is SIL OFL-1.1 — see [Orbitron-OFL.txt](Orbitron-OFL.txt). Google Sans Flex carries no
corpus license file and ships under the owner's explicit redistribution clearance — see its bullet
above before forking.
