---
name: compose-preview
description: Render Compose @Preview functions to PNG outside Android Studio. Use this to verify UI changes, iterate on designs, and compare before/after states across Android (Jetpack Compose) and Compose Multiplatform Desktop projects.
---

# Compose Preview

Render `@Preview` composables to PNG images without launching Android Studio.
Works on both Android (Jetpack Compose via Robolectric) and Compose Multiplatform
Desktop (via `ImageComposeScene` + Skia).

## What this skill provides

- A Gradle plugin (`ee.schimke.composeai.preview`) that discovers `@Preview`
  annotations from compiled classes and registers rendering tasks.
- A `compose-preview` CLI that drives the Gradle build via the Tooling API
  and surfaces rendered PNG paths.
- A VS Code extension with a preview panel, CodeLens and hover actions on
  `@Preview` functions, and commands for rendering all or a single file.

## Gradle tasks

Applied to each module that declares the plugin:

| Task | Purpose |
|------|---------|
| `:<module>:discoverPreviews` | Scan compiled classes, emit `build/compose-previews/previews.json`. |
| `:<module>:renderAllPreviews` | Discover + render every `@Preview` to PNG under `build/compose-previews/`. |

Both are Gradle-cacheable with strict configuration caching — unchanged inputs
produce no re-work.

## CLI

The CLI auto-detects the Gradle project root (walks up for `gradlew`) and, by
default, every module that has the plugin applied.

```
compose-preview <command> [options]

Commands:
  show     Discover + render previews; print id, path, sha256, changed flag
  list     List discovered previews
  render   Render previews; with --output copies a single match to disk
  a11y     Render previews and print ATF accessibility findings
  doctor   Verify Java 21 + project compatibility (run before Setup)

Options:
  --module <name>      Target a single module (default: auto-detect)
  --variant <variant>  Android build variant (default: debug)
  --filter <pattern>   Case-insensitive substring match on preview id
  --id <exact>         Exact match on preview id
  --json               Emit JSON (show, list)
  --output <path>      Copy matched preview PNG to this path (render)
  --progress           Print per-task milestone/heartbeat lines to stderr
  --verbose, -v        Full Gradle build output (implies --progress)
  --timeout <seconds>  Gradle build timeout (default: 300)
```

OSC 9;4 terminal progress (native taskbar/tab progress bar) is on by default
in a TTY and auto-disables when stdout is piped. Textual progress lines are
off by default and opt-in via `--progress`.

Exit codes: `0` success, `1` build failure, `2` render failure, `3` no previews.

JSON output per entry includes the full `PreviewParams` (device, widthDp,
heightDp, fontScale, uiMode, …), the absolute `pngPath`, the `sha256` of
the PNG bytes, and a `changed` boolean computed against the previous
invocation. State is persisted per-module under
`<module>/build/compose-previews/.cli-state.json` and gets wiped by
`./gradlew clean`.

## Workflow: iterate on a design

1. **List** previews: `compose-preview list` (optionally `--filter <name>` or
   `--id <exact>`).
2. **Render** current state: `compose-preview show --json`. Each entry includes
   the absolute `pngPath`, its `sha256`, and a `changed` flag relative to the
   previous invocation — read the PNG to view the image.
3. **Edit** the composable.
4. **Re-render**: `compose-preview show --json` again. Gradle task caching reruns
   only what changed; agents can inspect `changed: true` entries to know
   which PNGs need re-reading, avoiding wasted reads of unchanged images.
5. **Verify visually** — always read the PNG after a UI change. Don't assume
   the change looks correct.

## VS Code extension

With the `vscjava.vscode-gradle` extension installed, the Compose Preview
extension provides:

- A **preview panel** (webview) listing rendered previews for the active module.
- **CodeLens** and **hover** actions above every `@Preview` function in Kotlin
  files (re-render, open PNG).
- Commands:
  - `Compose Preview: Refresh` / `Render All`
  - `Compose Preview: Run for File` — render only previews in the active file.
- Auto-refresh on editor save (debounced) and on active-editor switch to a
  Kotlin file.

## Setup

The plugin is published to Maven Central, so no credentials, PAT, or
registry configuration is required. Most projects already have
`mavenCentral()` in their plugin repositories (AGP and the Kotlin Gradle
Plugin are both hosted there).

Run the steps in order. Step 0 is a precheck — if it fails, **stop** and
fix the environment before editing any Gradle files.

### 0. Install the CLI and run `doctor`

Bootstrap the CLI, then verify the environment:

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/install.sh | bash

compose-preview doctor
```

The install script is idempotent, pulls the latest release into
`~/.local/opt/compose-preview/<version>/`, and symlinks
`~/.local/bin/compose-preview`. If that directory isn't on your `PATH`, the
script prints the exact command to add it (`fish_add_path …` or a `PATH=`
line for bash/zsh).

`compose-preview doctor` verifies Java 21 is on `PATH` (the only hard
prereq for the plugin now that Maven Central resolution needs no auth).

### 1. Register the plugin repository (only if mavenCentral is missing)

Most Android/KMP projects already include `mavenCentral()` in
`pluginManagement.repositories`. If yours doesn't, add it.

**Kotlin DSL — `settings.gradle.kts`:**

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
```

