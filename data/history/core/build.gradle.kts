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
    artifactId = "data-history-core",
    displayName = "Compose Preview - History Data Product Core",
    description = "Shared history data-product model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
