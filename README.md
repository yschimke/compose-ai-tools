# compose-ai-tools

Render `@Preview` composables to PNG outside Android Studio, so AI coding
agents can see what they're changing. Works with Jetpack Compose (Android,
via Robolectric) and Compose Multiplatform Desktop (via `ImageComposeScene`).

## What it ships

- **Agent skill** — [`skills/compose-preview/SKILL.md`](skills/compose-preview/SKILL.md).
  Point any agent that can fetch a URL at it; the skill is a complete
  install-and-iterate playbook. Bootstrap a host machine with
  [`scripts/install.sh`](scripts/install.sh).
- **VS Code extension** — published to the
  [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview)
  and [Open VSX](https://open-vsx.org/extension/yuri-schimke/compose-preview)
  (for VSCodium / Cursor / Windsurf). Install from inside the IDE: open
  the Extensions view (⇧⌘X / Ctrl+Shift+X), search **Compose Preview**,
  click *Install*. Source in [`vscode-extension/`](vscode-extension/).
- **GitHub Actions** — composite actions for CI:
  [`install`](.github/actions/install/) (CLI on `$PATH`),
  [`preview-baselines`](.github/actions/preview-baselines/) (push baselines),
  [`preview-comment`](.github/actions/preview-comment/) (before/after PR
  comments), [`a11y-report`](.github/actions/a11y-report/) (accessibility
  findings).

<img height="400" alt="VS Code extension preview panel" src="https://github.com/user-attachments/assets/fe9be596-13d9-4880-9e20-cedd6992f650" />

## Setup

The plugin is published to [Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
— no auth, no PAT.

<!-- x-release-please-start-version -->
```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.8.6"
}
```
<!-- x-release-please-end -->

Working examples: [`samples/android/build.gradle.kts`](samples/android/build.gradle.kts),
[`samples/wear/build.gradle.kts`](samples/wear/build.gradle.kts),
[`samples/cmp/build.gradle.kts`](samples/cmp/build.gradle.kts).

Then:

```sh
./gradlew :app:discoverPreviews    # scan @Preview annotations
./gradlew :app:renderAllPreviews   # render every @Preview to PNG
```

Requires Java 17+, Gradle 9.4.1+, AGP 9.1+ (Android), Kotlin 2.2.21,
Compose Multiplatform 1.10.3 (Desktop).

## More

- [How it works](docs/AGENTS.md) — discovery, renderer, caching.
- [Cloud sandbox setup](skills/compose-preview/design/CLAUDE_CLOUD.md) — Claude Code on the web, network allowlist.
- [CI workflows](skills/compose-preview/design/CI_PREVIEWS.md) — `preview_main` baselines, PR diff comments.
- [Development](docs/DEVELOPMENT.md) — building plugin, CLI, and extension from source.
- [Releases](https://github.com/yschimke/compose-ai-tools/releases) ·
  [Changelog](CHANGELOG.md) ·
  [License (Apache 2.0)](LICENSE)
