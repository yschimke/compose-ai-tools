package ee.schimke.composeai.daemon.harness

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.ClientCapabilities
import ee.schimke.composeai.daemon.protocol.FileChangedParams
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.InitializeParams
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.JsonRpcNotification
import ee.schimke.composeai.daemon.protocol.JsonRpcRequest
import ee.schimke.composeai.daemon.protocol.RenderNowParams
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.SetFocusParams
import ee.schimke.composeai.daemon.protocol.SetVisibleParams
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * VS-Code-shaped client driving a [FakeDaemonMain] subprocess over `Content-Length`-framed JSON-RPC
 * — see [TEST-HARNESS § 6](../../../docs/daemon/TEST-HARNESS.md#6-subprocess-management).
 *
 * Mirrors `JsonRpcServerIntegrationTest`'s in-process loop but over real stdin/stdout pipes
 * (`ProcessBuilder.redirectError(PIPE)` + `redirectOutput(PIPE)`); stderr is ring-buffered into
 * [stderrBuffer] for failure diagnostics.
 *
 * **Construction.** Use [start] (the companion) rather than the constructor directly — it spawns
 * the JVM, kicks off the reader threads, and returns a [HarnessClient] ready for the
 * `initialize`/`initialized` handshake. `close()` (or [shutdownAndExit]) tears the subprocess down.
 *
 * **Demuxing.** The stdout reader thread classifies each frame as either a response (has `id`) or a
 * notification (has `method`, no `id`) and routes them to per-id response slots ([responseSlots])
 * or a single FIFO notification queue ([notifications]). This avoids the "spin-and-re-enqueue"
 * pattern that loses notification ordering when multiple polls race.
 */
class HarnessClient
private constructor(
  private val process: Process,
  private val stdoutReader: Thread,
  private val stderrReader: Thread,
  private val stderrBuffer: StringBuffer,
  private val responseSlots: ConcurrentHashMap<Long, LinkedBlockingQueue<JsonObject>>,
  private val notifications: LinkedBlockingQueue<JsonObject>,
) : Closeable {

  private val json = Json { ignoreUnknownKeys = true }
  private val nextId = AtomicLong(1)

  /**
   * Drives the `initialize` JSON-RPC request. Returns the daemon's [InitializeResult].
   *
   * Defaults are scenario-friendly stubs (`workspaceRoot`, `moduleId`, …); real scenarios passing
   * non-default values are expected to take this whole call themselves and supply real ones.
   */
  fun initialize(
    workspaceRoot: String = "/tmp",
    moduleId: String = ":harness",
    moduleProjectDir: String = "/tmp",
    capabilities: ClientCapabilities = ClientCapabilities(visibility = true, metrics = false),
  ): InitializeResult {
    val id = nextId.getAndIncrement()
    val params =
      InitializeParams(
        protocolVersion = 1,
        clientVersion = "harness-v0",
        workspaceRoot = workspaceRoot,
        moduleId = moduleId,
        moduleProjectDir = moduleProjectDir,
        capabilities = capabilities,
      )
    val request =
      JsonRpcRequest(
        id = id,
        method = "initialize",
        params = json.encodeToJsonElement(InitializeParams.serializer(), params),
      )
    val response = sendAndPoll(id, request, 30.seconds)
    val resultElem =
      response["result"]
        ?: error("initialize: no result in response — error=${response["error"]}, full=${response}")
    return json.decodeFromJsonElement(InitializeResult.serializer(), resultElem)
  }

  /** Sends the `initialized` notification; required before any further request. */
  fun sendInitialized() {
    val notification = JsonRpcNotification(method = "initialized", params = JsonObject(emptyMap()))
    sendFrame(json.encodeToString(JsonRpcNotification.serializer(), notification))
  }

  /** Sends a `setVisible` notification — the daemon's input for visibility filtering. */
  fun setVisible(ids: List<String>) {
    val params = SetVisibleParams(ids = ids)
    sendNotificationFrame(
      "setVisible",
      json.encodeToJsonElement(SetVisibleParams.serializer(), params),
    )
  }

  /** Sends a `setFocus` notification — the daemon prioritises these IDs in its render queue. */
  fun setFocus(ids: List<String>) {
    val params = SetFocusParams(ids = ids)
    sendNotificationFrame("setFocus", json.encodeToJsonElement(SetFocusParams.serializer(), params))
  }

  /**
   * Sends a `fileChanged` notification. The kind / changeType drive the daemon's classification.
   */
  fun fileChanged(
    path: String,
    kind: FileKind = FileKind.SOURCE,
    changeType: ChangeType = ChangeType.MODIFIED,
  ) {
    val params = FileChangedParams(path = path, kind = kind, changeType = changeType)
    sendNotificationFrame(
      "fileChanged",
      json.encodeToJsonElement(FileChangedParams.serializer(), params),
    )
  }

  private fun sendNotificationFrame(
    method: String,
    params: kotlinx.serialization.json.JsonElement,
  ) {
    val n = JsonRpcNotification(method = method, params = params)
    sendFrame(json.encodeToString(JsonRpcNotification.serializer(), n))
  }

  /** Drives `renderNow` for the given preview ids at the given [tier]. */
  fun renderNow(previews: List<String>, tier: RenderTier = RenderTier.FAST): RenderNowResult {
    val id = nextId.getAndIncrement()
    val params = RenderNowParams(previews = previews, tier = tier)
    val request =
      JsonRpcRequest(
        id = id,
        method = "renderNow",
        params = json.encodeToJsonElement(RenderNowParams.serializer(), params),
      )
    val response = sendAndPoll(id, request, 30.seconds)
    val resultElem =
      response["result"]
        ?: error("renderNow: no result — error=${response["error"]}, full=${response}")
    return json.decodeFromJsonElement(RenderNowResult.serializer(), resultElem)
  }

  /**
   * Blocks until a notification with [method] arrives, dropping any earlier notifications with
   * other methods (FIFO-but-filtered semantics — matches what a VS Code client would do when it
   * pumps the read loop and dispatches by method). Throws when [timeout] elapses.
   */
  fun pollNotification(method: String, timeout: Duration): JsonObject =
    pollNotificationMatching(method, timeout) { true }

  /**
   * Like [pollNotification] but with a per-frame [predicate] over the parsed notification — used by
   * v1 scenarios that need to wait for a specific preview id's `renderFinished` (or
   * `renderStarted`) when multiple are in-flight simultaneously. Frames whose method matches but
   * predicate doesn't are dropped, same as method-mismatch frames.
   */
  fun pollNotificationMatching(
    method: String,
    timeout: Duration,
    predicate: (JsonObject) -> Boolean,
  ): JsonObject {
    val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
    while (System.currentTimeMillis() < deadline) {
      val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
      val msg = notifications.poll(remaining, TimeUnit.MILLISECONDS)
      if (msg == null) {
        if (!process.isAlive) {
          error(
            "pollNotification($method): subprocess exited (code=${process.exitValue()}) " +
              "before the expected notification arrived. Stderr=\n${dumpStderr()}"
          )
        }
        continue
      }
      val seenMethod = msg["method"]?.jsonPrimitive?.contentOrNull
      if (seenMethod == method && predicate(msg)) return msg
      // Otherwise drop — interleaved notifications for other matchers don't block us.
    }
    error("pollNotification($method) timed out after $timeout. Stderr=\n${dumpStderr()}")
  }

  /** Polls a `renderFinished` notification whose `params.id == previewId`. */
  fun pollRenderFinishedFor(previewId: String, timeout: Duration): JsonObject =
    pollNotificationMatching("renderFinished", timeout) { msg ->
      msg["params"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull == previewId
    }

  /** Polls a `renderStarted` notification whose `params.id == previewId`. */
  fun pollRenderStartedFor(previewId: String, timeout: Duration): JsonObject =
    pollNotificationMatching("renderStarted", timeout) { msg ->
      msg["params"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull == previewId
    }

  /**
   * Sends a `shutdown` request **without blocking** for the response. Returns the request id; the
   * caller drives [awaitResponse] when ready to assert the response's arrival ordering. This is the
   * primitive S2 (drain semantics) needs to verify that `shutdown` does not resolve until the
   * in-flight `renderFinished` arrived.
   */
  fun sendShutdownAsync(): Long {
    val id = nextId.getAndIncrement()
    val request = JsonRpcRequest(id = id, method = "shutdown", params = JsonNull)
    responseSlots.computeIfAbsent(id) { LinkedBlockingQueue() }
    sendFrame(json.encodeToString(JsonRpcRequest.serializer(), request))
    return id
  }

  /** Polls the response slot for [id]. Throws on timeout. */
  fun awaitResponse(id: Long, timeout: Duration): JsonObject {
    val slot = responseSlots[id] ?: error("awaitResponse: no slot for id=$id")
    val response = slot.poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    responseSlots.remove(id)
    if (response == null) {
      val alive = process.isAlive
      val exitDetail = if (alive) "still alive" else "exited code=${process.exitValue()}"
      error(
        "awaitResponse(id=$id) timed out after $timeout (subprocess $exitDetail). Stderr=\n${dumpStderr()}"
      )
    }
    return response
  }

  /** Sends `exit` and waits for the subprocess to terminate. Returns the exit code. */
  fun sendExitAndWait(timeout: Duration = 15.seconds): Int {
    val exitNotification = JsonRpcNotification(method = "exit", params = JsonNull)
    sendFrame(json.encodeToString(JsonRpcNotification.serializer(), exitNotification))
    val exited = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    if (!exited) {
      System.err.println("HarnessClient: subprocess did not exit in $timeout, sending destroy()")
      process.destroy()
      process.waitFor(2, TimeUnit.SECONDS)
      if (process.isAlive) process.destroyForcibly()
    }
    stdoutReader.join(2_000)
    stderrReader.join(2_000)
    return process.exitValue()
  }

  /**
   * Sends `shutdown` (drains in-flight renders) → `exit`, waits for the subprocess to exit, and
   * returns its exit code. Best-effort cleanup on failure.
   */
  fun shutdownAndExit(timeout: Duration = 15.seconds): Int {
    val id = nextId.getAndIncrement()
    val request = JsonRpcRequest(id = id, method = "shutdown", params = JsonNull)
    sendAndPoll(id, request, timeout)
    val exitNotification = JsonRpcNotification(method = "exit", params = JsonNull)
    sendFrame(json.encodeToString(JsonRpcNotification.serializer(), exitNotification))
    val exited = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    if (!exited) {
      System.err.println("HarnessClient: subprocess did not exit in $timeout, sending destroy()")
      process.destroy()
      process.waitFor(2, TimeUnit.SECONDS)
      if (process.isAlive) process.destroyForcibly()
    }
    stdoutReader.join(2_000)
    stderrReader.join(2_000)
    return process.exitValue()
  }

  /** Snapshot of buffered stderr — for failing-test diagnostics. */
  fun dumpStderr(): String = stderrBuffer.toString()

  /**
   * Whether the spawned subprocess has terminated. Used by S6 (B2.1) to observe a
   * daemon-self-initiated exit after a `classpathDirty` notification — the daemon never sees a
   * client-side `shutdown` in that flow, so [shutdownAndExit] doesn't apply.
   */
  fun subprocessExited(): Boolean = !process.isAlive

  /**
   * Returns the spawned subprocess's exit code, blocking up to [timeoutMs] for it to terminate.
   * Returns null if the timeout elapses before the subprocess exits. Used by S6 to assert the exit
   * code without sending `exit` first.
   */
  fun waitForExit(timeoutMs: Long): Int? {
    val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!exited) return null
    return process.exitValue()
  }

  override fun close() {
    if (process.isAlive) process.destroy()
    process.waitFor(2, TimeUnit.SECONDS)
    if (process.isAlive) process.destroyForcibly()
    stdoutReader.join(2_000)
    stderrReader.join(2_000)
  }

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  private fun sendAndPoll(id: Long, request: JsonRpcRequest, timeout: Duration): JsonObject {
    val slot = responseSlots.computeIfAbsent(id) { LinkedBlockingQueue() }
    sendFrame(json.encodeToString(JsonRpcRequest.serializer(), request))
    val response = slot.poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    responseSlots.remove(id)
    if (response == null) {
      val alive = process.isAlive
      val exitDetail = if (alive) "still alive" else "exited code=${process.exitValue()}"
      error(
        "sendAndPoll(id=$id, method=${request.method}) timed out after $timeout " +
          "(subprocess $exitDetail). Stderr=\n${dumpStderr()}"
      )
    }
    return response
  }

  private fun sendFrame(jsonText: String) {
    val payload = jsonText.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    val out = process.outputStream
    synchronized(out) {
      out.write(header)
      out.write(payload)
      out.flush()
    }
  }

  companion object {

    /**
     * D-harness.v1.5a-and-later overload — delegates the spawn step to [launcher]. Picks
     * [FakeHarnessLauncher] vs [RealDesktopHarnessLauncher] based on `-Pharness.host` (see
     * `HarnessTestSupport.launcherFor`).
     */
    fun start(launcher: HarnessLauncher): HarnessClient {
      val process = launcher.spawn()
      val stderrBuffer = StringBuffer()
      val responseSlots = ConcurrentHashMap<Long, LinkedBlockingQueue<JsonObject>>()
      val notifications = LinkedBlockingQueue<JsonObject>()
      val stdoutThread =
        Thread(
            { runStdoutReader(process.inputStream, responseSlots, notifications) },
            "harness-client-stdout-${launcher.name}",
          )
          .apply { isDaemon = true }
      val stderrThread =
        Thread(
            { runStderrReader(process.errorStream, stderrBuffer) },
            "harness-client-stderr-${launcher.name}",
          )
          .apply { isDaemon = true }
      stdoutThread.start()
      stderrThread.start()
      return HarnessClient(
        process = process,
        stdoutReader = stdoutThread,
        stderrReader = stderrThread,
        stderrBuffer = stderrBuffer,
        responseSlots = responseSlots,
        notifications = notifications,
      )
    }

    /**
     * Pre-D-harness.v1.5a shorthand kept so the existing 7 fake-mode scenario tests compile
     * unchanged. Equivalent to `start(FakeHarnessLauncher(fixtureDir, classpath, mainClass,
     * extraJvmArgs))`.
     *
     * The classpath is passed in (the test caller usually parses it from `java.class.path`); the
     * JVM is selected via the running JVM's `java.home` inside [FakeHarnessLauncher.spawn] so the
     * harness inherits the toolchain configured by Gradle for the surrounding test run.
     */
    fun start(
      fixtureDir: File,
      classpath: List<File>,
      mainClass: String = "ee.schimke.composeai.daemon.harness.FakeDaemonMain",
      extraJvmArgs: List<String> = emptyList(),
    ): HarnessClient =
      start(
        FakeHarnessLauncher(
          fixtureDir = fixtureDir,
          classpath = classpath,
          mainClass = mainClass,
          extraJvmArgs = extraJvmArgs,
        )
      )

    private fun runStdoutReader(
      input: InputStream,
      responseSlots: ConcurrentHashMap<Long, LinkedBlockingQueue<JsonObject>>,
      notifications: LinkedBlockingQueue<JsonObject>,
    ) {
      val json = Json { ignoreUnknownKeys = true }
      try {
        while (true) {
          val frame = readFrame(input) ?: return
          val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
          val responseId = obj["id"]?.jsonPrimitive?.long
          if (responseId != null) {
            responseSlots.computeIfAbsent(responseId) { LinkedBlockingQueue() }.put(obj)
          } else {
            // No id → notification per JSON-RPC 2.0.
            notifications.put(obj)
          }
        }
      } catch (_: IOException) {
        // EOF on the subprocess; exit cleanly.
      } catch (e: Throwable) {
        System.err.println("HarnessClient stdout reader: ${e.message}")
        e.printStackTrace(System.err)
      }
    }

    private fun runStderrReader(input: InputStream, buffer: StringBuffer) {
      try {
        val reader = input.bufferedReader(Charsets.UTF_8)
        while (true) {
          val line = reader.readLine() ?: return
          synchronized(buffer) {
            buffer.append(line).append('\n')
            // Ring-buffer at ~64KB so we don't OOM if the daemon spews stderr.
            if (buffer.length > 64 * 1024) {
              buffer.delete(0, buffer.length - 64 * 1024)
            }
          }
        }
      } catch (_: IOException) {
        // EOF
      }
    }

    private fun readFrame(input: InputStream): ByteArray? {
      var contentLength = -1
      val headerBuf = ByteArrayOutputStream(64)
      var sawAny = false
      while (true) {
        val line = readHeaderLine(input, headerBuf) ?: return if (sawAny) null else null
        sawAny = true
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon <= 0) error("malformed header line: '$line'")
        val name = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()
        if (name.equals("Content-Length", ignoreCase = true)) {
          contentLength = value.toIntOrNull() ?: error("non-integer Content-Length: '$value'")
        }
      }
      if (contentLength < 0) error("missing Content-Length header")
      val payload = ByteArray(contentLength)
      var off = 0
      while (off < contentLength) {
        val n = input.read(payload, off, contentLength - off)
        if (n < 0) return null
        off += n
      }
      return payload
    }

    private fun readHeaderLine(input: InputStream, buf: ByteArrayOutputStream): String? {
      buf.reset()
      while (true) {
        val b = input.read()
        if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
        if (b == '\n'.code) {
          val bytes = buf.toByteArray()
          val end =
            if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1
            else bytes.size
          return String(bytes, 0, end, Charsets.US_ASCII)
        }
        buf.write(b)
      }
    }
  }
}
