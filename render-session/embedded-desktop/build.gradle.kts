// In-process Compose Desktop backend for the render-session library: hosts `:daemon:desktop`'s
// `runDaemon(...)` on a background thread over in-memory pipes, surfaced as a `RenderSession` —
// same protocol, no fork.
//
// Embedded (this) saves the JVM-fork startup (~1–2s per session) at the cost of Compose Desktop +
// Skiko + daemon on the caller's classpath — good for test rigs and IDE plugins.
// `:render-session-subprocess` keeps the caller minimal and allows daemon JVM args.
//
// Limitations: no Android/Robolectric equivalent (opening one fails cleanly), and descriptor system
// properties are JVM-global, so run one session at a time.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":render-session-api"))
  implementation(libs.composeai.common.io)

  // The daemon entry point, the JSON-RPC client, and `:render-session-subprocess`'s
  // transport-agnostic `DaemonClientRenderSession` delegate.
  implementation(project(":render-session-subprocess"))
  implementation(libs.composeai.daemon.desktop)
  implementation(libs.composeai.daemon.core)
  // Only ever needed `DaemonClient` from here — this was `:mcp` until #3824 item 3 lifted the
  // transport into its own module, so an embedded render session no longer resolves an MCP server.
  implementation(libs.composeai.daemon.client)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "render-session-embedded-desktop",
    displayName = "Compose Preview — Embedded Desktop Render Session",
    description =
      "In-process Compose Multiplatform Desktop backend for the compose-preview render-session " +
        "API. Hosts the daemon's JSON-RPC server in the calling JVM via piped streams; no " +
        "subprocess fork. Pairs with :render-session-api.",
  )
  inceptionYear.set("2026")
}
