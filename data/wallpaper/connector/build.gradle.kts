plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":daemon:core"))
  api(project(":data-render-compose"))
  implementation(compose.runtime)
  implementation(compose.ui)
  implementation(compose.material3)
  // KMP port of Material Color Utilities — `dynamicColorScheme(seed, isDark, style, contrast)`
  // returns the canonical Material 3 dynamic ColorScheme from a single seed color, matching what
  // Android's wallpaper-derived theming produces.
  implementation(libs.material.kolor)
  testImplementation(libs.junit)
  testImplementation(compose.desktop.currentOs)
  testImplementation(compose.runtime)
  testImplementation(compose.ui)
  testImplementation(compose.foundation)
  testImplementation(compose.material3)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-wallpaper-connector",
    displayName = "Compose Preview - Wallpaper Data Product Connector",
    description = "Daemon-side wallpaper seed-color data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
