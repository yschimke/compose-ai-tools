// `:data-touch-overlay-connector` — shared Compose Multiplatform module hosting the touch-event
// visualization extension (`TouchOverlayExtension` `AroundComposable`) + its planner
// (`TouchOverlayPreviewOverrideExtension`). Consumed by both `:daemon:desktop` and
// `:daemon:android`.
//
// Unlike `:data-keyboard-connector` (which forks Android vs desktop because the Android side uses
// `androidx.core.WindowInsetsCompat`), the touch overlay's source is purely
// `androidx.compose.foundation` + `androidx.compose.ui` (pointer-input, draw-scope, frame-clock
// animation). All APIs are available on both Android Compose and Compose Multiplatform Desktop, so
// a single shared module suffices — mirrors the `:data-render-compose` setup (the layer used by
// both daemon backends for `AroundComposable` infra).

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  api(project(":daemon:core"))
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.foundation)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-touch-overlay-connector",
    displayName = "Compose Preview - Touch Overlay Data Product Connector",
    description =
      "Daemon-side touch-event visualization connector: paints translucent rings at every pressed pointer plus short-lived expanding pulses on down/up — Android's 'Show touches' developer-mode toggle, but agent-pixel-true. Activated via `renderNow.overrides.touchOverlay = true` or for live recording sessions.",
  )
  inceptionYear.set("2026")
}
