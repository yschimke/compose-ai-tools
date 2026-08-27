package ee.schimke.composeai.daemon.client

import ee.schimke.composeai.daemon.DaemonLaunchDescriptor
import ee.schimke.composeai.io.classpathArgFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject

/**
 * Production [DaemonClientFactory]: forks a JVM per [DaemonLaunchDescriptor] and pipes its stdio
 * into a [DaemonClient]. Mirrors `RealDesktopHarnessLauncher` from `:daemon:harness`.
 */
class SubprocessDaemonClientFactory : DaemonClientFactory {
  override fun spawn(workspaceId: WorkspaceId, descriptor: DaemonLaunchDescriptor): DaemonSpawn {
    require(descriptor.enabled) {
      "daemon disabled for ${descriptor.modulePath} — set composePreview { daemon { enabled = true } }"
    }
    val javaBin =
      descriptor.javaLauncher ?: File(System.getProperty("java.home"), "bin/java").absolutePath
    val command =
      buildList<String> {
        // The optional OS jail (playground per-session sandbox). Empty for every ordinary daemon,
        // so the launched argv is byte-identical to the pre-sandbox one.
        addAll(descriptor.jailCommand)
        add(javaBin)
        addAll(descriptor.jvmArgs)
        descriptor.systemProperties.forEach { (k, v) -> add("-D$k=$v") }
        // Inside a jail the parent's temp dir may not exist (bwrap mounts its own /tmp), so the
        // argfile goes in the one directory both sides can see: the daemon's working directory.
        add(
          classpathArgFile(
            descriptor.classpath,
            File(descriptor.workingDirectory).takeIf { descriptor.jailCommand.isNotEmpty() },
          )
        )
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
    forwardStderr(process, "$workspaceId/${descriptor.modulePath}")
    descriptor.hardTtlSeconds?.let { ttl ->
      armHardTtl(process, ttl, "$workspaceId/${descriptor.modulePath}")
    }
    return SubprocessDaemonSpawn(process)
  }

  /**
   * The hard wall-clock TTL: a daemon thread that force-kills the JVM at the deadline regardless of
   * what it is doing. Cooperative shutdown is not enough for a sandboxed playground session — a
   * snippet can spin a tight loop that never services a JSON-RPC `shutdown` — so the parent shoots
   * it. A process that exits on its own first makes this a no-op.
   */
  private fun armHardTtl(process: Process, ttlSeconds: Long, tag: String) {
    Thread(
        {
          if (!process.waitFor(ttlSeconds, TimeUnit.SECONDS)) {
            System.err.println("[daemon $tag] hard TTL of ${ttlSeconds}s reached — killing sandbox")
            process.destroyForcibly()
          }
        },
        "daemon-hard-ttl-$tag",
      )
      .apply { isDaemon = true }
      .start()
  }

  private fun forwardStderr(process: Process, tag: String) {
    Thread(
        {
          process.errorStream.bufferedReader().useLines { lines ->
            lines.forEach { System.err.println("[daemon $tag] $it") }
          }
        },
        "daemon-stderr-$tag",
      )
      .apply { isDaemon = true }
      .start()
  }
}

internal class SubprocessDaemonSpawn(private val process: Process) : DaemonSpawn {
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

  override fun shutdown(timeout: Duration) {
    require(!timeout.isNegative()) { "shutdown timeout must not be negative" }
    val deadlineNanos = System.nanoTime() + timeout.inWholeNanoseconds

    runCatching { _client.shutdownAndExit(timeout) }
    waitForUntil(deadlineNanos)

    if (process.isAlive) {
      process.destroy()
      waitForUntil(deadlineNanos)
      if (process.isAlive) process.destroyForcibly()
    }
    runCatching { _client.close() }
  }

  private fun waitForUntil(deadlineNanos: Long) {
    if (!process.isAlive) return
    val remainingMillis = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L
    if (remainingMillis > 0L) process.waitFor(remainingMillis, TimeUnit.MILLISECONDS)
  }
}