**Groovy DSL — `settings.gradle`:**

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
```

### 2. Apply the plugin

**Kotlin DSL — `<module>/build.gradle.kts`:**

<!-- x-release-please-start-version -->
```kotlin
plugins {
    id("ee.schimke.composeai.preview") version "0.4.0"
}

composePreview {
    variant.set("debug")   // Android build variant (default: "debug")
    sdkVersion.set(35)     // Robolectric SDK version (default: 35)
    enabled.set(true)      // set false to skip task registration
}
```
<!-- x-release-please-end -->

**Groovy DSL — `<module>/build.gradle`:**

<!-- x-release-please-start-version -->
```groovy
plugins {
    id 'ee.schimke.composeai.preview' version '0.4.0'
}

composePreview {
    variant = 'debug'
    sdkVersion = 35
    enabled = true
}
```
<!-- x-release-please-end -->

CMP Desktop projects additionally need
`implementation(compose.components.uiToolingPreview)` — the bundled `@Preview`
annotation has `SOURCE` retention and is invisible to classpath scanning
otherwise.

The Android variant relies on Robolectric with native graphics; the plugin
takes care of the relevant test/tooling dependencies so you don't configure
them manually. Agents MUST NOT run internal tasks like `collectPreviewInfo` —
they're wired by the plugin itself.

## Multi-preview annotations

Functions can declare multiple `@Preview` variants via meta-annotations (e.g.
`@PreviewFontScale`, `@WearPreviewDevices`, `@WearPreviewFontScales`). Each
variant appears as its own entry in `previews.json` with a unique id, so all
CLI commands address them individually — no variant index needed.

## Animations and the paused frame clock (Android only)

The default Android renderer pauses the Compose `mainClock`, advances by
`CAPTURE_ADVANCE_MS`, then captures. That's what makes infinite animations
(`CircularProgressIndicator`, `rememberInfiniteTransition`, hand-rolled
`withFrameNanos` loops) terminate deterministically instead of hanging
Compose's idling resource — agents do **not** need to write `awaitIdle` or
`mainClock.advanceTimeBy` calls themselves.

To capture a single composable at multiple points along an animation
timeline, stack `@RoboComposePreviewOptions` from Roborazzi. Each
`ManualClockOptions(advanceTimeMillis = …)` entry fans out into its own
capture, with the suffix `_TIME_<ms>ms` appended to the preview id:

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

Add `implementation(libs.roborazzi.annotations)` (or
`com.github.takahirom.roborazzi:roborazzi-annotations`) to expose the
annotation; the metadata is source-retained but read by ClassGraph at
discovery time.

Each capture lands in `previews.json` and the CLI's `captures[]` array with
`advanceTimeMillis` set, alongside per-capture `pngPath`, `sha256`, and
`changed`. Reviewers can diff frames side-by-side without rebuilding.

Caveat: a11y mode (`composePreview.accessibilityChecks.enabled = true`)
disables the paused-clock path because ATF needs live semantics; infinite
animations tick through during capture. Don't combine ATF with timeline
fan-outs unless you're prepared for noisy diffs.

CMP Desktop calls `scene.render()` twice so `LaunchedEffect`s settle before
encode — there's no per-preview clock control there yet; pick a static frame
in your composable if you need determinism.

## Scrolling captures

For previews that exercise scrollable content (`LazyColumn`,
`TransformingLazyColumn`, `LazyRow`, …), add `@ScrollingPreview` from
`ee.schimke.composeai:preview-annotations`:

```kotlin
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview

@Preview(name = "End", showBackground = true)
@ScrollingPreview(mode = ScrollMode.END)
@Composable
fun MyListEndPreview() { MyList() }

