# Development

Instructions for building the project from source and running it locally against
the bundled samples, without going through GitHub Packages or the VS Code
Marketplace.

There are three shipping artifacts, each with its own local install story:

| Artifact | Built by | Consumed via |
|----------|----------|--------------|
| Gradle plugin (`ee.schimke.composeai.preview`) | `includeBuild` (in-repo, preferred) or `./gradlew :gradle-plugin:publishToMavenLocal` | `includeBuild("…/gradle-plugin")` or `mavenLocal()` in a consumer's `settings.gradle.kts` |
| CLI (`compose-preview`) | `./gradlew :cli:installDist` | Symlink into `~/.local/bin` (or add to `$PATH`) |
| VS Code extension | `cd vscode-extension && npm install && npm run compile` | Extension Dev Host (F5), folder symlink, or `.vsix` |

## Prerequisites

- Java 21 (`JAVA_HOME` set)
- Gradle wrapper ships with the repo — do **not** install Gradle separately
- Node 20+ and `npm` (for the VS Code extension)
- VS Code 1.85+ (only if working on the extension)

The bundled samples ([sample-android/](../sample-android/),
[sample-cmp/](../sample-cmp/)) depend on the `gradle-plugin` module via
[`includeBuild`](../settings.gradle.kts#L20), so running `./gradlew` at the repo
root always picks up local plugin changes without any publishing step.

## Gradle plugin

Inside this repo, the samples pick up the plugin automatically through the
composite build. Nothing to do.

For an external consumer project on the same machine, pick one of the two
approaches below.

### Option A — Composite build (recommended)

Have the consumer include this repo's `gradle-plugin/` directory directly. No
publish step, no cached artifacts to go stale.

```kotlin
// consumer's settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    includeBuild("../compose-ai-tools/gradle-plugin")
}
```

```kotlin
// consumer's <module>/build.gradle.kts
plugins {
    id("ee.schimke.composeai.preview")   // no version — resolved from includeBuild
}
```

Edits to plugin source rebuild automatically on the next consumer build.

### Option B — `publishToMavenLocal`

Useful when you want to pin a specific built version, or when the consumer
can't reach the plugin sources (e.g. Docker build, other machine via rsync).

```
./gradlew :gradle-plugin:publishToMavenLocal
```

Then in the consumer's `settings.gradle.kts`, swap the GitHub Packages block
from the [README](../README.md#3-register-the-plugin-repository) for
`mavenLocal()`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        mavenLocal()
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

The version string must match the one in
[gradle-plugin/build.gradle.kts](../gradle-plugin/build.gradle.kts#L9) — default
is `0.1.0-SNAPSHOT` unless `PLUGIN_VERSION` is set.

### Smoke test

```
./gradlew :sample-cmp:renderAllPreviews
open sample-cmp/build/compose-previews/renders/
```

## CLI

Build the install image (a native-ish shell launcher + all jars on the
classpath):

```
./gradlew :cli:installDist
```

Output: `cli/build/install/compose-preview/bin/compose-preview`.

Wire it onto your `$PATH` — easiest is a symlink into a directory already on
`$PATH`:

```
ln -sf "$PWD/cli/build/install/compose-preview/bin/compose-preview" ~/.local/bin/compose-preview
```

Verify against a sample:

```
cd sample-cmp
compose-preview list
compose-preview show --filter RedBox
```

Re-running `./gradlew :cli:installDist` after code changes refreshes the
launcher — the symlink does not need to be re-created.

## VS Code extension

Install dev dependencies and compile TypeScript once:

```
cd vscode-extension
npm install
npm run compile
```

Three ways to run the extension locally, in order of preference for day-to-day
work:

### Option 1 — Extension Development Host (recommended while coding)

1. Open [vscode-extension/](../vscode-extension/) as its own VS Code window.
2. Press `F5` (or Run → Start Debugging).
3. A second VS Code window opens with the extension loaded against the
   **repo root** — both [sample-android/](../sample-android/) and
   [sample-cmp/](../sample-cmp/) are visible, and the module picker at the
   top of the Compose Preview panel lets you switch between them. Edit
   TypeScript, press `Ctrl+Shift+F5` to reload.

The launch config lives at
[vscode-extension/.vscode/launch.json](../vscode-extension/.vscode/launch.json)
with four variants: repo root (default), sample-cmp only, sample-android only,
and an empty host. Pick from the Run & Debug dropdown before pressing F5.

For a tighter edit/reload cycle, run `npm run watch` in a terminal — the
compile-on-save will be picked up by the next host reload.

### Option 2 — Symlink into the user extensions directory

Useful when you want the extension active in your everyday VS Code without
running a second window. Make sure `out/` is built (`npm run compile`), then:

```
ln -sfn "$PWD" ~/.vscode/extensions/schimke.compose-preview-dev
```

Fully restart VS Code (reloading the window is not always enough on first
install). To update, just re-run `npm run compile` — VS Code picks up the new
`out/*.js` next time it activates the extension.

To uninstall: `rm ~/.vscode/extensions/schimke.compose-preview-dev`.

> Do **not** also have the Marketplace version installed — VS Code will load
> both and you'll see duplicate views.

### Option 3 — Build and install a .vsix

Closest to what a real user gets:

```
npm run package
code --install-extension compose-preview-0.1.0.vsix
```

The `package` script runs `vsce package --no-dependencies`, which requires
`out/` to already be built (the `compile` script is chained in automatically).

## Running the test suites

```
./gradlew check                   # gradle-plugin unit + functional tests, CLI tests
cd vscode-extension && npm test   # extension unit tests (mocha)
```

## Troubleshooting

### Android renders fail with `planes[0] is null` after bumping Robolectric

The Android renderer captures via
[`HardwareRenderingScreenshot.takeScreenshot`](../renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/RobolectricRenderTest.kt),
which goes through `ImageReader` + `HardwareRenderer.syncAndDraw`. In Robolectric
4.16.1, `ShadowNativeImageReaderSurfaceImage.nativeCreatePlanes` is gated
`maxSdk = UPSIDE_DOWN_CAKE` (API 34). Running against API 35+ leaves the native
method un-shadowed, so `acquireNextImage().getPlanes()[0]` comes back null.

The renderer pins itself to SDK 34 via `@Config(sdk = [34])` on
`RobolectricRenderTest`. When upgrading Robolectric, re-run
`./gradlew :sample-android:renderPreviews` with the pin removed (or bumped); if
it passes, drop the pin. Tracking issues: robolectric/robolectric#9595, #9745,
#9971.

### `IllegalAccessException: … DirectByteBuffer … modifiers "public"`

Robolectric's `ShadowVMRuntime.getAddressOfDirectByteBuffer` reflects into
`java.nio.DirectByteBuffer.address()`; under JDK 17+ module rules that fails
without `--add-opens=java.base/java.nio=ALL-UNNAMED`. The `renderPreviews`
Gradle task already adds that opens (along with `java.lang` and
`java.lang.reflect`) in
[ComposePreviewPlugin.kt](../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/ComposePreviewPlugin.kt);
if a downstream Test task hits this error, mirror the JVM args.

### `jlink executable … does not exist` when building from VS Code

The `vscjava.vscode-gradle` extension inherits its JDK from
`redhat.java`, which ships a **JRE** (no `jlink`). The Android Gradle
Plugin's `JdkImageTransform` needs `jlink`, so Android builds triggered
from inside VS Code fail with:

```
jlink executable …/redhat.java-*/jre/*/bin/jlink does not exist.
```

Point both the Java language server and the Gradle integration at a
full JDK 21 install in your **user** `settings.json`:

```jsonc
{
  "java.jdt.ls.java.home": "/usr/lib/jvm/java-21-openjdk",
  "java.import.gradle.java.home": "/usr/lib/jvm/java-21-openjdk"
}
```

Adjust the path for your OS (`/Library/Java/JavaVirtualMachines/…/Contents/Home`
on macOS, `C:\\Program Files\\Java\\jdk-21` on Windows). Reload the
window after changing. Verify with `ls $JDK_PATH/bin/jlink` — it must
exist.
