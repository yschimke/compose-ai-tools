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
    artifactId = "data-layoutinspector-core",
    displayName = "Compose Preview - Layout Inspector Data Product Core",
    description = "Shared layout inspector data-product model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