@WearPreviewLargeRound
@ScrollingPreview(mode = ScrollMode.LONG, reduceMotion = true)
@Composable
fun MyListLongPreview() { MyList() }
```

- `ScrollMode.END` drives the scroller to its content end and captures one
  frame. Pair with a static `@Preview` of the same composable to diff the
  top and bottom states.
- `ScrollMode.LONG` stitches multiple slices into a single tall PNG covering
  the whole scrollable extent. On round Wear faces the output is clipped to a
  capsule shape — top half-circle, rectangular middle, bottom half-circle —
  so the watch edge is preserved at the first and last slices.
- `maxScrollPx` caps how far the renderer scrolls (use it on infinite or
  pathologically long scrollers; `0` means unbounded).
- `reduceMotion = true` (default) wraps the body in
  `LocalReduceMotion provides ReduceMotion(true)` — important for Wear
  `TransformingLazyColumn`, where item transforms otherwise vary slice-to-slice
  and produce noisy diffs.
- Only `ScrollAxis.VERTICAL` is rendered today.

Each scroll capture is a separate entry in the CLI's `captures[]` with
`scroll` set (`{mode: "END"}` or `{mode: "LONG", index, total, heightPx, …}`).

`@ScrollingPreview` is Android-only at present; CMP Desktop ignores the
annotation.

## Accessibility (a11y)

Two complementary checks. Always do both before shipping a UI change:

### 1. Visual review (every change)

Read the rendered PNG. The renderer captures the actual composition, so
contrast, hit-target size, truncation, RTL mirroring, font-scale overflow,
night-mode colors, and Wear edge clipping are all inspectable directly.
`compose-preview show --json` surfaces `changed: true` per capture so you
only re-read what moved.

For broad coverage, run the preview through font-scale and night-mode
multi-preview meta-annotations (e.g. `@PreviewFontScale`,
`@PreviewLightDark`, `@WearPreviewFontScales`) — each variant lands as its
own entry with a unique id.

### 2. ATF (Accessibility Test Framework) checks

Opt in per-module:

```kotlin
composePreview {
    accessibilityChecks {
        enabled = true              // run ATF, surface findings
        failOnErrors = true         // optional: gate the build on ERROR-level findings
        failOnWarnings = false      // optional: gate on WARNING-level findings
        annotateScreenshots = true  // default; numbered badges + legend per preview
    }
}
```

When enabled, the Android renderer switches to its `renderWithA11y` path:
`LocalInspectionMode = false` so Compose populates real semantics, and after
capture ATF walks the `ViewRootForTest` view via `AccessibilityChecker`.
Findings (touch-target size, contrast, missing content descriptions, etc.)
land in `accessibility.json` and an annotated PNG with numbered badges +
legend.

CLI:

```sh
compose-preview a11y                       # human-readable per-preview findings
compose-preview a11y --json --changed-only # for agent re-render loops
compose-preview a11y --fail-on errors      # exit non-zero on ERROR-level
```

JSON results include `a11yFindings[]` (level/type/message/viewDescription)
and `a11yAnnotatedPath` (the badge-overlay PNG). When you see findings,
read the annotated PNG to locate them — the numbers in the legend match the
badges on the screenshot.

Trade-off worth knowing: a11y mode disables the paused frame clock (ATF
needs live semantics), so infinite animations tick through during capture.
Toggle a11y off for animation-heavy previews if the diff churn becomes
distracting.

## Wear design guidance

When creating or iterating on Wear OS designs, refer to the
**[Wear UI Guide](./design/WEAR_UI.md)** for:

- **Material 3 Expressive** principles and best practices.
- Recommended **Compose Material 3** APIs (e.g., `TransformingLazyColumn`,
  `EdgeButton`).
- Proper **System UI** integration (e.g., `TimeText`, `AppScaffold`).
- **Responsive layout** strategies across screen sizes.

## CI preview baselines (`preview_main` branch)

Projects that use the Gradle plugin can set up CI workflows to maintain a
`preview_main` branch with rendered PNGs and a `baselines.json` file (preview
ID → SHA-256). This serves two purposes:

1. **Browsable gallery** — the branch has a `README.md` with inline images,
   viewable directly on GitHub.
2. **PR diff comments** — a companion workflow renders previews on each PR,
   compares against the baselines, and posts a before/after comment.

### Checking if baselines are available

```bash
git ls-remote --exit-code origin preview_main
```

### Fetching current main previews

```bash
# Get the baselines manifest
git fetch origin preview_main
git show origin/preview_main:baselines.json

# Get a specific rendered PNG
git show origin/preview_main:renders/<module>/<preview-id>.png > preview.png
```

Or via raw URL:
```
https://raw.githubusercontent.com/<owner>/<repo>/preview_main/renders/<module>/<preview-id>.png
```

### Adding preview CI to your project

The actions are published as composite GitHub Actions. Add two workflow files
to your project:

**`.github/workflows/preview-baselines.yml`** — updates `preview_main` on push
to main:

```yaml
name: Preview Baselines
on:
  push:
    branches: [main]
permissions:
  contents: write
jobs:
  update:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v4
      - uses: yschimke/compose-ai-tools/.github/actions/preview-baselines@main
```

**`.github/workflows/preview-comment.yml`** — posts before/after comments on
PRs and cleans up on close:

```yaml
name: Preview Comment
on:
  pull_request:
    types: [opened, synchronize, closed]
permissions:
  contents: write
  pull-requests: write
jobs:
  compare:
    if: github.event.action != 'closed'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v4
      - uses: yschimke/compose-ai-tools/.github/actions/preview-comment@main
  cleanup:
    if: github.event.action == 'closed'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: yschimke/compose-ai-tools/.github/actions/preview-cleanup@main
```

The actions download the `compose-preview` CLI from the latest release,
auto-discover all modules that apply the plugin, and handle the baselines
branch and PR comment lifecycle. Gradle build caching via `setup-gradle`
keeps subsequent renders fast.

## Tips

- First render is slow (module compile + renderer bootstrap); later renders
  reuse Gradle caching and are much faster.
- Resource changes (`.xml`, `.json`) trigger recompilation and re-render on the
  next task run.
- Always visually verify after UI changes — show the user the before and after
  PNG.
- Iterate on a single variant first (e.g. `small_round` at 1x font scale), then
  follow up with fixes for other sizes and scales.
- Use a coloured border or an overlay `Canvas` when highlighting something
  specific for the user.
