@file:Suppress("DEPRECATION")
@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
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
  // Pure-JVM scroll primitives: `ScrollAxis` enum, `ScrollLongFramePlan` /
  // `ScrollGifFramePlan` planners, `ScrollSliceStitcher.stitchSlices`, `ScrollGifEncoder.encode`,
  // plus the `buildGifScrollScript` shape function. The Android driver
  // (`:data-scroll-android`'s `driveScrollByViewport`/`driveScrollBy`) is intentionally NOT
  // pulled in here — desktop drives scroll through `SemanticsActions.ScrollBy` against a
  // `ComposeUiTest`'s semantic owner directly.
  implementation(project(":data-scroll-core"))
  implementation(project(":common-io"))
  // `kind=LOTTIE` previews: DesktopRendererMain inflates a discovered Lottie asset via the
  // `LottiePreview` helper (brings Compottie + Compose foundation transitively).
  implementation(project(":lottie-preview-runtime"))
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
  // Plain-Compose named overrides — DesktopRendererMain drains `PreviewOverrideController` after each
  // render and writes the `renders/<stem>.overrides.json` sidecar `BundlePreviewTask` packs. The
  // consumer's `previewOverride*` calls resolve to the same controller, so depending on the runtime
  // here guarantees the class is present even when the consumer didn't add it (then nothing is
  // declared and no sidecar is written). `core` (re-exported) carries the payload serializer.
  implementation(project(":data-preview-overrides-runtime"))

  testImplementation(libs.junit)
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
