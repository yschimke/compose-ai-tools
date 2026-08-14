package ee.schimke.composeai.cli.serve

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The empirical half of the Phase-4 gate: launch a **throwaway JVM inside the configured jail** and
 * have it report what it can still reach. [PlaygroundPublicGate] admits the playground under
 * `--public` only on a clean report, so "is this box sandboxed?" is answered by measurement rather
 * than by trusting a profile name or an operator's `custom:` argv.
 *
 * The probe deliberately runs the **same launch shape** a snippet gets — same jail argv, same JDK,
 * same read-only classpath binds, same writable work dir — so a jail that would break a real render
 * (no `/lib64` bound, `bwrap` blocked by a hardened kernel) fails preflight loudly at startup
 * instead of silently degrading every playground run to "no image".
 *
 * Four checks, each a property the sandbox exists to provide:
 * - **egress blocked** — a snippet must not be able to reach the network from the serve box.
 * - **filesystem contained** — a canary file the parent creates *outside* the jail's bind set must
 *   be invisible to the child.
 * - **process isolated** — the serve host's own pid must not be visible in the child's `/proc`.
 * - **work dir writable** — the containment must not be so total that a real render can't run.
 */
object PlaygroundSandboxProbe {

  /**
   * The single line the in-jail probe prints, so ordinary JVM noise on stdout can't be mistaken.
   */
  const val REPORT_PREFIX = "PLAYGROUND_SANDBOX_PROBE "

  /** Main class of the in-jail probe; spawned as `java -cp <cli classpath> <this>`. */
  const val PROBE_MAIN_CLASS = "ee.schimke.composeai.cli.serve.PlaygroundSandboxProbeMain"

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * What the child observed. [ran] false means the jail never produced a report at all (the tool is
   * missing, the kernel refused the namespace, the JVM died) — a refusal, not a pass; the checks
   * are then meaningless and left false.
   */
  @Serializable
  data class Report(
    val ran: Boolean = false,
    val detail: String = "",
    val egressBlocked: Boolean = false,
    val filesystemContained: Boolean = false,
    val processIsolated: Boolean = false,
    val workDirWritable: Boolean = false,
  ) {
    /** Human-readable list of the properties this jail failed to provide; empty ⇒ clean. */
    fun failedChecks(): List<String> = buildList {
      if (!ran) add("the probe JVM never reported")
      if (!egressBlocked) add("outbound network reachable from inside the sandbox")
      if (!filesystemContained) add("host files outside the session work dir are readable")
      if (!processIsolated) add("host processes are visible from inside the sandbox")
      if (!workDirWritable) add("the session work dir is not writable (a render could not run)")
    }

    fun summary(): String =
      if (!ran) "sandbox preflight did not run: $detail"
      else
        "sandbox preflight: egressBlocked=$egressBlocked filesystemContained=$filesystemContained " +
          "processIsolated=$processIsolated workDirWritable=$workDirWritable"
  }

  /** One spawned probe's raw outcome; injected in tests instead of forking a JVM. */
  data class Launch(val exitCode: Int, val stdout: String, val stderr: String)

  /**
   * Run the preflight for [sandbox].
   *
   * @param javaHome the JDK the snippet JVMs launch from (bound read-only into the jail).
   * @param classpath the CLI's own classpath — the probe main lives in it, and binding it is also
   *   the closest analogue of a snippet's catalog classpath.
   * @param workRoot the playground work root; the probe gets a fresh writable dir under it.
   * @param launcher spawns argv and returns its outcome; defaults to a real subprocess.
   */
  fun run(
    sandbox: PlaygroundSandbox,
    javaHome: File,
    classpath: List<String>,
    workRoot: File,
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    launcher: (List<String>, File) -> Launch = ::spawn,
  ): Report {
    if (!sandbox.isActive) return Report(ran = false, detail = "no sandbox profile configured")
    if (classpath.isEmpty()) {
      return Report(ran = false, detail = "the serve process reported an empty classpath")
    }
    val probeDir = File(workRoot, "sandbox-probe").apply { deleteRecursively() }
    val canaryDir = File(workRoot, "sandbox-probe-canary").apply { deleteRecursively() }
    return try {
      probeDir.mkdirs()
      canaryDir.mkdirs()
      // The canary lives OUTSIDE everything the jail is told to bind. A child that can read it has
      // the run of the host filesystem.
      val canary = File(canaryDir, "canary.txt").apply { writeText("playground-sandbox-canary") }
      val javaBin = File(javaHome, "bin/java").absolutePath
      val argv =
        sandbox.command(
          PlaygroundSandbox.Paths(
            workDir = probeDir,
            readOnly = classpath.map { File(it) },
            javaHome = javaHome,
          )
        ) +
          listOf(
            javaBin,
            "-XX:+ExitOnOutOfMemoryError",
            "-Xmx${PlaygroundSandbox.MIN_HEAP_MB}m",
            "-cp",
            classpath.joinToString(File.pathSeparator),
            PROBE_MAIN_CLASS,
            probeDir.absolutePath,
            canary.absolutePath,
            ProcessHandle.current().pid().toString(),
          )
      val launch =
        try {
          launcher(argv, probeDir)
        } catch (e: Exception) {
          return Report(
            ran = false,
            detail =
              "could not launch '${argv.firstOrNull()}': ${e.message ?: e.javaClass.simpleName}",
          )
        }
      parse(launch)
    } catch (e: Exception) {
      Report(ran = false, detail = "preflight failed: ${e.message ?: e.javaClass.simpleName}")
    } finally {
      runCatching { probeDir.deleteRecursively() }
      runCatching { canaryDir.deleteRecursively() }
    }
  }

