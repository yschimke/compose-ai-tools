plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `daemon:core` carries `PreviewOverrides`, which `:data-keyboard-connector`'s planner reads via
  // the protocol's optional `keyboard` field. Keeping the kind constant on a tiny JVM module
  // mirrors
  // `:data-focus-core` / `:data-ambient-core` so MCP clients in other languages can depend on the
  // soft-keyboard product identity without dragging in the connector or any Compose / Robolectric
  // runtime.
  api(project(":daemon:core"))
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-keyboard-core",
    displayName = "Compose Preview - Soft Keyboard Data Product Core",
    description = "Shared soft-keyboard (IME) data-product identifier for Compose Preview.",
  )
  inceptionYear.set("2026")
}
