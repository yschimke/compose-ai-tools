---
name: preview
description: Render Compose @Preview functions to PNG outside Android Studio. Use this to verify UI changes, iterate on designs, and compare before/after states across Android (Jetpack Compose) and Compose Multiplatform Desktop projects.
---

# Compose Preview

Render `@Preview` composables to PNG images without launching Android Studio.
Works on both Android (Jetpack Compose via Robolectric) and Compose Multiplatform
Desktop (via `ImageComposeScene` + Skia).

## What this skill provides

- A Gradle plugin (`ee.schimke.composeai.preview`) that discovers `@Preview`
  annotations from compiled classes and registers rendering tasks.
- A `compose-preview` CLI (GraalVM native-image) that drives the Gradle build via the
  Tooling API and surfaces rendered PNG paths.
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

### 1. Register the plugin repository in `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven {
            name = "composeAiTools"
            url = uri("https://maven.pkg.github.com/yschimke/compose-ai-tools")
            credentials {
                username = providers.gradleProperty("composeAiTools.githubUser").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("composeAiTools.githubToken").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "ee.schimke.composeai.preview") {
                useModule("ee.schimke.composeai:gradle-plugin:${requested.version}")
            }
        }
    }
}
```

Credentials live in `~/.gradle/gradle.properties` (a GitHub PAT with
`read:packages` is enough):

```properties
composeAiTools.githubUser=your-github-username
composeAiTools.githubToken=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### 2. Apply the plugin

```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.1.1"
}

composePreview {
    variant.set("debug")   // Android build variant (default: "debug")
    sdkVersion.set(35)     // Robolectric SDK version (default: 35)
    enabled.set(true)      // set false to skip task registration
}
```

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

## Wear design guidance

When creating or iterating on Wear OS designs, refer to the
**[Wear UI Guide](./design/WEAR_UI.md)** for:

- **Material 3 Expressive** principles and best practices.
- Recommended **Compose Material 3** APIs (e.g., `TransformingLazyColumn`,
  `EdgeButton`).
- Proper **System UI** integration (e.g., `TimeText`, `AppScaffold`).
- **Responsive layout** strategies across screen sizes.

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
