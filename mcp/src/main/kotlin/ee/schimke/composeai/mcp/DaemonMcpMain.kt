package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.client.SubprocessDaemonClientFactory
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.File

/**
 * Entry point for the standalone MCP server. Stdio transport in v0; remote / HTTP transports are a
 * follow-up (see docs/daemon/MCP-KOTLIN.md § "Transports").
 *
 * **CLI:**
 *
 * ```
 * compose-preview-mcp [--project <path>[:<rootProjectName>]]...
 *                     [--replicas-per-daemon <N>]
 *                     [--ui-builder-url <URL>]
 *                     [--ui-builder-actor <ACTOR>]
 *                     [--streamable-http --http-port <PORT>]
 *                     [--storybook]
 * ```
 *
 * `--storybook` (or `-Dcomposeai.mcp.profile=storybook`) runs the **Storybook-compatibility
 * profile**: the server exposes only the Storybook-MCP tools (`list-all-documentation`,
 * `get-documentation-for-story`, `preview-stories`, `run-story-tests`) with the native tools
 * hidden, and identifies as `compose-preview-storybook`. Point a Storybook-MCP-trained agent at
 * this to drive compose-preview unmodified. Omit it for the full native tool set (the default).
 *
 * Each `--project` flag pre-registers a workspace with the supervisor at startup so connecting
 * clients see the project in `list_projects` immediately. Projects can also be added at runtime via
 * the `register_project` MCP tool.
 *
 * `--replicas-per-daemon N` (or the `composeai.mcp.replicasPerDaemon` system property) configures
 * the in-JVM sandbox pool size: total sandboxes per (workspace, module) = `1 + N`. SANDBOX-POOL.md
 * Layer 3 collapsed what used to be N+1 separate JVM subprocesses into a single daemon JVM hosting
 * N+1 Robolectric sandboxes, so this knob no longer multiplies the JVM-baseline cost. Default
 * [DaemonSupervisor.DEFAULT_REPLICAS_PER_DAEMON] (= 4, i.e. 5 sandboxes per daemon). Set `0` to opt
 * out and run a single sandbox per daemon.
 *
 * On stdin EOF the server tears down every supervised daemon (sending `shutdown` + `exit` per
 * PROTOCOL.md § 3) and exits cleanly.
 */
object DaemonMcpMain {

  @JvmStatic
  fun main(args: Array<String>) {
    disableKotlinLoggingStartupMessage()
    parseStreamableHttp(args)?.let { config ->
      require(parseProjects(args).isEmpty()) {
        "--streamable-http serves the shared UI Builder only; --project is stdio-only"
      }
      require(!parseStorybookProfile(args)) {
        "--streamable-http serves the shared UI Builder only; --storybook is stdio-only"
      }
      runUiBuilderStreamableHttp(config)
      return
    }
    val replicasPerDaemon = parseReplicasPerDaemon(args)
    val storybookProfile = parseStorybookProfile(args)
    // Storybook compatibility is intentionally a closed tool surface. Do not even construct the
    // remote adapter in that profile: an ambient UI-builder URL must not add tools or make a token
    // mandatory for a Storybook-only process.
    val uiBuilderMcp = if (storybookProfile) null else parseUiBuilderMcp(args)
    val supervisor =
      DaemonSupervisor(
        descriptorProvider = DescriptorProvider.readingFromDisk(),
        clientFactory = SubprocessDaemonClientFactory(),
        replicasPerDaemon = replicasPerDaemon,
      )
    val server =
      if (storybookProfile) {
        // Storybook-compat face: present ONLY the Storybook-MCP tools (native tools hidden) and
        // identify as `compose-preview-storybook`, so a Storybook-MCP-trained agent sees a clean
        // Storybook surface. Same daemon core + handlers underneath.
        DaemonMcpServer(
          supervisor,
          serverInfo = Implementation(name = "compose-preview-storybook", version = "v0"),
          profile = McpToolProfile.STORYBOOK,
        )
      } else {
        DaemonMcpServer(supervisor, uiBuilderMcp = uiBuilderMcp)
      }

    parseProjects(args).forEach { (path, name) ->
      runCatching { supervisor.registerProject(File(path), name) }
        .onFailure {
          System.err.println("compose-preview-mcp: failed to register $path: ${it.message}")
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread { runCatching { supervisor.shutdown() } })

    val session = server.newSession(input = System.`in`, output = System.out)
    session.start()
    // Block main thread until stdin EOF (reader exits), then exit cleanly. The reader is a daemon
    // thread so the JVM would otherwise terminate immediately; awaitClose pins main here.
    session.awaitClose()
    runCatching { supervisor.shutdown() }
  }

  private fun disableKotlinLoggingStartupMessage() {
    runCatching {
      val configurationClass =
        Class.forName("io.github.oshai.kotlinlogging.KotlinLoggingConfiguration")
      val instance = configurationClass.getField("INSTANCE").get(null)
      configurationClass
        .getMethod("setLogStartupMessage", java.lang.Boolean.TYPE)
        .invoke(instance, false)
    }
  }

  private fun parseUiBuilderMcp(args: Array<String>): UiBuilderMcpAdapter? {
    val url =
      option(args, "--ui-builder-url")
        ?: System.getProperty("composeai.uiBuilder.url")
        ?: System.getenv("COMPOSE_PREVIEW_UI_BUILDER_URL")
        ?: return null
    val token =
      System.getenv("COMPOSE_PREVIEW_UI_BUILDER_TOKEN")
        ?: error("--ui-builder-url requires COMPOSE_PREVIEW_UI_BUILDER_TOKEN")
    val actor =
      option(args, "--ui-builder-actor")
        ?: System.getProperty("composeai.uiBuilder.actor")
        ?: System.getenv("COMPOSE_PREVIEW_UI_BUILDER_ACTOR")
    val client =
      if (actor == null) UiBuilderDesignApiClient.remote(url, token)
      else UiBuilderDesignApiClient.remote(url, token, actor)
    return UiBuilderMcpAdapter(client)
  }

  private fun option(args: Array<String>, name: String): String? {
    args
      .firstOrNull { it.startsWith("$name=") }
      ?.let {
        return it.substringAfter('=').takeIf(String::isNotBlank) ?: error("$name requires a value")
      }
    val index = args.indexOf(name)
    if (index < 0) return null
    return args.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }?.takeIf(String::isNotBlank)
      ?: error("$name requires a value")
  }

