plugins {
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
    artifactId = "data-recomposition-core",
    displayName = "Compose Preview - Recomposition Data Product Core",
    description = "Shared recomposition data-product model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
