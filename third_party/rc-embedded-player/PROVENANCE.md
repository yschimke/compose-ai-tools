# Vendored: `rc-embedded-player` (AndroidX experimental Compose embedded Remote Compose player)

`RcPlayer` — a **pure-Compose interpreter** for a Remote Compose `CoreDocument`. It walks the
document's operation tree and emits Compose layout and draw nodes directly, rather than painting to
a framework `Canvas` inside an Android `View`.

That contrast is the reason we vendor it. The player compose-ai-tools uses today
(`RemoteComposeIrReplay` → `androidx.compose.remote.player.compose.RemoteDocumentPlayer`) is backed
by `remote-player-view`'s `RemoteComposePlayer`, an Android `View` bridged into Compose via
`AndroidView`. The embedded player is what a host embedding Remote Compose content *inside* a Compose
tree actually gets — different layout, text, and draw code, and therefore different pixels. Having
both lets `rc-compare` diff them against the same baked PNG.

## Upstream

- Repository: <https://github.com/androidx/androidx>
- Path: `compose/remote/integration-tests/player-compose-embedded/src/main/java/androidx/compose/remote/player/compose/embedded`
- Commit: `c8e7d738d7c76df3a87281ba8c3b880622df6282` (`androidx-main`, 2026-07-29)
- License: Apache-2.0

There is **no published artifact** for this player. Upstream declares the module as
`SoftwareType.TEST_APPLICATION` — an integration-test app — so the player ships only as sources
inside it. Vendoring is the only way to depend on it.

## What is vendored

The player proper: the package root plus `layout/`, `modifier/`, and `state/` (42 files). Upstream's
`demos/`, `integration/previews/`, and the `androidx.wear.compose.remote.material3.previews` sample
previews that live in the same source set are **not** vendored — they are demo/test scaffolding for
the integration-test app, and they drag in Wear Material3 and `remote-creation-compose` capture.

Package names are kept verbatim (`androidx.compose.remote.player.compose.embedded`) so refreshing
the snapshot against a newer androidx checkout is a plain `diff -r` with no rename noise.

## Copyright

**Every source file in this module carries the AOSP Apache-2.0 header, without exception.** That
holds for the three kinds of file here:

- the vendored player sources, which keep upstream's header verbatim — a refresh must not strip it,
  and the local-delta comments below sit *inside* those files rather than replacing their headers;
- `src/main/res/values/font_certs.xml`, copied verbatim from androidx with its own 2022 header;
- the files written here (`build.gradle.kts`, `src/test/.../RcEmbeddedRenderHarness.kt`), which carry
  the same header because they exist only to build and exercise AOSP-derived code in an AOSP package.

Check before committing a refresh:

```sh
for f in $(find src -type f \( -name '*.kt' -o -name '*.xml' \)) build.gradle.kts; do
  head -6 "$f" | grep -q 'The Android Open Source Project' || echo "MISSING: $f"
done
```

## Version skew

Upstream builds this player against the **in-tree** `remote-core` / `remote-player-core`. We build
it against the published alphas the version catalog pins (`compose-remote = 1.0.0-alpha15`). The
player reaches a number of `@RestrictTo(LIBRARY_GROUP)` members, and `CoreDataAccessors.kt` reaches
private `CoreDocument` state **reflectively** (upstream guards those names with its own
`CoreReflectionGuardTest`). Both are sensitive to the gap between `androidx-main` and alpha15, so a
snapshot refresh should be paired with a render of the `rc-compare` lane, not just a compile.

## Local modifications

See the `rc-embedded` column of the catalogs' `rc-compare.html` for the current visual delta against
the baked PNG. Local deltas over the upstream snapshot are listed here as they are made, each with
the upstream tracking issue it was reported under.

Each delta below is a **build-against-published-alpha** gap, not a rendering fix: upstream compiles
this player against the in-tree `remote-creation-compose`, where these symbols are public and
present. They are grouped in the tracking issue as "the embedded player cannot be built outside the
androidx tree against the published alphas".

- **Named-action dispatch for `LambdaAction` / `PendingIntentAction` dropped** (`RcPlayer.kt`, the
  `LocalRemoteNamedActionHandler` block). `LambdaAction` does not exist in
  `remote-creation-compose:1.0.0-alpha15`, and `PendingIntentAction` is `internal` there, so
  `parseId` is not callable from outside the module. The handler now forwards straight to
  `onNamedAction`. Both paths are *interactive click dispatch*; the render lane never fires an
  action, so nothing the comparison measures is affected.
- **`CapturedDocument` lambda/pending-intent forwarding dropped** (`RcPlayer.kt`, the
  `CapturedDocument` overload). `CapturedDocument` in alpha15 carries neither a `lambdas` nor a
  `pendingIntents` property. Same reasoning; that overload is for live capture, which this vendored
  copy does not use.

Neither delta is on the draw path. If a future alpha exposes the two action types, both blocks
revert to upstream verbatim.

