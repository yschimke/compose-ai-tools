# How compose-preview works

End-to-end view of how a `@Preview` composable becomes a PNG. For the
contributor-oriented architecture map (which class lives where, why each
backend made the choices it made), see [AGENTS.md](AGENTS.md#architecture).

## Discovery

Scan compiled class files for `@Preview` annotations →
`build/compose-previews/previews.json`.

```
For each method in each compiled class:

  1. Check for direct @Preview or @Preview.Container annotations on the method.
     If found, extract preview parameters (name, device, dimensions, backgroundColor, etc.)
     and emit a preview entry.

  2. Otherwise, walk the method's annotations looking for multi-preview meta-annotations.
     For each annotation, check whether *its* annotation class carries @Preview.
     Recurse through meta-annotations (with cycle detection via a visited set).
     Emit a preview entry for each @Preview found transitively.

Deduplicate by fully-qualified name + preview name + device + dimensions.
```

## Rendering (Desktop)

Launch a subprocess with the module's full classpath plus the
`renderer-desktop` module.

```
1. Load the target class by name and resolve the composable function
   via the Compose runtime's reflection API.

2. Create a headless ImageComposeScene at the target dimensions (2x density).

3. Set the scene content to: a background fill (from the @Preview annotation's
   backgroundColor), with the composable function invoked inside it.
   LocalInspectionMode is enabled so preview-aware composables render correctly.

4. Render two frames (the second allows animations and effects to settle).

5. Encode the Skia surface to PNG and write to the output file.
```

## Rendering (Android)

Launch a Gradle `Test` task inside a Robolectric sandbox with native
graphics (`graphicsMode=NATIVE`, `pixelCopyRenderMode=hardware`).

```
1. Bootstrap a ComponentActivity through `createAndroidComposeRule`.
   Apply the @Preview qualifiers (size, density, locale, uiMode, round,
   orientation) via `RuntimeEnvironment.setQualifiers` and `setFontScale`.

2. Set the activity content to a background fill + reflected composable
   invocation, with `LocalInspectionMode = true`.

3. Pause Compose's main clock (`autoAdvance = false`) and step it by a
   fixed amount so infinite animations terminate deterministically instead
   of hanging the idling resource.

4. Capture the root view via `captureRoboImage`, which routes ShadowPixelCopy
   through HardwareRenderer + ImageReader to replay Compose's RenderNodes,
   compress as PNG, write to file.
```

### Settling a preview that arrives late

The mirror image of the section below: not "freeze this transition part-way"
but "wait until it has finished". A component whose content is driven in by
*time* rather than by a gesture — Wear's `ConfirmationDialogContent`, which
starts its children at `alpha = 0` and animates them in from a
`LaunchedEffect` after a delay, or a field whose value is written after the
first composition — is captured at its first frame by both renderers' default
advance, so the published still is an empty container or a resting label
sitting on top of its own value (issue #4202).

`@SettledPreview` moves the shutter past it:

```kotlin
@SettledPreview                  // advance until quiescent, up to 1000ms
@SettledPreview(afterMs = 600)   // or: advance exactly 600ms, then capture
```

It applies to **still** captures only — a scroll drive runs its own settle, an
`@AnimatedPreview` GIF and an `@InteractionPreview` recording drive the clock
themselves, and an explicit `advanceTimeMillis` is a snapshot of a coordinate
the author chose. It also targets `ANNOTATION_CLASS`, so a catalog whose
stickers all wrap stock design-system composables can hoist it once onto its
own multi-preview annotation instead of hunting for the affected components:
the animation is usually internal to the component, and a sticker that merely
calls `DatePicker()` has no way to know a label tween is running inside it.

The two lanes reach the settled frame differently, and the difference is worth
knowing:

* **Android (Robolectric)** advances `mainClock` to `CAPTURE_ADVANCE_MS +
  window`. `advanceTimeBy` dispatches a frame per 16ms step, so the reveal
  actually runs rather than being teleported past. Expressed as a clock
  coordinate rather than an extra advance, so several settled captures on one
  composition settle once between them.
* **Desktop (`ImageComposeScene`)** needs more than a raised frame clock:
  `scene.render(nanoTime)` drives Compose's clock but not
  `kotlinx.coroutines.delay`, which on the scene's default context resolves
  against **wall time** — so a reveal gated behind `delay(200)` never arrives
  inside a render that takes microseconds. Settled captures therefore run on
  `DesktopSettleClock`, a dispatcher that owns the delay queue and puts it on
  the same virtual timeline as the frame clock. Because it owns that queue it
  can also answer the question pixels can't — *is something still scheduled?* —
  so auto mode stops at the reveal's end (nothing scheduled, nothing
  invalidated) instead of always paying the full window. Every unsettled still
  keeps the scene's default context, and its bytes.

Sampling pixels alone cannot decide this, which is why the existing
quiescence probe doesn't already cover it: a reveal that hasn't started yet is
pixel-identical to a settled one, so a frame-to-frame comparison latches onto
the empty container and calls it stable. Making that probe's *default* safer
for authors who never think to reach for an annotation — not arming its fast
path until the first observed frame delta, and surfacing an exhausted sample
budget somewhere a consumer's build can fail on — is issue #4239.

An animation with no end can't quiesce, so it captures at the `maxMs` bound —
the annotation belongs on a reveal, not on a spinner.

**Lane coverage.** The batch render honours the settle on both backends, and
the **Android daemon** (`compose-preview serve` against a Robolectric module)
folds the window into the same `captureAdvanceMs` base the batch renderer uses,
so a live Wear/Android frame agrees with the published PNG. The **desktop
daemon** does not yet: it holds a frame cursor across the two `render()` calls
of a capture and reuses it for the interactive session, so installing a settle
clock there is a change to that cursor's contract rather than a change to one
render — tracked in issue #4238. A settled CMP preview served live therefore
still shows its first frame; its published PNG is settled.

### Freezing a preview at an intermediate animation frame

The paused clock is the only deterministic handle on "part-way through". A
preview that wants a specific point in a transition should pin the *capture
instant* and size its animations against it:

```kotlin
@Preview(name = "Filmstrip")
@RoboComposePreviewOptions(manualClockOptions = [ManualClockOptions(advanceTimeMillis = 600L)])
```

A capture with an explicit `advanceTimeMillis` is an exact snapshot: the
renderer advances the paused clock to that virtual time, captures once, and
skips the adaptive pixel-quiescence probe it runs for ordinary stills. So a
`tween(durationMillis = d)` read at `t` is at `t / d` of its travel, every run,
on every machine.

What does **not** work is asking the transition where it is by fraction.
`SeekableTransitionState.seekTo(fraction)` seeks a fraction of
`Transition.totalDurationNanos`, and that total is the max over the child
animations registered *so far* — a set that shared-element transitions keep
growing, and that seeking itself adds initial-value animations to. Measured on
`SharedElementFilmstripPreview` with every spec pinned to a fixed `tween`, its
five panels still reported totals of 600/787/1050/1387/1800ms, two of them
moving again between consecutive frames and differently on each run. The
fraction was exact; the duration it was a fraction of was not, so the preview
re-rendered differently on PRs that could not affect it (issue #4097). Sweeping
a fraction with `animateTo` is fine — `NowPlayingContainerTransformPreview`
does, and it renders byte-identically — because it ends where it aimed and the
frames in between are frames of an animation, not claims about a fraction.

Two smaller rules come with the recipe, both learned the same way:

* **Give the start pose one frame before flipping the target state.** Shared
  elements need a laid-out "from" to animate out of; flip in the first
  composition and they snap straight to the target, so every panel renders at
  ~100% no matter what it is labelled.
* **Pin every spec, including the defaults you did not write.** `fadeIn()` and
  `AnimatedContent`'s default `SizeTransform()` are springs, and a spring's
  duration is estimated from the distance it happens to see — one stray default
  puts one animation on a different timeline from the rest.

## Caching

Both discovery and rendering are Gradle-cacheable tasks with declared
input/output contracts. Unchanged source files produce no re-work on
subsequent runs. Configuration caching is strict (`problems=fail`).

## Plugin configuration

Apply the plugin to a module (see
[`samples/android/build.gradle.kts`](../samples/android/build.gradle.kts),
[`samples/wear/build.gradle.kts`](../samples/wear/build.gradle.kts), or
[`samples/cmp/build.gradle.kts`](../samples/cmp/build.gradle.kts) for
working examples):

```kotlin
composePreview {
    variant.set("debug")     // Android build variant (default: "debug")
    sdkVersion.set(35)       // Robolectric SDK version (default: 35)
    enabled.set(true)        // disable to skip registration (default: true)
}
```

### `hostTheme` — the Android theme previews are hosted under

Previews render inside a bare `ComponentActivity`. Compose doesn't read the
platform theme, so this rarely matters — until a preview hosts an `AndroidView`.
A platform `View` resolves its default style through *theme attributes*, and an
app-owned one (`?attr/primaryText` inside a `TextAppearance`, say) that the host
activity's theme doesn't define throws
`UnsupportedOperationException: Failed to resolve attribute at index N` at
inflation. That escapes composition, so the render aborts and the preview
produces **no PNG at all** — the render writes a `<png>.error.json` instead.

An **application** module needs no configuration: the host activity inherits
`<application android:theme="…">` from the merged manifest. A **library** module
has no application theme to inherit, so name one:

```kotlin
composePreview {
    hostTheme.set("@style/Theme.MyDesignSystem")
}
```

Also accepts `com.example:style/Theme.Foo` or a bare `Theme.Foo`. Override for a
single run with `-PcomposePreview.hostTheme=…` or `-Dcomposeai.render.hostTheme=…`.
A name that resolves to nothing logs a warning and leaves the platform default in
place rather than failing the build. Android Studio's preview pane has a theme
picker for the same reason; this is its build-time equivalent. See
[`samples/android-library`](../samples/android-library) for a worked fixture.

When `enabled = false`, the plugin skips registering the preview tasks
(`composePreviewDiscover` / render / daemon-start) but still writes the
`build/compose-previews/applied.json` marker (carrying `enabled: false`). The
VS Code extension reads that marker: the module stays **visible** in discovery
and the doctor report, but the extension never **schedules** a preview task for
it — so opening or saving a file in a disabled module no longer produces a
"task not found" failure. (This is the "keep but flag" choice from #2016:
`ModuleInfo.enabled` gates scheduling in `GradleService`, leaving visibility
intact. A missing `enabled` — legacy markers, scan-detected modules — is
treated as enabled.)

## Project structure

| Module | Purpose |
|--------|---------|
| `gradle-plugin/` | Gradle plugin — discovery, rendering task orchestration |
| `renderers/desktop/` | Desktop renderer — `ImageComposeScene` + Skia PNG capture |
| `renderers/android/` | Android renderer — Robolectric harness |
| `api/preview-annotations/` | Shared annotations consumed by samples (`@ScrollingPreview`, etc.) |
| `cli/` | CLI — Tooling-API driver over `composePreviewDiscover` / `composePreviewRenderAll` |
| `vscode-extension/` | VS Code extension that surfaces rendered previews in the editor |
| `samples/android/` | Android sample with colored box `@Preview` composables |
| `samples/android-library/` | Android library variant — exercises AAR class-jar discovery |
| `samples/android-screenshot-test/` | Co-existence with `com.android.compose.screenshot` |
| `samples/wear/` | Wear OS sample — Material 3 Expressive, `EdgeButton`, tile previews |
| `samples/cmp/` | CMP Desktop sample with colored box `@Preview` composables |
| `samples/remotecompose/` | Remote Compose sample — wrapper-inside-Composable vs. `@PreviewWrapper(RemotePreviewWrapper::class)` against `wear-compose-remote-material3` |

## Requirements

Consumer (what your project needs to apply the plugin):

- Gradle 8.13+ (enforced at apply-time by
  [`GradleVersionCheck`](../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/GradleVersionCheck.kt))
- Java 17 or newer (renderer / plugin target JDK 17 bytecode; any newer JDK runs them)
- AGP 8.13.0+ (Android projects)
- Kotlin 2.0.21+ (the published-API floor — `kotlinCoreLibraries` in
  [`gradle/libs.versions.toml`](../gradle/libs.versions.toml), enforced
  by tapmoc on every plugin/renderer build)
- Compose Multiplatform 1.10.3+ (Desktop projects)

The bottom edge is exercised end-to-end on every push to `main` (plus the
nightly cron) by the `agp8-min` job in
[`.github/workflows/integration.yml`](../.github/workflows/integration.yml)
against the fixture under
[`.github/ci/fixtures/agp8-min/`](../.github/ci/fixtures/agp8-min/). It does
not run on pull requests — PRs run a single cheap integration cell
(`wear-os-samples (ComposeStarter)`) and everything else in that workflow
lands on `main`. The
project's own build toolchain (what contributors use) is documented in
[`AGENTS.md`](AGENTS.md) and is intentionally much newer than the
consumer floor.
