# Compose Preview

Live preview rendering for Jetpack Compose and Compose Multiplatform `@Preview`
functions — directly in VS Code, without Android Studio.

<img height="400" alt="Compose Preview panel in VS Code" src="https://github.com/user-attachments/assets/fe9be596-13d9-4880-9e20-cedd6992f650" />

## How it works

The extension drives the
[`ee.schimke.composeai.preview`](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
Gradle plugin through the Tooling API. The plugin scans compiled classes for
`@Preview` annotations (including transitive multi-preview meta-annotations),
renders them to PNG, and the extension loads those PNGs into a webview.

- **Android** projects render inside a Robolectric sandbox with native
  graphics.
- **Compose Multiplatform Desktop** projects render with `ImageComposeScene`
  and Skia.

## Prerequisites

- Java 17 on `PATH` or `JAVA_HOME`.
- Gradle 9.4.1+ (the bundled wrapper in your project is fine).
- The
  [Gradle for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-gradle)
  extension (installed automatically as a dependency).
- CMP Desktop projects additionally need
  `implementation(compose.components.uiToolingPreview)` — the bundled
  `@Preview` annotation has `SOURCE` retention and is otherwise invisible to
  the discovery step.

### Kotlin language server (optional)

A Kotlin LSP is **not required** to render previews — the Gradle plugin
discovers `@Preview` annotations by scanning compiled `.class` files, so
rendering, the preview panel, and the refresh / render-all commands all work
without one. The extension contributes the `kotlin` languageId for `.kt` /
`.kts` files itself (metadata only — no grammar), so scope resolution still
finds your preview file in bare hosts (e.g. Antigravity IDE) where no Kotlin
extension is installed. Install a Kotlin extension when you want syntax
highlighting and the language-server extras below — VS Code merges language
contributions, so they layer on top.

A language server (e.g. `fwcd.kotlin`, `kotlin-language-server`, or any LSP
that registers a document-symbol provider for `kotlin`) enables editor-side
extras:

- **CodeLens** "Focus in Preview panel" above each `@Preview` function.
- **Hover** metadata on `@Preview` functions.
- **Gutter decorations** next to preview functions.
- **Compile-error gate** — saves a 5–30 s Gradle round-trip when the active
  file has Error-severity diagnostics, by surfacing them instead of starting
  a build. With no LSP attached, every save goes straight to Gradle.

All four degrade silently when no LSP is present.

## Apply the Gradle plugin

The extension auto-applies the Compose Preview plugin to any module that
already applies `com.android.application`, `com.android.library`, or
`org.jetbrains.compose` — no `build.gradle.kts` edit needed. A bundled init
script is passed to every Gradle invocation via `--init-script` and pulls
the plugin from Maven Central.

To opt in manually instead, add
[`ee.schimke.composeai.preview`](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
to the module whose previews you want to render:

<!-- x-release-please-start-version -->

```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.13.1"
}
```

<!-- x-release-please-end -->

The plugin is on Maven Central, so no extra repository setup is needed when
`mavenCentral()` is already in your `pluginManagement.repositories`. See the
[project README](https://github.com/yschimke/compose-ai-tools#setup) for
snapshot builds.

## Modes

`composePreview.mode` is a binary pin: **`full`** _(default)_ runs the
preview daemon with data extensions (a11y overlays, hierarchy, focus-mode
inspector layers) and live / interactive previews; **`minimal`** drives only
the Gradle plugin, disables everything daemon-backed, and stops renders from
firing automatically on save (click the refresh button or run
`Compose Preview: Refresh Previews` / `Render All Previews` instead).

`full` mode works on any workspace that applies `com.android.application`,
`com.android.library`, or `org.jetbrains.compose` — the bundled `--init-script`
makes the preview plugin available without editing `build.gradle.kts`. Switch
to `minimal` when the daemon's memory cost isn't worth it for your project.

## Usage

1. Open a Kotlin project that applies the Compose Preview Gradle plugin.
2. Click the **Compose Preview** icon in the activity bar.
3. Use the **Previews** panel to browse discovered `@Preview` functions and
   their rendered images.

### Commands

| Command                                | Description                                                                      |
| -------------------------------------- | -------------------------------------------------------------------------------- |
| `Compose Preview: Refresh Previews`    | Re-read `previews.json` and rendered PNGs from `build/compose-previews/`.        |
| `Compose Preview: Render All Previews` | Run the `composePreviewRenderAll` Gradle task to discover and render everything. |

### Settings

| Setting                        | Default  | Description                                                                                                                                                                                                                                                                                                                                      |
| ------------------------------ | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `composePreview.mode`          | `full`   | Backend mode: `full` runs the daemon with data extensions and live previews; `minimal` drives only the Gradle plugin (no daemon, no auto-render on save). The bundled init script is what makes `full` safe to default to on any Android / Compose workspace. Requires a window reload to take effect. Legacy `auto` values fall back to `full`. |
| `composePreview.variant`       | `debug`  | Build variant to use for preview rendering (Android).                                                                                                                                                                                                                                                                                            |
| `composePreview.logging.level` | `normal` | Verbosity for the "Compose Preview" output channel. `quiet` shows only errors and the BUILD outcome; `normal` keeps active task headers and summary lines but drops UP-TO-DATE/SKIPPED noise, configuration-cache bookkeeping, and dedupes the repeated Roborazzi ActionBar warnings; `verbose` shows every line from Gradle and the daemon.     |

## Links

- [Source & documentation](https://github.com/yschimke/compose-ai-tools)
- [Issue tracker](https://github.com/yschimke/compose-ai-tools/issues)
- [Changelog](https://github.com/yschimke/compose-ai-tools/blob/main/CHANGELOG.md)

## License

Apache-2.0
