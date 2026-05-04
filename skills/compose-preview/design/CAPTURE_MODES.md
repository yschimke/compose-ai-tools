# Capture modes

Beyond a plain `@Preview`, the renderer supports multi-variant fan-out,
continuous animation capture, deterministic timeline snapshots, MCP scripted
recordings, and scrolling captures.

## Multi-preview annotations

Functions can declare multiple `@Preview` variants via meta-annotations (e.g.
`@PreviewFontScale`, `@WearPreviewDevices`, `@WearPreviewFontScales`). Each
variant appears as its own entry in `previews.json` with a unique id, so all
CLI commands address them individually — no variant index needed.

## `@AnimatedPreview`: continuous GIF capture (Android only)

Use `@AnimatedPreview` from `ee.schimke.composeai:preview-annotations` when
the goal is to capture an actual animation artifact. This is the first choice
for spinners, progress indicators, and other moving previews that should be
verified as motion rather than as several unrelated PNG snapshots.

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.CircularProgressIndicator
import ee.schimke.composeai.preview.AnimatedPreview

@Preview(
    name = "Animated Circular Progress",
    device = "id:wearos_large_round",
    showSystemUi = true,
    showBackground = true,
)
@AnimatedPreview(durationMs = 1200, frameIntervalMs = 100, showCurves = false)
@Composable
fun AnimatedCircularProgressPreview() {
    CircularProgressIndicator()
}
```

The rendered artifact is a GIF under `build/compose-previews/renders/`. For
`durationMs = 1200` and `frameIntervalMs = 100`, expect 13 frames: frame 0 plus
one frame every 100ms through 1200ms.

Use `durationMs = 0` to auto-detect finite animations. Indeterminate or
infinite animations usually need a positive `durationMs` so the renderer knows
how long to record. Set `showCurves = false` for ordinary visual regression or
agent inspection; set it to `true` when you specifically want curve diagnostics
in the output.

Add the annotations artifact to the previewed module:

```kotlin
// libs.versions.toml
[versions]
composePreviewAnnotations = "0.8.12"

[libraries]
compose-preview-annotations = {
    module = "ee.schimke.composeai:preview-annotations",
    version.ref = "composePreviewAnnotations"
}
```

```kotlin
// build.gradle.kts
implementation(libs.compose.preview.annotations)
```

Direct dependency form:

```kotlin
implementation("ee.schimke.composeai:preview-annotations:<version>")
```

If you are using a locally installed snapshot CLI/plugin, the matching
`preview-annotations:<snapshot>` artifact may not be published. Use the latest
published `preview-annotations` artifact unless you have also published the
snapshot annotations artifact locally. The renderer discovers annotations by
FQN, so a published annotations artifact can still work with a newer snapshot
renderer.

## Manual clock snapshots (Android only)

The Android renderer pauses the Compose `mainClock` and advances by a fixed
step before capture, so infinite animations
(`CircularProgressIndicator`, `rememberInfiniteTransition`, `withFrameNanos`
loops) terminate deterministically instead of hanging the idling resource.
You don't need to call `awaitIdle` or `mainClock.advanceTimeBy` yourself.

To capture one composable at multiple timeline points, stack
`@RoboComposePreviewOptions` from Roborazzi — each `ManualClockOptions`
entry becomes its own capture with a `_TIME_<ms>ms` id suffix:

```kotlin
import com.github.takahirom.roborazzi.annotations.ManualClockOptions
import com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions

