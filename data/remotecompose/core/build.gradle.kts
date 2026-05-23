plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // `daemon:core` carries `PreviewOverrides`, `RemoteComposeOverride`, `RemoteNamedValue`, and
  // `RemoteHostAction` (the wire-shape the connector's planner reads from). Mirrors
  // `:data-permissions-core` / `:data-keyboard-core` — the kind constant + payload class lives on a
  // tiny JVM module so MCP clients in other languages can depend on the Remote Compose product
  // identity without dragging in the connector, Compose, or the alpha `androidx.compose.remote.*`
  // artifacts.
  api(project(":daemon:core"))
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-remotecompose-core",
    displayName = "Compose Preview - Remote Compose Data Product Core",
    description =
      "Shared Remote Compose data-product model classes for Compose Preview (named-value store, host action queue, profile).",
  )
  inceptionYear.set("2026")
}
