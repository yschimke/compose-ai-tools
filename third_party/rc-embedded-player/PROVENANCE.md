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

The player is *nearly* platform-agnostic — `remote-core`, which carries the document and operation
model, is a plain `java-library` upstream with no Android dependency. The Android coupling that
remains is thin and concentrated:

- `RcPlayerPaint.kt` — `android.graphics.Paint` for canvas text draws, `RuntimeShader` (AGSL) for
  shader ops, `BitmapShader`/`Shader`/`Matrix`.
- `RcPlayerDrawing.kt`, `state/RcPlayerState.kt` — `android.graphics.Bitmap`, `Rect`,
  `BitmapDrawable`.
- `EmbeddedPlayerTypefaceResolver.kt` — `android.graphics.Typeface`.
- `GraphContext.kt`, `RcPlayer.kt` — `AndroidRemoteContext` as the platform `RemoteContext`.

Splitting those behind `expect`/`actual` gives a `jvm` target that renders through Compose Desktop's
Skia backend, which is what lets the `rc-compare` lane rasterize `.rc` documents headlessly **without
Robolectric**. Text has to move to Compose's own `TextMeasurer`/`DrawScope` primitives rather than a
framework `Paint`, and AGSL has no JVM equivalent (desktop Compose exposes SkSL `RuntimeEffect`), so
shader parity across the two targets will not be exact.
