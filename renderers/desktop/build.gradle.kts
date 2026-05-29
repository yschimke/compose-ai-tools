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
  implementation(compose.ui)
  implementation(compose.foundation)
  implementation(compose.material3)
  implementation(compose.runtime)
  implementation(compose.components.uiToolingPreview)
  // JetBrains Compose UI Test — gives the renderer `runComposeUiTest { ... }`, the desktop
  // equivalent of Android's `AndroidComposeTestRule`. Used by `DesktopRendererMain.renderScroll`
  // to drive `@ScrollingPreview` LONG / GIF modes: setContent, mainClock for animation control,
  // semantic queries for finding the scrollable, captureToImage for per-frame PNGs. NOT a test
  // dependency — invoked from production main code (the function name is misleading; it's an
  // entry-point for the test API, not a JUnit-only construct).
  implementation(compose.uiTest)
  // Pure-JVM scroll primitives: `ScrollAxis` enum, `ScrollLongFramePlan` /
  // `ScrollGifFramePlan` planners, `ScrollSliceStitcher.stitchSlices`, `ScrollGifEncoder.encode`,
  // plus the `buildGifScrollScript` shape function. The Android driver
  // (`:data-scroll-android`'s `driveScrollByViewport`/`driveScrollBy`) is intentionally NOT
  // pulled in here — desktop drives scroll through `SemanticsActions.ScrollBy` against a
  // `ComposeUiTest`'s semantic owner directly.
  implementation(project(":data-scroll-core"))
  // Pure-JVM accent / bidi transforms + the `Pseudolocale` enum used to detect `en-XA` / `ar-XB`
  // tags. Renderer applies the around-composable inline (LocalLayoutDirection.Rtl for ar-XB) and
  // rewrites the locale tag before it reaches `LocaleList`.
  implementation(project(":data-pseudolocale-core"))
  // Display-filter connector — DesktopRendererMain reads `composeai.displayfilter.filters` after
  // each successful PNG render and calls `DisplayFilterDataProducer.writeArtifacts(...)` to emit
  // per-filter variants alongside the base capture. Same dep on the daemon side; the producer is
  // renderer-agnostic (BufferedImage / ImageIO).
  implementation(project(":data-displayfilter-connector"))

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
