plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `daemon:core` carries the GestureKindOverride enum the payload mirrors. MCP clients in other
  // languages already consume the protocol module; pulling `data-gestures-core` adds the gesture
  // payload schema alongside.
  api(project(":daemon:core"))
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-gestures-core",
    displayName = "Compose Preview - Wear OS Gestures Data Product Core",
    description =
      "Shared Wear OS one-handed-gesture data-product model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
