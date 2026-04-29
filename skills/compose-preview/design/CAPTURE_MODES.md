# Capture modes

Beyond a plain `@Preview`, the renderer supports multi-variant fan-out,
deterministic animation captures, and scrolling captures.

## Multi-preview annotations

Functions can declare multiple `@Preview` variants via meta-annotations (e.g.
`@PreviewFontScale`, `@WearPreviewDevices`, `@WearPreviewFontScales`). Each
variant appears as its own entry in `previews.json` with a unique id, so all
CLI commands address them individually — no variant index needed.

## Animations and the paused frame clock (Android only)

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
