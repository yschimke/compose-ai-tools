plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-shared-element-core",
    displayName = "Compose Preview - Shared Element Data Product Core",
    description = "Shared-element transition findings model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
