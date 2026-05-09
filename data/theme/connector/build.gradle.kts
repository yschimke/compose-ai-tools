@file:Suppress("DEPRECATION")

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
  api(project(":data-theme-core"))
  implementation(compose.runtime)
  implementation(compose.ui)
  implementation(compose.material3)
  testImplementation(libs.junit)
  testImplementation(compose.desktop.currentOs)
  testImplementation(compose.runtime)
  testImplementation(compose.ui)
  testImplementation(compose.foundation)
  testImplementation(compose.material3)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-theme-connector",
    displayName = "Compose Preview - Theme Data Product Connector",
    description = "Daemon-side theme data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
