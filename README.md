# compose-ai-tools

A Gradle plugin and CLI that discovers `@Preview` composables and renders them to PNG — outside
of Android Studio. Works with both Android (Jetpack Compose) and Compose Multiplatform
Desktop projects.

See [SKILL.md](https://github.com/yschimke/compose-ai-tools/blob/main/docs/SKILL.md)

```
$ compose-preview list --module sample-wear
com.example.samplewear.PreviewsKt.ActivityListPreview_Devices - Large Round  (com/example/samplewear/Previews.kt)
com.example.samplewear.PreviewsKt.ActivityListPreview_Devices - Small Round  (com/example/samplewear/Previews.kt)
com.example.samplewear.PreviewsKt.ActivityListFontScalesPreview_Fonts - Large  (com/example/samplewear/Previews.kt)
com.example.samplewear.PreviewsKt.ActivityListFontScalesPreview_Fonts - Larger  (com/example/samplewear/Previews.kt)
com.example.samplewear.PreviewsKt.ActivityListFontScalesPreview_Fonts - Largest  (com/example/samplewear/Previews.kt)
com.example.samplewear.PreviewsKt.ActivityListFontScalesPreview_Fonts - Medium  (com/example/samplewear/Previews.kt)
com.example.samplewear.PreviewsKt.ActivityListFontScalesPreview_Fonts - Normal  (com/example/samplewear/Previews.kt)
com.example.samplewear.PreviewsKt.ActivityListFontScalesPreview_Fonts - Small  (com/example/samplewear/Previews.kt)
com.example.samplewear.PreviewsKt.ButtonPreview_Devices - Large Round  (com/example/samplewear/Previews.kt)
com.example.samplewear.PreviewsKt.ButtonPreview_Devices - Small Round  (com/example/samplewear/Previews.kt)
```

Also provides a VS Code plugin that displays them

<img height="400" alt="image" src="https://github.com/user-attachments/assets/fe9be596-13d9-4880-9e20-cedd6992f650" />


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

### Rendering (Android)

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

You need a Personal Access Token to read Maven artifacts from GitHub Packages
(authentication is required even for public repos). You only need the
**`read:packages`** scope — nothing else.

**Classic token (simplest):**

1. Go to <https://github.com/settings/tokens/new>.
2. Name: e.g. `compose-ai-tools`. Expiration: whatever you prefer.
3. Tick only **`read:packages`**.
4. Click **Generate token** and copy the value (starts with `ghp_`).

**Fine-grained token:** at
<https://github.com/settings/personal-access-tokens/new> grant **Account
permissions → Packages: Read-only**. Repository access can stay at "Public
repositories (read-only)".

**Or reuse your `gh` CLI token** if you're already signed in:

```sh
gh auth token   # prints a token with read:packages by default
```

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
}
```

### 4. Apply the plugin

```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.3.2"
}
```

Check [Releases](https://github.com/yschimke/compose-ai-tools/releases) for the
latest version. CMP Desktop projects also need
`implementation(compose.components.uiToolingPreview)` — the bundled
`@Preview` annotation has `SOURCE` retention and is invisible to ClassGraph.

## Install the CLI

Download `compose-preview-0.3.2.tar.gz` (or `.zip`) from the
[v0.3.2 release](https://github.com/yschimke/compose-ai-tools/releases/tag/v0.3.2)
and put the `bin/` directory on your `PATH`:

```sh
curl -L -o compose-preview.tar.gz \
  https://github.com/yschimke/compose-ai-tools/releases/download/v0.3.2/compose-preview-0.3.2.tar.gz
tar -xzf compose-preview.tar.gz
export PATH="$PWD/compose-preview-0.3.2/bin:$PATH"

compose-preview --help
```

Requires Java 21 on `PATH` (or `JAVA_HOME`).

## Install the VS Code extension

Download `compose-preview-0.3.2.vsix` from the
[v0.3.2 release](https://github.com/yschimke/compose-ai-tools/releases/tag/v0.3.2)
and install it:

```sh
code --install-extension compose-preview-0.3.2.vsix
```

Or in VS Code: **Extensions → ⋯ → Install from VSIX…** and pick the file.

The extension uses the Gradle plugin to render previews, so apply
`ee.schimke.composeai.preview` version `0.3.2` in your project as shown above.

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
| `renderer-android/` | Android renderer — Robolectric harness |
| `cli/` | CLI with GraalVM native-image support |
| `sample-android/` | Android sample with colored box `@Preview` composables |
| `sample-cmp/` | CMP Desktop sample with colored box `@Preview` composables |

## Requirements

- Gradle 9.4.1+
- Java 21
- AGP 9.1.0 (Android projects)
- Kotlin 2.2.21
- Compose Multiplatform 1.10.3 (Desktop projects)

## Contributing

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for building the plugin, CLI, and
VS Code extension from source and running them locally against the bundled
samples.
