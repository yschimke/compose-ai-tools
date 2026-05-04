plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":data-render-core"))
  api(project(":data-render-compose"))
  api(project(":daemon:core"))
  implementation(platform(libs.compose.bom.compat))
  implementation(libs.compose.foundation)
  implementation(libs.compose.runtime)
  implementation(libs.compose.ui)
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
