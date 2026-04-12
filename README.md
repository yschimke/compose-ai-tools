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

## Setup

The plugin is published to [GitHub Packages](https://github.com/yschimke/compose-ai-tools/packages). GitHub
requires authentication for reading Maven artifacts even from public repos,
so you need a Personal Access Token.

### 1. Create a GitHub PAT

Generate a [classic PAT](https://github.com/settings/tokens/new) with the
**`read:packages`** scope — that's the only scope consumers need.

If you already use `gh`, you can reuse its token: `gh auth token`.

### 2. Store credentials (not in the repo)

Add to `~/.gradle/gradle.properties` so they apply to every build on your machine:

```properties
composeAiTools.githubUser=your-github-username
composeAiTools.githubToken=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

On CI, pass them via environment variables `GITHUB_ACTOR` / `GITHUB_TOKEN`
(both are pre-populated in GitHub Actions — no config needed).

### 3. Register the plugin repository

In `settings.gradle.kts`:

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

The `resolutionStrategy` block is required because GitHub Packages doesn't host
Gradle plugin marker artifacts — we point the plugin id at the underlying Maven
coordinates directly.

### 4. Apply the plugin

```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.1.1"
}
```

Check [Releases](https://github.com/yschimke/compose-ai-tools/releases) for the
latest version. CMP Desktop projects also need
`implementation(compose.components.uiToolingPreview)` — the bundled
`@Preview` annotation has `SOURCE` retention and is invisible to ClassGraph.

## Usage

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
