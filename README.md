# compose-ai-tools

**Give your AI coding agent eyes for Compose UI.** A Gradle plugin, CLI, and
VS Code extension that render `@Preview` composables to PNG — outside Android
Studio — so agents like Claude Code, Cursor, Gemini in Android Studio, and
Copilot can actually *see* what they're changing and iterate on it.

Works with Jetpack Compose (Android) and Compose Multiplatform Desktop.

<!--
  TODO: replace the two placeholders below with:
    1. A screenshot of an agent chat session where the agent reads a rendered
       @Preview PNG and iterates on the design.
    2. A screenshot of a PR opened by an agent that used compose-preview to
       verify the change (diff + before/after PNGs in the PR body).
  Drop the images in an issue/discussion to get a stable GitHub user-images URL,
  or commit under docs/images/ and reference with a relative path.
-->

<p align="center">
  <img width="45%" alt="Agent chat iterating on a Compose preview" src="https://github.com/user-attachments/assets/REPLACE-ME-agent-chat" />
  &nbsp;
  <img width="45%" alt="PR opened by an agent using compose-preview" src="https://github.com/user-attachments/assets/REPLACE-ME-agent-pr" />
</p>

## Point your agent at this

The fastest way to start: hand your agent this URL and ask it to set up the
project.

```
https://github.com/yschimke/compose-ai-tools/blob/main/skills/compose-preview/SKILL.md
```

Example prompts:

> *"Set up compose-preview in this project using the skill at
> https://github.com/yschimke/compose-ai-tools/blob/main/skills/compose-preview/SKILL.md,
> then render the previews in `app/` and show me the results."*

> *"Read
> https://github.com/yschimke/compose-ai-tools/blob/main/skills/compose-preview/SKILL.md
> and iterate on `HomeScreen_loaded` until the empty state matches the
> screenshot I attached."*

The [SKILL.md](https://github.com/yschimke/compose-ai-tools/blob/main/skills/compose-preview/SKILL.md)
file is a complete agent playbook: install steps, commands, iteration loop,
state-hoisting guidance for making composables previewable, and which commands
are safe to pre-approve. Any agent that can fetch a URL — Claude Code, Cursor,
Windsurf, Aider, Gemini in Android Studio — can follow it cold.

## Why this matters

Android Studio's built-in AI agents are great at editing Kotlin, but they're
flying blind on UI. They can't open the Preview pane, can't see the rendered
pixels, and often guess at visual changes. `compose-preview` closes the loop:

- **Agents see what they built.** Rendered PNGs go to a path the agent can
  read, with `sha256` + `changed` flags so it knows which frames to re-inspect.
- **Fast feedback.** Gradle-cacheable tasks; unchanged inputs skip re-render.
- **No emulator, no AS running.** Desktop uses `ImageComposeScene` + Skia;
  Android uses Robolectric with native graphics.
- **Multi-preview aware.** `@PreviewFontScale`, `@WearPreviewDevices`,
  `@PreviewLightDark` all fan out to individual captures.

## Setup (for humans)

The plugin is published to [Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin).
No authentication, no PAT — just apply it.

### Apply the plugin

<!-- x-release-please-start-version -->
```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.7.6"
}
```
<!-- x-release-please-end -->

Most projects already have `mavenCentral()` in their
`pluginManagement.repositories`. If yours doesn't, add it alongside
`gradlePluginPortal()` and `google()` in `settings.gradle.kts`.

CMP Desktop projects additionally need
`implementation(compose.components.uiToolingPreview)` — the bundled `@Preview`
annotation has `SOURCE` retention and is invisible to ClassGraph.

Check [Releases](https://github.com/yschimke/compose-ai-tools/releases) for
the latest version.

### Install the CLI

```sh
curl -fsSL https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/install.sh | bash

compose-preview doctor
```

The installer is idempotent, drops the latest release under
`~/.local/opt/compose-preview/<version>/`, and symlinks
`~/.local/bin/compose-preview`. `doctor` verifies Java 17 is on `PATH`.

### Install the VS Code extension

```sh
code --install-extension yuri-schimke.compose-preview
```

Or from the [Marketplace](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview).
The extension renders through the Gradle plugin — apply
`ee.schimke.composeai.preview` version `0.7.6` <!-- x-release-please-version --> in your project too.

<img height="400" alt="VS Code extension preview panel" src="https://github.com/user-attachments/assets/fe9be596-13d9-4880-9e20-cedd6992f650" />

## Usage

```sh
./gradlew :app:discoverPreviews    # scan for @Preview annotations
./gradlew :app:renderAllPreviews   # discover + render to PNG

compose-preview list               # list discovered previews
compose-preview show --json        # render + emit paths, sha256, changed flags
compose-preview a11y               # render + ATF accessibility findings
```

Full CLI reference and agent iteration loop: [SKILL.md](skills/compose-preview/SKILL.md).

### Configuration

```kotlin
composePreview {
    variant.set("debug")     // Android build variant (default: "debug")
    sdkVersion.set(35)       // Robolectric SDK version (default: 35)
    enabled.set(true)        // disable to skip registration (default: true)
}
```

### Testing against a snapshot

Every push to `main` publishes a `-SNAPSHOT` build. Add the snapshots repo to
`pluginManagement` and bump to the next patch `-SNAPSHOT`:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
        }
    }
}
```

See [docs/RELEASING.md](docs/RELEASING.md) for detail.

## Learn more

- **[skills/compose-preview/SKILL.md](skills/compose-preview/SKILL.md)** — the
  agent playbook. Commands, iteration loop, state hoisting, a11y, scrolling
  captures, Wear Tiles, Remote Compose.
- **[docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md)** — discovery/render pipeline
  internals, module layout, requirements.
- **[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)** — building the plugin, CLI,
  and VS Code extension from source.
- **[docs/RELEASING.md](docs/RELEASING.md)** — release process and snapshots.

## Contributing

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
