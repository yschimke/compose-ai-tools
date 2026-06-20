@file:Suppress("DEPRECATION")

// Renderer-desktop daemon module — see docs/daemon/DESIGN.md § 4
// ("Renderer-agnostic surface") and § 6 (module layout).
//
// Desktop counterpart of `:daemon:android`. Both modules implement
// the renderer-agnostic surface contributed by `:daemon:core`
// (`RenderHost`, `JsonRpcServer`, the @Serializable protocol types) and only
// differ in their concrete `RenderHost` implementation:
//
//  - `:daemon:android` → `RobolectricHost` (Robolectric sandbox + the
//    dummy-`@Test` runner trick from DESIGN.md § 9).
//  - `:daemon:desktop` → `DesktopHost` (long-lived JVM + render
//    thread; per-render `ImageComposeScene`). B-desktop.1.4 lands the real
//    render body in `RenderEngine.kt`; B-desktop.1.5 wires `DaemonMain` to
//    `JsonRpcServer` from core.
//
// Compose Multiplatform module (Kotlin JVM + compose plugins). The compose
// plugins are required so B-desktop.1.4's `RenderEngine` can compile against
// `androidx.compose.runtime.Composable` / `androidx.compose.ui.ImageComposeScene`,
// and so the test source set can declare `@Preview` / `@Composable` fixtures.
// The same plugin set is used by `:renderer-desktop`.
//
// **Published to Maven Central** as `ee.schimke.composeai:daemon-desktop` —
// pairs with `daemon-core` so the desktop daemon is consumable by coordinate.
// Pre-1.0; see DESIGN.md § 17.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  // D-harness.v1.5a — `PreviewManifestRouter` reads a JSON manifest mapping previewId to
  // RenderSpec for harness-driven real-mode runs. Plugin only adds the @Serializable processor;
  // kotlinx-serialization-json is already on the classpath via `:daemon:core`'s
  // `api(libs.kotlinx.serialization.json)`.
  alias(libs.plugins.kotlin.serialization)
  // D-harness.v1.5a — exposes the `RedSquare` fixture composable to `:daemon:harness`'s
  // test classpath via `testFixtures(project(":daemon:desktop"))`, so the real-mode
  // S1 doesn't need its own Compose plugin / fixture duplication. Test source set's
  // `RedFixturePreviews` is unchanged; the testFixtures source set re-exports `RedSquare` for
  // cross-module consumers.
  `java-test-fixtures`
}

