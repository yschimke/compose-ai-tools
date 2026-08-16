package ee.schimke.composeai.cli.serve

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toPath

/**
 * The compile half of the playground's per-session sandbox (`docs/design/PLAYGROUND.md` §6.3,
 * issue #3090): run `kotlinc` **outside** the serve JVM, inside the same jail the render lanes use.
 *
 * Phase 4 ([PlaygroundSandbox]) contained everything that *executes* a snippet — the first frame,
 * the RC capture, the live session — but [PlaygroundBtaCompiler] still compiled in-process. That
 * compile does not run snippet code, so it was never an arbitrary-execution hole; it is a
 * **resource** one. The Kotlin compiler is the least predictable thing on the request path: a
 * pathological snippet (deep generic inference, a huge literal, an exponential type) can burn CPU
 * and heap inside the serve JVM itself, where `-Xmx` is the operator's, not the snippet's, and no
 * wall clock applies. Moving it into the jail puts the compiler under the same memory ceiling, CPU
 * quota and hard TTL as everything else a stranger touches — and a compiler that OOMs now kills a
 * disposable child instead of the host.
 *
 * The subprocess speaks the same one-JSON-line protocol as [PlaygroundSandboxProbe]: the parent
 * writes a [PlaygroundCompileRequest] into the snippet's own (jail-writable) work dir, the child
 * prints one [PlaygroundCompileReport], and anything else — a jail that wouldn't launch, a killed
 * JVM, unparseable output — becomes a single file-level error so the route still answers the JSON
 * contract instead of throwing.
 *
 * **Cost.** Each compile pays a cold BTA toolchain bootstrap (the in-process compiler amortised it
 * across requests by holding one `BtaCompileSession`). That is the deliberate v1 trade: a warm
 * compile JVM per catalog classpath would claw the seconds back, but it re-introduces exactly the
 * long-lived, shared, snippet-touched process Phase 4 exists to avoid — so it stays a follow-up
 * with a measurement behind it rather than a guess.
 */
