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

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.TaskAction

plugins {
  id("composeai.base-conventions")
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
  // Okio-based file IO (`SystemFileSystem`) for descriptor reads + PNG/video byte reads.
  implementation(project(":common-io"))
  // `:daemon:core` and `:render-session-api` are advertised as `api` because `:mcp` exposes
  // their types on its public surface (e.g. `SupervisedDaemon.session: RenderSession`,
  // `DaemonClasspathDescriptor`, the protocol message types reused by
  // `:render-session-subprocess`). Without `api` the generated POM scopes them as `runtime`
  // only and consumers resolving from POM metadata fail to compile against the MCP APIs.
  api(project(":daemon:core"))
  api(project(":render-session-api"))
  // Semantics diff engine + payload model for the `diff_semantics` tool (issue #1785).
  implementation(project(":data-layoutinspector-core"))

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

// Boundary check: `:mcp` must NOT pull `gradle-tooling-api` (directly or transitively). Cold-shell
// gradle invocation for the MCP install / doctor flow lives in `:cli`'s `McpCommand` and routes
// through `:gradle-preview-driver`; the daemon-driven path in `:mcp` itself never talks to Gradle.
// Without this guard, a future change adding `:gradle-preview-driver` to `:mcp` would silently
// drag tooling-api onto the MCP server's runtime — exactly the duplication issue #1394 closed.
abstract class CheckMcpToolingApiBoundary : DefaultTask() {
  @get:org.gradle.api.tasks.Classpath abstract val runtimeClasspath: ConfigurableFileCollection

  @TaskAction
  fun check() {
    val forbidden =
      runtimeClasspath.files
        .map { it.name }
        .filter { it.startsWith("gradle-tooling-api") && it.endsWith(".jar") }
        .sorted()
    check(forbidden.isEmpty()) {
      ":mcp must not depend on gradle-tooling-api; route cold-shell gradle work through " +
        ":gradle-preview-driver from :cli instead. Found on runtimeClasspath: " +
        forbidden.joinToString(", ")
    }
  }
}

val checkMcpToolingApiBoundary =
  tasks.register<CheckMcpToolingApiBoundary>("checkMcpToolingApiBoundary") {
    description = "Fails if gradle-tooling-api leaks onto :mcp's runtime classpath."
    group = "verification"
    runtimeClasspath.from(configurations.named("runtimeClasspath"))
  }

tasks.named("check") { dependsOn(checkMcpToolingApiBoundary) }
