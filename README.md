# compose-ai-tools

Render `@Preview` composables to PNG outside Android Studio, so AI coding
agents can see what they're changing. Works with Jetpack Compose (Android,
via Robolectric) and Compose Multiplatform Desktop (via `ImageComposeScene`).

Renders include
[paused-clock animation captures](skills/compose-preview/SKILL.md#animations-and-the-paused-frame-clock-android-only)
(GIF or single frame) and opt-in
[ATF accessibility checks](skills/compose-preview/SKILL.md#accessibility-a11y)
with annotated overlays.

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

## Samples

Source under [`samples/`](samples/). Rendered baselines (PNGs and animation
GIFs, regenerated on every push to `main`) are browsable inline on the
[`preview_main`](https://github.com/yschimke/compose-ai-tools/tree/preview_main)
branch:

- [`samples:android`](https://github.com/yschimke/compose-ai-tools/tree/preview_main#samplesandroid) — phone, font-family showcase, scrolling captures, animation timelines.
- [`samples:wear`](https://github.com/yschimke/compose-ai-tools/tree/preview_main#sampleswear) — Wear OS Material 3 Expressive, `EdgeButton`, tile previews.
- [`samples:cmp`](https://github.com/yschimke/compose-ai-tools/tree/preview_main#samplescmp) — Compose Multiplatform Desktop.
- [`samples:remotecompose`](https://github.com/yschimke/compose-ai-tools/tree/preview_main#samplesremotecompose) — Remote Compose against `wear-compose-remote-material3`.

ATF a11y findings for the same samples are on the
[`a11y_main`](https://github.com/yschimke/compose-ai-tools/tree/a11y_main)
branch.

## Agent PR hall of fame

Real-world PRs opened by AI coding agents that used `compose-preview` to
verify their changes.

<!-- Add interesting agent PRs here as they happen — link, agent, one-liner. -->

*Currently empty — open a PR or [an issue](https://github.com/yschimke/compose-ai-tools/issues/new)
if you have one to add.*

## More

- [How it works](docs/HOW_IT_WORKS.md) — discovery, renderer, caching, project structure, plugin configuration.
- [CI install action](.github/actions/install/README.md) — pin the CLI on `$PATH` in any GitHub Actions job, with version-catalog + Renovate recipes.
- [Cloud sandbox setup](skills/compose-preview/design/CLAUDE_CLOUD.md) — Claude Code on the web, network allowlist.
- [CI workflows](skills/compose-preview/design/CI_PREVIEWS.md) — `preview_main` baselines, PR diff comments.
- [Development](docs/DEVELOPMENT.md) — building plugin, CLI, and extension from source; consuming `-SNAPSHOT` builds.
- [Architecture (contributor)](docs/AGENTS.md) — class-by-class map of the four-stage pipeline.
- [Releases](https://github.com/yschimke/compose-ai-tools/releases) ·
  [Changelog](CHANGELOG.md) ·
  [License (Apache 2.0)](LICENSE)
