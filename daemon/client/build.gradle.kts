// JVM client for the preview daemon's JSON-RPC-over-stdio protocol.
//
// `DaemonClient` speaks the Content-Length-framed wire format from PROTOCOL.md; the
// `DaemonClientFactory` port spawns a daemon and hands back a `DaemonSpawn` that owns it, with
// `SubprocessDaemonClientFactory` as the production implementation (forks a JVM per launch
// descriptor). Test doubles implement the same port over piped streams.
//
// This module exists because these types used to live in `:mcp`. Consuming the render-session
// library therefore dragged an MCP server onto the classpath — the last leak the preview-server
// contract probe recorded (#3824 preparation item 3). Nothing here knows about MCP: no tools, no
// resources, no supervisor. `:mcp` now depends on this module rather than owning it.
//
// The package is `ee.schimke.composeai.daemon.client`, deliberately not the old
// `ee.schimke.composeai.mcp`. Two published artifacts sharing one package is a split package, which
// breaks JPMS and OSGi consumers and confuses everyone else. Per docs/API_STABILITY.md § 1 the
// published surface of `:mcp` is its MCP tool names and input schemas — its Kotlin types are
// internal and may move — so the rename needs no compatibility shim.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // Protocol message types (`RenderNowParams`, `InitializeResult`, `PreviewOverrides`, …) and
  // `DaemonLaunchDescriptor` are all over this module's public surface, so `api` rather than
  // `implementation`: a consumer resolving from POM metadata must see them to compile.
  api(project(":daemon:core"))
  // `classpathArgFile` — writes the daemon's @argfile so the argv survives Windows length limits.
  implementation(project(":common-io"))
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "daemon-client",
    displayName = "Compose Preview — Daemon Client",
    description =
      "JVM client for the compose-preview daemon's JSON-RPC-over-stdio protocol: the wire client, " +
        "the spawn port, and the subprocess implementation that forks a daemon JVM.",
  )
  inceptionYear.set("2026")
}
