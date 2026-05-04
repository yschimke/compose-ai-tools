plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  api(project(":data-render-core"))
  api(libs.jetbrains.compose.runtime)
  api(libs.jetbrains.compose.ui)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-render-compose",
    displayName = "Compose Preview - Render Data Product Compose Hooks",
    description = "Compose-aware hooks for generic render data-extension application.",
  )
  inceptionYear.set("2026")
}
