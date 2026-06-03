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
  api(project(":data-recomposition-core"))
  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.ui)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-recomposition-connector",
    displayName = "Compose Preview - Recomposition Data Product Connector",
    description = "Daemon-side recomposition data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
