package ee.schimke.composeai.daemon.pool

import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RenderResult
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Out-of-process sandbox pool (issue #3072) — the parent half.
 *
 * Robolectric's native-graphics runtime binds to a **single classloader per process**: it loads
 * `libandroid_runtime.so` once and registers its JNI natives against whichever sandbox's
 * instrumented framework classes reach it first. A second sandbox in the same JVM boots with a
 * `Typeface` whose system font map never populated, and the first native call that touches it takes
 * the whole process down with a `SIGSEGV`. That is a hard, load-once-per-JVM constraint, not a
 * version bug — so extra sandboxes get extra **processes**.
 *
 * Each worker is a plain JVM (`java -cp <this JVM's classpath> …SandboxWorkerMain`) hosting exactly
 * one Robolectric sandbox, talking newline-delimited JSON ([WorkerRequest] / [WorkerResponse]) over
 * a loopback socket that the *parent* listens on and the worker dials back. Listening in the parent
 * means no port has to be agreed in advance and a worker that dies before connecting fails the
 * accept with a clear diagnostic rather than hanging on a connect retry loop.
 *
 * Threading: one outstanding request per worker, guarded by that worker's [Worker.lock]. That is
 * the same contract an in-JVM slot had — a sandbox renders one preview at a time — so the pool adds
 * no new concurrency semantics, only a process boundary.
 */
class SandboxProcessPool(
  /** Number of worker processes; equals `sandboxCount - 1` (slot 0 stays in the daemon JVM). */
  val workerCount: Int,
  /** Wall-clock budget for a worker's `java` launch + Robolectric boot + ready handshake. */
  private val bootTimeoutMs: Long,
  /**
   * Test seam: extra system properties handed to every worker. Production passes nothing — workers
   * inherit the daemon's own `composeai.*` / `robolectric.*` properties (see [workerSysprops]).
   */
  private val extraSysprops: Map<String, String> = emptyMap(),
) : AutoCloseable {

  private class Worker(
    val index: Int,
    val process: Process,
    val socket: Socket,
    val reader: BufferedReader,
    val writer: BufferedWriter,
    val pid: Long,
  ) {
    val lock = ReentrantLock()
    @Volatile var dead: Boolean = false
  }

  private val workers = arrayOfNulls<Worker>(workerCount)

  /**
   * Bound lazily on the first [bootWorker] so a host that never starts its pool (`sandboxCount =
   * 1`, or a host constructed but never started) opens no socket at all. Loopback-bound: workers
   * are always local children.
   */
  private var serverSocket: ServerSocket? = null

  private val serverLock = ReentrantLock()

  @Volatile private var closed = false

  private fun ensureServerSocket(): ServerSocket = serverLock.withLock {
    serverSocket
      ?: ServerSocket(0, workerCount + 4, InetAddress.getLoopbackAddress()).also {
        serverSocket = it
      }
  }

  /**
   * Spawns worker [index] and blocks until it reports [WorkerResponse.Ready] (its Robolectric
   * sandbox is booted and it is polling for renders). Throws on launch failure, accept timeout, or
   * a worker-side boot failure — the caller decides whether that caps the pool (background boot) or
   * aborts the host (eager boot), exactly as the in-JVM path did.
   */
  fun bootWorker(index: Int) {
    require(index in 0 until workerCount) {
      "worker index $index out of range 0..${workerCount - 1}"
    }
    check(!closed) { "SandboxProcessPool is closed" }
    val server = ensureServerSocket()
    val process = launchWorkerProcess(index, server.localPort)
    val socket =
      try {
        server.soTimeout = bootTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        server.accept()
      } catch (t: Throwable) {
        process.destroyForcibly()
        throw IllegalStateException(
          "sandbox worker $index never connected back within ${bootTimeoutMs}ms " +
            "(exited=${!process.isAlive}); see the [sandbox-worker-$index] stderr above.",
          t,
        )
      }
    socket.tcpNoDelay = true
    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    // The worker boots its sandbox *after* connecting, so the ready line can take as long as a
    // Robolectric bootstrap. Reuse the boot budget for it rather than a socket-level default.
    socket.soTimeout = bootTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val hello =
      try {
        readResponse(reader)
      } catch (t: Throwable) {
        process.destroyForcibly()
        throw IllegalStateException("sandbox worker $index died before reporting ready", t)
      }
    when (hello) {
      is WorkerResponse.Ready -> {
        socket.soTimeout = 0
        workers[index] = Worker(index, process, socket, reader, writer, hello.pid)
        System.err.println(
          "compose-ai-daemon: sandbox worker $index ready (pid=${hello.pid}, port=${server.localPort})"
        )
      }
      is WorkerResponse.BootFailed -> {
        process.destroyForcibly()
        error("sandbox worker $index failed to boot its Robolectric sandbox: ${hello.diagnostic}")
      }
      else -> {
        process.destroyForcibly()
        error("sandbox worker $index sent $hello before reporting ready")
      }
    }
  }

  /** True once [bootWorker] has completed for [index] and the worker is still alive. */
  fun isReady(index: Int): Boolean = workers.getOrNull(index)?.let { !it.dead } == true

  /** Worker process ids, in slot order; `null` for slots that never booted. Diagnostics + tests. */
  fun workerPids(): List<Long?> = workers.map { if (it?.dead == false) it.pid else null }

  /**
   * Dispatches [request] to worker [index] and blocks for its result. Re-throws a worker-side
   * render failure as a [RemoteSandboxRenderException] carrying the worker's flattened cause chain,
   * so `JsonRpcServer`'s existing Throwable path turns it into the same typed `renderFailed` an
   * in-process failure produces.
   */
  fun submit(index: Int, request: RenderRequest.Render, timeoutMs: Long): RenderResult {
    val worker =
      workers.getOrNull(index)?.takeIf { !it.dead }
        ?: error("sandbox worker $index is not ready (pool of $workerCount)")
    return worker.lock.withLock {
      val response =
        exchange(
          worker,
          WorkerRequest.Render(id = request.id, payload = request.payload, timeoutMs = timeoutMs),
          // Give the socket read a margin over the render budget so a worker that answers just
          // inside its own deadline still beats ours.
          readTimeoutMs = timeoutMs + SOCKET_READ_MARGIN_MS,
        )
      when (response) {
        is WorkerResponse.Result -> response.result.toRenderResult()
        is WorkerResponse.Failed -> throw RemoteSandboxRenderException(response.diagnostic)
        else -> error("sandbox worker $index answered a render with $response")
      }
    }
  }

  /**
   * Broadcasts `swapUserClassLoaders` to every live worker. Best-effort per worker: a worker that
   * fails to answer is marked dead and logged rather than failing the whole hot-reload — the host
   * keeps serving on its remaining slots.
   */
  fun swapUserClassLoaders() {
    for (worker in workers) {
      if (worker == null || worker.dead) continue
      try {
        worker.lock.withLock {
          exchange(worker, WorkerRequest.Swap, readTimeoutMs = SWAP_TIMEOUT_MS)
        }
      } catch (t: Throwable) {
        markDead(worker, "classloader swap failed", t)
      }
    }
  }

  /** Politely stops every worker, then force-kills anything still alive after [timeoutMs]. */
  fun shutdown(timeoutMs: Long) {
    closed = true
    for (worker in workers) {
      if (worker == null) continue
      runCatching {
        worker.lock.withLock {
          if (!worker.dead) exchange(worker, WorkerRequest.Shutdown, readTimeoutMs = timeoutMs)
        }
      }
      runCatching { worker.socket.close() }
      if (!worker.process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        System.err.println(
          "compose-ai-daemon: sandbox worker ${worker.index} (pid=${worker.pid}) did not exit " +
            "within ${timeoutMs}ms; killing"
        )
        worker.process.destroyForcibly()
      }
      worker.dead = true
    }
    runCatching { serverLock.withLock { serverSocket?.close() } }
  }

  override fun close() = shutdown(SHUTDOWN_TIMEOUT_MS)

  private fun markDead(worker: Worker, what: String, cause: Throwable) {
    worker.dead = true
    System.err.println(
      "compose-ai-daemon: sandbox worker ${worker.index} (pid=${worker.pid}) $what " +
        "(${cause.javaClass.simpleName}: ${cause.message}); dropping it from the pool"
    )
    runCatching { worker.socket.close() }
    runCatching { worker.process.destroyForcibly() }
  }

  private fun exchange(
    worker: Worker,
    request: WorkerRequest,
    readTimeoutMs: Long,
  ): WorkerResponse {
    try {
      worker.socket.soTimeout = readTimeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
      worker.writer.write(workerJson.encodeToString(WorkerRequest.serializer(), request))
      worker.writer.write("\n")
      worker.writer.flush()
      return readResponse(worker.reader)
    } catch (t: Throwable) {
      markDead(worker, "died mid-request", t)
      throw IllegalStateException(
        "sandbox worker ${worker.index} (pid=${worker.pid}) failed while handling $request",
        t,
      )
    }
  }

  private fun readResponse(reader: BufferedReader): WorkerResponse {
    val line = reader.readLine() ?: error("sandbox worker closed its socket (EOF)")
    return workerJson.decodeFromString(WorkerResponse.serializer(), line)
  }

  private fun launchWorkerProcess(index: Int, port: Int): Process {
    val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
    val command = buildList {
      add(java)
      addAll(inheritedJvmArgs())
      addAll(workerSysprops(index, port).map { (k, v) -> "-D$k=$v" })
      add("-cp")
      add(System.getProperty("java.class.path") ?: "")
      add(SandboxWorkerMain::class.java.name)
    }
    val process =
      ProcessBuilder(command)
        .redirectErrorStream(false)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    // The worker speaks its protocol over the socket, so both of its stdio streams are pure
    // diagnostics — pump them onto the daemon's stderr with a slot tag. Unpumped pipes fill and
    // deadlock a chatty Robolectric bootstrap, so this is load-bearing, not just nice logging.
    pumpToStderr(process.errorStream, "sandbox-worker-$index")
    pumpToStderr(process.inputStream, "sandbox-worker-$index")
    return process
  }

  private fun pumpToStderr(stream: java.io.InputStream, tag: String) {
    Thread(
        {
          stream.bufferedReader().useLines { lines ->
            for (line in lines) System.err.println("[$tag] $line")
          }
        },
        "compose-ai-$tag-log",
      )
      .apply {
        isDaemon = true
        start()
      }
  }

  /**
   * JVM flags the worker inherits from the daemon JVM: heap sizing, GC, `--add-opens`, and the
   * module flags Robolectric needs. Deliberately dropped: anything that must not be duplicated in a
   * second process — a debugger/JDWP port (would fail to bind), JaCoCo/agent attachments (would
   * write to the same exec file), and the pool's own properties, which [workerSysprops] re-derives.
   */
  private fun inheritedJvmArgs(): List<String> =
    ManagementFactory.getRuntimeMXBean().inputArguments.filter { arg ->
      when {
        arg.startsWith("-agentlib:") -> false
        arg.startsWith("-agentpath:") -> false
        arg.startsWith("-javaagent:") -> false
        arg.startsWith("-Xrunjdwp") -> false
        arg.startsWith("-D") -> false // re-derived below, minus the pool-control properties
        else -> true
      }
    }

  /**
   * System properties the worker needs. The `composeai.*` / `robolectric.*` / `android.*` families
   * carry everything that shapes a render — user-class dirs, the SDK pin, Robolectric's offline /
   * dependency-dir settings, feature flags — so forwarding them wholesale keeps a worker's render
   * configured identically to the daemon's own sandbox.
   *
   * Overridden per worker: `sandboxCount` is forced to 1 (a worker JVM hosts exactly one sandbox —
   * the whole point), background boot is off (the worker's own `start()` must block until its
   * sandbox is up, because that is what the ready handshake means), and the boot-time warm render
   * is left to the parent, which already warms each slot as it comes up.
   */
  private fun workerSysprops(index: Int, port: Int): Map<String, String> {
    val forwarded = linkedMapOf<String, String>()
    for ((rawKey, rawValue) in System.getProperties()) {
      val key = rawKey as? String ?: continue
      val value = rawValue as? String ?: continue
      if (FORWARDED_PREFIXES.any { key.startsWith(it) }) forwarded[key] = value
    }
    forwarded.keys.removeAll(WORKER_OVERRIDDEN_PROPS)
    forwarded[SANDBOX_COUNT_PROP] = "1"
    forwarded[WORKER_PORT_PROP] = port.toString()
    forwarded[WORKER_SLOT_PROP] = index.toString()
    forwarded.putAll(extraSysprops)
    return forwarded
  }

  companion object {
    const val WORKER_PORT_PROP: String = "composeai.daemon.sandboxWorker.port"
    const val WORKER_SLOT_PROP: String = "composeai.daemon.sandboxWorker.slot"

    private const val SANDBOX_COUNT_PROP = "composeai.daemon.sandboxCount"

    private val FORWARDED_PREFIXES = listOf("composeai.", "robolectric.", "android.", "roborazzi.")

    /**
     * Pool-control properties a worker must never inherit verbatim: the pool size (a worker hosts
     * one sandbox), background boot (the ready handshake requires an eager boot), and the parent's
     * own worker coordinates when a worker somehow spawns from a worker.
     */
    private val WORKER_OVERRIDDEN_PROPS =
      setOf(
        SANDBOX_COUNT_PROP,
        "composeai.daemon.backgroundSandboxBoot",
        "composeai.daemon.warmRenderOnBoot",
        WORKER_PORT_PROP,
        WORKER_SLOT_PROP,
      )

    private const val SOCKET_READ_MARGIN_MS = 15_000L
    private const val SWAP_TIMEOUT_MS = 30_000L
    private const val SHUTDOWN_TIMEOUT_MS = 30_000L
  }
}
