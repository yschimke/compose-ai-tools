# AGENTS.md

This file provides guidance to Agents when working with code in this repository.

## What this project is

A Gradle plugin (`ee.schimke.composeai.preview`) plus supporting tools that discover `@Preview` composables in compiled Kotlin classes and render them to PNG outside Android Studio. Targets both Jetpack Compose (Android, via Robolectric) and Compose Multiplatform Desktop (via `ImageComposeScene`).

## Common commands

Build / test everything:
```
./gradlew check                   # plugin unit + functional tests, CLI tests
```

Render the sample previews (end-to-end smoke test of the full pipeline):
```
./gradlew :sample-cmp:renderAllPreviews
./gradlew :sample-android:renderAllPreviews
```

The samples consume the plugin through `includeBuild("gradle-plugin")` in [settings.gradle.kts](settings.gradle.kts), so any plugin edit is picked up automatically — no publish step.

Single test:
```
./gradlew :gradle-plugin:test --tests "ee.schimke.composeai.plugin.DeviceDimensionsTest"
./gradlew :gradle-plugin:functionalTest --tests "ee.schimke.composeai.plugin.RenderFunctionalTest"
```

CLI (install to `cli/build/install/compose-preview/bin/compose-preview`):
```
./gradlew :cli:installDist
```

VS Code extension:
```
cd vscode-extension && npm install && npm run compile && npm test
```
For live dev, open [vscode-extension/](vscode-extension/) in VS Code and press F5 — see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the three install modes and their tradeoffs.

Publish plugin locally for external consumers (not needed for in-repo samples):
```
./gradlew :gradle-plugin:publishToMavenLocal
```

## Architecture

Four-stage pipeline, spread across the modules:

1. **Discovery** — [gradle-plugin/](gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/) scans compiled `.class` files with ClassGraph for `@Preview` annotations (including transitive multi-preview meta-annotations with cycle detection) and writes `build/compose-previews/previews.json`. Entry point: [DiscoverPreviewsTask.kt](gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DiscoverPreviewsTask.kt).

2. **Task wiring** — [ComposePreviewPlugin.kt](gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/ComposePreviewPlugin.kt) registers `discoverPreviews`, `renderPreviews`, `historizePreviews`, and the user-facing `renderAllPreviews` aggregate. It detects Android vs CMP Desktop at configuration time and takes different paths:
   - **Android:** uses AGP `artifactView` filters (`artifactType=jar`, `android-classes`) to resolve AAR-extracted class jars, copies JVM args from AGP's `test<Variant>UnitTest` task, and launches a Gradle `Test` task that runs [RobolectricRenderTest.kt](renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/RobolectricRenderTest.kt) inside a Robolectric sandbox with `graphicsMode=NATIVE`. `android.jar` is added so the Robolectric runner classes load before the sandbox classloader takes over.
   - **Desktop/JVM:** creates a `composePreviewRenderer` configuration pointing at `:renderer-desktop`, then launches [DesktopRendererMain.kt](renderer-desktop/src/main/kotlin/ee/schimke/composeai/renderer/DesktopRendererMain.kt) as a subprocess with the module's runtime classpath plus the renderer.

3. **Rendering** — both backends reflect the target composable function, invoke it inside a background fill with `LocalInspectionMode=true`, and capture to PNG. Desktop uses `ImageComposeScene` at 2x density. Android boots a `ComponentActivity` under `Theme_Material_Light_NoActionBar`, installs a paused `BroadcastFrameClock`-backed `Recomposer` (so infinite animations park on their initial frame), forces the injected `ComposeView` to `MATCH_PARENT`, measures/lays-out the decor at the preview's pixel size, and captures via `org.robolectric.shadows.HardwareRenderingScreenshot.takeScreenshot` (reached through reflection to bypass `PixelCopy`'s `canHaveDisplayList` gate, which Robolectric doesn't satisfy even under NATIVE graphics).

4. **History (optional)** — [HistorizePreviewsTask.kt](gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/HistorizePreviewsTask.kt) archives changed PNGs into `.compose-preview-history/` (outside `build/`, survives `clean`). Enabled via `composePreview.historyEnabled`.

The CLI ([cli/](cli/src/main/kotlin/ee/schimke/composeai/cli/)) and VS Code extension ([vscode-extension/](vscode-extension/src/)) are thin drivers over the Gradle tasks — they shell out via the Tooling API (`GradleConnector.kt`, `gradleService.ts`) and read the resulting `previews.json` / PNG files. The CLI also ships a `compose-preview` binary with `installDist` for use as an agent/MCP backend.

## Git conventions

- **Do not add `Co-Authored-By` trailers** to git commit messages. Commits should be attributed solely to the committer.

## Important constraints

- **Configuration cache is strict** (`problems=fail` in [gradle.properties](gradle.properties)). Changes to plugin code must resolve classpaths/JVM args at configuration time via lazy providers — never call `.files` inside a task action or touch `project.*` at execution time.
- **CMP Desktop previews require `implementation(compose.components.uiToolingPreview)`** — the bundled `@Preview` has `SOURCE` retention and is invisible to ClassGraph otherwise.
- **Toolchain:** Java 21, Kotlin 2.2.21, Gradle 9.4.1+, AGP 9.1.0, CMP 1.10.3. Always use the bundled `./gradlew` wrapper.
- **Do not run `collectPreviewInfo` / other internal plugin tasks by hand** — the plugin wires them as dependencies of `renderAllPreviews`.
- **Plugin version** is driven by `.release-please-manifest.json` at the repo root (single source of truth, maintained by release-please). The three `build.gradle.kts` files read that manifest and compute next-patch `-SNAPSHOT` for local builds; CI overrides with the `PLUGIN_VERSION` env var from the git tag or `snapshot.yml`. See [docs/RELEASING.md](RELEASING.md).
- **Android renderer is pinned to Robolectric SDK 34** via `@Config(sdk = [34])` in [RobolectricRenderTest.kt](renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/RobolectricRenderTest.kt). Robolectric 4.16.1's `ShadowNativeImageReaderSurfaceImage.nativeCreatePlanes` is gated `maxSdk=UPSIDE_DOWN_CAKE`; on API 35+ the un-shadowed AOSP native yields `Image.planes[0] == null` and capture fails. Re-test and remove the pin when bumping Robolectric.
- **Renderer-vs-consumer AndroidX version alignment is load-bearing.** The renderer AAR goes out of its way to avoid dragging newer Compose / Activity / Core versions onto the consumer's unit-test classpath (since AGP builds `apk-for-local-test.ap_` from the consumer's own deps, classes-vs-resources mismatches are easy to introduce). Known failure signatures, current mitigations (`compileOnly` + `extendsFrom(testConfig)` + `ui-test-manifest` injection), follow-ups for `compose-preview doctor`, and tile-rendering gaps are catalogued in [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md) — consult it before bumping `libs.versions.toml` or changing how `AndroidPreviewSupport` wires the test classpath.

## Tests

- `:gradle-plugin:test` — unit tests on preview-data / device-dimension parsing.
- `:gradle-plugin:functionalTest` — Gradle TestKit tests that apply the plugin to synthetic projects and assert on `previews.json` + rendered PNGs. These are the source of truth for end-to-end plugin behavior; add one here when changing discovery or task wiring.
- `:renderer-android:test` — JVM unit tests for render helpers (no Robolectric).
- `vscode-extension` uses Mocha against compiled `out/test/**`.
