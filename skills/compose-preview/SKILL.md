---
name: compose-preview
description: Render Compose @Preview functions to PNG outside Android Studio. Use this to verify UI changes, iterate on designs, and compare before/after states across Android (Jetpack Compose) and Compose Multiplatform Desktop projects.
---

# Compose Preview

Render `@Preview` composables to PNG images without launching Android Studio.
Works on both Android (Jetpack Compose via Robolectric) and Compose Multiplatform
Desktop (via `ImageComposeScene` + Skia).

## Source

This skill is maintained at
[github.com/yschimke/compose-ai-tools](https://github.com/yschimke/compose-ai-tools)
under `skills/compose-preview/`. To check for updates, compare the installed
copy against the `main` branch there (e.g. `git ls-remote
https://github.com/yschimke/compose-ai-tools HEAD` for the latest commit, or
fetch `skills/compose-preview/SKILL.md` from `main` and diff).

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
  doctor   Verify Java 17 + project compatibility (run before Setup)

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

## Permissions for agent workflows

Agents using this skill run the same handful of commands on every iteration
(render, read PNGs, occasionally stage copies). Most agent harnesses
(Claude Code, Cursor, Cline, Aider, Copilot, etc.) support some form of
pre-approval — allowlist entries, trusted-command lists, or auto-accept
rules. The exact syntax differs, so the lists below are patterns rather
than a specific config file. Translate them into your harness's format and
keep anything that publishes or mutates shared state on the prompt path.

**Safe to pre-approve** (read/render, only writes under gitignored `build/`):

- `compose-preview` — all subcommands write under `build/compose-previews/`.
- `./gradlew` / `gradle` — already trusted in most JVM projects.
- Reading `**/build/**` — rendered PNGs and staged copies live here.
- `mkdir -p`, `cp`, `rm -f` — for staging copies (see below).
- `git worktree add|remove|list`, `git ls-remote`, `git fetch`, `git show` —
  used by the PR-review workflow to render a base branch without touching
  the working copy.
- `gh pr view`, `gh pr diff`, and `GET` on `gh api repos/…/comments`.

**Require explicit consent** (publish or mutate shared state — keep on the
prompt path):

- `gh gist create` — public by default; even `--secret` URLs are shareable.
- `gh pr comment`, `gh pr review`, `POST`/`PATCH`/`DELETE` via `gh api`.
- `git push`, `git commit`, `git branch -D`, `git reset --hard`.
- Uploads to external hosts (image hosts, paste services).

If the user approves a gist or PR comment once, don't persist it as a
general allowlist entry — the next PR may not want it.

### Staging PNGs outside the render output

`compose-preview` writes each PNG under
`<module>/build/compose-previews/renders/<id>.png`. Reading those paths
directly works for a single iteration, but agents often want to hold
captures steady across a diff — before/after pairs, the subset a PR
touches, or images copied next to a worktree that's about to be removed.

Stage those copies **somewhere under `build/`**. Every Android/KMP project
`.gitignore`s that path, so nothing leaks into commits, and the location is
consistent across checkouts. The exact layout (`build/preview-staging/`,
`build/agent/<ts>/`, a module-local `build/…`, etc.) is up to the agent —
pick what fits the task.

Don't stage outside `build/`. Checked-in paths like `docs/` or `screenshots/`
risk committing generated binaries.

## Designing composables for previewability

`@Preview` only calls composables with zero arguments (or all-default). That
rules out anything taking a `ViewModel`, repository, or DI-injected service.
The standard fix is **state hoisting** — split each screen into two layers:

```kotlin
// Stateful wrapper: wires runtime dependencies. Not previewable.
@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    HomeScreen(state = state, onRefresh = viewModel::refresh)
}

// Stateless UI: pure function of state + callbacks. Previewable with
// hand-rolled fixtures — no mocks, no test dispatchers, no DI.
@Composable
fun HomeScreen(state: HomeState, onRefresh: () -> Unit) { /* … */ }

@Preview @Composable fun HomeScreen_loaded() =
    HomeScreen(HomeState.Loaded(items = sampleItems), onRefresh = {})

@Preview @Composable fun HomeScreen_empty() =
    HomeScreen(HomeState.Empty, onRefresh = {})

@Preview @Composable fun HomeScreen_error() =
    HomeScreen(HomeState.Error("Network unavailable"), onRefresh = {})
```

Every visual state is a fixture — the state is data, constructed inline.
This is also the foundation for testing UI without standing up business
logic: the same stateless composable that a preview renders is the one a
screenshot test or Compose UI test exercises.

**Agent guidance:** if you're asked to iterate on a composable that accepts
a ViewModel, repository, or injected dependency, **first propose extracting
a stateless inner composable and preview that instead.** Rendering the
stateful wrapper either fails outright or produces a misleading
empty/loading frame that doesn't exercise the UI. The one-time extraction
cost unlocks the fast `compose-preview` iteration loop for every future
change on that screen.

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

`compose-preview doctor` verifies Java 17 is on `PATH` (the only hard
prereq for the plugin now that Maven Central resolution needs no auth).

### 1. Register the plugin repository (only if mavenCentral is missing)

Most Android/KMP projects already include `mavenCentral()` in
`pluginManagement.repositories` in `settings.gradle.kts`. If yours doesn't,
add it alongside `gradlePluginPortal()` and `google()`.

### 2. Apply the plugin

In `<module>/build.gradle.kts`:

<!-- x-release-please-start-version -->
```kotlin
plugins {
    id("ee.schimke.composeai.preview") version "0.7.0"
}

composePreview {
    variant.set("debug")   // Android build variant (default: "debug")
    sdkVersion.set(35)     // Robolectric SDK version (default: 35)
    enabled.set(true)      // set false to skip task registration
}
```
<!-- x-release-please-end -->

(Groovy DSL works identically — translate property assignments to `=`.)

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

When enabled, Compose populates real semantics (inspection mode off) and
ATF walks the captured view. Findings (touch-target size, contrast, missing
`contentDescription`, etc.) land in `accessibility.json` and an annotated
PNG with numbered badges.

```sh
compose-preview a11y                       # human-readable findings
compose-preview a11y --json --changed-only # for re-render loops
compose-preview a11y --fail-on errors      # non-zero on ERROR-level
```

JSON entries include `a11yFindings[]` (level/type/message/viewDescription)
and `a11yAnnotatedPath`. Read the annotated PNG to map numbered badges back
to findings. Trade-off: a11y mode disables the paused clock, so infinite
animations tick during capture — toggle it off for animation-heavy previews.

## Wear design guidance

When creating or iterating on Wear OS designs, refer to the
**[Wear UI Guide](./design/WEAR_UI.md)** for:

- **Material 3 Expressive** principles and best practices.
- Recommended **Compose Material 3** APIs (e.g., `TransformingLazyColumn`,
  `EdgeButton`).
- Proper **System UI** integration (e.g., `TimeText`, `AppScaffold`).
- **Responsive layout** strategies across screen sizes.

## CI and PR workflows

- **[design/CI_PREVIEWS.md](design/CI_PREVIEWS.md)** — setting up the
  `preview_main` baselines branch and the PR comment GitHub Actions. Read
  this if you're adding preview CI to a repo or need to fetch baselines
  from an existing one.
- **[design/AGENT_PR.md](design/AGENT_PR.md)** — structuring the body of an
  agent-authored PR, and the full workflow for reviewing one locally
  (render base + head, diff, post a human-readable comment). Read this
  when opening or reviewing a PR that touches UI.

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
