plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  api(project(":data-render-core"))
  api(platform(libs.compose.bom.compat))
  api(libs.compose.runtime)
  api(libs.compose.ui)

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
