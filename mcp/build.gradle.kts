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

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

group = "ee.schimke.composeai"

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

dependencies {
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  // For DaemonClasspathDescriptor (read at supervisor spawn time) and the protocol message types
  // exchanged with daemon JVMs.
  implementation(project(":daemon:core"))

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.kotlinx.coroutines.core)
}

// We deliberately do NOT depend on `io.modelcontextprotocol:kotlin-sdk` in v0:
// - The SDK auto-registers internal handlers for `resources/list` / `resources/read` / `subscribe`,
//   making the dynamic catalog + push subscription model we need awkward to bolt on.
// - Rolling our own keeps the wire layer self-contained, mirrors the proven
//   `:daemon:core` `JsonRpcServer` framing, and removes a 0.x-version-pin risk.
// - The SDK can be reintroduced as a non-breaking internal refactor once the surface stabilises.

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach {
  useJUnit()
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