@Preview(name = "Spinner", showBackground = true)
@RoboComposePreviewOptions(
    manualClockOptions = [
        ManualClockOptions(advanceTimeMillis = 0L),
        ManualClockOptions(advanceTimeMillis = 500L),
        ManualClockOptions(advanceTimeMillis = 1500L),
    ],
)
@Composable
fun SpinnerPreview() { /* … */ }
```

Requires `implementation(libs.roborazzi.annotations)` (or
`com.github.takahirom.roborazzi:roborazzi-annotations`). Each capture
appears in the CLI's `captures[]` with `advanceTimeMillis` set.

Caveats: a11y mode disables the paused clock (ATF needs live semantics), so
don't combine it with timeline fan-outs. CMP Desktop has no per-preview
clock control — pick a static frame if you need determinism.

## MCP `record_preview`: scripted/live recording

Use MCP `record_preview` when an agent needs a scripted recording with a
preview URI, daemon state, input events, or a non-GIF output such as APNG. For
an animation that does not require real interaction, a no-op pointer script is
enough to define the recording duration:

```json
{
  "uri": "compose-preview://<workspace>/_wear/ee.example.WearPreviewsKt.AnimatedCircularProgressPreview_Animated Circular Progress",
  "fps": 10,
  "scale": 1.0,
  "format": "apng",
  "events": [
    { "tMs": 0, "kind": "pointerDown", "pixelX": 227, "pixelY": 227 },
    { "tMs": 1200, "kind": "pointerUp", "pixelX": 227, "pixelY": 227 }
  ]
}
```

Scripts can also include audit/control markers. Today only `recording.probe`
is dispatched; `state.save`, `state.restore`, `lifecycle.event`, and
`preview.reload` are advertised on the daemon's `dataExtensions` as
`supported = false` roadmap entries, and `record_preview` rejects them up
front (compose-ai-tools#714).

```json
{
  "uri": "compose-preview://<workspace>/<module>/<preview>",
  "events": [
    { "tMs": 0, "kind": "click", "pixelX": 120, "pixelY": 40 },
    { "tMs": 200, "kind": "recording.probe", "label": "after-click" }
  ]
}
```

Events with the same `tMs` form a single script step. Control events in that
step are applied before the frame for that timestamp is captured, so colocate
a verification `recording.probe` with the input that should change state.

Always inspect `scriptEvents` in the metadata. Input and `recording.probe`
events may be `applied` or `unsupported` (the daemon's defense-in-depth path
for events MCP didn't reject — older MCP servers or direct daemon clients).
Non-input script event ids are namespaced and must be advertised under
`capabilities.dataExtensions[].recordingScriptEvents[]` with
`supported = true`.

The response includes `recordingId`, `mimeType`, `sizeBytes`, `frameCount`,
`durationMs`, `frameWidthPx`, `frameHeightPx`, `frames[]`, and `scriptEvents[]`.
Raw frames are also written under:

```text
<module>/build/compose-previews/daemon-recordings/frames/<recordingId>/frame-00000.png
```

## Verification tips

When ImageMagick is available, agents can verify animation output without
depending only on GIF/APNG playback:

```sh
identify build/compose-previews/renders/<preview>.gif
shasum -a 256 build/compose-previews/daemon-recordings/frames/rec-1/frame-*.png
compare -metric AE frame-00000.png frame-00006.png /tmp/frame-diff.png
```

Useful checks: frame count matches the requested duration/fps, hashes differ
across representative frames, and `compare -metric AE` reports changed pixels.

## Scrolling captures

For previews that exercise scrollable content (`LazyColumn`,
`TransformingLazyColumn`, `LazyRow`, …), add `@ScrollingPreview` from
`ee.schimke.composeai:preview-annotations`:

```kotlin
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview

@Preview(name = "End", showBackground = true)
@ScrollingPreview(modes = [ScrollMode.END])
@Composable
fun MyListEndPreview() { MyList() }

// One function → two captures. Produces `..._SCROLL_top.png` (initial
// frame) and `..._SCROLL_end.png` (scrolled to content end).
@Preview(name = "Scroll", showBackground = true)
@ScrollingPreview(modes = [ScrollMode.TOP, ScrollMode.END])
@Composable
fun MyListTopAndEndPreview() { MyList() }

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun MyListLongPreview() { MyList() }
```

Modes:

- `TOP` — initial unscrolled frame. Useful alongside END/LONG in a single
  function so a sibling preview isn't needed.
- `END` — scrolls to content end, captures one frame.
- `LONG` — stitches slices into one tall PNG covering the full scrollable
  extent. On round Wear faces the output is clipped to a capsule shape
  (half-circle top, rectangular middle, half-circle bottom).

Knobs: `maxScrollPx` caps scroll distance on END/LONG (`0` = unbounded);
`reduceMotion = true` (default) disables Wear `TransformingLazyColumn`
transforms that would otherwise vary slice-to-slice. Only vertical scrolling
is supported. `@ScrollingPreview` is Android-only.

Filenames: single-mode → plain `renders/<id>.png`; multi-mode →
`renders/<id>_SCROLL_<mode>.png`, emitted in enum order (TOP, END, LONG).
Each capture is a separate entry in the CLI's `captures[]` with `scroll`
set.