class PlaygroundJailedCompiler(
  private val sandbox: PlaygroundSandbox,
  private val javaHome: File,
  /** The serve process's own classpath — the child runs [PLAYGROUND_COMPILE_MAIN] off it. */
  private val cliClasspath: List<String>,
  /** `kotlin-build-tools-impl` + friends, staged in the install's `lib-bta/`. */
  private val btaImplJars: List<String>,
  /** `kotlin-compose-compiler-plugin-embeddable`, split out of the same dir. */
  private val compilerPluginJars: List<String>,
  private val moduleName: String = "playground",
  /**
   * How many snippet compiles may hold a JVM at once. **Load-bearing:** the in-process compiler it
   * replaces serialized compiles behind one `BtaCompileSession`, so concurrency was implicitly 1; a
   * subprocess per request removes that, and per-process caps bound one compile without bounding
   * the *aggregate* — N concurrent compiles is N × [PlaygroundSandbox.memoryMb]. This is the
   * playground's compile-side counterpart to `--live-seats`: peak compile memory an operator has to
   * budget for is `slots × memoryMb`.
   */
  private val slots: Int = DEFAULT_COMPILE_SLOTS,
  /** How long a request waits for a slot before answering "busy" rather than queueing forever. */
  private val slotWaitSeconds: Long = DEFAULT_SLOT_WAIT_SECONDS,
  private val timeoutSeconds: Long = DEFAULT_COMPILE_TIMEOUT_SECONDS,
  /** Injected in tests; null (the default) forks a real JVM via [spawn]. */
  private val launcher: ((List<String>, File) -> PlaygroundSandboxProbe.Launch)? = null,
) : PlaygroundCompileService.Compiler {

  private val compileSlots = java.util.concurrent.Semaphore(slots.coerceAtLeast(1), true)

  /**
   * A compile never outlives the sandbox's own wall-clock deadline: an operator who shortens
   * `--playground-sandbox-ttl` is asking for *everything* snippet-related to be reclaimed by then,
   * and the render path already honours it via the descriptor's `hardTtlSeconds`. Whichever of the
   * two budgets is tighter wins.
   */
  internal val effectiveTimeoutSeconds: Long =
    if (sandbox.isActive) minOf(timeoutSeconds, sandbox.ttlSeconds) else timeoutSeconds

  override fun compile(
    sources: List<Path>,
    classpath: List<Path>,
    outputDir: Path,
  ): List<PlaygroundDiagnostic> {
    return launchCompile(sources, classpath, outputDir)
  }

  override fun compileIncremental(
    sources: List<Path>,
    classpath: List<Path>,
    outputDir: Path,
    workingDir: Path,
    modified: List<Path>,
    removed: List<Path>,
    firstBuild: Boolean,
  ): PlaygroundCompileService.IncrementalCompileResult =
    PlaygroundCompileService.IncrementalCompileResult(
      diagnostics =
        launchCompile(
          sources,
          classpath,
          outputDir,
          incrementalWorkingDir = workingDir,
          modified = modified,
          removed = removed,
          firstBuild = firstBuild,
        ),
      // The child is cold, but it uses the persistent IC/output state and therefore is genuinely
      // incremental. A future warm worker removes the remaining toolchain-bootstrap cost.
      incremental = !firstBuild,
    )

  private fun launchCompile(
    sources: List<Path>,
    classpath: List<Path>,
    outputDir: Path,
    incrementalWorkingDir: Path? = null,
    modified: List<Path> = emptyList(),
    removed: List<Path> = emptyList(),
    firstBuild: Boolean = false,
  ): List<PlaygroundDiagnostic> {
    // The snippet's work dir — the one path the jail leaves writable, and the parent of both the
    // staged sources and the class output. Everything the compile writes (classes, the IC dir, this
    // request file) therefore lands inside the directory the token store deletes.
    val workDir = File(outputDir.toString()).parentFile ?: File(outputDir.toString())
    val requestFile = File(workDir, "compile-request.json")
    val request =
      PlaygroundCompileRequest(
        sources = sources.map { it.toString() },
        classpath = classpath.map { it.toString() },
        outputDir = outputDir.toString(),
        btaImplJars = btaImplJars,
        compilerPluginJars = compilerPluginJars,
        icWorkingDir = incrementalWorkingDir?.toString() ?: File(workDir, "bta-ic").absolutePath,
        moduleName = moduleName,
        incremental = incrementalWorkingDir != null,
        modified = modified.map { it.toString() },
        removed = removed.map { it.toString() },
        firstBuild = firstBuild,
      )
    // Admission control BEFORE the fork: without it, every concurrent POST is another whole JVM
    // holding another whole memory budget, and the per-process caps say nothing about the total.
    if (!compileSlots.tryAcquire(slotWaitSeconds, TimeUnit.SECONDS)) {
      return listOf(
        internalError(
          "the playground is busy compiling (all $slots compile slots in use) — try again shortly"
        )
      )
    }
    return try {
      requestFile.writeText(JSON.encodeToString(PlaygroundCompileRequest.serializer(), request))
      val argv = command(workDir, classpath, requestFile)
      val launch =
        try {
          (launcher ?: ::spawn)(argv, workDir)
        } catch (e: Exception) {
          return listOf(
            internalError(
              "could not launch the compile sandbox '${argv.firstOrNull()}': " +
                (e.message ?: e.javaClass.simpleName)
            )
          )
        }
      parse(launch)
    } catch (e: Exception) {
      listOf(internalError("compile sandbox failed: ${e.message ?: e.javaClass.simpleName}"))
    } finally {
      compileSlots.release()
      runCatching { requestFile.delete() }
    }
  }

  /** The jail argv + the child JVM's command line. Split out so a test can assert its shape. */
  internal fun command(workDir: File, classpath: List<Path>, requestFile: File): List<String> {
    // Read-only: everything the compiler reads — the catalog classpath it compiles against, the BTA
    // impl + Compose plugin jars, and the CLI's own classpath (the child's main class lives there).
    val readOnly =
      (classpath.map { File(it.toString()) } +
          btaImplJars.map(::File) +
          compilerPluginJars.map(::File) +
          cliClasspath.map(::File))
        .distinct()
    return sandbox.command(
      PlaygroundSandbox.Paths(workDir = workDir, readOnly = readOnly, javaHome = javaHome)
    ) +
      listOf(File(javaHome, "bin/java").absolutePath) +
      sandbox.jvmArgs(workDir) +
      listOf(
        "-cp",
        cliClasspath.joinToString(File.pathSeparator),
        PLAYGROUND_COMPILE_MAIN,
        requestFile.absolutePath,
      )
  }

  /** Read the child's one report line, or explain — as a diagnostic — why there wasn't one. */
  internal fun parse(launch: PlaygroundSandboxProbe.Launch): List<PlaygroundDiagnostic> {
    val line = launch.stdout.lineSequence().firstOrNull { it.startsWith(REPORT_PREFIX) }
    if (line == null) {
      val why =
        launch.stderr.lineSequence().filter { it.isNotBlank() }.lastOrNull()
          ?: "exit code ${launch.exitCode}, no output"
      // A compiler killed by its own cgroup/TTL lands here: the snippet gets a real error rather
      // than a mystery "no @Preview found" two steps later.
      return listOf(internalError("the compile sandbox produced no result — ${why.take(400)}"))
    }
    return try {
      JSON.decodeFromString(PlaygroundCompileReport.serializer(), line.removePrefix(REPORT_PREFIX))
        .diagnostics
    } catch (e: Exception) {
      listOf(internalError("unreadable compile report: ${e.message ?: e.javaClass.simpleName}"))
    }
  }

  private fun internalError(message: String) =
    PlaygroundDiagnostic(severity = PlaygroundSeverity.ERROR, message = message)

  private fun spawn(argv: List<String>, workDir: File): PlaygroundSandboxProbe.Launch {
    val process =
      ProcessBuilder(argv)
        .directory(workDir)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    val out = StringBuilder()
    val err = StringBuilder()
    val outThread = drain(process.inputStream, out)
    val errThread = drain(process.errorStream, err)
    val finished = process.waitFor(effectiveTimeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      process.waitFor(5, TimeUnit.SECONDS)
    }
    // Best-effort: a drain thread that outlives this is still appending, so both reads below stay
    // under the same monitor its appends take. Without that, `toString()` copies the backing array
    // while a concurrent `append` may be resizing it — a torn read, or an
    // ArrayIndexOutOfBoundsException out of StringBuilder itself.
    outThread.join(DRAIN_JOIN_MILLIS)
    errThread.join(DRAIN_JOIN_MILLIS)
    return PlaygroundSandboxProbe.Launch(
      exitCode = if (finished) process.exitValue() else -1,
      stdout = synchronized(out) { out.toString() },
      stderr =
        if (finished) synchronized(err) { err.toString() }
        else "the compile exceeded its ${effectiveTimeoutSeconds}s budget",
    )
  }

  private fun drain(stream: java.io.InputStream, into: StringBuilder): Thread = Thread {
    runCatching {
      stream.bufferedReader().forEachLine { synchronized(into) { into.appendLine(it) } }
    }
  }
    .apply {
      isDaemon = true
      start()
    }

  companion object {
    /** Prefix of the child's single report line, so JVM noise on stdout can't be mistaken. */
    const val REPORT_PREFIX = "PLAYGROUND_COMPILE "

    const val PLAYGROUND_COMPILE_MAIN = "ee.schimke.composeai.cli.serve.PlaygroundCompileMain"

    /**
     * A cold BTA bootstrap plus a snippet compile; generous, because the cost of being wrong is a
     * spurious "no result" on a slow box. Clamped to the sandbox's own wall-clock TTL when that is
     * tighter (see `effectiveTimeoutSeconds`).
     */
    const val DEFAULT_COMPILE_TIMEOUT_SECONDS = 180L

    /**
     * Concurrent compile JVMs. Two, not one: one in flight while another is being typed is the
     * common case, and a sandbox's memory cap is per process — so the host budget an operator
     * reasons about is `slots × --playground-sandbox-memory-mb` (3 GB at the defaults). Raise it
     * only with that arithmetic in hand.
     */
    const val DEFAULT_COMPILE_SLOTS = 2

    /** Long enough to ride out one in-flight compile, short enough that a client isn't stranded. */
    const val DEFAULT_SLOT_WAIT_SECONDS = 30L

    /**
     * How long to wait for a drain thread after the child exits. The pipes are closed by then, so
     * this only ever expires on a wedged reader — and the reads it guards are synchronized, so
     * expiring costs a truncated log rather than a corrupted one.
     */
    private const val DRAIN_JOIN_MILLIS = 2_000L

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * Wrap [inProcess] in a jailed subprocess when [sandbox] is active; otherwise hand back the
     * in-process compiler unchanged. A dev host with no sandbox keeps today's warm, fast compile
     * (and today's exposure, which is bounded by the token gate); a `--public` host cannot reach
     * here without a verified sandbox, so its compiles are always jailed.
     */
    fun wrap(
      sandbox: PlaygroundSandbox,
      inProcess: PlaygroundCompileService.Compiler,
      btaImplJars: List<File>,
      compilerPluginJars: List<File>,
      slots: Int = DEFAULT_COMPILE_SLOTS,
      onLog: (String) -> Unit = { System.err.println("serve: $it") },
    ): PlaygroundCompileService.Compiler {
      if (!sandbox.isActive) return inProcess
      val cliClasspath =
        System.getProperty("java.class.path").orEmpty().split(File.pathSeparator).filter {
          it.isNotBlank()
        }
      if (cliClasspath.isEmpty() || btaImplJars.isEmpty()) {
        // Fail *loud but soft*: the lane still works, just with the pre-#3090 exposure, and the
        // operator is told which half is missing rather than quietly getting an unjailed compiler.
        onLog(
          "playground: cannot jail the compiler (" +
            (if (cliClasspath.isEmpty()) "the serve process reports no classpath"
            else "no lib-bta/ jars") +
            ") — compiles run in-process; snippet execution is still sandboxed."
        )
        return inProcess
      }
      // A dropped jail still gets a disposable, capped child — better than the in-process
      // compiler — but saying it runs "inside the sandbox" would be a lie, and this line is
      // exactly where an operator looks to confirm the jail took.
      onLog(
        if (sandbox.jailDropped)
          "playground: compiles run in a capped child with NO jail (the ${sandbox.profile.id} " +
            "sandbox could not launch here), $slots at a time (peak ${slots * sandbox.memoryMb}MB)"
        else
          "playground: compiles run inside the ${sandbox.profile.id} sandbox, " +
            "$slots at a time (peak ${slots * sandbox.memoryMb}MB)"
      )
      return PlaygroundJailedCompiler(
        sandbox = sandbox,
        javaHome = File(System.getProperty("java.home")),
        cliClasspath = cliClasspath,
        btaImplJars = btaImplJars.map { it.absolutePath },
        compilerPluginJars = compilerPluginJars.map { it.absolutePath },
        slots = slots,
      )
    }
  }
}

/**
 * What the parent asks the in-jail compiler to do. Paths are absolute; the child opens nothing
 * else.
 */
@Serializable
data class PlaygroundCompileRequest(
  val sources: List<String>,
  val classpath: List<String>,
  val outputDir: String,
  val btaImplJars: List<String>,
  val compilerPluginJars: List<String>,
  val icWorkingDir: String,
  val moduleName: String,
  val incremental: Boolean = false,
  val modified: List<String> = emptyList(),
  val removed: List<String> = emptyList(),
  val firstBuild: Boolean = false,
)

/** What it answers: the same diagnostics an in-process compile would have returned. */
@Serializable
data class PlaygroundCompileReport(val diagnostics: List<PlaygroundDiagnostic> = emptyList())

/**
 * The in-jail compiler entrypoint: read one [PlaygroundCompileRequest], run the *same*
 * [PlaygroundBtaCompiler] the in-process path uses, print one [PlaygroundCompileReport].
 *
 * Deliberately thin — the compile behaviour (BTA session, Compose plugin wiring, diagnostic
 * mapping) stays in one place, so jailing the compiler can't silently drift from not jailing it.
 */
object PlaygroundCompileMain {

  @JvmStatic
  fun main(args: Array<String>) {
    val requestPath = args.firstOrNull()
    if (requestPath == null) {
      System.err.println("usage: PlaygroundCompileMain <request.json>")
      return
    }
    val json = Json { ignoreUnknownKeys = true }
    val diagnostics =
      try {
        val request =
          json.decodeFromString(PlaygroundCompileRequest.serializer(), File(requestPath).readText())
        PlaygroundBtaCompiler(
            btaImplJars = request.btaImplJars.map { File(it).toPath() },
            compilerPluginJars = request.compilerPluginJars.map { File(it).toPath() },
            icWorkingDir = File(request.icWorkingDir).toPath(),
            moduleName = request.moduleName,
          )
          .let { compiler ->
            if (request.incremental)
              compiler
                .compileIncremental(
                  sources = request.sources.map { it.toPath() },
                  classpath = request.classpath.map { it.toPath() },
                  outputDir = request.outputDir.toPath(),
                  workingDir = request.icWorkingDir.toPath(),
                  modified = request.modified.map { it.toPath() },
                  removed = request.removed.map { it.toPath() },
                  firstBuild = request.firstBuild,
                )
                .diagnostics
            else
              compiler.compile(
                sources = request.sources.map { it.toPath() },
                classpath = request.classpath.map { it.toPath() },
                outputDir = request.outputDir.toPath(),
              )
          }
      } catch (t: Throwable) {
        listOf(
          PlaygroundDiagnostic(
            severity = PlaygroundSeverity.ERROR,
            message = "compile sandbox error: ${t.message ?: t.javaClass.simpleName}",
          )
        )
      }
    println(
      PlaygroundJailedCompiler.REPORT_PREFIX +
        Json.encodeToString(
          PlaygroundCompileReport.serializer(),
          PlaygroundCompileReport(diagnostics),
        )
    )
  }
}
