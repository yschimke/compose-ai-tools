# Android Studio parity

"Do our renders match what Android Studio shows?" is now a test, not a belief.

## How the reference is produced

Studio's preview pane draws through **Layoutlib**. Google ships that same engine
headlessly in the `com.android.compose.screenshot` Gradle plugin, so the reference images are
generated rather than hand-captured — no IDE, no GUI, no screenshot that silently goes stale.

- Fixtures: [`StudioParityMatrix.kt`](../samples/android-screenshot-test/src/screenshotTest/kotlin/com/example/sampleandroidscreenshot/StudioParityMatrix.kt)
  — one `@Preview` per mode, mirroring `:samples:android`'s `PreviewModeMatrix`.
- References: committed under `samples/android-screenshot-test/src/screenshotTestDebug/reference/`,
  which is the screenshot plugin's own convention. Because they are in the repo, the gate runs
  anywhere without regenerating anything.
- Gate: [`StudioParityTest`](../samples/android-screenshot-test/src/test/kotlin/com/example/sampleandroidscreenshot/StudioParityTest.kt)
  pairs each reference with our renderer's PNG, pins **both** engines' sizes exactly, and compares
  pixels where the sizes agree. It also writes side-by-side composites to
  `build/studio-parity/` (uploaded as a CI artifact).

Every `@Preview` needs `@PreviewTest` on it as well: from alpha15 the screenshot plugin discovers
nothing without that marker and the task fails with "did not discover any tests".

## Commands

```bash
# regenerate the Layoutlib references (after changing a fixture) — writes into src/, commit them
./gradlew -Pandroid.experimental.enableScreenshotTest=true \
  :samples:android-screenshot-test:updateDebugScreenshotTest

# run the parity gate (renders ours first, then diffs)
./gradlew -Pandroid.experimental.enableScreenshotTest=true \
  :samples:android-screenshot-test:testDebugUnitTest --tests "*StudioParityTest*"
```

The `-P` flag is required in both cases: it materialises the `screenshotTest` source set. Without
it the fixtures don't compile into the build and the test skips itself.

## Why sizes are pinned instead of compared loosely

Text rasterises differently in Layoutlib (native Skia + platform fonts) than under our Robolectric
renderer, so byte-equality is unreachable — the matching previews still differ on ~0.1–0.4% of
pixels. The gate therefore compares the *fraction* of differing pixels, which cleanly separates
anti-aliasing noise from real divergence (the live ones below sit at 20–35%).

Both engines' expected sizes are written down per preview. Where they agree that pins the parity;
where they disagree the divergence is recorded with its cause, so closing one **fails** the test
and forces the table to be updated in the same change.

## Known divergences

| # | What | Where it shows | Issue |
|---|---|---|---|
| 1 | **Half-pixel rounding.** A fixed axis is `ceil(dp × density)` in Layoutlib and `floor(...)` for us, so any axis landing on a half pixel is 1px short — e.g. `heightDp = 100` at 2.625× is 263 vs our 262. Whole-pixel device frames agree exactly. | 5 previews | [#3095](https://github.com/yschimke/compose-ai-tools/issues/3095) |
| 2 | **Fixed-frame constraints.** Layoutlib measures the composable with *tight* constraints equal to the frame, so a `Modifier.size` child (a preferred size) stretches to fill it. We measure loose and letterbox the child against the harness background. Same canvas, ~35% of pixels differ. | `ParityFixedPreview`, `ParityFixedWidthPreview` | [#3096](https://github.com/yschimke/compose-ai-tools/issues/3096) |
| 3 | **Round device mask.** Layoutlib clips an `isRound` wear device to its circle, leaving the corners transparent. We render the full square. | `ParityWearDevicePreview` | [#3097](https://github.com/yschimke/compose-ai-tools/issues/3097) |

Adding a fixture without a matching expectation entry fails the gate, so a new mode can't be
rendered by both engines and compared by neither.
