# compose-ai-tools

A Gradle plugin and CLI that discovers `@Preview` composables and renders them to PNG — outside
of Android Studio. Works with both Android (Jetpack Compose) and Compose Multiplatform
Desktop projects.

See [SKILL.md](https://github.com/yschimke/compose-ai-tools/blob/main/skills/compose-preview/SKILL.md)

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

## Cloud sandboxes (Claude Code on the web, etc.)

If you're running this in a cloud agent sandbox, two things are required:

1. **Allowlist the four Google hosts** the Android + downloadable-fonts render
   paths pull from. In the Claude Code web UI, switch **Network access** from
   *Trusted* to **Custom**, keep *"include Trusted defaults"* on, and add:

   | Host | Used for |
   | --- | --- |
   | `maven.google.com` | AGP + AndroidX resolution |
   | `dl.google.com` | Android SDK cmdline-tools / Google Maven mirror |
   | `fonts.googleapis.com` | Google Fonts API (downloadable fonts) |
   | `fonts.gstatic.com` | Google Fonts static assets |

   Pure CMP Desktop / JVM consumers can stay on *Trusted* — the four hosts
   are Android-specific.

   Building the `:cli` module *from source* (not using `install.sh`) also
   needs `repo.gradle.org` — it hosts `gradle-tooling-api` and isn't on the
   Trusted defaults. If you consume the released CLI tarball this doesn't
   apply.

2. **Drop the install script into your environment setup.** It installs
   the CLI and the skill bundle (at `~/.claude/skills/compose-preview/`),
   reuses the pre-installed JDK 21 (falling back to apt-installing JDK 17
   only if no 17+ JDK is present), and appends `PATH` (and `JAVA_HOME`
   when the fallback fires) to `$CLAUDE_ENV_FILE` so every subsequent
   tool invocation in the session inherits them:

   ```sh
   curl -fsSL https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/install.sh | bash
   ```

`compose-preview doctor` probes all four hosts and, when it sees
`$CLAUDE_CODE_SESSION_ID` / `$CLAUDE_ENV_FILE`, tailors its remediation to the
Claude Code UI (Trusted → Custom, add the missing host). Full walk-through
including Gradle pre-warming, Android SDK install, and proxy gotchas:
[skills/compose-preview/design/CLAUDE_CLOUD.md](skills/compose-preview/design/CLAUDE_CLOUD.md).

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

Launch a Gradle `Test` task inside a Robolectric sandbox with native graphics
(`graphicsMode=NATIVE`, `pixelCopyRenderMode=hardware`).

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

### Caching

Both discovery and rendering are Gradle cacheable tasks with declared input/output
contracts. Unchanged source files produce no re-work on subsequent runs.
Configuration caching is strict (`problems=fail`).

## Setup

The plugin is published to [Maven Central](https://central.sonatype.com/artifact/ee.schimke.composeai/compose-preview-plugin).
No authentication, no PAT — just apply it.

### 1. Apply the plugin

<!-- x-release-please-start-version -->
```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.8.1"
}
```
<!-- x-release-please-end -->

Most projects already have `mavenCentral()` in their
`pluginManagement.repositories` (AGP and the Kotlin Gradle Plugin are both
on Central). If yours doesn't, add it:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
```

CMP Desktop projects additionally need
`implementation(compose.components.uiToolingPreview)` — the bundled
`@Preview` annotation has `SOURCE` retention and is invisible to ClassGraph.

Check [Releases](https://github.com/yschimke/compose-ai-tools/releases) for
the latest version.

### Testing against a snapshot

Every push to `main` publishes a `-SNAPSHOT` build to the Central snapshots
repository. To try an unreleased change, add the snapshots repo to
`pluginManagement` and bump the plugin version to the next patch
`-SNAPSHOT`:

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

```kotlin
// <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview") version "0.3.5-SNAPSHOT"
}
```

The snapshot version is the next patch ahead of the latest release
(e.g. last tag `v0.3.4` → `0.3.5-SNAPSHOT`). Snapshots are unsigned. See
[docs/RELEASING.md](docs/RELEASING.md) for more detail.

## Install the CLI

<!-- x-release-please-start-version -->
Download `compose-preview-0.8.1.tar.gz` (or `.zip`) from the
[v0.8.1 release](https://github.com/yschimke/compose-ai-tools/releases/tag/v0.4.0)
and put the `bin/` directory on your `PATH`:

```sh
curl -L -o compose-preview.tar.gz \
  https://github.com/yschimke/compose-ai-tools/releases/download/v0.8.1/compose-preview-0.4.0.tar.gz
tar -xzf compose-preview.tar.gz
export PATH="$PWD/compose-preview-0.8.1/bin:$PATH"

compose-preview --help
```
<!-- x-release-please-end -->

Requires Java 17 or newer on `PATH` (or `JAVA_HOME`). JDK 21 / 25 are
fine — the CLI and renderer are compiled to JDK 17 bytecode.

## Install the VS Code extension

Install [Compose Preview](https://marketplace.visualstudio.com/items?itemName=yuri-schimke.compose-preview)
from the VS Code Marketplace, or from the command line:

```sh
code --install-extension yuri-schimke.compose-preview
```

The extension uses the Gradle plugin to render previews, so apply
`ee.schimke.composeai.preview` version `0.8.1` <!-- x-release-please-version --> in your project as shown above.

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
| `cli/` | CLI — Tooling-API driver over `discoverPreviews` / `renderAllPreviews` |
| `sample-android/` | Android sample with colored box `@Preview` composables |
| `sample-cmp/` | CMP Desktop sample with colored box `@Preview` composables |
| `sample-remotecompose/` | Remote Compose sample — wrapper-inside-Composable vs. `@PreviewWrapper(RemotePreviewWrapper::class)` against `wear-compose-remote-material3` |

## Requirements

- Gradle 9.4.1+
- Java 17 or newer (renderer / plugin target JDK 17 bytecode; any newer JDK runs them)
- AGP 9.1.0 (Android projects)
- Kotlin 2.2.21
- Compose Multiplatform 1.10.3 (Desktop projects)

## Contributing

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for building the plugin, CLI, and
VS Code extension from source and running them locally against the bundled
samples.
