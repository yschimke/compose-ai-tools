plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `daemon:core` carries `PreviewOverrides`, which `:data-focus-connector`'s planner reads via the
  // protocol's optional `focus` field. Keeping the wire-shape module on `daemon:core` mirrors
  // `:data-ambient-core` / `:data-wallpaper-core` so MCP clients in other languages can depend on
  // the focus model without dragging in the connector or any Compose / Robolectric runtime.
  api(project(":daemon:core"))
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-focus-core",
    displayName = "Compose Preview - Focus Data Product Core",
    description = "Shared focus / keyboard-traversal model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
