plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// `:launcher-preview-runtime` — composable helper that wraps preview content in a
// launcher-widget-shaped grid-cell container with min/max cell constraints and a stepped
// animation between whole-cell sizes when the target changes in a live preview. JVM module
// so both Android consumers (via the AndroidX Compose runtime they already carry) and
// Compose Multiplatform Desktop consumers (via JetBrains' org.jetbrains.compose artifacts —
// the same runtime, multiplatform packaging) pick it up.

dependencies {
  api(libs.jetbrains.compose.runtime)
  api(libs.jetbrains.compose.ui)
  api(libs.jetbrains.compose.foundation)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "launcher-preview-runtime",
    displayName = "Compose Preview — Launcher Widget Runtime",
    description =
      "Composable helper that wraps preview content in a launcher-widget-shaped grid-cell " +
        "container with min/max cell constraints and a stepped animation between whole-cell " +
        "sizes when the target changes in a live preview.",
  )
  inceptionYear.set("2026")
}