  private fun options(args: Array<String>, name: String): List<String> {
    val values = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
      when {
        args[index] == name -> {
          val value = args.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
          require(!value.isNullOrBlank()) { "$name requires a value" }
          values += value
          index += 2
        }
        args[index].startsWith("$name=") -> {
          values +=
            args[index].substringAfter('=').takeIf(String::isNotBlank)
              ?: error("$name requires a value")
          index++
        }
        else -> index++
      }
    }
    return values
  }

  private fun parseStreamableHttp(args: Array<String>): UiBuilderStreamableHttpConfig? {
    if ("--streamable-http" !in args) return null
    val uiBuilderUrl =
      option(args, "--ui-builder-url")
        ?: System.getProperty("composeai.uiBuilder.url")
        ?: System.getenv("COMPOSE_PREVIEW_UI_BUILDER_URL")
        ?: error("--streamable-http requires --ui-builder-url")
    val host = option(args, "--http-host") ?: "127.0.0.1"
    val portRaw = option(args, "--http-port") ?: "8788"
    val port = portRaw.toIntOrNull() ?: error("--http-port must be an integer")
    val allowedHosts = options(args, "--http-allowed-host").ifEmpty { null }
    if (host !in setOf("127.0.0.1", "localhost", "::1") && allowedHosts == null) {
      error("non-loopback --http-host requires at least one --http-allowed-host")
    }
    return UiBuilderStreamableHttpConfig(
      uiBuilderUrl = uiBuilderUrl,
      host = host,
      port = port,
      path = option(args, "--http-path") ?: "/ui-builder/mcp",
      allowedHosts = allowedHosts,
      allowedOrigins = options(args, "--http-allowed-origin").ifEmpty { null },
    )
  }

  private fun parseProjects(args: Array<String>): List<Pair<String, String?>> {
    val out = mutableListOf<Pair<String, String?>>()
    var i = 0
    while (i < args.size) {
      val a = args[i]
      when {
        a == "--project" && i + 1 < args.size -> {
          val raw = args[i + 1]
          val (path, name) = splitProjectArg(raw)
          out.add(path to name)
          i += 2
        }
        a.startsWith("--project=") -> {
          val raw = a.removePrefix("--project=")
          val (path, name) = splitProjectArg(raw)
          out.add(path to name)
          i++
        }
        else -> i++
      }
    }
    return out
  }

  /**
   * True when the server should run the Storybook-compatibility profile — either the `--storybook`
   * flag or the `composeai.mcp.profile=storybook` system property. In that profile only the
   * Storybook-MCP tools are exposed (native tools hidden). See [McpToolProfile].
   */
  private fun parseStorybookProfile(args: Array<String>): Boolean {
    if (args.any { it == "--storybook" }) return true
    return System.getProperty("composeai.mcp.profile")?.equals("storybook", ignoreCase = true) ==
      true
  }

  private fun splitProjectArg(raw: String): Pair<String, String?> {
    // Format: <path>[:<rootProjectName>]. Path may itself contain ':' on non-Windows hosts (rare),
    // so we split on the *last* colon. Empty name → null.
    val idx = raw.lastIndexOf(':')
    return if (idx <= 0) raw to null
    else raw.substring(0, idx) to raw.substring(idx + 1).takeIf { it.isNotEmpty() }
  }

  private fun parseReplicasPerDaemon(args: Array<String>): Int {
    // CLI flag wins over the system property; system property wins over the default. Negative
    // or unparseable values fall back to the default with a stderr warning rather than crashing
    // the server — replication is non-load-bearing, so prefer "did something reasonable" to
    // refusing to start.
    val fromArgs =
      generateSequence(0) { it + 1 }
        .takeWhile { it < args.size }
        .firstNotNullOfOrNull { i ->
          when {
            args[i] == "--replicas-per-daemon" && i + 1 < args.size -> args[i + 1]
            args[i].startsWith("--replicas-per-daemon=") ->
              args[i].removePrefix("--replicas-per-daemon=")
            else -> null
          }
        }
    val raw = fromArgs ?: System.getProperty("composeai.mcp.replicasPerDaemon")
    if (raw.isNullOrBlank()) return DaemonSupervisor.DEFAULT_REPLICAS_PER_DAEMON
    val parsed = raw.toIntOrNull()
    if (parsed == null || parsed < 0) {
      System.err.println(
        "compose-preview-mcp: ignoring invalid --replicas-per-daemon='$raw' (want non-negative int); " +
          "falling back to default ${DaemonSupervisor.DEFAULT_REPLICAS_PER_DAEMON}"
      )
      return DaemonSupervisor.DEFAULT_REPLICAS_PER_DAEMON
    }
    return parsed
  }
}
