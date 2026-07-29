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

_(none yet — initial snapshot)_

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
