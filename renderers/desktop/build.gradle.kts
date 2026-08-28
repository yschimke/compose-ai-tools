@file:Suppress("DEPRECATION")
@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

/**
 * A second skiko, resolved for inspection only.
 *
 * The renderer compiles against whatever `compose-multiplatform` brings (0.144.6 today), so its own
 * test classpath can only ever demonstrate ONE `Image.encodeToData` shape — and the shape that
 * broke every desktop capture is the other one (#4190). This configuration resolves the newer jar
 * beside it without letting it near anything that runs, so the shape can be read off the real
 * artifact rather than off a hand-written stand-in.
 */
val skikoEncodeProbe =
  configurations.create("skikoEncodeProbe") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "A skiko resolved for reflection only, never on a compile or runtime classpath."
  }

/**
 * `compose-multiplatform-forward` must stay strictly ahead of `compose-multiplatform`.
 *
 * The forward runtime is only worth resolving if it is the *next* Compose line. Level with the
 * production pin it runs the invariant twice on the same graph; below it, Gradle conflict
 * resolution hands the task a **downgraded** one — and in both cases the task still passes, so
 * compatibility regressions in the line this exists to watch stop being covered with nothing
 * reporting it. That is not hypothetical: the two pins moved independently once already, and the
 * gap was only visible to someone who happened to read both lines of the catalog.
 *
 * A pre-release sorts below the release it precedes, so a production bump to `1.12.0` final fails
 * here against a forward pin of `1.12.0-rc01` rather than silently levelling.
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

// `asProvider()` because `compose-multiplatform-forward` shares this alias's prefix, which makes
// `libs.versions.compose.multiplatform` a group node rather than the leaf version.
val productionCompose = libs.versions.compose.multiplatform.asProvider().get()
val forwardCompose = libs.versions.compose.multiplatform.forward.get()

require(composeVersionIsNewer(forwardCompose, productionCompose)) {
  "compose-multiplatform-forward ($forwardCompose) must be strictly newer than " +
    "compose-multiplatform ($productionCompose). forwardComposeSystemThemeTest exists to run the " +
    "renderer invariant on the next Compose line; at or below the production pin it runs on an " +
    "identical or downgraded graph and passes while covering nothing. Bump the forward pin in " +
    "gradle/libs.versions.toml."
}

/**
 * The newest Compose line catalogs are allowed to run on, used only by the theme regression test.
 *
 * Extending the ordinary test runtime keeps this module's own compiled output and non-Compose
 * dependencies, while the explicit newer desktop distribution wins Gradle conflict resolution for
 * Compose and Skiko. This is the consumer shape that exposed the erased LocalSystemTheme enum
 * change; running only against [libs.versions.compose.multiplatform] would miss it.
 */
