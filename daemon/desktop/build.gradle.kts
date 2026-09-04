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
  // JVM client for the native XR render server. Explicit since `:daemon:core` stopped api-exposing
  // it: this module owns `XrManagerSessions`, the adapter from `XrSessionManager` onto the daemon's
  // `XrSessions` port, so the renderer client is a detail of the desktop daemon rather than of the
  // published protocol contract.
  implementation(project(":renderer-xr-client"))
  implementation(libs.composeai.common.io)
  // PreviewBackground — the shared showBackground/backgroundColor/uiMode resolution.
  implementation(libs.composeai.data.render.core)
  implementation(project(":data-render-connector"))
  // `CompositionTracing` — composable-level spans folded into the render trace.
  implementation(project(":data-render-compose"))
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
  // Plain-Compose named-override connector — same portable module `:daemon:android` consumes. Seeds
  // `renderNow.overrides.namedOverrides` into the `previewOverride*` lookups and produces the
  // `compose/overrides` data product (the preview's declared editable knobs).
  implementation(project(":data-preview-overrides-connector"))
  // The controller itself, not just the connector that plans it: the render body records a
  // parameter knob's declaration through `PreviewOverrideController.record` so both override
  // formats reach `compose/overrides` on one channel. The connector depends on the runtime with
  // `implementation`, so it isn't on this module's compile classpath transitively.
  implementation(project(":data-preview-overrides-runtime"))
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
  // emits the wire-format DTOs from `:preview-data-api` (`ee.schimke.composeai.previewdata.*`). ATF
  // is
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
  // Slot mode: RenderEngine provides `LocalSlotMode` (from `:slot-preview-runtime`) around the
  // rendered content, so a `PreviewSlot` marker draws a placeholder when `slotMode` is set.
  implementation(project(":slot-preview-runtime"))

  // Bundle previews keep generated composeResources on the disposable user classloader. The
  // daemon provides a JvmResourceReader for that loader around every composition so CMP resource
  // lookups do not fall back to components-resources' parent-classloader-bound default reader.
  implementation(libs.jetbrains.compose.components.resources)

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
  // `LocalPreviewBackgroundCleared` — the `SurfaceCardSquare` fixture reads it to prove the
  // `clearBackground` override reaches a composable that drops its own opaque fill.
  "testFixturesImplementation"(project(":slot-preview-runtime"))
  // `previewOverride*` — the `OverridableSquare` fixture declares a `fill` colour knob so
  // `OverrideIntegrationTest` can prove a `namedOverrides` seed reaches the composition and
  // changes the rendered pixels (the end-to-end named-override render path).
  "testFixturesImplementation"(project(":data-preview-overrides-runtime"))
}

/**
 * The `compose/figma-svg` export's Material interaction states, run one Compose line forward.
 *
 * `ForwardMaterialInteractionExportTest` is the reason this exists. Material forked its ripple node
 * — `androidx.compose.material.ripple.RippleNode` is the original, material3 1.5.0-alpha took a
 * copy into `androidx.compose.material3.ripple`, CMP material3 1.12 another into
 * `androidx.compose.material3.internal.ripple` — and the export matched the first name only, so on
 * every catalog running the newer lines it recognised no ripple node and silently dropped the focus
 * ring, the state layer and the press ripple alike (#4980). Nothing went red: this whole repository
 * renders at CMP 1.11, the line where the original match was correct, and `OverrideIntegrationTest`
 * covers precisely this surface at that pin.
 *
 * So the runtime has to move twice, not once. `compose.desktop.currentOs` carries **no material3**
 * (its POM is foundation/material/runtime/ui), so upgrading the desktop distribution alone leaves
 * material3 on the pre-fork line and the task passes while covering nothing — the same silent no-op
 * `:renderer-desktop`'s forward-pin guard exists to prevent. Both are pinned, and both are required
 * to be strictly ahead of what the module compiles against.
 */
val forwardComposeTestRuntime =
  configurations.create("forwardComposeTestRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Daemon tests running on the forward Compose Multiplatform + material3 runtime."
    extendsFrom(configurations.testRuntimeClasspath.get())
  }

// `asProvider()` because `compose-multiplatform-forward` shares this alias's prefix, which makes
// `libs.versions.compose.multiplatform` a group node rather than the leaf version.
val productionCompose = libs.versions.compose.multiplatform.asProvider().get()
val forwardCompose = libs.versions.compose.multiplatform.forward.get()
val productionMaterial3 = libs.versions.compose.multiplatform.material3.asProvider().get()
val forwardMaterial3 = libs.versions.compose.multiplatform.material3.forward.get()

