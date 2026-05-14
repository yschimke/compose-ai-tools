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

## Apply the Gradle plugin

The extension auto-applies the Compose Preview plugin to any module that
already applies `com.android.application`, `com.android.library`, or
`org.jetbrains.compose` — no `build.gradle.kts` edit needed. A bundled init
script is passed to every Gradle invocation via `--init-script` and pulls
the plugin from Maven Central. Workspaces that don't apply one of those host
plugins fall back to **minimal mode** (see below); workspaces that apply the
plugin explicitly continue to work the same way.

To opt in manually instead, add
[`ee.schimke.composeai.preview`](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
to the module whose previews you want to render:

<!-- x-release-please-start-version -->

```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.10.11"
}
```

<!-- x-release-please-end -->

The plugin is on Maven Central, so no extra repository setup is needed when
`mavenCentral()` is already in your `pluginManagement.repositories`. See the
[project README](https://github.com/yschimke/compose-ai-tools#setup) for
snapshot builds.

To disable auto-inject and require an explicit plugin application, set
`composePreview.autoInject.enabled` to `false`.

## Minimal mode

When no module in the workspace applies the Compose Preview plugin (and
auto-inject can't attach onto an Android / Compose host plugin), the
extension boots into **minimal mode**: the preview daemon, data extensions
(a11y overlays, hierarchy, focus-mode inspector layers), and live /
interactive previews are all disabled, and renders no longer fire
automatically on save. Click the refresh button (or run
`Compose Preview: Refresh Previews` / `Render All Previews`) to render.

Override via `composePreview.mode`: `auto` (default) picks based on the
workspace, `minimal` forces the gradle-only path, `full` forces the daemon
backend. After a Gradle sync writes the authoritative plugin-applied
markers, the extension offers a one-click reload if a switch to full mode
becomes possible.

## Usage

1. Open a Kotlin project that applies the Compose Preview Gradle plugin.
2. Click the **Compose Preview** icon in the activity bar.
3. Use the **Previews** panel to browse discovered `@Preview` functions and
   their rendered images.

### Commands

| Command                                | Description                                                                |
| -------------------------------------- | -------------------------------------------------------------------------- |
| `Compose Preview: Refresh Previews`    | Re-read `previews.json` and rendered PNGs from `build/compose-previews/`.  |
| `Compose Preview: Render All Previews` | Run the `renderAllPreviews` Gradle task to discover and render everything. |

### Settings

| Setting                             | Default  | Description                                                                                                                                                                                                                                                                                                                                  |
| ----------------------------------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `composePreview.mode`               | `auto`   | Backend mode: `auto` picks `full` when the plugin is applied (or can be auto-injected onto an Android / Compose host), else `minimal`. Force `full` or `minimal` to override. Requires a window reload to take effect.                                                                                                                       |
| `composePreview.autoInject.enabled` | `true`   | Pass the bundled init script via `--init-script` so the plugin auto-applies to Android / Compose projects. Disable to keep Gradle invocations untouched and require manual `id("ee.schimke.composeai.preview")` setup.                                                                                                                       |
| `composePreview.variant`            | `debug`  | Build variant to use for preview rendering (Android).                                                                                                                                                                                                                                                                                        |
| `composePreview.logging.level`      | `normal` | Verbosity for the "Compose Preview" output channel. `quiet` shows only errors and the BUILD outcome; `normal` keeps active task headers and summary lines but drops UP-TO-DATE/SKIPPED noise, configuration-cache bookkeeping, and dedupes the repeated Roborazzi ActionBar warnings; `verbose` shows every line from Gradle and the daemon. |

## Links

- [Source & documentation](https://github.com/yschimke/compose-ai-tools)
- [Issue tracker](https://github.com/yschimke/compose-ai-tools/issues)
- [Changelog](https://github.com/yschimke/compose-ai-tools/blob/main/CHANGELOG.md)

## License

Apache-2.0
