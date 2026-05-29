# AGENTS.md

This file provides guidance to Agents when working with code in this repository.

## What this project is

A Gradle plugin (`ee.schimke.composeai.preview`) plus supporting tools that discover `@Preview` composables in compiled Kotlin classes and render them to PNG outside Android Studio. Targets both Jetpack Compose (Android, via Robolectric) and Compose Multiplatform Desktop (via `ImageComposeScene`).

## Documentation map: contributor vs. consumer

Two audiences, two doc trees. Don't conflate them:

- **This file + `docs/`** — contributor docs for working on *this repo*: editing the plugin, CLI, renderer modules, or VS Code extension; running the in-repo samples through `includeBuild("gradle-plugin")`; publishing releases. Build commands here use `./gradlew` against the local source tree.
- **[`yschimke/skills`](https://github.com/yschimke/skills)** — consumer docs for the *published* plugin and CLI live in a separate content repo. Two skill bundles:
  - [`compose-preview`](https://github.com/yschimke/skills/tree/main/skills/compose-preview) — applying `id("ee.schimke.composeai.preview")` to a downstream project and driving `compose-preview` against it. The `references/` subtree is per-target-stack and per-feature guidance:
    - [`permissions.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/permissions.md) — agent allowlists, staging PNGs under `build/`
    - [`state-hoisting.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/state-hoisting.md) — making composables previewable
    - [`capture-modes.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/capture-modes.md) — multi-preview annotations, paused-clock animations, `@ScrollingPreview`
    - [`a11y.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/a11y.md) — ATF accessibility checks
    - [`display-filters.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/display-filters.md) — post-process colour-matrix variants (bedtime grayscale, invert, daltonizer simulations)
    - [`agent-cloud.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/agent-cloud.md) — running in Claude/Codex/Gemini cloud environments (network allowlist, Setup script with `install.sh --android-sdk`, JVM-proxy gotcha)
    - [`cmp-shared.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/cmp-shared.md) — applying the plugin to a CMP `:shared` (`com.android.kotlin.multiplatform.library`) module: previews go in `commonMain`, JVM target gives the Desktop renderer something to attach to (issue #248)
    - [`wear-ui.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/wear-ui.md) — Material 3 Expressive design language for Wear OS
    - [`wear-tiles.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/wear-tiles.md) — Wear Tiles (protolayout-based, not Compose)
    - [`remote-compose.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/remote-compose.md) — Remote Compose (RemoteDocument byte stream for watch faces, tiles, widgets)
    - [`resource-previews.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/resource-previews.md) — Android XML resource captures (`<vector>`, `<adaptive-icon>`)
    - [`vscode.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/vscode.md) — VS Code extension (humans, not agents)
  - [`compose-preview-review`](https://github.com/yschimke/skills/tree/main/skills/compose-preview-review) — sibling skill covering the PR-review surface: authoring agent-opened PRs, reviewing UI PRs locally (base + head render, diff, comment), and wiring `compose-preview/main` baselines + PR-comment GitHub Actions.
    - [`agent-pr.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview-review/references/agent-pr.md) — authoring agent-opened PRs and reviewing PRs opened by other agents
    - [`ci-previews.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview-review/references/ci-previews.md) — maintaining a `compose-preview/main` branch with rendered PNGs and a `baselines.json` for diff-on-PR workflows

The bootstrap installer's canonical home is now [`yschimke/skills/scripts/install.sh`](https://github.com/yschimke/skills/blob/main/scripts/install.sh); it pulls both skill bundles from `yschimke/skills` (default `main`) and the CLI tarball from this repo's releases. A thin curl-pipe stub remains at this repo's `scripts/install.sh` so old snippets keep working. When you change consumer-facing behaviour (a new flag, a network requirement, a setup-script step), edit `yschimke/skills` — both the SKILL.md and the installer live there. Cross-link from here when contributors need the same information for sandbox setup (e.g. the Android SDK bootstrap referenced from "Bringing up a fresh sandbox" below).

## Common commands

Build / test everything:
```
./gradlew check                   # plugin unit + functional tests, CLI tests
```

Render the sample previews (end-to-end smoke test of the full pipeline):
```
./gradlew :samples:cmp:composePreviewRenderAll
./gradlew :samples:android:composePreviewRenderAll
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

2. **Task wiring** — [ComposePreviewPlugin.kt](gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/ComposePreviewPlugin.kt) registers `composePreviewDiscover`, `composePreviewRender`, and the user-facing `composePreviewRenderAll` aggregate. It detects Android vs CMP Desktop at configuration time and takes different paths:
   - **Android:** uses AGP `artifactView` filters (`artifactType=jar`, `android-classes`) to resolve AAR-extracted class jars, copies JVM args from AGP's `test<Variant>UnitTest` task, and launches a Gradle `Test` task that runs [RobolectricRenderTest.kt](renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/RobolectricRenderTest.kt) inside a Robolectric sandbox with `graphicsMode=NATIVE`. `android.jar` is added so the Robolectric runner classes load before the sandbox classloader takes over.
   - **Desktop/JVM:** creates a `composePreviewRenderer` configuration pointing at `:renderer-desktop`, then launches [DesktopRendererMain.kt](renderer-desktop/src/main/kotlin/ee/schimke/composeai/renderer/DesktopRendererMain.kt) as a subprocess with the module's runtime classpath plus the renderer.

3. **Rendering** — both backends reflect the target composable function, invoke it inside a background fill, and capture to PNG.

   - **Desktop:** `ImageComposeScene` at 2x density; two `scene.render()` calls so `LaunchedEffect`s / animations get one frame to settle before encoding.
   - **Android:** `createAndroidComposeRule<ComponentActivity>()` + `onRoot().captureRoboImage(...)`. Single always-on path: `mainClock.autoAdvance = false`, `advanceTimeBy(CAPTURE_ADVANCE_MS)`, then capture. The paused clock is what lets infinite animations (indeterminate `CircularProgressIndicator`, `rememberInfiniteTransition`, hand-rolled `withFrameNanos` loops) terminate deterministically instead of hanging Compose's idling resource. Time is expressed in ms rather than frame count so a future `@RoboComposePreviewOptions` / `ManualClockOptions(advanceTimeMillis = …)` override plugs straight in. `LocalInspectionMode = false` so animations actually tick. **a11y is daemon-only** — the standalone `composePreviewRender` Test task does not run ATF and does not write accessibility sidecars. The daemon (`:daemon:android`'s `RenderEngine`) is the single producer of a11y data products; consumers reach it through the VS Code chip toggle (per-preview `data/subscribe`), `compose-preview a11y` (spins up a temporary daemon), or the MCP server. Findings never fail a render — a11y is a data producer, not a gate. There is no DSL or Gradle property toggle anymore.
   
   Options are applied by hand in `renderDefault` rather than through `RoborazziComposeOptions` (its `configured(...)` chain wants an `ActivityScenario` it owns, awkward to share with `ComposeTestRule`): size/locale/uiMode/round/orientation via `RuntimeEnvironment.setQualifiers` (strict grammar order — locale, width, height, round, orientation, night); fontScale via `RuntimeEnvironment.setFontScale` (Configuration field, not a qualifier — same knob Roborazzi's `RoborazziComposeFontScaleOption` uses); background and inspection via `CompositionLocalProvider`.
   
   Capture path: `ShadowPixelCopy` is routed to `HardwareRenderingScreenshot` → `ImageReader + HardwareRenderer.syncAndDraw` via `robolectric.pixelCopyRenderMode=hardware` on the `composePreviewRender` `Test` task — the only path that replays Compose's `RenderNode`s correctly under Robolectric.

`renders/` is ephemeral: rewritten every run, stale files deleted. Filenames are normalized — see [docs/RENDER_FILENAMES.md](RENDER_FILENAMES.md).

The CLI ([cli/](cli/src/main/kotlin/ee/schimke/composeai/cli/)) and VS Code extension ([vscode-extension/](vscode-extension/src/)) are thin drivers over the Gradle tasks — they shell out via the Tooling API (`GradleConnector.kt`, `gradleService.ts`) and read the resulting `previews.json` / PNG files. The CLI also ships a `compose-preview` binary with `installDist` for use as an agent/MCP backend.

## State seams

Coordination state for the edit→render→subscribe loop lives in a handful of named, unit-tested classes rather than module-level `Map`/`Set`/AbortController fields. **When you need to add to this loop, extend the existing seam rather than introducing a parallel mutable.**

VS Code extension (`vscode-extension/src/`):

- **`RefreshQueue`** ([refreshQueue.ts](../vscode-extension/src/refreshQueue.ts)) — save-driven refresh coalescing FSM. Owns `inFlight` / `pendingTarget` / `debounceElapsed` / `seenFiles`. Single entry point `dispatchSave(target, opts)` for both editor saves and file-watcher events. Tests inject a fake clock; real production uses `defaultRefreshQueueEffects`.
- **`EditorScope`** ([editorScope.ts](../vscode-extension/src/editorScope.ts)) — the `(file, module)` pair the panel is pinned to. Comparison is by `modulePath`, never reference identity. Don't add a parallel "what file is shown" tracker.
- **`PreviewModuleIndex`** (same file) — `previewId → owning module` lookup, with `replaceModule(module, freshIds)` for the "purge stale entries then install fresh ones" pattern. Per-preview action handlers (chip toggles, focus inspector, history) route through this.
- **`DaemonScheduler`** ([daemon/daemonScheduler.ts](../vscode-extension/src/daemon/daemonScheduler.ts)) — owns per-module subscription state (`subscribedPairs`) and exposes `setDataProductSubscription` which returns the `a11yTransition` verdict. The extension doesn't keep its own a11y subscription mirror.

Daemon (`daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/`):

- **`SubscriptionStore`** ([SubscriptionStore.kt](../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/SubscriptionStore.kt)) — sticky `(previewId, kind)` bookkeeping. Three teardown methods (`unsubscribe`, `retainVisible`, `removeKinds`) return the dropped pairs so the caller routes `onUnsubscribe` through the right producer surface (`publicDataProducts` vs `activeDataProducts`).
- **`DeferredDiscoveryQueue`** ([DeferredDiscoveryQueue.kt](../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/DeferredDiscoveryQueue.kt)) — paths waiting for the post-render discovery cascade plus the watchdog that drains them if no render arrives. Watchdog scheduler is injectable; tests use a manual scheduler so race outcomes are deterministic.
- **`DataProductRegistry.renderModeFor(kind)`** ([DataProductRegistry.kt](../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/DataProductRegistry.kt)) — producers declare which kinds need a renderer mode tag (`"a11y"`, etc.). The dispatcher iterates subscribed kinds and asks the registry; **do not pattern-match on the kind string in `JsonRpcServer`**.

The seams are deliberately split per concern; a new feature usually maps to extending exactly one. If your change wants to add a new module-level `Map<String, …>` to `extension.ts` or `JsonRpcServer.kt`, check whether one of these classes already owns the conceptual state first.

## Git conventions

- **Do not add `Co-Authored-By` trailers** to git commit messages. Commits should be attributed solely to the committer.
- **Use conventional commits for PR titles and commit subjects** (`fix:`, `feat:`, `docs:`, `test:`, etc.) so squash merges feed release-please correctly.
- **Before adding commits to an existing PR branch, check whether the PR has already landed.** Fetch `origin` and inspect the PR state or compare `origin/main` first. If the PR is merged, start a fresh branch from `origin/main` for follow-up work instead of stacking new commits onto the merged branch.
- **Run the formatter before committing.** CI's `format` job runs `./gradlew ktfmtCheckAll` and it's a hard gate — `ktfmtCheck` aborts on the first unformatted file. Before each commit that touches `*.kt`/`*.kts`, run `./gradlew ktfmtFormat` (or `./gradlew :<module>:ktfmtFormatMain :<module>:ktfmtFormatTest` for the touched modules) and stage the result. For VS Code extension TypeScript changes, run `npm --prefix vscode-extension run format`. Don't push without re-running these — the fix-up round-trip costs more than running the formatter locally.

## PR workflow

These rules also live in [CLAUDE.md](../CLAUDE.md#pr-workflow-must-follow); detail and rationale below.

- **Open PRs automatically.** When a coding task is finished, committed, and pushed, open a PR against `main` without prompting. Conventional-commit title, short summary, test-plan checklist — same shape as the `gh pr create` heredoc template the harness ships with. Skip this only when the user explicitly said "no PR" or framed the task as pure exploration. Always check first whether the branch already has an open PR (`list_pull_requests` or `search_pull_requests` filtered by head); if so, push to it instead of opening a duplicate.
- **Subscribe to every PR you open.** Call `subscribe_pr_activity` for the PR's number on the same turn you open it. Don't ask the user whether to track — tracking is the default. Mention it in the reply so the user knows the session is now listening (e.g. "Opened PR #N and subscribed to its activity").
- **Respond to PR review comments and CI events automatically.** `<github-webhook-activity>` events on tracked PRs are not no-ops. For each event:
  - If the requested change is clear, in-scope, and not architecturally significant — push the fix, update the status checklist on the PR, and don't narrate each round in chat.
  - If the comment is ambiguous or touches something significant — use `AskUserQuestion` with enough context that the user can answer without scrolling back.
  - For CI failures on a PR the user asked you to babysit — re-diagnose and re-kick (rebase, re-run, push fix) until green. After several rounds with no progress, reply with the diagnosis and where you're stuck instead of going silent.
  - When everything is green, reply with the green status. That IS the deliverable.
- **Stop on request.** "Stop watching", "unsubscribe", "leave it alone" — call `unsubscribe_pr_activity` for that PR and stop pushing.
- **Don't auto-merge.** Opening, tracking, and fix-up commits are automatic; merging is the user's call. Don't enable auto-merge unless the user explicitly asks.

## VS Code panel UI changes

Edits under `vscode-extension/src/webview/` or `vscode-extension/media/preview*.css` need a visual record. Capture a baseline + post-change PNG via the preview-harness and send both to the user before reporting the task done — the harness boots the real `<preview-app>` bundle headlessly against fixture JSON and is the panel's equivalent of a Compose `@Preview`. Loop and fixture authoring are documented in [`vscode-extension/preview-harness/README.md`](../vscode-extension/preview-harness/README.md#agent-workflow); seed fixtures are `grid-default` (multi-card grid) and `a11y-findings` (focus mode + Accessibility bundle). Use it for shape, layout, and theming — not as a substitute for `npm test` / `test:electron`, which still own behavioural correctness.

## Important constraints

- **No hardcoded special-case logic for extensions in the renderer / daemon / protocol layers.** Per-feature wiring like `if (spec.wallpaper != null) wrap(...)` or `inbound["material3Theme"]?.let { ... }` is a smell. The renderer drives extensions through metadata: a `PreviewOverrideExtension` (a `DataExtension<PreviewOverrides>`) registered in `PreviewOverrideExtensions` is what decides whether and how to wrap a preview, based on the merged `PreviewOverrides` bag. Adding a new override-driven feature lives entirely inside its connector module (`data/<feature>/connector/`) plus a `DaemonMain` registration line — `RenderEngine`, `JsonRpcServer.encodeRenderPayload`, and `PreviewManifestRouter` should not need to grow new branches. Same rule applies to data-product registries: don't sprinkle `if (kind == "compose/foo")` checks across the dispatcher; surface kinds via `DataProductRegistry.capabilities` and route through `CompositeDataProductRegistry`. **CLI canned reports follow the same rule**: implement `ExtensionReportRenderer` (per-extension load / annotate / print / threshold), register in `builtInExtensionReporters()`, and either bind it to a named command via `ReportCommand` (`A11yCommand` is the example) or leave it discoverable via `compose-preview extensions list` + `--with-extension <id>`. **Profiles (`compose-preview profile <path.json>`) are a thin args-synthesiser over `ReportCommand`** — captured flag combinations, not a programming model; richer user-defined behaviour is tracked as Kotlin scripting in [issue #1084](https://github.com/yschimke/compose-ai-tools/issues/1084) and should NOT be added by extending the profile schema. The CLI used to hard-code a11y-shaped fields into the base `Command` class; that's been factored out — don't reintroduce it.
- **Isolated Projects is on** (`org.gradle.unsafe.isolated-projects=true` in [gradle.properties](gradle.properties)), which also makes the **configuration cache strict** (`problems=fail`). Changes to plugin code must resolve classpaths/JVM args at configuration time via lazy providers — never call `.files` inside a task action or touch `project.*` at execution time. IP additionally forbids cross-project access: no `allprojects {}` / `subprojects {}`, no `project(":other")`, no `gradle.taskGraph.whenReady { allTasks }`. Shared per-project setup goes in a `build-logic` convention plugin applied per-module, not pulled in from the root.
  - **Every module must apply `composeai.base-conventions`** in its `plugins {}` block — this is what brings ktfmt (`googleStyle`) and the history-gate test system property that the old root `allprojects {}` block used to provide build-wide. A new module that omits it won't be formatted or covered by the `ktfmtCheckAll` gate. (The root project is the one exception: a build-logic plugin on the root classpath leaks to every subproject and collides with their versioned plugin aliases, so the root carries no ktfmt — keep root `build.gradle.kts` / `settings.gradle.kts` formatted by hand.) `ktfmtCheckAll` / `ktfmtFormatAll` discover the module set via a path list `settings.gradle.kts` hands to the root build through a system property.
  - **The two `functionalTestWith*` e2e aggregates need a two-phase invocation** (`./gradlew :cli:installDist :gradle-plugin:publishToMavenLocal` first, then the e2e task) — IP can't express the old cross-build "functionalTest mustRunAfter installDist" ordering. CI already does this; mirror it locally.
- **CMP Desktop previews require `implementation(compose.components.uiToolingPreview)`** — the bundled `@Preview` has `SOURCE` retention and is invisible to ClassGraph otherwise.
- **Toolchain:** Java 17, Kotlin 2.3.20, Gradle 9.4.1+, AGP 9.1.1, CMP 1.10.3. Always use the bundled `./gradlew` wrapper. Don't loosen the toolchain to a newer JDK to avoid the install — AGP 9.1.0 / Robolectric still target 17, and bumping silently produces classes-vs-resources skew on the consumer's unit-test classpath.
- **Bringing up a fresh sandbox.** Run [`scripts/install.sh --android-sdk`](../scripts/install.sh) — it installs JDK 17 (when `./gradlew` fails with "Unable to download toolchain") and the Android `cmdline-tools` + platform 36 + build-tools (when an Android sample fails with "SDK location not found"). Pass `--jdk 17,21` to install multiple JDK majors in one go. `ANDROID_HOME` defaults to `/opt/android-sdk` on cloud sandboxes (root or `CLAUDE_CLOUD=1`), `$HOME/Library/Android/sdk` on macOS, and `$HOME/Android/Sdk` elsewhere; `local.properties` lands as `sdk.dir=<that path>` (gitignored; CI uses `ANDROID_HOME` instead). Cold end-to-end run takes ~3 min. Consumer-facing cloud Setup-script recipe in [skills/compose-preview/references/agent-cloud.md](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/agent-cloud.md#custom-mode-android-consumers).
- **Do not run `collectPreviewInfo` / other internal plugin tasks by hand** — the plugin wires them as dependencies of `composePreviewRenderAll`.
- **Plugin version** is driven by `.release-please-manifest.json` at the repo root (single source of truth, maintained by release-please). The three `build.gradle.kts` files read that manifest and compute next-patch `-SNAPSHOT` for local builds; CI overrides with the `PLUGIN_VERSION` env var from the git tag or `snapshot.yml`. See [docs/RELEASING.md](RELEASING.md).
- **Android renderer's Robolectric SDK tracks the consumer's `compileSdk`.** The gradle plugin reads `android.compileSdk` from each consumer module's `finalizeDsl` and writes the matching `sdk=N` line into the generated `robolectric.properties` (see [GenerateRobolectricPropertiesTask](../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/GenerateRobolectricPropertiesTask.kt) + [AndroidPreviewSupport](../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/AndroidPreviewSupport.kt)). The previous hardcoded `sdk=35` triggered `PackageParser: Requires newer sdk version #36 (current version is #35)` on `compileSdk = 36` consumers (issue #1248). Consumers can override the auto-detected value with `composePreview.sdkVersion = N` for the rare case where they want a different framework level than `compileSdk`. Supported range is gated by the bundled Robolectric (currently `4.16.1`, API 21–36); Robolectric SDK 36 additionally requires the test JVM to be JDK 21+ (`DefaultSdkProvider.verifySupportedSdk`), so consumers on `compileSdk = 36` need to be on a JDK 21 toolchain. Our own modules stay on JDK 17 + Robolectric SDK 35 (see `RobolectricHost.ANDROID_SDK` and the `@Config(sdk = [35])` annotations in `:daemon:android` / `:data-uiautomator-*` self-tests); bump those together with `robolectric` in [gradle/libs.versions.toml](../gradle/libs.versions.toml) and the project's toolchain. Capture depends on Robolectric's shadowed `ImageReader` / `PixelCopy` path, historically fragile across SDK × Robolectric combinations (e.g. `ShadowNativeImageReaderSurfaceImage.nativeCreatePlanes` is `maxSdk`-gated). Re-run `:samples:android:composePreviewRenderAll` end-to-end when bumping either the SDK ceiling or Robolectric.
- **Renderer-vs-consumer AndroidX version alignment is load-bearing.** The renderer AAR goes out of its way to avoid dragging newer Compose / Activity / Core versions onto the consumer's unit-test classpath (since AGP builds `apk-for-local-test.ap_` from the consumer's own deps, classes-vs-resources mismatches are easy to introduce). Known failure signatures, current mitigations (`compileOnly` + `extendsFrom(testConfig)` + `ui-test-manifest` injection), follow-ups for `compose-preview doctor`, and tile-rendering gaps are catalogued in [docs/RENDERER_COMPATIBILITY.md](RENDERER_COMPATIBILITY.md) — consult it before bumping `libs.versions.toml` or changing how `AndroidPreviewSupport` wires the test classpath.

## Tests

- `:gradle-plugin:test` — unit tests on preview-data / device-dimension parsing.
- `:gradle-plugin:functionalTest` — Gradle TestKit tests that apply the plugin to synthetic projects and assert on `previews.json` + rendered PNGs. These are the source of truth for end-to-end plugin behavior; add one here when changing discovery or task wiring.
- `:renderer-android:test` — JVM unit tests for render helpers (no Robolectric).
- `vscode-extension` uses Mocha against compiled `out/test/**`.