- **GMS font-provider certificates vendored locally** (`src/main/res/values/font_certs.xml`, and the
  `GoogleFontR` import in `EmbeddedPlayerTypefaceResolver.kt` + `RcPlayerTextLayout.kt` repointed
  from `androidx.compose.ui.text.googlefonts.R` to this module's own `R`). Upstream reads
  `com_google_android_gms_fonts_certs` off the google-fonts library's `R`, but the **published**
  `androidx.compose.ui:ui-text-google-fonts` AAR ships an empty `<resources/>` and a zero-byte
  `R.txt` — that array lives only in the library's `src/androidTest/res`, so it never reaches a
  consumer. The file is copied verbatim from
  `compose/ui/ui-text-google-fonts/src/androidTest/res/values/font_certs.xml` at the pinned commit
  (same Apache-2.0 source). Behaviour is unchanged: same certificates, same provider.

  Worth reporting upstream on its own — any out-of-tree consumer following the documented
  downloadable-fonts pattern against the published artifact hits this, not just this player.

### Not a source delta, but required to build

`androidResources` has to be enabled explicitly in `build.gradle.kts` — AGP 9 defaults it to `false`
for library modules, and the module now carries its own resource table (the certs above).

## Planned: CMP android/jvm

Goal: a `jvm` target that renders through Compose Desktop's Skia backend, so the `rc-compare` lane
rasterizes `.rc` documents headlessly **without Robolectric** — and, as a side effect, without the
software-canvas ambiguity that made the shader finding hard to attribute (see the tracking issue).

### Measured surface

**32 of the 42 vendored files reference nothing platform-specific** — no `android.*`, no
`androidx.core.*`, no `player.core.platform.*`, no `ui.text.googlefonts`. They move as-is. The
remaining 10, with what actually couples them:

| file | coupling |
| --- | --- |
| `RcPlayerPaint.kt` | `Paint`, `RuntimeShader`, `BitmapShader`, `Shader`, `Matrix`, `Build` |
| `RcPlayerDrawing.kt` | `Bitmap`, `Rect`, `drawable` |
| `RcPlayer.kt` | `SuppressLint`, `PendingIntent`, `AndroidRemoteContext` |
| `EmbeddedPlayerTypefaceResolver.kt` | `Typeface`, `Handler`, `Looper`, `Log`, `FontRequest`, `FontsContractCompat`, `AndroidRemoteContext`, `TypefaceResolver`, `FontInstance` |
| `RcImageLoader.kt` | `Bitmap`, `drawable`, `content.res` |
| `state/RcPlayerState.kt` | `Bitmap` |
| `GraphContext.kt` | extends `AndroidRemoteContext` |
| `RcPlayerParticles.kt` | `AndroidPaintContext` |
| `RcPlayerTextLayout.kt` | `googlefonts.Font`, `googlefonts.GoogleFont` |
| `DrawablePainter.kt` | `drawable.Drawable` — Android-only by definition, no jvm counterpart needed |

### The source-set shape is `jvmCommon`, not `common`

`remote-core` — the document and operation model the player reads throughout — is a plain
`java-library` upstream, **not** a multiplatform artifact. So the shared code is not
platform-agnostic; it is *JVM*-common. The layout has to be an intermediate source set both targets
depend on:

```
commonMain        (empty, or Compose-only helpers)
└── jvmCommonMain  ← the 32 clean files + the `remote-core` dependency
    ├── androidMain ← the 10 above, as today
    └── jvmMain     ← their jvm actuals
```

Putting `remote-core` in `commonMain` would not resolve. This is the single most important structural
constraint and the easiest one to get wrong.

### What a `JvmRemoteContext` actually costs

Less than the line counts suggest. `RemoteContext` (remote-core, JVM) declares **42 abstract
members**, and `AndroidRemoteContext` implements them in 669 lines — but the contract is
overwhelmingly a *variable/state store*, which is platform-neutral: `loadFloat`/`getFloat`,
`loadColor`/`getColor`, `loadText`/`getText`, `loadInteger`, `setNamed*Override` /
`clearNamed*Override`, `addCollection`, `putDataMap`/`getDataMap`, `putObject`/`getObject`,
`listensTo`, `updateOps`, `loadAnimatedFloat`, `loadPathData`/`getPathData` (plain float arrays).

Genuinely platform-bound, and short: `loadBitmap` (decode), `hapticEffect` (no-op on jvm),
`runAction`/`runNamedAction`/`addClickArea` (host callbacks), `loadShader`/`getShader` (storage of a
core `ShaderData`).

`AndroidPaintContext` is 1510 lines but is reached **only** by `RcPlayerParticles.kt` — the embedded
player draws through Compose's `DrawScope`, not the core's paint pipeline. Particles can stay
Android-only in a first cut rather than forcing a Skia `PaintContext` port.

### Known parity limits before starting

- **Text.** `RcPlayerPaint.kt` builds a framework `android.graphics.Paint` for the canvas text draw
  ops. On jvm this has to move to Compose's own `TextMeasurer` / `DrawScope`, so text metrics will
  not be bit-identical across the two targets.
- **Shaders.** AGSL has no JVM equivalent; desktop Compose exposes SkSL `RuntimeEffect`, which is
  close but not the same language or the same uniform plumbing. Shader parity across targets will not
  be exact — and note the embedded player's shader path already diverges from the View player on
  *Android* (89% on `ShaderGradientSticker`), so that wants fixing before it is used as a jvm
  baseline.
- **Downloadable fonts.** `google:`-prefixed fonts go through `FontRequest`/`FontsContractCompat`,
  which is Android-only. The jvm target needs either a bundled-font path or an explicit unsupported.

### Sequencing

1. Restructure to KMP with **only** the android target, moving the 32/10 split into
   `jvmCommonMain`/`androidMain`. No `expect`/`actual` needed yet — `androidMain` sees
   `jvmCommonMain` directly. Verify by re-running the 24-document render and confirming the numbers
   are unchanged.
2. Add the `jvm` target and the `expect`/`actual` seams, starting with `RemoteContext` and image
   decode.
3. Port text off framework `Paint`.
4. Shaders last, after the Android-side shader divergence is understood.

Step 1 is the safe, verifiable milestone: it is a pure source-set move whose success criterion is
"the existing render output does not change".