val composeForwardTestRuntime =
  configurations.create("composeForwardTestRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Renderer tests running on the forward Compose Multiplatform 1.12 runtime."
    extendsFrom(configurations.testRuntimeClasspath.get())
  }

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.jetbrains.compose.material3)
  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.components.ui.tooling.preview)
  // JetBrains Compose UI Test — gives the renderer `runComposeUiTest { ... }`, the desktop
  // equivalent of Android's `AndroidComposeTestRule`. Used by `DesktopRendererMain.renderScroll`
  // to drive `@ScrollingPreview` LONG / GIF modes: setContent, mainClock for animation control,
  // semantic queries for finding the scrollable, captureToImage for per-frame PNGs. NOT a test
  // dependency — invoked from production main code (the function name is misleading; it's an
  // entry-point for the test API, not a JUnit-only construct).
  implementation(libs.jetbrains.compose.ui.test)
  // The `@InteractionPreview` script expansion (`InteractionScript.timeline`, the `TAP_PRESS_MS` /
  // `MAX_INTERACTION_DURATION_MS` bounds) and the APNG encoder + frame-delay rationals, shared with
  // the Robolectric renderer so one component's capture can't disagree with its sibling's about how
  // long the gesture ran or how fast the file plays back.
  implementation(project(":data-motion-core"))
  // Pure-JVM scroll primitives: `ScrollAxis` enum, `ScrollLongFramePlan` /
  // `ScrollGifFramePlan` planners, `ScrollSliceStitcher.stitchSlices`, `ScrollGifEncoder.encode`,
  // plus the `buildGifScrollScript` shape function. The Android driver
  // (`:data-scroll-android`'s `driveScrollByViewport`/`driveScrollBy`) is intentionally NOT
  // pulled in here — desktop drives scroll through `SemanticsActions.ScrollBy` against a
  // `ComposeUiTest`'s semantic owner directly.
  implementation(project(":data-scroll-core"))
  // `@FocusedPreview` drive — `FocusController` (the per-capture state holder + settle window),
  // the `FocusOverrideExtension` around-composable that installs keyboard input mode and walks
  // `FocusManager.moveFocus(...)`, and `FocusOverlayDesktop` for `overlay = true`. Same seam the
  // Android renderer consumes from `:data-focus-connector`: no focus logic is reimplemented in
  // `DesktopFocusRenderer`, it only decides when to flip the controller and where to press.
  implementation(project(":data-focus-connector-desktop"))
  implementation(project(":common-io"))
  // PreviewBackground — the shared showBackground/backgroundColor/uiMode resolution.
  implementation(project(":data-render-core"))
  // Runtime-aware LocalSystemTheme binding. CMP 1.12 changed the local from the androidx enum to
  // the Skiko enum without changing its erased JVM getter, so a direct provider silently breaks
  // dark previews when this 1.11-compiled renderer runs inside a 1.12 catalog.
  implementation(project(":data-render-compose"))
  // `kind=LOTTIE` previews: DesktopRendererMain inflates a discovered Lottie asset via the
  // `LottiePreview` helper (brings Compottie + Compose foundation transitively).
  implementation(project(":lottie-preview-runtime"))
  // `kind=SVG` previews: DesktopRendererMain draws a discovered SVG asset via the `SvgPreview`
  // helper (Skia-backed `loadSvgPainter`, shared with the consumer-facing authoring path).
  implementation(project(":svg-preview-runtime"))
  // Pure-JVM accent / bidi transforms + the `Pseudolocale` enum used to detect `en-XA` / `ar-XB`
  // tags. Renderer applies the around-composable inline (LocalLayoutDirection.Rtl for ar-XB) and
  // rewrites the locale tag before it reaches `LocaleList`.
  implementation(project(":data-pseudolocale-core"))
  // Display-filter connector — DesktopRendererMain reads `composeai.displayfilter.filters` after
  // each successful PNG render and calls `DisplayFilterDataProducer.writeArtifacts(...)` to emit
  // per-filter variants alongside the base capture. Same dep on the daemon side; the producer is
  // renderer-agnostic (BufferedImage / ImageIO).
  implementation(project(":data-displayfilter-connector"))
  // Device-frame connector — DesktopRendererMain reads `composeai.deviceframe.device` after each
  // render and calls `DeviceFrameDataProducer.writeArtifacts(...)` to composite the PNG into a real
  // device-art bezel. Renderer-agnostic (BufferedImage / ImageIO + Ktor/OkHttp fetch).
  implementation(project(":data-deviceframe-connector"))
  // Plain-Compose named overrides — DesktopRendererMain drains `PreviewOverrideController` after
  // each
  // render and writes the `renders/<stem>.overrides.json` sidecar `BundlePreviewTask` packs. The
  // consumer's `previewOverride*` calls resolve to the same controller, so depending on the runtime
  // here guarantees the class is present even when the consumer didn't add it (then nothing is
  // declared and no sidecar is written). `core` (re-exported) carries the payload serializer.
  implementation(project(":data-preview-overrides-runtime"))

  testImplementation(libs.junit)

  // Deliberately NOT a test dependency: it is resolved into its own configuration and handed to the
  // test as a path, so `SkikoBridgeShapeTest` can load it in an isolated classloader. Putting it on
  // the test runtime classpath instead would let conflict resolution pick ONE skiko for the whole
  // module, which is precisely the mechanism being guarded against.
  skikoEncodeProbe(libs.skiko.awt.encode.probe)

  // Keep this explicit rather than deriving it from the production pin: the point of the task is
  // to preserve a second, forward runtime line. `currentOs` chooses the correct native Skiko
  // artifact on macOS/Linux/Windows; the strict version upgrades that distribution from 1.11.1.
  composeForwardTestRuntime(compose.desktop.currentOs) { version { strictly(forwardCompose) } }
}

tasks.withType<Test>().configureEach {
  // A lazily-resolved, content-keyed view rather than the live Configuration: the same reason
  // `ComposePreviewTasks` feeds its guards `incoming.artifactView { }.files` — a Configuration on a
  // task field is not serializable by the configuration cache.
  val probeJars = skikoEncodeProbe.incoming.artifactView {}.files
  inputs
    .files(probeJars)
    .withPropertyName("skikoEncodeProbe")
    .withNormalizer(ClasspathNormalizer::class)
  doFirst { systemProperty("composeai.test.skikoEncodeProbe", probeJars.asPath) }
}

val forwardComposeSystemThemeTest =
  tasks.register<Test>("forwardComposeSystemThemeTest") {
    group = "verification"
    // Derived, not spelled out: a hardcoded number here goes stale on the next forward bump and
    // then describes a runtime the task is not using.
    description = "Runs the light/dark renderer invariant on Compose Multiplatform $forwardCompose."
    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.output + sourceSets.main.get().output + composeForwardTestRuntime
    filter { includeTestsMatching("ee.schimke.composeai.renderer.UiModeSystemThemeTest") }
    shouldRunAfter(tasks.test)
  }

tasks.check { dependsOn(forwardComposeSystemThemeTest) }

composeAiMavenPublishing {
  coordinates(
    artifactId = "renderer-desktop",
    displayName = "Compose Preview — Desktop Renderer",
    description =
      "Compose Multiplatform Desktop renderer for `@Preview` functions, used by the " +
        "compose-preview Gradle plugin to produce PNGs via `ImageComposeScene` outside " +
        "Android Studio.",
  )
  inceptionYear.set("2025")
}
