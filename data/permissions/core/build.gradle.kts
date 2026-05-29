plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `daemon:core` carries `PreviewOverrides` and `PermissionsOverride` (the wire-shape the
  // connector's planner reads from). Mirrors `:data-keyboard-core` / `:data-focus-core` /
  // `:data-ambient-core` — the kind constant lives on a tiny JVM module so MCP clients in other
  // languages can depend on the permissions product identity without dragging in the connector,
  // Compose, or Robolectric.
  api(project(":daemon:core"))
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-permissions-core",
    displayName = "Compose Preview - Permissions Data Product Core",
    description =
      "Shared Android runtime-permissions data-product model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
