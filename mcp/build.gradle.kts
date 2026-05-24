// Top-level MCP server module — exposes the preview daemon as a Model Context Protocol server.
// See docs/daemon/MCP.md (high-level design) and docs/daemon/MCP-KOTLIN.md (implementation
// specifics) — except the module path: this is `:mcp`, top-level, not nested under `:daemon`.
//
// The MCP shim is renderer-agnostic: it depends only on `:daemon:core` for protocol message
// types and spawns daemon JVMs via launch descriptors emitted by `composePreviewDaemonStart`.
// It never depends on `:daemon:android` or `:daemon:desktop`.
//
// One server process can multiplex per-(workspace, module) daemons across multiple distinct
// projects or worktrees — see `DaemonSupervisor` for the workspace-id derivation.
//
// Published to Maven Central as `ee.schimke.composeai:mcp` because :render-session-subprocess
// and :render-session-embedded-desktop compile-time-reference its `DaemonClient` /
// `SubprocessDaemonClientFactory` / `DaemonLaunchDescriptor` / `WorkspaceId` /
// `RegisteredProject` / `DaemonSpawn` types. Without this coordinate on Central, downstream
// consumers calling `SubprocessRenderSessions.open(...)` hit a runtime linkage failure even
// though they resolved the rest of the published graph. The MCP server's `main()` ships in
// the `compose-preview-mcp-*.tar.gz` GitHub Release artifact (see release.yml) — the Maven
// jar is the library face of the same code.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

base { archivesName.set("compose-preview-mcp") }

application {
  applicationName = "compose-preview-mcp"
  mainClass.set("ee.schimke.composeai.mcp.DaemonMcpMain")
}

// Match `:cli` — `archiveExtension = "tar.gz"` keeps the in-archive root as
// `compose-preview-mcp-<version>/` rather than leaking `.tar.gz` into the dir name.
tasks.named<Tar>("distTar") {
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
}

dependencies {
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.mcp.kotlin.sdk.server)
  // `:daemon:core` and `:render-session-api` are advertised as `api` because `:mcp` exposes
  // their types on its public surface (e.g. `SupervisedDaemon.session: RenderSession`,
  // `DaemonClasspathDescriptor`, the protocol message types reused by
  // `:render-session-subprocess`). Without `api` the generated POM scopes them as `runtime`
  // only and consumers resolving from POM metadata fail to compile against the MCP APIs.
  api(project(":daemon:core"))
  api(project(":render-session-api"))

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.kotlinx.coroutines.core)
}

tasks.withType<Test>().configureEach {
  // Opt-in real-mode: `-Pmcp.real=true` flips the JUnit `Assume` gate in
  // `RealMcpEndToEndTest`. Mirrors `:daemon:harness`'s `-Pharness.host=real` pattern.
  // The optional `-Pmcp.workdir=<path>` lets out-of-tree runs point the test at a different
  // checkout; defaults to the test's own working directory.
  val mcpReal = providers.gradleProperty("mcp.real").orNull == "true"
  systemProperty("composeai.mcp.real", mcpReal.toString())
  providers.gradleProperty("mcp.workdir").orNull?.let {
    systemProperty("composeai.mcp.workdir", it)
  }
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "mcp",
    displayName = "Compose Preview — MCP Server",
    description =
      "Model Context Protocol server for the compose-preview daemon. Multiplexes per-(workspace, " +
        "module) daemon JVMs spawned from launch descriptors emitted by composePreviewDaemonStart, " +
        "and exposes the JSON-RPC `DaemonClient` / `SubprocessDaemonClientFactory` reused by the " +
        "render-session-subprocess and render-session-embedded-desktop libraries.",
  )
  inceptionYear.set("2025")
}
