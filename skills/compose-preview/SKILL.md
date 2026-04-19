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
    id("ee.schimke.composeai.preview") version "0.6.2"
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
    id 'ee.schimke.composeai.preview' version '0.6.2'
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
@ScrollingPreview(mode = ScrollMode.LONG)
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
          java-version: 17
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
          java-version: 17
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

## Authoring an Agent PR (body structure)

A reviewer opening an agent-authored PR should see **the goal** before they
see **what the agent did**. Structure the PR body in two sections, in this
order:

### 1. Goal — the user's prompt, lightly reworded

Paste the prompt that kicked off the work, cleaned up but **not rewritten**:

- Fix typos, expand pronouns that only make sense in chat ("it", "this"),
  resolve references to earlier turns ("like we discussed" → the actual
  constraint), strip conversational filler ("please", "can you").
- Keep the user's voice, scope, and emphasis. Don't add justifications,
  don't soften "must" into "should", don't invent acceptance criteria the
  user didn't state.
- One short paragraph, or a bulleted list if the user's prompt already had
  bullets. Not a re-specification.

This section anchors the review: every change the agent made should trace
back to something here. If the diff does something this section doesn't ask
for, that's a scope-creep flag for the reviewer.

### 2. Summary — what the agent actually did

Separate subsection (`## Summary` or `## What changed`) covering, in order:

- **Changes made** — one bullet per distinct change, file-path-anchored.
- **Things tried and abandoned** — approaches the agent rejected and why,
  so the reviewer doesn't suggest them again.
- **Known gaps** — anything from the goal the agent didn't do (and why:
  out of scope, blocked, unclear). Explicit gaps beat silent omissions.
- **Verification** — how the agent checked the change. For UI work, list
  the `@Preview` ids that were re-rendered and read, not just "tested
  locally".

Keep each bullet under ~2 lines. Reviewers skim; a 40-line wall-of-summary
defeats the point.

If a CI preview comment will be posted, don't duplicate the before/after in
the body — link to the comment instead. The body stays intent-focused; the
sticky comment carries the visuals.

## Reviewing a PR (agent workflow)

When asked to review a PR — especially one opened by another agent — your job
is to make the UI change legible to a *human* reviewer, not to re-do the
agent's work. Most repos will **not** have any preview-diff CI set up, so
assume you're rendering locally and that the human who invoked you is the
primary audience.

### 1. Render base and head locally

Use a worktree for the base so the agent's working copy stays on the PR head:

```bash
BASE=$(gh pr view <N> --json baseRefName -q .baseRefName)
git worktree add ../_pr_base "origin/$BASE"

(cd ../_pr_base && compose-preview show --json) > base.json
compose-preview show --json > head.json

git worktree remove ../_pr_base
```

Diff by preview `id` + `sha256`. Bucket into **changed**, **new**, and
**removed**. Read the PNGs for each entry in `changed` and `new` directly
from the paths in the JSON — this is what the human invoking you will read
too.

### 2. Default: show the human the diffs inline, post a text comment

Without pre-existing image hosting, the simplest flow is:

1. **Read** the before/after PNGs yourself — you now have the visual context.
2. **Summarise** the deltas in plain text for the human running the review
   (per-preview: what changed, what to look for).
3. **Post a text-only review comment** to the PR. Include preview ids,
   `sha256` (first 8 chars), and the local path so the human — or a later
   agent — can reproduce:

   ```
   ## Preview diff (rendered locally, not hosted)

   **3 changed, 1 new, 0 removed** · base `origin/main@abc1234`

   ### Changed
   - `home:HomeScreen_dark` — bg #1a1a → #0d0d0d, divider gained 1dp radius
     · sha `a1b2c3d4` → `e5f6a7b8`
   - `home:HomeScreen_fontscale_1.3` — CTA wraps to 2 lines at 1.3×
     · sha `11223344` → `99aabbcc` — **likely regression, flag**

   ### New
   - `home:HomeScreen_empty` — ➕ no baseline; verify this is intentional
     · sha `deadbeef`

   _Images not uploaded. Run `compose-preview show --filter HomeScreen --json`
   locally to reproduce._
   ```

   This is far better than silence and doesn't require any infrastructure.