/**
 * Version ordering that treats a pre-release as *below* the release it precedes, so a production
 * bump to a final `1.12.0` fails here against a forward pin of `1.12.0-rc01` rather than silently
 * levelling. At or below the production pin the forward task runs on an identical or downgraded
 * graph and passes while covering nothing.
 *
 * A deliberate copy of `:renderer-desktop`'s, not an accident: it is the same rule, and the two
 * would be worth sharing — but `build-logic` currently exposes convention plugins and nothing a
 * build script can call, so hoisting a comparator there is a new pattern on a classpath every
 * module in the build depends on. Keep the two in step; if a third forward pin appears, hoist it.
 */
fun composeVersionIsNewer(candidate: String, than: String): Boolean {
  fun core(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

  val candidateCore = core(candidate)
  val thanCore = core(than)
  for (index in 0 until maxOf(candidateCore.size, thanCore.size)) {
    val left = candidateCore.getOrElse(index) { 0 }
    val right = thanCore.getOrElse(index) { 0 }
    if (left != right) return left > right
  }
  val candidatePre = candidate.substringAfter('-', "")
  val thanPre = than.substringAfter('-', "")
  return when {
    candidatePre.isEmpty() && thanPre.isEmpty() -> false
    candidatePre.isEmpty() -> true
    thanPre.isEmpty() -> false
    else -> candidatePre > thanPre
  }
}

require(composeVersionIsNewer(forwardCompose, productionCompose)) {
  "compose-multiplatform-forward ($forwardCompose) must be strictly newer than " +
    "compose-multiplatform ($productionCompose) — see forwardComposeInteractionExportTest."
}

require(composeVersionIsNewer(forwardMaterial3, productionMaterial3)) {
  "compose-multiplatform-material3-forward ($forwardMaterial3) must be strictly newer than " +
    "compose-multiplatform-material3 ($productionMaterial3). forwardComposeInteractionExportTest " +
    "exists to run the figma-svg export against the material3 that forked its ripple node; at or " +
    "below the production pin it runs on the pre-fork node and passes while covering nothing. " +
    "Bump the forward pin in gradle/libs.versions.toml."
}

dependencies {
  // Explicit rather than derived from the production pins: the point of the task is to preserve a
  // second, forward runtime line. `strictly` is what wins conflict resolution against the 1.11
  // coordinates this configuration inherits from `testRuntimeClasspath`.
  forwardComposeTestRuntime(compose.desktop.currentOs) { version { strictly(forwardCompose) } }
  forwardComposeTestRuntime(libs.jetbrains.forward.compose.material3) {
    version { strictly(forwardMaterial3) }
  }
}

val forwardComposeInteractionExportTest =
  tasks.register<Test>("forwardComposeInteractionExportTest") {
    group = "verification"
    // Derived, not spelled out: hardcoded numbers here go stale on the next forward bump and then
    // describe a runtime the task is not using.
    description =
      "Runs the figma-svg interaction-state export on Compose Multiplatform $forwardCompose " +
        "with material3 $forwardMaterial3."
    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath =
      testSourceSet.output +
        sourceSets.main.get().output +
        sourceSets.getByName("testFixtures").output +
        forwardComposeTestRuntime
    filter {
      includeTestsMatching("ee.schimke.composeai.daemon.ForwardMaterialInteractionExportTest")
    }
    shouldRunAfter(tasks.test)
  }

// `check` for anyone running it, and `test` because that is what actually runs.
//
// CI never invokes `check`: the full branch of ci.yml's module-unit-test job runs
// `test jvmTest desktopTest checkKotlinAbi`, and the scoped branch runs whatever
// `.github/ci/affected-gradle-tests.py` resolves — which is `test`/`jvmTest` per project. That
// resolver's own docstring records this repository having already shipped a gate wired only into
// `check` and therefore never run; `finalizedBy` is what keeps this one out of that bucket. It
// also means a local `:daemon:desktop:test` covers the forward runtime, which is the point: the
// regression it guards was invisible precisely because the interaction-state suite only ever ran
// one Compose line.
tasks.check { dependsOn(forwardComposeInteractionExportTest) }

tasks.test {
  finalizedBy(forwardComposeInteractionExportTest)
  // The forward class asserts behaviour that only exists once material3 has forked its ripple
  // node, so on the production pin it would fail for the right reason at the wrong time. It runs
  // in `forwardComposeInteractionExportTest` and nowhere else — and that task's first test fails
  // loudly if the forward runtime ever resolves back to the production line, so this exclusion
  // cannot end up hiding a class that has quietly stopped being run anywhere.
  filter {
    excludeTestsMatching("ee.schimke.composeai.daemon.ForwardMaterialInteractionExportTest")
  }
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
