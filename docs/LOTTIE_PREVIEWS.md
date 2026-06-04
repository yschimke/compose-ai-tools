# Lottie previews

Render [Lottie](https://airbnb.io/lottie/) animation assets through the compose-preview pipeline.
This is the first slice of the "Lottie & Rive previews end to end" work — it covers authoring,
discovery, the Desktop render path, and self-contained bundles (**asset + configured values**). The
interactive daemon / VS Code timeline-scrubbing layer and the Android + Rive backends are tracked as
follow-ups below.

## What's implemented

A `:lottie-preview-runtime` composable helper, rendered through the existing Desktop renderer via
[Compottie](https://github.com/alexzhirkevich/compottie) (the KMP Lottie runtime).

```kotlin
import ee.schimke.composeai.preview.lottie.LottiePreview

@Preview
@Composable
fun LoadingStartPreview() {
  // `lottie/loading.json` lives under src/main/resources/
  LottiePreview(asset = "lottie/loading.json", progress = 0f, modifier = Modifier.size(200.dp))
}

@Preview
@Composable
fun LoadingMidPreview() {
  LottiePreview(asset = "lottie/loading.json", progress = 0.5f, modifier = Modifier.size(200.dp))
}
```

Each `@Preview` captures a deterministic frame at the configured `progress` (`0f`..`1f`). Fan
multiple `@Preview`s at different `progress` values to review keyframes. Worked example:
[`samples/cmp`](../samples/cmp/src/main/kotlin/com/example/samplecmp/LottiePreviews.kt) +
[`samples/cmp/src/main/resources/lottie/spin.json`](../samples/cmp/src/main/resources/lottie/spin.json).

## How it fits the pipeline

| Stage | Mechanism |
| --- | --- |
| **Authoring** | `LottiePreview(asset, progress)` in [`:lottie-preview-runtime`](../runtimes/lottie) — a standalone JVM/Compose helper, sibling to `:splash-preview-runtime` / `:notification-preview-runtime`. No dependency on the renderer. |
| **Discovery** | Standard `@Preview` discovery — no new annotation or discovery strategy. |
| **Render** | Desktop renderer (`ImageComposeScene`). Compottie's `LottieComposition.parse(json)` is **synchronous**, so the composition is ready on the first composed frame — critical because the renderer captures after two `scene.render()` passes and does not pump coroutines, so the async `rememberLottieComposition` would render blank. |
| **Asset loading** | The asset is a classpath resource (`src/main/resources/lottie/…`). The preview plugin now links the consumer's processed-resources dir onto the **render** classpath (previously only the *bundle* task saw it), so `LottiePreview` resolves the asset via the classloader at render time. This is a generic fix — any preview reading a classpath resource (fonts, images) benefits. |
| **Bundle** | Self-contained: the asset (`lottie/spin.json`) and the `@Preview` bytecode carrying the configured `progress` literals are both packed into `classes/app.jar`, alongside the rendered PNGs. A bundle replay re-runs the same code against the same asset. |

### Compottie version pin

Pinned to **2.1.0** in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml): it is built
against JetBrains Compose foundation **1.10.1** (same minor as our `compose-multiplatform = 1.10.3`).
2.2.x jumps to foundation 1.11.0, which would drag a *newer* Compose onto the renderer classpath —
the failure direction [`RENDERER_COMPATIBILITY.md`](RENDERER_COMPATIBILITY.md) warns about. Bump only
in lockstep with `compose-multiplatform`.

## Follow-ups

The remaining "end to end ... via Daemon interactively, and also in VS Code" surface, plus the other
backends. The blueprint for each is the existing **Remote Compose** feature
(`data/remotecompose/`), which already does asset-byte + named-value overrides + a data product +
interactive editing + a VS Code presenter.

1. **Interactive timeline (daemon).** Add a `lottie` field to `PreviewOverrides`
   (`daemon/core/.../protocol/Messages.kt`) carrying `progress` (and later `marker`/`speed`), a
   `LottieController` process-static holder + a `LottieOverrideExtension`
   (`DataExtension<PreviewOverrides>`) mirroring `RemoteComposeController` /
   `RemoteComposeOverrideExtension`, so `renderNow.overrides.lottie.progress` re-renders at a new
   frame. `LottiePreview` reads the controller's progress when present, falling back to its param.
2. **Data product + VS Code scrubber.** A `animation/lottie` `DataProductRegistry` surfacing the
   composition's frame count / duration / markers, plus a `lottieBundlePresenter.ts` with a progress
   slider that posts `interactive/setLottie` (mirrors `remoteComposeBundlePresenter.ts` +
   `interactive/setRemoteCompose`). The existing `@AnimatedPreview` GIF path can also emit a scrubbed
   animation.
3. **Android backend.** A Robolectric render path (Compottie has an Android variant) so Lottie
   previews work in `:samples:android`, sibling to the Android-only runtime modules.
4. **Rive.** Tracked separately — Rive's Kotlin runtime is Android/JNI-only with **no** JVM/Desktop
   renderer, so it needs a feasibility spike before a design (JNI under Robolectric, or Rive's newer
   Skia/WebGL path).
