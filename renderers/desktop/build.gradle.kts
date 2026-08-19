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
val skikoEncodeProbe: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  description = "A skiko resolved for reflection only, never on a compile or runtime classpath."
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
