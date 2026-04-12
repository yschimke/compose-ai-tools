# compose-ai-tools

A Gradle plugin that discovers `@Preview` composables and renders them to PNG — outside
of Android Studio. Works with both Android (Jetpack Compose) and Compose Multiplatform
Desktop projects.

## How it works

### Discovery

Scan compiled class files for `@Preview` annotations → `build/compose-previews/previews.json`.

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

### Rendering (Desktop)

Launch a subprocess with the module's full classpath plus the renderer-desktop module.

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

### Rendering (Android — WIP)

Launch a subprocess inside a Robolectric sandbox with native graphics.

```
1. Bootstrap a ComponentActivity through Robolectric's activity lifecycle.
   Configure the shadow display to match the target dimensions.

2. Set the activity content to the composable (same as Desktop:
   background fill + reflected composable invocation + inspection mode).

3. Advance the main looper frame-by-frame (16ms per frame, up to 20 frames).
   After each frame, sample ~64 pixels and compute a checksum.
   Stop when the checksum is stable for 2 consecutive frames.

4. Draw the activity's root view to a bitmap, compress as PNG, write to file.
```

### Caching

Both discovery and rendering are Gradle cacheable tasks with declared input/output
contracts. Unchanged source files produce no re-work on subsequent runs.
Configuration caching is strict (`problems=fail`).

## Usage

Apply the plugin to any module with Compose:

```kotlin
// build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview")
}
```

Run discovery and rendering:

```
./gradlew :app:discoverPreviews    # scan for @Preview annotations
./gradlew :app:renderAllPreviews   # discover + render to PNG
```

### Configuration

```kotlin
composePreview {
    variant.set("debug")     // Android build variant (default: "debug")
    sdkVersion.set(35)       // Robolectric SDK version (default: 35)
    enabled.set(true)        // disable to skip registration (default: true)
}
```

### Preview annotations

Use `androidx.compose.ui.tooling.preview.Preview` with `RUNTIME` retention. The CMP
Desktop annotation (`androidx.compose.desktop.ui.tooling.preview.Preview`) has `SOURCE`
retention and is invisible to ClassGraph.

```kotlin
// Add to CMP Desktop projects:
implementation(compose.components.uiToolingPreview)
```

## Project structure

| Module | Purpose |
|--------|---------|
| `gradle-plugin/` | Gradle plugin — discovery, rendering task orchestration |
| `renderer-desktop/` | Desktop renderer — `ImageComposeScene` + Skia PNG capture |
| `renderer-android/` | Android renderer — Robolectric harness (WIP) |
| `cli/` | CLI with GraalVM native-image support |
| `sample-android/` | Android sample with colored box `@Preview` composables |
| `sample-cmp/` | CMP Desktop sample with colored box `@Preview` composables |

## Requirements

- Gradle 9.4.1+
- Java 21
- AGP 9.1.0 (Android projects)
- Kotlin 2.3.20
- Compose Multiplatform 1.10.3 (Desktop projects)
