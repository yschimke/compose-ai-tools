// Subprocess-backed implementation of the public render-session library.
//
// Spawns the preview daemon JVM via the launch descriptor written by the gradle plugin's
// `composePreviewDaemonStart` task, drives it over Content-Length-framed JSON-RPC over stdio,
// and exposes the result as a `RenderSession`. This is the default backend — works on any JVM
// with a JDK on the host machine, no Robolectric / AGP / Compose deps required at the call site.
//
// Builds on `:mcp`'s existing `DaemonClient` + `SubprocessDaemonClientFactory` rather than
// re-implementing the JSON-RPC transport. The MCP server keeps consuming those types directly;
// the public render-session API is the supported surface for third-party tooling.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":render-session-api"))

  // Existing JSON-RPC client + subprocess spawn infrastructure. Public surface here is the
  // `RenderSession` contract; `:mcp` types stay internal to this module.
  implementation(project(":mcp"))
  implementation(project(":daemon:core"))
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "render-session-subprocess",
    displayName = "Compose Preview — Subprocess Render Session",
    description =
      "Daemon-subprocess-backed implementation of the compose-preview render-session API. Spawns " +
        "a daemon JVM per session, drives it via JSON-RPC, presents the result as a RenderSession.",
  )
  inceptionYear.set("2026")
}
