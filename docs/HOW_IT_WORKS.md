# How compose-preview works

End-to-end view of how a `@Preview` composable becomes a PNG. For the
contributor-oriented architecture map (which class lives where, why each
backend made the choices it made), see [AGENTS.md](AGENTS.md#architecture).

## Discovery

Scan compiled class files for `@Preview` annotations →
`build/compose-previews/previews.json`.

```
For each method in each compiled class:

  1. Check for direct @Preview or @Preview.Container annotations on the method.
     If found, extract preview parameters (name, device, dimensions, backgroundColor, etc.)
     and emit a preview entry.

  2. Otherwise, walk the method's annotations looking for multi-preview meta-annotations.
     For each annotation, check whether *its* annotation class carries @Preview.
     Recurse through meta-annotations (with cycle detection via a visited set).
     Emit a preview entry for each @Preview found transitively.

Deduplicate by fully-qualified name + preview name + device + dimensions.
```

## Rendering (Desktop)

Launch a subprocess with the module's full classpath plus the
`renderer-desktop` module.

```
1. Load the target class by name and resolve the composable function
   via the Compose runtime's reflection API.

2. Create a headless ImageComposeScene at the target dimensions (2x density).

3. Set the scene content to: a background fill (from the @Preview annotation's
   backgroundColor), with the composable function invoked inside it.
   LocalInspectionMode is enabled so preview-aware composables render correctly.

4. Render two frames (the second allows animations and effects to settle).

5. Encode the Skia surface to PNG and write to the output file.
```

## Rendering (Android)

Launch a Gradle `Test` task inside a Robolectric sandbox with native
graphics (`graphicsMode=NATIVE`, `pixelCopyRenderMode=hardware`).

```
1. Bootstrap a ComponentActivity through `createAndroidComposeRule`.
   Apply the @Preview qualifiers (size, density, locale, uiMode, round,
   orientation) via `RuntimeEnvironment.setQualifiers` and `setFontScale`.

2. Set the activity content to a background fill + reflected composable
   invocation, with `LocalInspectionMode = true`.

3. Pause Compose's main clock (`autoAdvance = false`) and step it by a
   fixed amount so infinite animations terminate deterministically instead
   of hanging the idling resource.

4. Capture the root view via `captureRoboImage`, which routes ShadowPixelCopy
   through HardwareRenderer + ImageReader to replay Compose's RenderNodes,
   compress as PNG, write to file.
```

## Caching

Both discovery and rendering are Gradle-cacheable tasks with declared
input/output contracts. Unchanged source files produce no re-work on
subsequent runs. Configuration caching is strict (`problems=fail`).

## Plugin configuration

Apply the plugin to a module (see
[`samples/android/build.gradle.kts`](../samples/android/build.gradle.kts),
[`samples/wear/build.gradle.kts`](../samples/wear/build.gradle.kts), or
[`samples/cmp/build.gradle.kts`](../samples/cmp/build.gradle.kts) for
working examples):

```kotlin
composePreview {
    variant.set("debug")     // Android build variant (default: "debug")
    sdkVersion.set(35)       // Robolectric SDK version (default: 35)
    enabled.set(true)        // disable to skip registration (default: true)
}
```

## Project structure

| Module | Purpose |
|--------|---------|
| `gradle-plugin/` | Gradle plugin — discovery, rendering task orchestration |
| `renderer-desktop/` | Desktop renderer — `ImageComposeScene` + Skia PNG capture |
| `renderer-android/` | Android renderer — Robolectric harness |
| `preview-annotations/` | Shared annotations consumed by samples (`@ScrollingPreview`, etc.) |
| `cli/` | CLI — Tooling-API driver over `discoverPreviews` / `renderAllPreviews` |
| `vscode-extension/` | VS Code extension that surfaces rendered previews in the editor |
| `samples/android/` | Android sample with colored box `@Preview` composables |
| `samples/android-library/` | Android library variant — exercises AAR class-jar discovery |
| `samples/android-screenshot-test/` | Co-existence with `com.android.compose.screenshot` |
| `samples/wear/` | Wear OS sample — Material 3 Expressive, `EdgeButton`, tile previews |
| `samples/cmp/` | CMP Desktop sample with colored box `@Preview` composables |
| `samples/remotecompose/` | Remote Compose sample — wrapper-inside-Composable vs. `@PreviewWrapper(RemotePreviewWrapper::class)` against `wear-compose-remote-material3` |

## Requirements

- Gradle 9.4.1+
- Java 17 or newer (renderer / plugin target JDK 17 bytecode; any newer JDK runs them)
- AGP 9.1.0 (Android projects)
- Kotlin 2.2.21
- Compose Multiplatform 1.10.3 (Desktop projects)
