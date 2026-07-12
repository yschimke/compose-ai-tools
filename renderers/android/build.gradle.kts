import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.tapmoc)
}

android {
  namespace = "ee.schimke.composeai.renderer"
  // `AndroidSingleVariantLibrary` in `mavenPublishing {}` below wires the
  // `singleVariant("release")` publication for us — don't declare it twice.
  // wear-compose 1.7.0-alpha's AARs declare `minCompileSdk = 37`; compile against API 37 so the
  // `compileOnly` `wear.compose.foundation` types resolve. Override the conventions `compileSdk =
  // 36`.
  compileSdk = 37
  defaultConfig {
    // `compileOnly` wear deps don't propagate, so consumers (`:daemon:android` / `:daemon:desktop`
    // at compileSdk 36) link against this AAR without bumping their own compileSdk.
    aarMetadata { minCompileSdk = 36 }
  }
  testOptions {
    unitTests.all {
      // The Robolectric-on-JDK-17+ open set the production render JVM uses
      // (`AndroidPreviewClasspath.buildJvmArgs`). `WearScrollSvgGrowthTest` renders a full Wear
      // scaffold whose `TimeText` curved-text renderer reaches `DirectByteBuffer.address()` via
      // `PathIterator` — without `--add-opens=java.base/java.nio` that throws
      // `InaccessibleObjectException` under Robolectric's `ShadowVMRuntime`.
      it.jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
      )
    }
  }
}

