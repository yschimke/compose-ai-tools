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
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
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
  // For DaemonClasspathDescriptor (read at supervisor spawn time) and the protocol message types
  // exchanged with daemon JVMs.
  implementation(project(":daemon:core"))

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
