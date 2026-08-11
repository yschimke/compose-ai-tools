# Typeface support across the Remote Compose player lanes

Audit of how each of the five Remote Compose render lanes the preview server (`compose-preview
serve`) and the `compose-preview` CLI can drive resolves a **typeface**, and where those lanes
disagree about the same document. The lanes themselves are described in
[`RcPlayerBackend`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/RcPlayerBackend.kt) —
this document is only about fonts.

A Remote Compose document names its typeface in one of three ways, and each is a separate support
question:

- **a built-in family id** — `0 = default`, `1 = sans-serif`, `2 = serif`, `3 = monospace`;
- **a named family** — `RemoteFontFamily.Named("Orbitron")`, optionally namespaced `google:` (fetch
  it from the downloadable-font provider) or `device:` (whatever the host calls it);
- **an embedded face** — a `FontData` operation carrying the font file's bytes inside the document.

## Support matrix

| | built-in ids | named (local) | `google:` downloadable | embedded `FontData` |
| --- | --- | --- | --- | --- |
| **JS** (vendored TS player, in-browser) | ✅ concrete stacks | ⚠️ passed to CSS; host must have the face | ✅ fetched from the Google Fonts CSS API | ✅ registered as a `FontFace` keyed by document font id |
| **JS** — font-variation axes | ✅ `wght` exactly, `wdth` quantised to the nine `font-stretch` keywords | — | ✅ requested as axis *ranges*, which is what makes the face variable | ⚠️ the same canvas `wght`/`wdth` support; other axes have no canvas expression |
| **CMP Wasm** (this repo's player, in-browser) | ✅ from the host manifest | ✅ if the manifest carries it | ⚠️ manifest only — no fetch | ✅ `decodeInlineFonts` |
| **CMP Wasm** — font-variation axes | ✅ layout ops (`CoreText`) | — | — | ❌ canvas ops (reported, not silent) |
| **Java** (AOSP `remote-player-view`, server-side) | ✅ framework typefaces | ⚠️ `/system/fonts/` filename scan | ✅ served by `RcGoogleFontTypefaceResolver` | ✅ `Font.Builder(ByteBuffer)` |
| **Java** — font-variation axes | ❌ stock `SimpleFontInstance` ignores them | ⚠️ only if the scanned file is variable | ✅ `loadVariable` + `Typeface.Builder(file).setFontVariationSettings(…)` | ✅ `Font.Builder` rebuilt with the axes |
| **CMP Android** (vendored embedded player, server-side) | ✅ framework typefaces | ⚠️ `Typeface.create(name)` | ✅ `FontsContractCompat` | ❌ ignored |
| **CMP Android** — font-variation axes | ✅ layout ops, on the family's variable file | — | ✅ `loadVariable` + `Font(File, …, variationSettings)` | ❌ canvas ops |
| **CMP JVM** (embedded player over Skiko, server-side) | ✅ | ⚠️ host families, else nearest standard | ✅ downloaded via `GoogleFontTypefaceResolver` | ❌ ignored |
| **CMP JVM** — font-variation axes | ✅ layout ops, on the family's variable file | — | ✅ `loadVariable` + an axis-carrying font identity | ❌ canvas ops |

Two rows of that table are worth stating as findings, because they make two chips in the *same*
viewer disagree about the *same* document:

1. **A `google:` family renders differently in `java` than in `cmp-android` and `js`.** The AOSP
   `DefaultTypefaceResolver` (`remote-player-core`) resolves a named family by scanning
   `/system/fonts/` for a file whose name contains the family, then falls back to
   `Typeface.create(name, style)`; there is no `FontsContractCompat`/GMS call anywhere in
   `remote-player-view` or `remote-player-core`, so `google:Orbitron` cannot resolve — it renders in
   the platform default. The vendored embedded player's
   [`EmbeddedPlayerTypefaceResolver`](../../third_party/rc-embedded-player/src/main/kotlin/androidx/compose/remote/player/compose/embedded/EmbeddedPlayerTypefaceResolver.kt)
   *does* issue a `FontsContractCompat.requestFont`, which under the daemon's Robolectric sandbox is
   intercepted by
   [`ShadowFontsContractCompat`](../../renderers/android/src/main/kotlin/ee/schimke/composeai/renderer/ShadowFontsContractCompat.kt)
   and served from the machine-local Google font cache (`composeai.fonts.cacheDir`, set for the
   serve daemon by [`ServeBundleDaemon`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeBundleDaemon.kt)
   and for the CLI daemon by [`BundleDaemonCommand`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/BundleDaemonCommand.kt)).
   So the branded face appears under `cmp-android`, `cmp-jvm` and `js`, and silently does not under
   `java` — which is the **default** server-side snapshot lane. **Closed** for all three: `cmp-jvm`
   and then `java` both got a shim over the shared `GoogleFontCache` — for the view player, a
   `TypefaceResolver` installed on the `RemoteComposePlayer` that serves `google:` names from that
   cache and delegates everything else to `DefaultTypefaceResolver` (see the Java section).
2. **Embedded `FontData` remains absent from the two vendored embedded players.** Java and CMP Wasm
   build a face from the document's bytes, while CMP Android and CMP JVM still ignore them. The JS
   lane now decodes opcode 189, registers the bytes as a collision-resistant `FontFace` alias, and
   resolves both canvas paint and `CoreText` through that alias. Its registration joins the same
   readiness/repaint path as downloadable fonts, and a browser fixture verifies that an operation
   after `FontData` still draws in the embedded face rather than the decoder truncating the stream.

## Where the files come from

Three of the lanes (`java`, `cmp-android`, `cmp-jvm`) resolve a `google:` family through the shared
machine-local cache in [`:data-fonts-google`](../../data/fonts/google), so a family is *the same
file* in each of them rather than three faces that merely share a name. What that cache can serve is
therefore the ceiling on what any of those lanes can draw, and it has two shapes:

- **A static instance, from the CSS API.** `fonts.googleapis.com/css2` with a legacy User-Agent is
  the only key-free way to get TrueType at all (modern UAs get WOFF2, which neither Android nor Skia
  parses). Every `format('truetype')` URL it answers with is a **baked instance** — the axes have
  already been applied and discarded. That includes the `wght@100..1000` range query, which reads
  like it should be the variable font and is not: for Roboto Flex it returns 88 KB whose table
  directory is `GDEF GPOS GSUB OS/2 STAT cmap gasp glyf head hhea hmtx loca maxp name post`, with no
  `fvar` at all. This is the right file for drawing a family at a fixed weight, and it is why every
  server-side lane's font-variation work landed correct-but-invisible: there were no axes left in
  the file to vary.
- **The variable file, from the `google/fonts` repository.** `GoogleFontSource.loadVariable` fetches
  the pre-instancing original — `METADATA.pb` names it, a variable filename carrying its axis list in
  brackets (`RobotoFlex[GRAD,…,wdth,wght].ttf`) — and verifies the bytes actually carry an `fvar`
  table before caching them. A static-only family (Lobster Two) has none, which is a miss rather than
  an error. For Roboto Flex this is 1,787,292 bytes, byte-identical to the variable file the browser
  lane is fed from the catalog's vendored manifest — the lane whose axes already worked.

So a lane wanting to *vary* a family asks for the second and a lane wanting to *draw* it at a fixed
weight asks for the first; they are different files and both are cached, the variable one under a
weight-free name because one file serves every instance. **Asking the wrong one is silent** — a
static instance has no axes to reject, so it answers every `wdth` value with the same face and the
render simply comes out flat. That is what the `java` lane did for the two variable-font specimens,
and it is worth checking first whenever axes land correct-but-invisible in a lane resolving here.

## Lane detail

### JS — vendored TypeScript player, client-side

`cssFontStackFor` (`third_party/remote-compose-player/src/web/CanvasPaintContext.ts`) maps the
built-in ids to the concrete faces Android's `fonts.xml` resolves them to — `Roboto, sans-serif`,
`"Noto Serif", serif`, `"Droid Sans Mono", monospace` — so the browser matches the baked raster
*only if the page registered those faces*. Three hosts do, all from the same vendored files: the
offline compare harness inlines them as base64 `@font-face`
(`scripts/design-artifacts/rc-fonts.mjs`), the wasm catalog dist ships them, and the `serve` viewer
serves them —
[`ServeRcFonts`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeRcFonts.kt) publishes
`/rc-fonts/fonts.css` plus the four face files out of the CLI jar, and every page with a client-side
lane (the viewer's `js` chip, the PNG↔RC comparison, a shared `/d/<id>` document) links it and
*loads* it before painting via `rc-fonts.js`. The load matters as much as the declaration: canvas
neither drives a lazy `@font-face` nor repaints when one lands, so a declared-but-unloaded face is
silently substituted for the whole first frame.

Until #3480 the viewer registered nothing, so the `js` chip drew a document's generic families in
whatever the *visitor's* machine called `sans-serif` — different outlines, a ~4% different line box
(1.17em vs 1.12em measured per 100px in headless Chromium), and no Medium face at all, so text asked
for at 500 rendered Regular — while the PNG lanes beside it used the vendored files. That was a
fidelity gap in the viewer, not in the player, and it moved with the reader. Before/after/diff off a
running server: [`evidence/serve-rc-fonts/`](evidence/serve-rc-fonts/README.md).

Named families go through `WebFonts.ts`: `google:`-prefixed names are fetched from
`https://fonts.googleapis.com/css2` (on by default; `configureWebFonts({enabled:false})` is the
switch for a CSP-restricted webview or a hermetic lane), an unprefixed name is only *named* and left
to the host. The first paint happens in the fallback face and the player repaints itself through
`onFontLoaded`, so the interactive viewer needs no font-awareness; single-shot renderers await
`player.fontsReady()` (the serve format-compare page does).

**Font-variation axes are applied, and the request shape is what makes them possible.** The paint
bundle's axes used to be parsed and skipped; they now reach the canvas. Two halves:

- *What is fetched.* The default request enumerates weights (`ital,wght@0,100;…;1,900`) and css2
  answers it with a **pinned static instance per weight** — `font-weight: 100; font-stretch: 100%`,
  no axis to vary. Asked instead for the axis *ranges* a document uses (`wdth,wght@25..151,
  100..1000`), the same API answers with a genuine variable face (`font-weight: 100 1000;
  font-stretch: 25% 151%`). The player therefore accumulates the values it sees per family — the
  three lines of a `wdth` specimen are three separate paints, each carrying one value — and asks for
  the span. A single value is left alone: css2 rejects a degenerate `wdth@25..25` with a 400, and the
  enumerated request already covers it. The enumerated stylesheet is registered either way, so a
  family with no variable face at all (Lobster Two) is unaffected.
- *What is applied.* `wght` becomes the weight in the canvas `font` shorthand, exactly. `wdth`
  becomes `ctx.fontStretch` — and **that is quantised**: canvas rejects a `font-stretch` percentage
  in both places it could take one (assigning `ctx.font = "25% 32px …"` voids the whole assignment,
  and `ctx.fontStretch = '25%'` logs *not a valid enum value of type CanvasFontStretch*), so the
  value maps to the nearest of the nine CSS keywords. `wdth 25` lands on `ultra-condensed` (50%) and
  `wdth 151` on `extra-expanded` (150%). The ramp reads correctly and is not pixel-identical to the
  lanes that instance the file directly. Every other axis — `opsz`, `GRAD`, a custom one — has no
  canvas expression at all and is dropped. This is a platform ceiling, not a decode gap.

  **The ceiling is escapable, and measured.** `ctx.fontStretch` is the only *canvas* expression of
  `wdth`, but it is not the only way to get an exact axis onto a canvas: CSS Fonts 4's
  `font-variation-settings` is also a `@font-face` **descriptor**, and Chromium honours it both in a
  stylesheet rule and as the `variationSettings` member of the `FontFace` constructor. Registering
  the same source under an alias family whose descriptor pins the axes gives a face that needs no
  canvas-side expression at all — it *is* the instance — and covers every axis, `opsz` and `GRAD`
  included. Measured on the real Roboto Flex variable face at 32px, `"Hamburg · wdth 25"`: DOM ground
  truth (`font-variation-settings:"wdth" 25`) advances **243.73px**, today's `ultra-condensed`
  quantisation **253.11px** (3.8% wide, and every outline differs), and an alias `FontFace` carrying
  `variationSettings: '"wdth" 25'` **243.73px** — exact, on attached and detached canvases alike (the
  layer canvases the player draws into are detached, which rules out the simpler trick of setting
  `style.fontVariationSettings` on the canvas element: that works, and only while the element is in
  a document). `wght` needs none of this; it is already exact through the shorthand, verified against
  the same ground truth at 100/200/400/700/900/1000 with the enumerated and range stylesheets both
  registered, the way the player registers them. Not implemented — recorded here because "platform
  ceiling" overstated it, and because the fix is a registration, not a canvas feature request.

### CMP Wasm — this repo's player, client-side

`RcComposePlayer` takes a `fontFamilies: Map<String, RcFontFaces>` and the wasm app loads it from a
host manifest — `<fontsBase>/fonts.json`, default `./fonts/` relative to the player page
(`rc-player/wasm/.../Main.kt`). `wasmPlayerDist` copies the catalog's vendored `fonts/` directory
into the dist, so a `serve` that mounts `--rc-player-wasm-dir` serves the manifest at
`/rc-player-wasm/fonts/fonts.json` and the lane has Roboto Flex (default), Noto Serif (`serif`),
Droid Sans Mono (`monospace`) plus the catalog's named faces. Embedded `FontData` is decoded per
document (`decodeInlineFonts`).

**Every manifest role is loaded**, `default` included. A default-role family is a nameable family
like any other — the catalog's own text face is `Roboto Flex` — and loading it only as "the
fallback" left that name unresolvable: the support report checks a named family against exactly the
loaded set, so a document naming the default face *failed to load* rather than rendering in it.

**Font-variation axes are applied for layout text.** A `CoreText` style carries its axes as tag/value
pairs (properties 20/21), and the player resolves them into a Compose `FontVariation.Settings`,
instancing the host's face at those axes. That needs the face's *bytes*, not a built `FontFamily` —
Compose carries variations on a `Font`, and a family's faces can no longer be re-instanced — so the
host hands the player [`RcFontFaces`](../../rc-player/compose/src/commonMain/kotlin/ee/schimke/composeai/rcplayer/compose/RcHostFonts.kt)
(bytes + weight/slant) and instances are cached per axis set. Note the instance's *identity* has to
carry the axes too: Compose's font cache keys on it, so two instances of one file sharing an identity
are the same cached typeface and a `wght` ramp would draw every line at the first weight it saw.
Canvas text ops (`DrawText…`, paint-bundle axes) still map only `wght`/`ital`/`slnt`, and the support
report says so (`font axis wdth is not implemented`) rather than dropping them silently.

**That support is why the lane failed `remote-m3`'s strict pixel gate — the gate's reference was the
side without it, and the reason was not the one first recorded here.** The gate scores each wasm
render against the catalog's **baked** PNG, and the baked PNG comes from `RemoteOverridablePreview`,
whose player defaults to `RemoteComposePlayerKind.VIEW` — the AOSP view player, i.e. the **Java** row
of the matrix above. `VariableWeightRemote` / `VariableWidthRemote` are made of nothing but axes, and
the reference drew four identical weights and three identical widths while the lane under test drew
the ramps. This document read that as the AOSP `CoreText` renderer dropping style-carried axes; it
does not. `CoreText.paintingComponent` writes them with `PaintBundle.setTextAxis`, and
`AndroidPaintContext` applies them through `FontInstance.applyVariationSettings`. The gap was in this
repo: the connector's resolver instanced the axes on the file the **CSS API** served, which is a
baked instance with no `fvar` table, so every axis value came back as the same face. Fixed by
resolving the family's variable file for the axis path (see the Java section); the two lanes agree on
these previews under the strict default.

The baked/wasm/diff images are in
[`evidence/rc-remote-m3-variable-axes/`](evidence/rc-remote-m3-variable-axes/README.md), reproduced
from this repo — `composePreviewRenderAll` for the baked side, `wasmPlayerDist` for the other, each
`.rc` sidecar scored against the baked PNG beside it — at **1.08% / 1.82% / 2.80%**, the same three
numbers run 31209547232 reported, to two decimals. No catalog bundle is involved, so it is a
practical local check rather than a re-run of the job.

The third preview in that group is **not** part of this. `TypefaceSpecimenRemote` carries no axes,
both lanes resolve all four named families to the same faces, and its 1.08% diff is a pure outline
halo — glyph edges, no displacement, no substituted face. It is the `WatchScreenRemote` class of
residual, reading high only because four lines of 22sp display text on an empty 640×480 are almost
all edge. Three failures on one lane looked like one cause and were two.

Closing the axis pair turned out to be one line of file selection in the connector's resolver, not
upstream plumbing: `applyVariationSettings` was already wired end to end, it was simply rebuilding
the face from a file with no axes left in it. #3478.

There is no network fetch for a `google:` name — the prefix is stripped and looked up in the same
manifest. And unlike every other lane, an unsatisfiable family is **fatal rather than substituted**:
`composeSupportReport(...).requireFullyRenderable()` raises `custom font family X has no DataFont`
when a named family is neither in the manifest nor embedded, so the chip reports a load failure
instead of rendering in the wrong face. That is deliberate (a wrong typeface is invisible in the
output and scores inside the normal mismatch band), but it does mean the wasm lane is the one that
*fails* where the others quietly degrade.

### Java — AOSP `remote-player-view`, server-side

The default snapshot player for a Remote Compose preview on an Android backend. Typefaces come from
`DefaultTypefaceResolver` in `remote-player-core`: built-in ids map to `Typeface.DEFAULT`/`SERIF`/
`SANS_SERIF`/`MONOSPACE`; a named family is resolved by listing `/system/fonts/` and matching a
filename that *contains* the name (which the daemon's Robolectric sandbox has no directory for — it
prints `System fonts directory not found` and falls back); embedded `FontData` becomes an
`android.graphics.fonts.Font.Builder(ByteBuffer)`.

There is still no downloadable-font path *in the player*, so the connector supplies one:
[`RcGoogleFontTypefaceResolver`](../../data/remotecompose/connector/src/main/kotlin/ee/schimke/composeai/daemon/RcGoogleFontTypefaceResolver.kt)
is installed on the `RemoteComposePlayer` at both view-player call sites and serves a `google:` name
from the shared machine-local Google Fonts cache — the same `(family, weight, italic) -> File`
resolution the Robolectric downloadable-font shadow, the figma-svg embed path and the `cmp-jvm` lane
use — delegating everything else to `DefaultTypefaceResolver` so generics, `/system/fonts/` names and
inline `FontData` behave exactly as before. Nothing is installed when the render was given no cache
directory, so the lane degrades to its previous behaviour rather than failing.

Its `FontInstance` also implements `applyVariationSettings`, rebuilding the face with
`Typeface.Builder(file).setFontVariationSettings(…)` — the same thing the platform resolver does for
a `/system/fonts/` face — so a document carrying axes gets a real variable-font instance. **Which
file it rebuilds is the whole of it**, and it is the one thing this lane got wrong for longer than
the others: the base face comes from `GoogleFontSource.load`, i.e. the CSS API, and that is a *baked
instance* with no `fvar` table (see [Where the files come from](#where-the-files-come-from)). Asking
it for `wdth 25` returns a typeface identical to the base one, silently — which is why the catalog's
`wght`/`wdth` specimens drew flat here while the `cmp-android`, `cmp-jvm` and browser lanes, all of
which reach for `loadVariable`, drew the ramps. The axis instancing now resolves the family's
variable file the same way, lazily (a document with no axes never pays the ~1.7 MB) and keyed by
`(family, italic)` because one file serves every weight, falling back to the static file for a
family that has no variable face at all. A document that names axes but no `wght` also gets the
paint's weight appended as one, because the variable file's own default is 400 while the static file
encoded its weight in the bytes.

The routing above it is upstream and works: `CoreText` reads its style-carried axes (properties
20/21) and writes them into the paint bundle with `setTextAxis`, `PaintBundle` replays that as
`FONT_AXIS`, and `AndroidPaintContext.setFontVariationAxes` hands them to whatever `FontInstance` the
resolver returned. What the *stock* resolver returns for a built-in family is a `SimpleFontInstance`
whose `applyVariationSettings` is a documented no-op returning the base typeface — so a document that
varies axes on `sans-serif` rather than on a named family still draws flat in this lane, and that one
is genuinely upstream.

### CMP Android — vendored embedded player, server-side

Two different code paths, and they do not agree with each other:

- **Layout / `TextLayout` ops** go through `RcPlayerTextLayout.standardFontFamily`, which handles the
  built-in ids, a named family, and `google:` (via `GoogleFontFactory` + the GMS provider, i.e.
  Compose's downloadable-font path), plus font-variation axes.
- **Canvas `DrawText` ops** go through `RcPlayerPaint.toTextStyle`, which maps *only* the four
  built-in ids (`else -> FontFamily.Default`) and drops a named or `google:` family on the floor —
  the upstream `TODO: Support proper font family resolution (see aosp/4187117)` this module vendored
  verbatim. `PROVENANCE.md` records the same asymmetry.

So in this lane a branded typeface applies to laid-out text and not to canvas-drawn text in the same
document.

**Font-variation axes are applied for layout text, on a different file from the unvaried path.**
Compose's downloadable-font factory takes a weight and a style and has no `variationSettings`
parameter, so it can resolve a `google:` family but not vary it — the vendored `TODO: Support
variation settings for Google fonts`. Applying axes needs the face's bytes, and specifically the
*pre-instancing* file: the CSS API serves a baked static instance with no `fvar` table (see
[Where the files come from](#where-the-files-come-from) below), so there is nothing in the file the
unvaried path resolves to vary.
[`GoogleVariableFontFamilies`](../../third_party/rc-embedded-player/src/main/kotlin/ee/schimke/composeai/rcembedded/GoogleVariableFontFamilies.kt)
resolves the family's **variable** file through `GoogleFontSource.loadVariable` and builds a
`Font(File, …, variationSettings)`, which reaches
`Typeface.Builder.setFontVariationSettings` on API 26+. It is consulted only when a document
actually carries axes, so an unvaried specimen keeps the smaller static download; families are cached
per axis set, because a variable file's instances are different faces and a family-keyed cache would
hand a `wght 100` line the previous line's `wght 1000` family.

### CMP JVM — embedded player over Skiko, server-side

Built-in ids map to the multiplatform `FontFamily.SansSerif`/`Serif`/`Monospace` (layout ops) and to
a list of host family candidates skiko can match (canvas ops — skia has no notion of a generic
family, so `sans-serif`/`Helvetica`/`DejaVu Sans`/… are tried in order).

A `google:` family is **downloaded**. There is no font *provider* off Android, which is why this
lane used to substitute a local face, but there is a downloader: `GoogleFontTypefaceResolver` (in
`:third-party-rc-embedded-player-jvm`) resolves the family through `:data-fonts-google` — the same
`(family, weight, italic) -> File` machine-local cache the Robolectric downloadable-font shadow and
the figma-svg embed path use — and serves both jvm text seams from that one file: a Compose
`FontFamily` for the layout ops (`RcPlayerTextLayoutJvm`), a skiko `Typeface` for the canvas ops
(`RcPlayerTextPlatformJvm`). Sharing the cache is the point: `Orbitron` at 400 is the *same file*
here as in every other lane, not a second face that merely shares a name.

**Font-variation axes** reach the layout seam: a `CoreText` op's axis arrays become a Compose
`FontVariation.Settings`, and a request carrying any is served from the family's **variable** file
(`loadVariable`) rather than the static instance the unvaried path resolves — see
[Where the files come from](#where-the-files-come-from). The static path remains the fallback for a
family that has no variable file, and there a `wght` axis decides *which instance to fetch*, since
applying `wght 1000` to the 400 file would vary nothing.

Two things about instancing here are easy to get wrong and both were:

- The face is built through the `Font(identity, data, …)` overload with **the axes folded into the
  identity**, not `Font(file = …)`. Compose's skiko font cache keys on a font's identity and a
  `FileFont`'s identity is its path alone, so every instance of one variable file otherwise shares a
  cache entry and the first one built is handed to all of them.
- That failure is *invisible on a `wght` ramp* — weight alone can be synthesised, so the lines still
  look different — and obvious on `wdth`. Measured ink widths for the three-line `wdth` specimen:
  368 / 393 / 386 px before (no progression; the deltas are just the digits in the label) and
  329 / 393 / 438 px after. Which is why the catalog carries a width specimen at all.

Canvas text ops carry no axes in the shared paint state, so they are unaffected.

The resolver is switched on by `-Dcomposeai.fonts.cacheDir`, which `serve`'s cmp-jvm subprocess
(`RcJvmServerRenderer`) passes; without it — and on an offline miss, a failed fetch, a `device:`
family, or a bare local name — the lane keeps its previous behaviour: try the host's families, then
the default face. A substitution, never a failure. Beyond fonts, the ±1px text metrics remain the
documented parity limit of this lane (its `PROVENANCE.md` § "text"): Skia's shaping is reachable
from both targets, Android's font stack is not.

## Where the export sits

The `compose/figma-svg` export is downstream of all of this: it reads the captured family name, not
a typeface. `FigmaLayeredSvg.resolveFamily` / `embedFamily` classify that captured string, and
`FontFamily.Default` — whose `toString()` is a sentinel rather than a face name — is read as "no
family stated" at both the capture site and in the classifiers (issue #3209). The one place the
sentinel is still stringified on purpose is the theme data product's `TypographyToken.fontFamily`,
which is a display/diff value in the VS Code theming bundle ("this token does not override the
family") and never feeds a font resolver.
