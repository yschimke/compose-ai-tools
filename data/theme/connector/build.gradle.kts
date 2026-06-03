@file:Suppress("DEPRECATION")

plugins {
  id("composeai.base-conventions")
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
  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.material3)
  testImplementation(libs.junit)
  testImplementation(compose.desktop.currentOs)
  testImplementation(libs.jetbrains.compose.runtime)
  testImplementation(libs.jetbrains.compose.ui)
  testImplementation(libs.jetbrains.compose.foundation)
  testImplementation(libs.jetbrains.compose.material3)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-theme-connector",
    displayName = "Compose Preview - Theme Data Product Connector",
    description = "Daemon-side theme data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
