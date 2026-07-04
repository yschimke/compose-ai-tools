// `:svg-preview-runtime` — composable-helper authoring path for SVG image previews. Sister to
// `:lottie-preview-runtime`, and JVM/Desktop-flavoured for the same reason: it leans on Compose
// Desktop's Skia-backed `androidx.compose.ui.res.loadSvgPainter`, which turns raw SVG bytes into a
// first-class Compose `Painter` on the `ImageComposeScene` renderer with no platform image APIs.
//
// `SvgPreview(asset = "svg/badge.svg", colorFilter = ColorFilter.tint(Color.Red))` loads an SVG
// from a classpath resource (the consumer ships the `.svg` under `src/main/resources/`, which the
// preview plugin links onto the render classpath and packs into the bundle) and draws it, so an
// author can tint / scale / place the artwork inside a larger `@Preview`. Unlike Lottie there is no
// timeline — SVG is static, so there is no `progress` knob.
//
// Standalone on purpose — no compile dep on `:renderer-desktop` so the helper can be used in plain
// JVM unit tests or Bazel modules that don't carry the renderer.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  api(libs.jetbrains.compose.runtime)
  api(libs.jetbrains.compose.foundation)
  // `compose.ui` carries `androidx.compose.ui.res.loadSvgPainter` (Skia-backed) — the whole point
  // of
  // this module — so it's `api` rather than `implementation`.
  api(libs.jetbrains.compose.ui)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "svg-preview-runtime",
    displayName = "Compose Preview — SVG Runtime",
    description =
      "Composable helper that renders an SVG image asset inside a Compose `@Preview` (with optional " +
        "tint / content-scale), so authors can capture `.svg` artwork through the compose-preview " +
        "pipeline (Desktop renderer via Skia's loadSvgPainter).",
  )
  inceptionYear.set("2026")
}
