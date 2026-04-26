# AGENTS.md

This file provides guidance to Agents when working with code in this repository.

## What this project is

A Gradle plugin (`ee.schimke.composeai.preview`) plus supporting tools that discover `@Preview` composables in compiled Kotlin classes and render them to PNG outside Android Studio. Targets both Jetpack Compose (Android, via Robolectric) and Compose Multiplatform Desktop (via `ImageComposeScene`).

## Documentation map: contributor vs. consumer

Two audiences, two doc trees. Don't conflate them:

- **This file + `docs/`** — contributor docs for working on *this repo*: editing the plugin, CLI, renderer modules, or VS Code extension; running the in-repo samples through `includeBuild("gradle-plugin")`; publishing releases. Build commands here use `./gradlew` against the local source tree.
- **[`skills/compose-preview/SKILL.md`](../skills/compose-preview/SKILL.md) + [`skills/compose-preview/design/`](../skills/compose-preview/design/)** — consumer docs shipped to end users (humans and agents) of the *published* plugin and CLI. These describe how to apply `id("ee.schimke.composeai.preview")` to a downstream project and drive `compose-preview` against it. The `design/` subtree is per-target-stack guidance:
  - [`CLAUDE_CLOUD.md`](../skills/compose-preview/design/CLAUDE_CLOUD.md) — running in Claude Code on the web (network allowlist, Setup script with `install.sh --android-sdk`, JVM-proxy gotcha)
  - [`CI_PREVIEWS.md`](../skills/compose-preview/design/CI_PREVIEWS.md) — maintaining a `preview_main` branch with rendered PNGs and a `baselines.json` for diff-on-PR workflows
  - [`AGENT_PR.md`](../skills/compose-preview/design/AGENT_PR.md) — authoring agent-opened PRs and reviewing PRs opened by other agents
  - [`CMP_SHARED.md`](../skills/compose-preview/design/CMP_SHARED.md) — applying the plugin to a CMP `:shared` (`com.android.kotlin.multiplatform.library`) module: previews go in `commonMain`, JVM target gives the Desktop renderer something to attach to (issue #248)
  - [`WEAR_UI.md`](../skills/compose-preview/design/WEAR_UI.md) — Material 3 Expressive design language for Wear OS
  - [`WEAR_TILES.md`](../skills/compose-preview/design/WEAR_TILES.md) — Wear Tiles (protolayout-based, not Compose)
  - [`REMOTE_COMPOSE.md`](../skills/compose-preview/design/REMOTE_COMPOSE.md) — Remote Compose (RemoteDocument byte stream for watch faces, tiles, widgets)

The skill files are bundled into `compose-preview-skill-<ver>.tar.gz` at release time and end up under `~/.claude/skills/compose-preview/` after `scripts/install.sh` runs — so anything you change in `skills/` is what consumers (and their agents) see, not what contributors editing this repo see. When you change consumer-facing behaviour (a new flag, a network requirement, a setup-script step), update `skills/compose-preview/...`, not this file. Cross-link from here when contributors need the same information for sandbox setup (e.g. the Android SDK bootstrap referenced from "Bringing up a fresh sandbox" below).

## Common commands

Build / test everything:
```
./gradlew check                   # plugin unit + functional tests, CLI tests
```

Render the sample previews (end-to-end smoke test of the full pipeline):
```
./gradlew :samples:cmp:renderAllPreviews
./gradlew :samples:android:renderAllPreviews
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

3. **Rendering** — both backends reflect the target composable function, invoke it inside a background fill, and capture to PNG.

   - **Desktop:** `ImageComposeScene` at 2x density; two `scene.render()` calls so `LaunchedEffect`s / animations get one frame to settle before encoding.
   - **Android:** `createAndroidComposeRule<ComponentActivity>()` + `onRoot().captureRoboImage(...)`. Two paths selected at runtime from the `composeai.a11y.enabled` system property:
     - **Default** (`renderDefault`) — `mainClock.autoAdvance = false`, `advanceTimeBy(CAPTURE_ADVANCE_MS)`, then capture. The paused clock is what lets infinite animations (indeterminate `CircularProgressIndicator`, `rememberInfiniteTransition`, hand-rolled `withFrameNanos` loops) terminate deterministically instead of hanging Compose's idling resource. Time is expressed in ms rather than frame count so a future `@RoboComposePreviewOptions` / `ManualClockOptions(advanceTimeMillis = …)` override plugs straight in. `LocalInspectionMode = true`.
     - **A11y** (`renderWithA11y`, gated by `composePreview.accessibilityChecks.enabled`) — same rule, but `LocalInspectionMode = false` so Compose populates real accessibility semantics; after capture, ATF walks the `ViewRootForTest` view via `AccessibilityChecker`. Trade-off: infinite animations tick through rather than parking, because ATF needs live semantics.
   
   Options are applied by hand in `renderDefault` rather than through `RoborazziComposeOptions` (its `configured(...)` chain wants an `ActivityScenario` it owns, awkward to share with `ComposeTestRule`): size/locale/uiMode/round/orientation via `RuntimeEnvironment.setQualifiers` (strict grammar order — locale, width, height, round, orientation, night); fontScale via `RuntimeEnvironment.setFontScale` (Configuration field, not a qualifier — same knob Roborazzi's `RoborazziComposeFontScaleOption` uses); background and inspection via `CompositionLocalProvider`.
   
   Capture path: `ShadowPixelCopy` is routed to `HardwareRenderingScreenshot` → `ImageReader + HardwareRenderer.syncAndDraw` via `robolectric.pixelCopyRenderMode=hardware` on the `renderPreviews` `Test` task — the only path that replays Compose's `RenderNode`s correctly under Robolectric.

4. **History (optional)** — [HistorizePreviewsTask.kt](gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/HistorizePreviewsTask.kt) archives changed PNGs into `.compose-preview-history/` (outside `build/`, survives `clean`). Enabled via `composePreview.historyEnabled`.

`renders/` is ephemeral: rewritten every run, stale files deleted. Filenames are normalized — see [docs/RENDER_FILENAMES.md](RENDER_FILENAMES.md).

The CLI ([cli/](cli/src/main/kotlin/ee/schimke/composeai/cli/)) and VS Code extension ([vscode-extension/](vscode-extension/src/)) are thin drivers over the Gradle tasks — they shell out via the Tooling API (`GradleConnector.kt`, `gradleService.ts`) and read the resulting `previews.json` / PNG files. The CLI also ships a `compose-preview` binary with `installDist` for use as an agent/MCP backend.

## Git conventions

- **Do not add `Co-Authored-By` trailers** to git commit messages. Commits should be attributed solely to the committer.

## Important constraints

- **Configuration cache is strict** (`problems=fail` in [gradle.properties](gradle.properties)). Changes to plugin code must resolve classpaths/JVM args at configuration time via lazy providers — never call `.files` inside a task action or touch `project.*` at execution time.
- **CMP Desktop previews require `implementation(compose.components.uiToolingPreview)`** — the bundled `@Preview` has `SOURCE` retention and is invisible to ClassGraph otherwise.
- **Toolchain:** Java 17, Kotlin 2.3.20, Gradle 9.4.1+, AGP 9.1.1, CMP 1.10.3. Always use the bundled `./gradlew` wrapper. Don't loosen the toolchain to a newer JDK to avoid the install — AGP 9.1.0 / Robolectric still target 17, and bumping silently produces classes-vs-resources skew on the consumer's unit-test classpath.
- **Bringing up a fresh sandbox.** When `./gradlew` fails with "Unable to download toolchain": `sudo apt-get install -y openjdk-17-jdk-headless` — Gradle's auto-detection picks it up from `/usr/lib/jvm/`. When an Android-flavoured sample then fails with "SDK location not found", install the SDK manually (no apt package covers `compileSdk = 36`):
  ```
  curl -fsSL -o /tmp/cmdline-tools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  sudo unzip -q /tmp/cmdline-tools.zip -d /tmp && \
    sudo mkdir -p /opt/android-sdk/cmdline-tools && \
    sudo mv /tmp/cmdline-tools /opt/android-sdk/cmdline-tools/latest
  yes | sudo /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
  sudo /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
    "platforms;android-36" "platform-tools" "build-tools;36.0.0"
  echo "sdk.dir=/opt/android-sdk" > local.properties
  ```
  `local.properties` is `.gitignore`d; CI uses `ANDROID_HOME` instead. Re-running `:samples:wear:renderAllPreviews` after this end-to-end takes ~3 min cold. Or run [`scripts/install.sh --android-sdk`](../scripts/install.sh), which automates the same steps idempotently — see [skills/compose-preview/design/CLAUDE_CLOUD.md](../skills/compose-preview/design/CLAUDE_CLOUD.md#custom-mode-android-consumers) for the consumer-facing cloud Setup-script recipe.
- **Do not run `collectPreviewInfo` / other internal plugin tasks by hand** — the plugin wires them as dependencies of `renderAllPreviews`.
- **Plugin version** is driven by `.release-please-manifest.json` at the repo root (single source of truth, maintained by release-please). The three `build.gradle.kts` files read that manifest and compute next-patch `-SNAPSHOT` for local builds; CI overrides with the `PLUGIN_VERSION` env var from the git tag or `snapshot.yml`. See [docs/RELEASING.md](RELEASING.md).
- **Android renderer is pinned to Robolectric SDK 35** via `@Config(sdk = [35])` in [RobolectricRenderTest.kt](renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/RobolectricRenderTest.kt) (`renderer-android` itself is on `compileSdk = 36`). Capture depends on Robolectric's shadowed `ImageReader` / `PixelCopy` path, historically fragile across SDK × Robolectric combinations (e.g. `ShadowNativeImageReaderSurfaceImage.nativeCreatePlanes` is `maxSdk`-gated). Re-run `:samples:android:renderAllPreviews` end-to-end when bumping either the SDK level or Robolectric.
- **Renderer-vs-consumer AndroidX version alignment is load-bearing.** The renderer AAR goes out of its way to avoid dragging newer Compose / Activity / Core versions onto the consumer's unit-test classpath (since AGP builds `apk-for-local-test.ap_` from the consumer's own deps, classes-vs-resources mismatches are easy to introduce). Known failure signatures, current mitigations (`compileOnly` + `extendsFrom(testConfig)` + `ui-test-manifest` injection), follow-ups for `compose-preview doctor`, and tile-rendering gaps are catalogued in [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md) — consult it before bumping `libs.versions.toml` or changing how `AndroidPreviewSupport` wires the test classpath.

## Tests

- `:gradle-plugin:test` — unit tests on preview-data / device-dimension parsing.
- `:gradle-plugin:functionalTest` — Gradle TestKit tests that apply the plugin to synthetic projects and assert on `previews.json` + rendered PNGs. These are the source of truth for end-to-end plugin behavior; add one here when changing discovery or task wiring.
- `:renderer-android:test` — JVM unit tests for render helpers (no Robolectric).
- `vscode-extension` uses Mocha against compiled `out/test/**`.