dependencies {
  // Renderer-agnostic protocol types, JsonRpcServer, RenderHost interface,
  // and RenderRequest/RenderResult data classes — see DESIGN.md § 4. The
  // core module re-exposes kotlinx-serialization-json as `api`, so we don't
  // re-declare it here.
  implementation(project(":daemon:core"))
  // `assert.pixels` (issue #1967) reuses the `PixelDiff` golden-image comparator.
  // `:daemon:harness`'s
  // production classpath depends only on `:daemon:core` + `:common-io`, so this edge introduces no
  // cycle (harness's dependency on `:daemon:desktop` is test-only).
  implementation(project(":daemon:harness"))
  implementation(project(":common-io"))
  implementation(project(":data-render-connector"))
  implementation(project(":data-history-connector"))
  implementation(project(":data-theme-connector"))
  implementation(project(":data-wallpaper-connector"))
  // Pseudolocale (desktop): `LayoutDirection.Rtl` for `ar-XB` and the planner that maps
  // `localeTag` → `PseudolocaleOverrideExtensionDesktop`. The locale-list rewrite
  // (`en-XA` → `en`, `ar-XB` → `ar`) lives in `RenderEngine.localeProviders`.
  implementation(project(":data-pseudolocale-connector-desktop"))
  // Focus (desktop): the around-composable that flips `LocalInputModeManager` to keyboard mode and
  // drives `FocusManager.moveFocus(...)` from `renderNow.overrides.focus`. Issue #1205 — Android
  // wires `FocusPreviewOverrideExtension` from `:data-focus-connector`; CMP Desktop uses this
  // platform-portable mirror. The Android-only `FocusOverlay` (Android-View focus-owner reflection)
  // is not shipped on desktop.
  implementation(project(":data-focus-connector-desktop"))
  // Soft-keyboard (IME) connector (desktop): always-on `AroundComposable` that shadows
  // `LocalSoftwareKeyboardController` and overlays a fake-IME band when `KeyboardController`
  // reports the keyboard is up. Mirrors `:data-keyboard-connector` (Android). The desktop
  // session's `dispatch(KEY_*)` also calls into `KeyboardController` from this module.
  implementation(project(":data-keyboard-connector-desktop"))
  // Touch-event visualization connector — `TouchOverlayExtension` `AroundComposable` that paints
  // cyan rings at every pressed pointer + expanding pulses on down/up. Same module is consumed by
  // `:daemon:android` (the extension's source is pure Compose foundation/runtime — portable).
  // Activated by `renderNow.overrides.touchOverlay = true` or for live recording sessions.
  implementation(project(":data-touch-overlay-connector"))
  // Launcher-widget container-size connector — same module Android consumes. The around-composable
  // wraps the preview body in a sized `Box` matching the clamped whole-cell footprint, driven
  // from `renderNow.overrides.launcherWidget`.
  implementation(project(":data-launcher-widget-connector"))
  implementation(project(":data-recomposition-connector"))
  // Display-filter connector — `DisplayFilterDataProducer.writeArtifacts` runs the post-capture
  // pipeline and writes per-variant PNGs + the `displayfilter-variants.json` manifest after each
  // PNG capture. Same dep on the Android side; the producer is renderer-agnostic (BufferedImage).
  implementation(project(":data-displayfilter-connector"))
  // Fonts connector — issue #1201 phase 1. Producer is currently Android-only
  // (GoogleFontInterceptor / Typeface accounting); the registry side reads JSON artefacts from
  // disk so it can be advertised on desktop unconditionally — `data/fetch` returns
  // NotAvailable when no producer has written. This removes the "kind not advertised" symptom
  // on the panel's Performance / Fonts chips for CMP-desktop sessions.
  implementation(project(":data-fonts-connector"))
  // Layoutinspector / strings connectors — issue #1201 phase 2. Both modules were migrated from
  // `android.library` to Compose Multiplatform JVM so `:daemon:desktop` can depend on them; their
  // file-based registries (`compose/semantics`, `layout/inspector`, `text/strings`,
  // `i18n/translations`) return `NotAvailable` on desktop until a CMP-portable producer ports,
  // but advertising them removes the wire-level "kind not advertised" symptom that the panel's
  // Layout / Strings / Translations chips trip on today.
  implementation(project(":data-layoutinspector-connector"))
  implementation(project(":data-strings-connector"))
  // Navigation registry — issue #1201 phase 4. The registry was extracted from `:daemon:android`
  // into `:data-navigation-connector` so desktop can advertise the kind. Producer side is still
  // Android-only (Intent reflection); the registry returns `NotAvailable` on desktop until a
  // CMP-portable producer ports (Compose Navigation is multiplatform; the wire payload would only
  // need a portable replacement for the Intent reader).
  implementation(project(":data-navigation-connector"))
  // Accessibility (desktop, overlay-only) — the desktop a11y path extracts Compose semantics from
  // `ImageComposeScene.semanticsOwners`, draws the Paparazzi-style overlay + legend with AWT, and
  // emits the wire-format DTOs from `:preview-data-api` (`ee.schimke.composeai.cli.*`). ATF is
  // Android-only, so findings are always empty here — no dependency on `:data-a11y-core` (an
  // android-library). See `DesktopAccessibility*`.
  implementation(project(":preview-data-api"))
  // Scroll data-product connector — issue #1604. Advertises render/scroll/long and
  // render/scroll/gif as requiresRerender=true kinds, and carries `ScrollDataProductRegistry`
  // plus the `ScrollPreviewExtension.KIND_*` / descriptor constants. Pure-JVM module — mirrors the
  // Android side's `implementation(project(":data-scroll-connector"))`. `RenderEngine` branches
  // into the scroll scenario when the dispatcher's `data/fetch` re-render path queues
  // `mode=scroll-long` / `scroll-gif`, writing to the same on-disk paths the registry reads back.
  implementation(project(":data-scroll-connector"))
  // Renderer-desktop — the `runComposeUiTest`-driven scroll capture (`renderScrollPreview`) that
  // drives `SemanticsActions.ScrollBy`, stitches LONG slices, and encodes the GIF. Mirrors
  // `:daemon:android`'s `implementation(project(":renderer-android"))` dependency for the scroll
  // handlers; the daemon's `RenderEngine.runScrollScenario` delegates to it per re-render. The
  // function's `compose.uiTest` machinery stays internal to `:renderer-desktop` (that module's
  // `implementation` dep) and rides along on the runtime classpath transitively.
  implementation(project(":renderer-desktop"))
  // `kind=LOTTIE` live render: RenderEngine inflates a discovered Lottie asset via `LottiePreview`
  // (brings Compottie transitively). Direct dep — `:renderer-desktop` carries it only as
  // `implementation`, so it isn't on this module's compile classpath otherwise.
  implementation(project(":lottie-preview-runtime"))

  // Compose runtime / foundation / ui — the B-desktop.1.4 RenderEngine body
  // imports `ImageComposeScene`, `@Composable`, `currentComposer`,
  // `getDeclaredComposableMethod`, and a few Modifier / layout helpers.
  // Platform-agnostic surface; resolves to `*-desktop` variants on JVM
  // consumers via Compose Multiplatform's variant selection.
  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.material3)
  implementation(libs.jetbrains.compose.components.ui.tooling.preview)

  // `compose.desktop.currentOs` bakes the *build host's* Skiko platform into
  // the published POM (e.g. `desktop-jvm-linux-x64` when CI builds on Linux),
  // which would lock consumers to that platform. Declare as `compileOnly` so
  // we get `ImageComposeScene`, `Window`, etc. on the compile classpath but
  // the host-specific dep does NOT escape into the published POM.
  compileOnly(compose.desktop.currentOs)

  // Per-platform Skiko native runtime bundles, runtime-only. Each
  // `compose.desktop.<os_arch>` accessor resolves to
  // `org.jetbrains.compose.desktop:desktop-jvm-<os>-<arch>:<version>`, which
  // transitively pulls `org.jetbrains.skiko:skiko-awt-runtime-<os>-<arch>` —
  // the jar carrying the per-OS `libskiko-<os>-<arch>.so/.dylib/.dll`.
  // Skiko's loader picks the right native at runtime by inspecting
  // `os.name` / `os.arch`, so shipping all six is safe; only the matching
  // one is actually dlopen'd.
  //
  // Why this isn't `compose.desktop.currentOs`: that helper resolves to
  // exactly *one* of the per-OS coordinates at *configuration* time on the
  // build host, so the publication ends up advertising e.g. linux-x64 only
  // (whichever CI runs on) and consumers on other platforms hit
  // `LibraryLoadException: Cannot find libskiko-<their-os>-<their-arch>.so`
  // the first time a render touches `ImageComposeScene`. Listing all six
  // per-platform coordinates explicitly puts every native on the published
  // POM / Gradle Module Metadata so a vanilla `implementation
  // ("ee.schimke.composeai:daemon-desktop:<v>")` works on any
  // Compose-Desktop-supported host without consumers having to know about
  // Skiko, classifier resolution, or `compose.desktop.currentOs`. Trade-off
  // is roughly 35–40 MB of extra native jars in the consumer's runtime
  // closure for the platforms they're not on — acceptable for the
  // out-of-the-box correctness this buys.
  runtimeOnly(compose.desktop.linux_x64)
  runtimeOnly(compose.desktop.linux_arm64)
  runtimeOnly(compose.desktop.macos_x64)
  runtimeOnly(compose.desktop.macos_arm64)
  runtimeOnly(compose.desktop.windows_x64)
  runtimeOnly(compose.desktop.windows_arm64)

  testImplementation(libs.junit)
  // Tests declare a small fixture composable + drive RenderEngine against it,
  // so the test classpath needs the same Compose Multiplatform stack.
  testImplementation(compose.desktop.currentOs)
  testImplementation(libs.jetbrains.compose.runtime)
  testImplementation(libs.jetbrains.compose.foundation)
  testImplementation(libs.jetbrains.compose.ui)
  testImplementation(libs.jetbrains.compose.material3)
  testImplementation(libs.jetbrains.compose.components.ui.tooling.preview)

  // testFixtures source set holds `RedFixturePreviews.kt` so its `RedSquare` composable can be
  // consumed by `:daemon:harness`'s real-mode S1 (D-harness.v1.5a) without requiring that
  // module to apply Compose plugins. The Compose runtime/ui deps below mirror the test
  // declarations above; only the foundation + runtime + ui surface area the fixtures actually
  // touch is needed here.
  //
  // The per-OS Skiko native bundle propagates transitively through the main module's
  // `runtimeOnly(compose.desktop.<os_arch>)` deps (all six platforms), so the spawned daemon
  // JVM has `libskiko-<host>-<arch>.so/.dylib/.dll` resolvable on its classpath without the
  // testFixtures variant having to add it. Belt-and-braces: also declare `currentOs` here so
  // local in-process iteration on testFixtures (e.g. running just this module's tests against
  // its fixtures) doesn't depend on the main runtimeOnly closure being honoured by every
  // resolution path. testFixtures variants are skipped from the publishable component (see the
  // `afterEvaluate` block below), so this stays out of `daemon-desktop`'s POM either way.
  "testFixturesImplementation"(compose.desktop.currentOs)
  "testFixturesImplementation"(libs.jetbrains.compose.runtime)
  "testFixturesImplementation"(libs.jetbrains.compose.foundation)
  "testFixturesImplementation"(libs.jetbrains.compose.ui)
  "testFixturesImplementation"(libs.jetbrains.compose.material3)
}

