# Splash window in motion

`:samples:android`'s `SplashAnimatedGallery.kt` (`AnimatedSplashScreenSurface` + `@AnimatedPreview`),
alongside the pre-existing stills from `SplashScreenGallery.kt` (`SplashScreenSurface`) for
comparison.

The distinction these files exist to make concrete: an `<animated-vector>` used as
`windowSplashScreenAnimatedIcon` was *already* captured in motion by the resource-preview path — a
GIF plus a keyframe filmstrip, at the drawable's intrinsic size on a bare canvas. What nothing
rendered was the icon animating **inside the splash window**, at splash proportions, over
`windowSplashScreenBackground`. The `animated-*.gif` files are that missing surface.

| file | preview | what it shows |
| --- | --- | --- |
| `animated-icon-only.gif` | `SplashAnimatedIconOnlyPreview` | the bare pulse, 1.0 → 1.15 over 800ms on `fast_out_slow_in` |
| `animated-icon-with-ring.gif` | `SplashAnimatedWithBackgroundPreview` | icon pulsing inside a **static** `windowSplashScreenIconBackgroundColor` ring |
| `animated-dark-with-branding.gif` | `SplashAnimatedDarkThemePreview` | night-mode branch with `windowSplashScreenBrandingImage` |
| `static-icon-only.png` | `SplashIconOnlyPreview` | unchanged by this work — see below |
| `static-icon-with-ring.png` | `SplashIconWithBackgroundPreview` | unchanged by this work |
| `static-dark-theme.png` | `SplashDarkThemePreview` | unchanged by this work |

The three stills are included as the *first frame* reference: `SplashIconPulse.scaleFrom` defaults to
`1f`, so frame 0 of each GIF matches the corresponding still. That is also why `SplashSurfaceLayout`
takes a **nullable** `iconScale` rather than one defaulting to `1f` — a `graphicsLayer` would promote
the icon into its own render layer and shift anti-aliasing on the masked circle, so the static path
skips the modifier entirely and these three PNGs stay byte-identical across the change.

Regenerate with:

```
./gradlew :samples:android:composePreviewRenderAll -Dcomposeai.preview.filter=SplashAnimated --rerun-tasks
```

and read `samples/android/build/compose-previews/renders/Splash*`.

## Why these captures are 12.5fps and carry no curve panel

Each frame is 945 × 2100 px — a whole-screen surface at preview density, ~7.9MB as ARGB. The encode
stage decodes **every** captured frame into memory at once
(`RobolectricRenderTest.kt`'s `frameFiles.map { FramePngReader.decode(...) }`), and with
`showCurves` on it then builds a second, taller composited list alongside it. At the 33ms default
that is 49 frames ≈ 390MB raw plus the composed set on top, which overruns the render JVM heap: the
first attempt at these captures died with `OutOfMemoryError` in `DataBufferInt.<init>`, and one
preview surfaced it indirectly as a truncated `frame_48.png` reported as "ImageIO could not read it".

`frameIntervalMs = 80` + `showCurves = false` brings it to 21 frames ≈ 167MB, which fits. This is a
workaround at the call site, not a fix: capture already streams to disk and `ScrollGifEncoder`
already streams out via `prepareWriteSequence`/`writeToSequence`, so only that intermediate decode is
eager. Making it a lazy `Sequence` would put peak memory at O(1) in frame count and let these
previews go back to the 30fps default with curves — at which point this section and the annotation
arguments it explains should both be deleted.