### 3. Uploading images — only with explicit consent

If the human asks for images in the comment, pick **one** and confirm the
destination before acting:

- **Gist** (`gh gist create <png> --public`) — quick, one image per file,
  public by default. Ask before posting anything public.
- **Dedicated branch in the repo** (`preview_pr/<N>`) — clean raw URLs but
  creates a branch the user may not want; get confirmation.
- **Issue/PR attachment upload** — not reliably available via `gh`; skip.

Never use inline base64 or data URIs — GitHub strips them. Never push images
to a public branch, gist, or external host without the user explicitly
saying yes in the chat.

### 4. Write the comment for a human, not a log file

Whether or not there are images, the review comment is what the reviewer
will actually read. Optimise for scanning:

- **Lead with a one-line count**: `N changed · N new · N removed ·
  N unchanged` (per module if >1 module is touched). Reviewers decide
  whether to expand based on this line.
- **Only show deltas.** Unchanged previews go behind a collapsed `<details>`
  or get omitted entirely. Never post a wall of identical thumbnails.
- **Separate new/removed from changed.** New previews have no baseline and
  are easy to miss in a before/after layout — give them their own section
  with a ➕ marker. Same for removed (🗑️).
- **Side-by-side tables for changed** (when images are hosted): two-column
  markdown table, Before | After, identical widths, preview id as the row
  heading linked to the `@Preview` source line
  (`<repo>/blob/<sha>/<path>#L<line>`).
- **Group by module** and collapse with `<details><summary>` when there are
  more than ~5 previews in a bucket. First group expanded, rest collapsed.
- **Flag a11y regressions separately.** Diff `a11yFindings[]` against the
  base; a finding new on this PR is worth a 🔴 callout even if the PNG diff
  is cosmetic. Link the annotated PNG (or include its local path if no
  hosting).
- **Caption with `sha256` (first 8 chars) and byte size** under each image.
  Lets the reviewer confirm what they're looking at matches the manifest.
- **Respect the 65 536-char comment limit.** If the body would overflow,
  split into summary + per-module detail comments.
- **Don't editorialise the UI.** Describe *what* changed ("button radius
  4 → 12 dp, new ripple on press"), not whether it looks good — let the
  human make the aesthetic call.

### 5. Things worth flagging in the review text

Agent-authored PRs have predictable failure modes that the preview diff
surfaces:

- **New preview with no baseline → is it a real new surface, or did a rename
  strand an old id?** Check `removed` for a plausible predecessor.
- **Preview unchanged but source changed** → the composable may be guarded
  behind a param default the preview doesn't exercise. Ask for a preview
  variant that hits the new path.
- **A11y findings grew** → often a regression from hard-coded colours,
  missing `contentDescription`, or touch targets shrunk to match a redesign.
- **Scroll/animation flakes** in `captures[]` — if `changed: true` toggles
  run-to-run without code changes, the preview is non-deterministic; flag
  it rather than rubber-stamp the diff.

### 6. Optional: integrate with `preview-comment` CI (rare)

A small number of repos wire up the `preview-comment` GitHub Action (see the
CI section above). When it's installed, it posts a sticky comment keyed by
`<!-- preview-diff -->` with before/after images hosted from a
`preview_pr/<N>` branch.

Only do this if you've already confirmed it exists:

```bash
gh pr view <N> --json comments \
  --jq '.comments[] | select(.body | startswith("<!-- preview-diff -->")) | .body'
```

If it's there, **read it first** and cite it in your review rather than
rendering again. If you do post your own comment alongside it, reuse the
`<!-- preview-diff -->` marker (or a distinct one like
`<!-- preview-diff-local -->`) so repeat reviews update one comment instead
of stacking:

```bash
MARKER="<!-- preview-diff-local -->"
ID=$(gh api "repos/$REPO/issues/$PR/comments" --paginate \
  --jq ".[] | select(.body | startswith(\"$MARKER\")) | .id" | head -1)
if [ -n "$ID" ]; then
  gh api "repos/$REPO/issues/comments/$ID" -X PATCH -f body="$BODY"
else
  gh pr comment "$PR" --body "$BODY"
fi
```

Assume this path is **not** available unless you've just checked.

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
