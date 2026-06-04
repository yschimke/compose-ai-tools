// `:lottie-preview-runtime` — composable-helper authoring path for Lottie animation previews.
// Sister to `:notification-preview-runtime` / `:splash-preview-runtime`, but JVM/Desktop-flavoured:
// it leans on Compottie (the KMP Lottie runtime), which gives a first-class Compose `Painter` on
// the
// Desktop/`ImageComposeScene` renderer with no platform animation APIs.
//
// `LottiePreview(asset = "lottie/loading.json", progress = 0.5f)` loads a Lottie composition from a
// classpath resource (the consumer ships the `.json`/`.lottie` under `src/main/resources/`, which
// the
// preview plugin links onto the render classpath and packs into the bundle) and draws it at a
// fixed,
// deterministic `progress` so the captured PNG is reproducible. The progress knob is the seam the
// daemon's interactive override path (follow-up) drives so a VS Code slider can scrub the timeline.
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
  api(libs.jetbrains.compose.ui)
  // Compottie — the KMP Lottie runtime. `api` so consumers can reference `LottieComposition` /
  // dynamic-property helpers directly when they want finer control than `LottiePreview` exposes.
  api(libs.compottie)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "lottie-preview-runtime",
    displayName = "Compose Preview — Lottie Runtime",
    description =
      "Composable helper that renders a Lottie animation asset at a fixed progress inside a Compose " +
        "`@Preview`, so authors can capture deterministic frames of `.json`/`.lottie` animations " +
        "through the compose-preview pipeline (Desktop renderer via Compottie).",
  )
  inceptionYear.set("2026")
}
