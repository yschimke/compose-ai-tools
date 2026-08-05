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
| **Java** (AOSP `remote-player-view`, server-side) | ✅ framework typefaces | ⚠️ `/system/fonts/` filename scan | ❌ no provider call at all | ✅ `Font.Builder(ByteBuffer)` |
| **CMP Android** (vendored embedded player, server-side) | ✅ framework typefaces | ⚠️ `Typeface.create(name)` | ✅ `FontsContractCompat` | ❌ ignored |
| **CMP JVM** (embedded player over Skiko, server-side) | ✅ | ❌ nearest standard family | ❌ nearest standard family | ❌ ignored |

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
   So the branded face appears under `cmp-android` and `js`, and silently does not under `java` —
   which is the **default** server-side snapshot lane. Fixing it means giving the view player a
   `TypefaceResolver` backed by the same `GoogleFontCache`, which is the follow-up
   `third_party/remote-compose-player/PROVENANCE.md` already names.
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

`RcComposePlayer` takes a `fontFamilies: Map<String, FontFamily>` and the wasm app loads it from a
host manifest — `<fontsBase>/fonts.json`, default `./fonts/` relative to the player page
(`rc-player/wasm/.../Main.kt`). `wasmPlayerDist` copies the catalog's vendored `fonts/` directory
into the dist, so a `serve` that mounts `--rc-player-wasm-dir` serves the manifest at
`/rc-player-wasm/fonts/fonts.json` and the lane has Roboto Flex (default), Noto Serif (`serif`),
Droid Sans Mono (`monospace`) plus the catalog's named faces. Embedded `FontData` is decoded per
document (`decodeInlineFonts`).

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

`RcPlayerTextLayoutJvm.standardFontFamily` maps the built-in ids to the multiplatform
`FontFamily.SansSerif`/`Serif`/`Monospace` and folds everything else — named, `device:`, `google:` —
to the nearest standard family. This is the documented parity limit of the jvm lane (its
`PROVENANCE.md` § "downloadable fonts"), not an oversight: the Android font stack is not reachable
off Android, and the lane exists to prove the *player* is portable, not the platform's fonts.

## Where the export sits

The `compose/figma-svg` export is downstream of all of this: it reads the captured family name, not
a typeface. `FigmaLayeredSvg.resolveFamily` / `embedFamily` classify that captured string, and
`FontFamily.Default` — whose `toString()` is a sentinel rather than a face name — is read as "no
family stated" at both the capture site and in the classifiers (issue #3209). The one place the
sentinel is still stringified on purpose is the theme data product's `TypographyToken.fontFamily`,
which is a display/diff value in the VS Code theming bundle ("this token does not override the
family") and never feeds a font resolver.