dependencies {
  implementation(project(":common-io"))
  // Google Fonts CSS/TTF fetch in GoogleFontInterceptor (replaces java.net.HttpURLConnection).
  implementation(libs.okhttp)
  // D2.2 — `AccessibilityChecker`, `AccessibilityOverlay`, and the
  // `AccessibilityFinding` / `AccessibilityNode` / `AccessibilityEntry` model classes used to
  // live in this module. They moved to `:data-a11y-core` (published as
  // `data-a11y-core`); `api` re-exposes them so existing imports of
  // `ee.schimke.composeai.renderer.AccessibilityChecker` etc. still resolve.
  //
  // The `:data-a11y-hierarchy-android` producer (the Android-specific hierarchy walk +
  // `AccessibilityHierarchyExtension`) is NOT depended on here any more — the standalone
  // Robolectric `composePreviewRender` Test task is the "normal render only" path. A11y data
  // products
  // are produced exclusively by `:daemon:android`'s `RenderEngine`; consumers (VS Code chip,
  // `compose-preview a11y`, MCP) drive a11y through the daemon, never through this Test task.
  api(project(":data-a11y-core"))
  implementation(project(":data-render-core"))
  implementation(project(":data-scroll-core"))
  // `:data-scroll-android` carries the `AndroidComposeTestRule`-bound drivers
  // (`driveScrollToEnd`, `driveScrollByViewport`, `driveScrollToStart`, `driveScrollBy`,
  // `remainingScrollPx`). The pure-JVM stitcher, GIF encoder, frame planners and axis enum
  // stay in `:data-scroll-core` so the desktop renderer can pull them in without dragging
  // Compose test JUnit deps.
  implementation(project(":data-scroll-android"))
  // Layout-inspector models + the pure Wear scroll-slice stitcher (`WearScrollSliceStitcher`) that
  // `WearScrollSvgAssembler` drives; the connector's SVG producers stay a `testImplementation` /
  // daemon-side dependency, so main only pulls the backend-agnostic core.
  implementation(project(":data-layoutinspector-core"))
  // Focus / keyboard-traversal connector. Owns `KeyboardInputModeManager`, the
  // `LaunchedEffect`-driven focus walk via `FocusOverrideExtension`, the per-capture state
  // holder `FocusController`, and the post-capture `FocusOverlay`. The renderer's per-capture
  // loop pushes `RenderManifest.FocusCapture` into `FocusController.set(...)` and wraps content
  // with `FocusOverrideExtension(...)` so static `@Preview` rendering and daemon-driven
  // `renderNow.overrides.focus` share the same composable seam. **No hardcoded focus / keyboard
  // logic should live in this module any more — extend the connector instead.**
  implementation(project(":data-focus-connector"))
  // Wear OS ambient-mode connector. Owns `AmbientStateController` and `AmbientOverrideExtension`
  // (the `AroundComposable` that installs `LocalAmbientModeManager`). The renderer wraps content
  // with the extension whenever `RenderPreviewCapture.ambient` is set — `@AmbientPreview`
  // discovery stamps it onto every capture of an annotated function, and daemon-driven
  // `renderNow.overrides.ambient` plans the same extension through `RobolectricHost`. Same
  // architectural rule as focus: **no hardcoded ambient / `LocalAmbientModeManager` logic in
  // this module — extend the connector instead.**
  implementation(project(":data-ambient-connector"))
  // Wear OS one-handed-gesture connector. Owns `GestureStateController` and
  // `GestureOverrideExtension` (the `AroundComposable` that primes the controller with `showHints`
  // and installs `LocalGestureRegistry` / `LocalOneHandedGestureEnabled`). The renderer wraps
  // content with the extension whenever `RenderPreviewCapture.gestureHint` is set —
  // `@GestureHintPreview`
  // discovery stamps it onto every capture of an annotated function, and daemon-driven
  // `renderNow.overrides.gestures` plans the same extension through `RobolectricHost`. Same
  // architectural rule as ambient / focus: **no hardcoded gesture / `GestureHint` logic in this
  // module — extend the connector instead.**
  implementation(project(":data-gestures-connector"))
  // Launcher-widget container-size connector. Owns `LauncherWidgetExtension` (the
  // `AroundComposable` that wraps the preview in `Box(Modifier.size(...))` at the resolved cell
  // footprint) and the `LauncherWidgetPreviewOverrideExtension` planner. The renderer reads
  // `Capture.launcherWidget` (set when the consumer's `@Preview` carries a
  // `@LauncherWidgetPreview`) and builds the extension directly via `toLauncherWidgetOverride()`;
  // daemon-driven `renderNow.overrides.launcherWidget` lands at the same extension through the
  // planner registered in `RobolectricHost`. Same architectural rule as ambient / focus: **no
  // hardcoded launcher-widget sizing logic in this module — extend the connector instead.**
  implementation(project(":data-launcher-widget-connector"))
  // Glance — drives the native `@androidx.glance.preview.Preview` strategy
  // (`GlanceAppWidgetPreviewRenderer.kt`). `compileOnly` so the artefact isn't dragged onto every
  // renderer-android consumer's unit-test classpath; consumers who annotate Glance previews must
  // already have `androidx.glance:glance-appwidget` on their compile classpath, which then flows
  // onto the test classpath via the discovery → render fanout.
  compileOnly(libs.glance.appwidget)
  // Soft-keyboard (IME) connector. Owns `KeyboardController` (state) and
  // `KeyboardOverrideExtension`
  // (the `AroundComposable` that installs the shadow `LocalSoftwareKeyboardController` and overlays
  // the band when `KeyboardController.softInputVisible` flips). The renderer wraps content with the
  // extension on every capture so app-side `keyboardController.show()` (and the same path
  // `BasicTextField` uses internally on focus) raises the band naturally. Daemon-driven
  // `renderNow.overrides.keyboard` plans the same extension through `RobolectricHost`. Same
  // architectural rule as focus / ambient: no hardcoded IME logic in this module — extend the
  // connector instead.
  implementation(project(":data-keyboard-connector"))
  // Pseudolocale connector — `Pseudolocale.fromTag(...)` for the qualifier-rewrite branch and
  // `PseudolocaleOverrideExtension` for the around-composable wrap. Same architectural rule as
  // focus / ambient: no hardcoded pseudolocale logic in this module — extend the connector
  // instead.
  implementation(project(":data-pseudolocale-connector"))
  // Display-filter connector — RobolectricRenderTest reads `composeai.displayfilter.filters` after
  // each successful PNG capture and calls `DisplayFilterDataProducer.writeArtifacts(...)` to emit
  // per-filter variants alongside the base capture. Same dep on the daemon side; the producer is
  // renderer-agnostic (BufferedImage / ImageIO).
  implementation(project(":data-displayfilter-connector"))
  // Device-frame connector — RobolectricRenderTest reads `composeai.deviceframe.device` after each
  // successful capture and calls `DeviceFrameDataProducer.writeArtifacts(...)` to composite the PNG
  // into a real device-art bezel. Renderer-agnostic (BufferedImage / ImageIO + Ktor/OkHttp fetch).
  implementation(project(":data-deviceframe-connector"))
  // Plain-Compose named overrides — RobolectricRenderTest drains `PreviewOverrideController` after
  // each
  // capture and writes the `renders/<stem>.overrides.json` sidecar `BundlePreviewTask` packs. The
  // consumer's `previewOverride*` calls resolve to the same controller; depending on the runtime
  // here
  // guarantees the class is present even when the consumer didn't add it (then nothing is declared
  // and
  // no sidecar is written). `core` (re-exported) carries the payload serializer.
  implementation(project(":data-preview-overrides-runtime"))

  implementation(libs.robolectric)
  implementation(libs.junit)
  implementation(libs.kotlinx.serialization.json)

  // Classloader-forensics diagnostic library lives in `:daemon:core` (renderer-agnostic
  // surface — see docs/daemon/CLASSLOADER-FORENSICS.md). `testImplementation` because it's only
  // referenced by `ClassloaderForensicsTest` and shouldn't widen the renderer's main classpath.
  testImplementation(project(":daemon:core"))
  // ComposeSemanticsCoreFieldsTest exercises ComposeSemanticsDataProducer.writeArtifacts +
  // ComposeSemanticsDataProductRegistry against real Compose composables to assert each
  // preview surfaces the specific semantics field it isolates (testTag, contentDescription,
  // role+clickable, mergeMode). TextStringsTruncationTest exercises the same producer +
  // TextStringsDataProductRegistry against truncation fixtures to assert each preview
  // triggers the expected text/strings v2 truncation check.
  testImplementation(project(":data-layoutinspector-connector"))
  // I18nTranslationsFixtureTest exercises I18nTranslationsDataProducer.writeArtifacts +
  // I18nTranslationsDataProductRegistry against a Compose Text rendered under a fixture
  // strings.xml catalog (values/ + values-fr/) to assert the produced JSON resolves the
  // rendered string back to its R.string.* and surfaces the supported-locale set.
  testImplementation(project(":data-strings-connector"))

  // Compose / Activity / Compose-UI-test libs are `compileOnly` on purpose:
  // they must match what the CONSUMER module declares, because AGP's
  // `process<Variant>Resources` builds the unit-test merged resource APK
  // (`apk-for-local-test.ap_`) from the consumer's dependency graph — NOT
  // from our custom `composePreviewAndroidRenderer` configuration. If these
  // are `implementation`, Gradle drags our newer activity-compose 1.13 onto
  // the test JVM classpath — which transitively brings `androidx.navigationevent`
  // — while the consumer's merged resource APK stays on their older
  // activity. The test JVM then loads the 1.13 Activity bootstrap and crashes
  // on `NoClassDefFoundError: androidx/navigationevent/R$id` because the
  // navigationevent resources were never merged. Same class of failure for
  // `androidx.core.R.tag_compat_insets_dispatch` (compose-ui 1.10 →
  // androidx.core 1.16 resources missing on older consumers).
  //
  // `testImplementation` mirrors `compileOnly` for our own unit tests, which
  // need actual runtime classes. The plugin separately injects ui-test-manifest
  // into the consumer's `testImplementation` in AndroidPreviewSupport so its
  // `ComponentActivity` entry lands in the consumer's merged test manifest.
  //
  // We compile against the OLDER `compose-bom-compat` rather than the
  // top-level `compose-bom`. Rationale: AndroidX honours binary-backward-
  // compatibility within a major version, so bytecode emitted against a
  // 1.9.x API surface runs unchanged on consumer apps at 1.10.x / 1.11.x
  // (where we've confirmed all referenced methods still exist). The
  // reverse — compiling against 1.10.x — emits calls like
  // `Updater.init-impl(Composer, Object, Function2)` that didn't exist
  // before 1.10.2, so consumers pinned to older Compose BOMs fail with
  // NoSuchMethodError at render time. Our own unit tests use the same
  // compat BOM (not the latest) so accidentally reaching for a 1.10-only
  // API fails at our compile step, not at a downstream consumer's.
  //
  // `LocalScrollCaptureInProgress` (compose-ui ≥ 1.7) is looked up
  // reflectively via [ScrollCaptureInProgressLocal] — a null return
  // degrades scroll-capture to a no-op for consumers on even older Compose.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)
  compileOnly(libs.compose.material3)
  compileOnly(libs.compose.runtime)
  // Required to compile against data-ambient-connector's public extension class. Keep it
  // compile-only so Wear apps continue to supply their own runtime ambient API version.
  compileOnly(libs.wear.compose.foundation)
  // `compose.ui.tooling.preview` from `compose-bom-compat` (1.9.x) doesn't
  // ship `PreviewWrapper` / `PreviewWrapperProvider` — those landed in
  // ui-tooling-preview 1.11.0. We pin the 1.11+ variant here so
  // [SystemBarsPreviewWrapper] can extend `PreviewWrapperProvider` at compile
  // time. Consumers on Compose 1.11+ get the symbol from their own runtime;
  // consumers on 1.10 and below can still use the rest of the renderer
  // (loading [SystemBarsPreviewWrapper] is the only path that requires the
  // 1.11 symbol, and that only fires when a consumer explicitly references
  // it via `@PreviewWrapper(SystemBarsPreviewWrapper::class)`).
  compileOnly(libs.compose.ui.tooling.preview.wrapper)
  compileOnly(libs.activity.compose)
  compileOnly("androidx.compose.ui:ui-test-junit4")
  compileOnly("androidx.compose.ui:ui-test-manifest")
  // `@AnimatedPreview(showCurves = true)` snapshots the active
  // composition's slot table via `currentComposer.compositionData`
  // (`@InternalComposeApi` from compose-runtime) and walks it via
  // `androidx.compose.ui.tooling.data.asTree` (compose-ui-tooling-data).
  // Renderer compile-classpath deps stop there — `PreviewAnimationClock`,
  // `AnimationSearch`, and `ComposeAnimation` / `ComposeAnimatedProperty`
  // are reached via reflection at runtime so consumers without curve
  // support never need ui-tooling on their classpath.
  compileOnly("androidx.compose.ui:ui-tooling-data")

  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.ui)
  testImplementation(libs.compose.foundation)
  testImplementation(libs.compose.material3)
  testImplementation(libs.compose.runtime)
  testImplementation(libs.wear.compose.foundation)
  // Wear Material3 on the TEST classpath only — it supplies `SurfaceTransformation` /
  // `transformedHeight` so `WearScrollSvgGrowthTest` can render a round preview with the real Wear
  // item scaling and show reduce-motion flatten it. Test-scoped on purpose: the daemon must never
  // link wear-compose (it renders the user's app off a child classloader); this is the module that
  // renders Wear, so its own test classpath supplies the Wear surface.
  testImplementation(libs.wear.compose.material3)
  testImplementation(libs.compose.ui.tooling.preview)
  testImplementation(libs.activity.compose)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.compose.ui:ui-test-manifest")
  testImplementation("androidx.compose.ui:ui-tooling-data")
  // GoogleFont detector's unit test constructs a real
  // `Font(GoogleFont("Roboto"), provider)` so the reflective FQCN check
  // runs against an actual `GoogleFontImpl` instance. Test-only — the
  // main source deliberately stays off this artifact so consumers without
  // downloadable fonts don't get it transitively from our AAR.
  testImplementation("androidx.compose.ui:ui-text-google-fonts")

  implementation(libs.roborazzi)
  implementation(libs.roborazzi.compose)
  // ATF (roborazzi-accessibility-check + the transitive
  // `accessibility-test-framework`) is no longer wired here — the standalone Robolectric
  // `composePreviewRender` Test task does NOT run ATF. The daemon (`:daemon:android`) is the only
  // path that produces a11y data products and depends on these libraries via
  // `:data-a11y-hierarchy-android`.

  // Tiles rendering is reflection-driven at runtime (the consumer module
  // supplies the actual classes on the JUnit classpath), so we only need
  // these to compile TilePreviewRenderer — a consumer without tile deps
  // will never hit the TILE branch in RobolectricRenderTestBase.
  compileOnly(libs.wear.tiles)
  compileOnly(libs.wear.tiles.renderer)
  compileOnly(libs.wear.tiles.tooling.preview)
  compileOnly(libs.wear.protolayout)
  compileOnly(libs.wear.protolayout.expression)
  // Tile IR capture: `Layout.toProto()` / `Resources.toProto()` return generated messages from
  // protolayout-proto, whose inherited `toByteArray()` lives in the shaded protobuf runtime
  // (protolayout-external-protobuf). compileOnly so neither reaches a consumer's classpath — the
  // consumer's own protolayout pulls them at runtime under Robolectric. See TilePreviewRenderer.
  compileOnly(libs.wear.protolayout.proto)
  compileOnly(libs.wear.protolayout.external.protobuf)
}

mavenPublishing {
  configure(
    AndroidSingleVariantLibrary(
      javadocJar = JavadocJar.Empty(),
      sourcesJar = SourcesJar.Sources(),
      variant = "release",
    )
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "renderer-android",
    displayName = "Compose Preview — Android Renderer",
    description =
      "Robolectric-based renderer for Jetpack Compose @Preview functions, used by the compose-preview Gradle plugin to produce PNGs off-device.",
  )
  inceptionYear.set("2025")
}
