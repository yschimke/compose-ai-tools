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

## The gate cannot skip itself silently

That skip is the one thing capable of retiring this gate without anyone noticing, so it is guarded
in two places — and neither alone is enough.

**Inside the build.** "Our renders are missing" reads identically whether the source set was never
materialised (fine — nothing to compare) or it was materialised and the render produced nothing (not
fine). The Layoutlib references are committed, so their presence distinguishes nothing. So the build
states which case it is: `studioParity.required` is set from the same `screenshotTestEnabled`
property that materialises the source set, and with it true, absent renders are a **failure** rather
than a skip.

**In CI, independently.** That property is derived from the `-P` flag, so it cannot catch the flag
itself being dropped — that turns the requirement off and hands the test back its skip. Nothing
inside the build *can* catch it, because a flagless run is also the legitimate local case. CI
therefore asserts the gate **compared something**, by counting the side-by-side composites it writes
per compared fixture. That consults neither the flag nor Gradle: those files exist only if a
Layoutlib reference and one of our renders were actually put next to each other.

A single fixture that stops rendering was already a failure (`our renderer produced no PNG`). These
two cover the wholesale case, which short-circuits the test before that check runs.

Verified rather than reasoned about: with the fixtures' PNGs moved aside and the flag on, the gate
fails at the assertion; with the flag off, it still skips.

## Why sizes are pinned instead of compared loosely

Text rasterises differently in Layoutlib (native Skia + platform fonts) than under our Robolectric
renderer, so byte-equality is unreachable — the matching previews still differ on ~0.1–0.4% of
pixels. The gate therefore compares the *fraction* of differing pixels, which cleanly separates
anti-aliasing noise from real divergence (the live ones below sit at 20–35%).

Both engines' expected sizes are written down per preview. Where they agree that pins the parity;
where they disagree the divergence is recorded with its cause, so closing one **fails** the test
and forces the table to be updated in the same change.

## Known divergences

None currently pinned. The previous fixed-frame constraint, half-pixel rounding, and round-device
mask divergences are covered by the parity table and sample matrix tests.

Adding a fixture without a matching expectation entry fails the gate, so a new mode can't be
rendered by both engines and compared by neither.
