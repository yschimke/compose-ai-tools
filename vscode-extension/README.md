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

Add
[`ee.schimke.composeai.preview`](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin)
to the module whose previews you want to render:

<!-- x-release-please-start-version -->
```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.6.1"
}
```
<!-- x-release-please-end -->

The plugin is on Maven Central, so no extra repository setup is needed when
`mavenCentral()` is already in your `pluginManagement.repositories`. See the
[project README](https://github.com/yschimke/compose-ai-tools#setup) for
snapshot builds.

## Usage

1. Open a Kotlin project that applies the Compose Preview Gradle plugin.
2. Click the **Compose Preview** icon in the activity bar.
3. Use the **Previews** panel to browse discovered `@Preview` functions and
   their rendered images.

### Commands

| Command | Description |
|---|---|
| `Compose Preview: Refresh Previews` | Re-read `previews.json` and rendered PNGs from `build/compose-previews/`. |
| `Compose Preview: Render All Previews` | Run the `renderAllPreviews` Gradle task to discover and render everything. |

### Settings

| Setting | Default | Description |
|---|---|---|
| `composePreview.variant` | `debug` | Build variant to use for preview rendering (Android). |

## Links

- [Source & documentation](https://github.com/yschimke/compose-ai-tools)
- [Issue tracker](https://github.com/yschimke/compose-ai-tools/issues)
- [Changelog](https://github.com/yschimke/compose-ai-tools/blob/main/CHANGELOG.md)

## License

Apache-2.0
