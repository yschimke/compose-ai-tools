plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `daemon:core` carries the AmbientStateOverride enum the payload mirrors. MCP clients in other
  // languages already consume the protocol module; pulling `data-ambient-core` adds the ambient
  // payload schema alongside.
  api(project(":daemon:core"))
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-ambient-core",
    displayName = "Compose Preview - Ambient Data Product Core",
    description = "Shared Wear OS ambient-mode data-product model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