  /** Pull the report line out of a launch's stdout, or explain why there wasn't one. */
  internal fun parse(launch: Launch): Report {
    val line = launch.stdout.lineSequence().firstOrNull { it.startsWith(REPORT_PREFIX) }
    if (line == null) {
      val why =
        launch.stderr.lineSequence().filter { it.isNotBlank() }.lastOrNull()
          ?: "exit code ${launch.exitCode}, no output"
      return Report(ran = false, detail = why.take(400))
    }
    return try {
      json.decodeFromString(Report.serializer(), line.removePrefix(REPORT_PREFIX)).copy(ran = true)
    } catch (e: Exception) {
      Report(
        ran = false,
        detail = "unreadable probe report: ${e.message ?: e.javaClass.simpleName}",
      )
    }
  }

  private fun spawn(argv: List<String>, workDir: File): Launch {
    val process =
      ProcessBuilder(argv)
        .directory(workDir)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    val out = StringBuilder()
    val err = StringBuilder()
    val outThread = drain(process.inputStream, out, "playground-probe-stdout")
    val errThread = drain(process.errorStream, err, "playground-probe-stderr")
    val finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      process.waitFor(5, TimeUnit.SECONDS)
    }
    outThread.join(2_000)
    errThread.join(2_000)
    return Launch(
      exitCode = if (finished) process.exitValue() else -1,
      stdout = out.toString(),
      stderr =
        if (finished) err.toString() else "probe timed out after ${DEFAULT_TIMEOUT_SECONDS}s",
    )
  }

  private fun drain(stream: java.io.InputStream, into: StringBuilder, name: String): Thread =
    Thread(
        {
          runCatching {
            stream.bufferedReader().forEachLine { synchronized(into) { into.appendLine(it) } }
          }
        },
        name,
      )
      .apply {
        isDaemon = true
        start()
      }

  /** A cold JVM inside a fresh namespace; generous, but the whole preflight is once per startup. */
  const val DEFAULT_TIMEOUT_SECONDS = 60L
}

/**
 * The in-jail half of [PlaygroundSandboxProbe]: runs *inside* the sandbox, measures what it can
 * still reach, prints one [PlaygroundSandboxProbe.Report] line, and exits. Deliberately dependency-
 * free beyond the CLI jar itself, because it must start under the tightest jail the operator can
 * build (cleared environment, read-only host, empty network namespace).
 *
 * `argv`: `<writable work dir> <canary path outside the jail> <serve host pid>`.
 */
object PlaygroundSandboxProbeMain {

  @JvmStatic
  fun main(args: Array<String>) {
    val workDir = File(args.getOrElse(0) { "." })
    val canary = File(args.getOrElse(1) { "/nonexistent-canary" })
    val hostPid = args.getOrNull(2)?.toLongOrNull()
    val report = probe(workDir, canary, hostPid)
    println(
      PlaygroundSandboxProbe.REPORT_PREFIX +
        Json.encodeToString(PlaygroundSandboxProbe.Report.serializer(), report)
    )
  }

  /** Exposed for tests: the same measurement, without the process/stdout wrapper. */
  fun probe(workDir: File, canary: File, hostPid: Long?): PlaygroundSandboxProbe.Report =
    PlaygroundSandboxProbe.Report(
      ran = true,
      detail = "probed from pid ${ProcessHandle.current().pid()}",
      egressBlocked = egressBlocked(),
      filesystemContained = !canReadCanary(canary),
      // With no host pid to look for, treat isolation as unproven rather than proven.
      processIsolated = hostPid != null && !File("/proc/$hostPid").exists(),
      workDirWritable = workDirWritable(workDir),
    )

  /**
   * Every probe target must be unreachable. Routable, well-known addresses (no DNS — resolution
   * would hang rather than fail in an empty netns, and a blocked resolver is not the property we're
   * testing). A single successful connect means the sandbox leaks egress.
   */
  private fun egressBlocked(): Boolean = EGRESS_TARGETS.none { (host, port) ->
    runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), EGRESS_TIMEOUT_MILLIS) }
        true
      }
      .getOrDefault(false)
  }

  private fun canReadCanary(canary: File): Boolean = runCatching {
    canary.readText().isNotEmpty()
  }
    .getOrDefault(false)

  private fun workDirWritable(workDir: File): Boolean = runCatching {
    val probeFile = File(workDir, "writable.probe")
    probeFile.writeText("ok")
    val readBack = probeFile.readText() == "ok"
    probeFile.delete()
    readBack
  }
    .getOrDefault(false)

  private val EGRESS_TARGETS = listOf("1.1.1.1" to 443, "8.8.8.8" to 53, "93.184.216.34" to 80)

  private const val EGRESS_TIMEOUT_MILLIS = 2_000
}