// Convenience task — equivalent to `java -cp $(runtimeClasspath) ee.schimke.composeai.daemon
// .DaemonMain`. Lets local verification of the daemon happen without applying the
// `application` plugin (which would add `distZip`/`distTar`/etc. tasks we don't need yet). Wire-up
// to the Gradle plugin's daemon launch descriptor lands in a later Stream A task; this task is a
// debug entry point — `runDaemonMain` blocks waiting for stdin (the JSON-RPC channel) and exits
// when the client sends `shutdown` + `exit` (or stdin closes).
tasks.register<JavaExec>("runDaemonMain") {
  group = "application"
  description =
    "Runs DaemonMain (B-desktop.1.5: JsonRpcServer + DesktopHost). Blocks waiting for stdin."
  classpath =
    sourceSets["main"].runtimeClasspath + files(tasks.named("jar").map { (it as Jar).archiveFile })
  mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
  standardInput = System.`in`
  dependsOn("jar")
}

// `java-test-fixtures` adds testFixtures-* "Elements" configurations that Vanniktech's
// auto-detection picks up and ships as `-test-fixtures.jar` / `-test-fixtures-sources.jar`.
// The fixtures (`RedSquare`, etc.) are internal harness aids — do not publish them. Skip the
// publishable testFixtures variants from the `java` component. Done in `afterEvaluate` because
// Vanniktech adds the sources/javadoc variants to the component lazily — calling
// `withVariantsFromConfiguration` before `addVariantsFromConfiguration` fails (the variant
// isn't yet attached to the component even though the configuration exists).
afterEvaluate {
  val javaComponent = components["java"] as org.gradle.api.component.AdhocComponentWithVariants
  listOf(
      "testFixturesApiElements",
      "testFixturesRuntimeElements",
      "testFixturesSourcesElements",
      "testFixturesJavadocElements",
    )
    .forEach { name ->
      configurations.findByName(name)?.let {
        javaComponent.withVariantsFromConfiguration(it) { skip() }
      }
    }
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "daemon-desktop",
    displayName = "Compose Preview — Daemon Desktop",
    description =
      "Compose Multiplatform desktop backend of the compose-preview daemon: long-lived JVM + Skiko render thread, per-render ImageComposeScene. Pre-1.0; pairs with daemon-core.",
  )
  inceptionYear.set("2025")
}
