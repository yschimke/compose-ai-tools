# Lottie previews

Render [Lottie](https://airbnb.io/lottie/) animation assets through the compose-preview pipeline on
the Desktop backend, via [Compottie](https://github.com/alexzhirkevich/compottie) (the KMP Lottie
runtime). Two authoring paths, both rendering with **no Android Studio**:

1. **Drop the file in — no `@Preview` needed.** A `.json` Lottie document (detected by structure) or
   a `.lottie` archive under the module's resources is discovered directly and rendered. This is the
   "just having the file is enough" path; the asset *is* the preview's intermediate representation,
   so the bundle replays it with **zero consumer bytecode** — exactly like Remote Compose /
   protolayout IR.
2. **A `@Preview` that calls `LottiePreview(...)`** when you want a *configured* frame (a fixed
   `progress`) or to compose the animation into a larger layout.

A discovered file renders **two** artefacts by default: the still PNG baseline *and* an animated,
looping APNG that sweeps the asset's own timeline — so "having the file is enough" gives you a moving
preview, not just a frozen frame. See [Animated capture](#animated-capture--the-looping-apng).

The interactive daemon / VS Code timeline-scrubbing layer (live re-render at a chosen frame), the
Android backend, and Rive are tracked as follow-ups below.

## 1. File discovery — "just having the file is enough"

Drop a Lottie asset under `src/main/resources/` (e.g. `lottie/loading.json`). The plugin's discovery
step scans the processed-resources dirs, recognises Lottie `.json` files by their `v`+`layers`
fingerprint (ordinary config JSON is ignored) and `.lottie` archives by extension, and emits a
`kind=LOTTIE` preview entry in `previews.json` — no annotation, no Kotlin. The Desktop renderer
inflates the asset via Compottie with no consumer class to reflect, and the bundle packs the asset
bytes as `ir/<id>.<ext>` + a `BundleIr(format="lottie")`, dropping any enclosing bytecode.

```
src/main/resources/lottie/loading.json   →   renders/lottie__lottie_loading.png            (still baseline)
                                          →   renders/lottie__lottie_loading_animated.png   (animated APNG, intrinsic duration)
```

Works in **both desktop/JVM and Android modules.** In an Android module the asset is still rendered
by the JVM/Compottie *desktop* renderer — the `.json`/`.lottie` is portable IR, so there is **no
Android (Robolectric) Lottie player**. Discovery scans the Android module's Java-resource source dirs
(`src/main/resources`, plus the KMP `src/commonMain/resources` / `src/androidMain/resources`), and a
dedicated `composePreviewRenderLottie` task renders just the `kind=LOTTIE` entries through
`DesktopRendererMain` on a `:renderer-desktop` classpath (the Robolectric pass skips them). On
Android those renders land in a dir disjoint from `renders/` (`lottie-renders/`) so the two render
tasks don't share an output directory — overlapping outputs would disable Gradle's build cache for
both. Put your Lottie files under `src/main/resources/` in either module type.

> **Android requirement.** The Android preview pipeline only registers for modules that depend on
> Compose preview tooling (`androidx.compose.ui:ui-tooling-preview`) — the normal case for any module
> showing previews. A *pure-asset* Android module with Lottie files but **no** Compose dependency at
> all won't register the tasks (so its assets aren't discovered). Add the preview-tooling dependency
> (or keep such assets in a module that already has Compose) to opt in.

## Animated capture — the looping APNG

The animated companion sweeps the Lottie's **intrinsic timeline** — `durationFrames / frameRate`,
so a 60-frame clip authored at 30fps plays for 2000ms — sampled at ≈25fps into a looping **APNG**
(`lottie__<id>_animated.png`). Progress is stepped `i / frameCount` (exclusive of `1.0`) so the last
frame wraps seamlessly back to the first with no end-of-cycle stutter. The window is capped at 5000ms
so a long ambient loop still yields a small artefact (truncated, not slowed). No annotation or
duration is required — *the file's own timeline is the default*.

**Why APNG, not GIF.** A discovered asset renders on a **transparent** background, and GIF carries
only 1-bit transparency: `javax.imageio`'s GIF writer thresholds every partially-transparent pixel to
fully-opaque-or-transparent, which crushes the shape's anti-aliased edge into a hard two-colour
boundary. A sub-pixel edge shift between otherwise-identical CI renders then flips whole boundary
pixels, so the committed GIF baseline churned on essentially every push. APNG is a standard PNG
container with full 8-bit alpha, so the edge survives as a stable colour blend — and, kept as a
`.png` (served `image/png`), it still autoplays inline on GitHub, the web, VS Code webviews, and the
preview server (all browser-engine surfaces).

![Lottie animated companion — GIF (2-colour, jagged, churns) vs APNG (transparent, anti-aliased, stable)](images/lottie-animated-companion.png)

Mechanically (`renderer-desktop`'s `renderLottieApng`) a single `ImageComposeScene` is held and a
snapshot-backed `progress` state is swept across it, re-`render()`ing each step — so the Compottie
parse and Skia surface allocation happen once, not once per frame. Each RGBA frame Skiko emits as a
PNG is stitched into the APNG by `renderer-desktop`'s pure-JVM `ApngEncoder`, which copies each
frame's `IDAT` verbatim (no re-quantisation, so the alpha is preserved).

Each step is captured only once its pixels have *settled*: Compottie publishes a new `progress` to
its painter from another thread, so how many render passes the handoff needs varies run to run, and
a fixed pass count let the capture keep the previous step's pixels now and then. A single duplicated
frame rewrites the whole APNG, which is why the committed baseline flapped between two byte-states
and the diff bot reported `lottie/spin.json` as changed on unrelated PRs (issue #2868). The capture
therefore re-renders until three consecutive passes encode identically (bounded at eight) instead of
trusting a pass count.

The animated capture is **optional** in discovery: if a headless env can't encode it, the missing
artefact never trips `composePreviewRenderAll`'s required-render gate — the still PNG remains the
required baseline.

**Live daemon / VS Code.** The desktop daemon's `RenderEngine` accepts `renderMode = "lottie-gif"`
(the identifier is retained for wire compatibility; alongside the default still-frame path) and
dispatches it to the same `renderLottieApng` body, so a file-discovered Lottie animates through the
live daemon — and therefore VS Code — not just the one-shot Gradle task.

## 2. `LottiePreview` helper — configured frames

A `:lottie-preview-runtime` composable helper, for when you want a `@Preview` with a fixed progress
or the animation embedded in a larger composable.

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
| **Discovery (file path)** | `PreviewDiscovery` scans the resource dirs; a Lottie `.json` (sniffed by `v`+`layers`) or `.lottie` becomes a `kind=LOTTIE` `PreviewInfo` with the asset's resource-relative path on `PreviewParams.assetPath` and dimensions read from the document's `w`/`h`. Wired on **both** the Desktop discover task (processed-resources dirs) and the Android discover task (`src/main/resources` source dirs — `androidLottieResourceDirs`). |
| **Discovery (`@Preview` path)** | Standard `@Preview` discovery of a function calling `LottiePreview(asset, progress)`. |
| **Render (still)** | Desktop renderer (`ImageComposeScene`). For `kind=LOTTIE`, `DesktopRendererMain` skips class reflection and renders the asset via `LottiePreview` directly. Compottie's `LottieComposition.parse(json)` is **synchronous**, so the composition is ready on the first composed frame — critical because the renderer captures after two `scene.render()` passes and does not pump coroutines, so the async `rememberLottieComposition` would render blank. |
| **Render (animated)** | `renderer-desktop`'s `renderLottieApng` (selected by a `_animated.png` output name in the CLI, or `renderMode="lottie-gif"` in the daemon). Holds one `ImageComposeScene`, sweeps a snapshot-backed `progress` state across the intrinsic-duration frame window, and stitches the per-frame RGBA PNGs into an APNG via `renderer-desktop`'s `ApngEncoder` (full 8-bit alpha, so the transparent-background edge stays anti-aliased and stable). |
| **Asset loading** | The asset is a classpath resource. The plugin links the consumer's processed-resources dir onto the **render** *and* **daemon** classpaths (previously only the bundle task saw it), so the asset resolves via the classloader at render time. Generic — any preview reading a classpath resource (fonts, images) benefits. |
| **Bundle** | Self-contained. A `kind=LOTTIE` preview packs the asset bytes as `ir/<id>.<ext>` + a `BundleIr(format="lottie")` and contributes **no** bytecode — the bundle replays the asset with zero consumer source, like a Remote Compose / protolayout IR. A `@Preview`-authored Lottie instead packs the asset (under module resources) + the `@Preview` bytecode into `classes/app.jar`. |

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

0. **Live daemon render of `kind=LOTTIE` (prerequisite for VS Code).** The desktop daemon's
   `RenderEngine` reflects `spec.className` and has no `kind` branch, so a *file-discovered* Lottie
   preview can render through the one-shot `composePreviewRender` task and bundles, but not yet
   through the live daemon (VS Code). Thread `kind` + `assetPath` from `PreviewManifestRouter` into
   the `RenderSpec` and add a `kind=LOTTIE` branch that inflates via `LottiePreview` — plus a
   `BundleIrReplayStore.FORMAT_LOTTIE` replay path for opening Lottie-IR bundles in the desktop
   daemon (today only the Android `RenderEngine` replays IR).
1. **Interactive timeline (daemon).** ✅ *Done (progress scrub).* `PreviewOverrides` carries a
   `lottie: LottieOverride(progress)` field (`daemon/core/.../protocol/Messages.kt`); the desktop
   `RenderEngine` provides it as `LocalLottieProgress` around the rendered content, and
   `LottiePreview` honours that override over its authored `progress` argument. So
   `renderNow.overrides.lottie.progress = 0.42` re-renders the file-discovered Lottie (or any
   `@Preview` calling `LottiePreview`) at frame 42% — no controller needed because, unlike Remote
   Compose, the override is a single scalar read at draw time rather than a bag of named values
   user code writes back. Follow-on: `marker` / `speed` fields, and a held-scene
   `interactive/setLottie` that scrubs via snapshot recomposition instead of a fresh render.
2. **Data product + VS Code scrubber.** ✅ *Done.* The desktop daemon advertises an
   `animation/lottie` metadata product (`LottieTimelineDataProductRegistry`) that reads a
   `kind=LOTTIE` preview's timeline straight from the asset — `totalFrames`, `frameRate`,
   `durationMillis`, `width`, `height` — with no render (`requiresRerender = false`). The VS Code
   panel ships a **Lottie** bundle (`lottieScrubberPresenter.ts`): a timeline slider that reads that
   metadata for its range/labels and, on drag, posts `setLottieProgress`. The scrub is **sticky per
   preview**: `LottieProgressController` remembers the last position, and `RenderEngine` re-applies it
   on any later render that carries no override (a save / warmup re-render), so the frame — and the
   slider — stay pinned instead of snapping back to frame 0. When a **live session** is up for the
   preview the panel prefers `interactive/setLottie` (the Lottie analogue of
   `interactive/setRemoteCompose`): the daemon mutates the held scene's snapshot progress state and
   the file-Lottie content reads it in its draw-time `progress` lambda, so a drag repaints the held
   scene in place — no fresh `ImageComposeScene` per tick. The daemon coalesces rapid ticks to the
   latest. Otherwise it falls back to `renderNow.overrides.lottie.progress` (the wire path from #1).
   Follow-on polish: markers, and auto-starting a held session for the duration of a scrub so the
   efficient path applies even without LIVE mode.
3. **Android modules.** ✅ *Done — via the desktop renderer, no Android player.* An Android module's
   `src/main/resources/**.{json,lottie}` assets are discovered (`androidLottieResourceDirs`) and
   rendered by the JVM/Compottie `composePreviewRenderLottie` task (the Robolectric pass skips
   `kind=LOTTIE`). Compottie has no Android variant and none is needed — the asset is portable IR.
   Worked example: [`samples/android/src/main/resources/lottie/spin.json`](../samples/android/src/main/resources/lottie/spin.json).
   (A true Robolectric Lottie *player* — rendering `@Preview { LottiePreview(...) }` inside an Android
   composition — would still need a Compottie-Android artifact, which doesn't exist; that's not this.)
4. **Rive.** Tracked separately — Rive's Kotlin runtime is Android/JNI-only with **no** JVM/Desktop
   renderer, so it needs a feasibility spike before a design (JNI under Robolectric, or Rive's newer
   Skia/WebGL path).
