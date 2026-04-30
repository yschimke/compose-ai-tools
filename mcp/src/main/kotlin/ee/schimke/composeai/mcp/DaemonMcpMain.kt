package ee.schimke.composeai.mcp

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject

/**
 * Entry point for the standalone MCP server. Stdio transport in v0; remote / HTTP transports are a
 * follow-up (see docs/daemon/MCP-KOTLIN.md § "Transports").
 *
 * **CLI:**
 *
 * ```
 * compose-preview-mcp [--project <path>[:<rootProjectName>]]...
 * ```
 *
 * Each `--project` flag pre-registers a workspace with the supervisor at startup so connecting
 * clients see the project in `list_projects` immediately. Projects can also be added at runtime via
 * the `register_project` MCP tool.
 *
 * On stdin EOF the server tears down every supervised daemon (sending `shutdown` + `exit` per
 * PROTOCOL.md § 3) and exits cleanly.
 */
object DaemonMcpMain {

  @JvmStatic
  fun main(args: Array<String>) {
    val supervisor =
      DaemonSupervisor(
        descriptorProvider = DescriptorProvider.readingFromDisk(),
        clientFactory = SubprocessDaemonClientFactory(),
      )
    val server = DaemonMcpServer(supervisor)

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

  private fun splitProjectArg(raw: String): Pair<String, String?> {
    // Format: <path>[:<rootProjectName>]. Path may itself contain ':' on non-Windows hosts (rare),
    // so we split on the *last* colon. Empty name → null.
    val idx = raw.lastIndexOf(':')
    return if (idx <= 0) raw to null
    else raw.substring(0, idx) to raw.substring(idx + 1).takeIf { it.isNotEmpty() }
  }
}

/**
 * Production [DaemonClientFactory]: forks a JVM per [DaemonLaunchDescriptor] and pipes its stdio
 * into a [DaemonClient]. Mirrors `RealDesktopHarnessLauncher` from `:daemon:harness`.
 */
class SubprocessDaemonClientFactory : DaemonClientFactory {
  override fun spawn(project: RegisteredProject, descriptor: DaemonLaunchDescriptor): DaemonSpawn {
    require(descriptor.enabled) {
      "daemon disabled for ${descriptor.modulePath} — set composePreview.experimental.daemon.enabled = true"
    }
    val javaBin =
      descriptor.javaLauncher ?: File(System.getProperty("java.home"), "bin/java").absolutePath
    val cpString = descriptor.classpath.joinToString(File.pathSeparator)
    val command =
      buildList<String> {
        add(javaBin)
        addAll(descriptor.jvmArgs)
        descriptor.systemProperties.forEach { (k, v) -> add("-D$k=$v") }
        // Production daemon launches via the gradle-plugin descriptor don't set this; without it
        // the desktop / Robolectric hosts fall back to stub PNG paths because
        // `JsonRpcServer.handleRenderNow` only carries `previewId=<id>` in the payload, not the
        // className+functionName the render engine needs. The previews.json the descriptor
        // already points at via `manifestPath` is the exact shape `PreviewManifestRouter` expects
        // (a `{"previews":[{id, className, functionName, …}]}` JSON), so wire it through here so
        // real renders work without any plugin change.
        if (
          descriptor.manifestPath.isNotBlank() &&
            !descriptor.systemProperties.containsKey("composeai.harness.previewsManifest")
        ) {
          add("-Dcomposeai.harness.previewsManifest=${descriptor.manifestPath}")
        }
        add("-cp")
        add(cpString)
        add(descriptor.mainClass)
      }
    val process =
      ProcessBuilder(command)
        .directory(File(descriptor.workingDirectory))
        .redirectErrorStream(false)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    forwardStderr(process, "${project.workspaceId}/${descriptor.modulePath}")
    return SubprocessDaemonSpawn(process)
  }

  private fun forwardStderr(process: Process, tag: String) {
    Thread(
        {
          process.errorStream.bufferedReader().useLines { lines ->
            lines.forEach { System.err.println("[daemon $tag] $it") }
          }
        },
        "mcp-daemon-stderr-$tag",
      )
      .apply { isDaemon = true }
      .start()
  }
}

private class SubprocessDaemonSpawn(private val process: Process) : DaemonSpawn {
  private lateinit var _client: DaemonClient

  override val client: DaemonClient
    get() = _client

  override fun client(
    onNotification: (method: String, params: JsonObject?) -> Unit,
    onClose: () -> Unit,
  ): DaemonClient {
    _client =
      DaemonClient(
        input = process.inputStream,
        output = process.outputStream,
        onNotification = onNotification,
        onClose = onClose,
      )
    return _client
  }

  override fun shutdown() {
    runCatching { _client.shutdownAndExit() }
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
      process.destroy()
      if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }
    runCatching { _client.close() }
  }
}
