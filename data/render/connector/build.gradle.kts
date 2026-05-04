plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":data-render-core"))
  api(project(":data-render-compose"))
  api(project(":daemon:core"))
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.ui)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-render-connector",
    displayName = "Compose Preview - Render Data Product Connector",
    description = "Daemon-side render data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
