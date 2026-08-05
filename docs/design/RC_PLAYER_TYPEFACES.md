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
| **JS** (vendored TS player, in-browser) | ✅ concrete stacks | ⚠️ passed to CSS; host must have the face | ✅ fetched from the Google Fonts CSS API | ❌ bytes stored, never registered |
| **CMP Wasm** (this repo's player, in-browser) | ✅ from the host manifest | ✅ if the manifest carries it | ⚠️ manifest only — no fetch | ✅ `decodeInlineFonts` |
| **CMP Wasm** — font-variation axes | ✅ layout ops (`CoreText`) | — | — | ❌ canvas ops (reported, not silent) |
| **Java** (AOSP `remote-player-view`, server-side) | ✅ framework typefaces | ⚠️ `/system/fonts/` filename scan | ❌ no provider call at all | ✅ `Font.Builder(ByteBuffer)` |
| **CMP Android** (vendored embedded player, server-side) | ✅ framework typefaces | ⚠️ `Typeface.create(name)` | ✅ `FontsContractCompat` | ❌ ignored |
| **CMP JVM** (embedded player over Skiko, server-side) | ✅ | ⚠️ host families, else nearest standard | ✅ downloaded via `GoogleFontTypefaceResolver` | ❌ ignored |

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
   `java` — which is the **default** server-side snapshot lane. `cmp-jvm` closed the same gap the
   way the view lane still could: a `TypefaceResolver`-shaped shim over the shared `GoogleFontCache`
   (see the CMP JVM section). Doing it for the view player means installing a `TypefaceResolver` on
   the `RemoteComposePlayer` that serves `google:` names from that cache and delegates everything
   else to `DefaultTypefaceResolver`.
2. **Embedded `FontData` is supported by exactly the two lanes nobody looks at first.** The Java and
   CMP Wasm lanes build a real face from the document's own bytes; the JS lane stores them
   (`RemoteContext.loadFont` keeps `{mFontData, fontBuilder: null}`) and never registers a
   `FontFace`, and neither vendored embedded player (Android or JVM) consults them at all. A
   document that ships its typeface therefore renders correctly in `java`/`cmp-wasm` and in a
   substituted face everywhere else.

## Lane detail

### JS — vendored TypeScript player, client-side

`cssFontStackFor` (`third_party/remote-compose-player/src/web/CanvasPaintContext.ts`) maps the
built-in ids to the concrete faces Android's `fonts.xml` resolves them to — `Roboto, sans-serif`,
`"Noto Serif", serif`, `"Droid Sans Mono", monospace` — so the browser matches the baked raster
*only if the page registered those faces*. The offline compare harness does exactly that
(`scripts/design-artifacts/rc-fonts.mjs` inlines the vendored files as `@font-face`), and the wasm
catalog dist ships them; **the `serve` viewer registers nothing**, so on a host without Roboto /
Noto Serif / Droid Sans Mono the `js` chip falls through to the browser's own generics while the
PNG lanes beside it use the vendored faces. That is a fidelity gap in the viewer, not in the player.

Named families go through `WebFonts.ts`: `google:`-prefixed names are fetched from
`https://fonts.googleapis.com/css2` (on by default; `configureWebFonts({enabled:false})` is the
switch for a CSP-restricted webview or a hermetic lane), an unprefixed name is only *named* and left
to the host. The first paint happens in the fallback face and the player repaints itself through
`onFontLoaded`, so the interactive viewer needs no font-awareness; single-shot renderers await
`player.fontsReady()` (the serve format-compare page does).

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
`android.graphics.fonts.Font.Builder(ByteBuffer)`. No downloadable-font path exists (finding 1).

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
