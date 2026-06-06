package ee.schimke.composeai.renderer.xr.client

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** One rendered frame pushed by the server as a `streamFrame` notification. */
public data class StreamFrame(
  val seq: Long,
  val width: Int,
  val height: Int,
  val encoding: String,
  /** Base64-encoded image bytes (`encoding` says the container, e.g. `png`). */
  val dataBase64: String,
)

/** Thrown on transport / protocol failure talking to the native render server. */
public class XrServerException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/**
 * JVM client for the native `xr-composite --serve` render server (see
 * `renderers/xr-composite/README.md` → "Server mode"). Speaks the same LSP-style `Content-Length`
 * JSON-RPC framing the daemon's subprocess backend uses, demultiplexing id-matched responses from
 * `streamFrame` notifications on a background reader thread.
 *
 * **Multi-session:** one client drives one native process that fans many sessions over a single
 * Filament engine. Every call carries a `sessionId`; the reader routes each `streamFrame` to a
 * per-session queue so concurrent sessions don't cross frames. The daemon's XR backend holds one
 * client and maps each `frameStreamId` to a session id.
 *
 * The client is constructed over raw streams so it is unit-testable without a real process; [spawn]
 * wires it to an actual `xr-composite --serve` child.
 *
 * Threading: safe for sequential calls per session from the caller; each request blocks for its
 * ack, then collects the matching session's frame.
 */
public class XrServerClient
internal constructor(
  private val output: OutputStream,
  input: InputStream,
  private val process: Process?,
) : AutoCloseable {

  private val json = Json { ignoreUnknownKeys = true }
  private val nextId = AtomicLong(1)
  private val responseSlots = ConcurrentHashMap<Long, LinkedBlockingQueue<JsonObject>>()
  // One frame queue per sessionId — the reader routes `streamFrame` notifications here by
  // sessionId.
  private val frames = ConcurrentHashMap<String, LinkedBlockingQueue<StreamFrame>>()
  private val reader =
    Thread({ runReader(input) }, "xr-server-client-reader").apply {
      isDaemon = true
      start()
    }

  /** Drives `initialize`; returns the server's `capabilities` object. */
  public fun initialize(timeout: Duration = 60.seconds): JsonObject {
    val result = request("initialize", buildJsonObject {}, timeout)
    return result["capabilities"]?.jsonObject
      ?: throw XrServerException("initialize: no capabilities in result ($result)")
  }

  /**
   * Drives `render` for [sessionId] with a full [scene] (a serialized `SpatialScene`). `sceneDir`
   * resolves relative panel textures; `environment` overrides the backdrop; `width`/`height` set
   * the session viewport. Returns the rendered [StreamFrame].
   */
  public fun render(
    sessionId: String,
    scene: JsonElement,
    sceneDir: String? = null,
    environment: String? = null,
    width: Int? = null,
    height: Int? = null,
    timeout: Duration = 60.seconds,
  ): StreamFrame {
    val params = buildJsonObject {
      put("sessionId", sessionId)
      put("scene", scene)
      sceneDir?.let { put("sceneDir", it) }
      environment?.let { put("environment", it) }
      width?.let { put("width", it) }
      height?.let { put("height", it) }
    }
    request("render", params, timeout)
    return awaitFrame(sessionId, timeout)
  }

  /**
   * Drives `xr/updatePanels` for [sessionId] — each entry in [panels] is `{id, texture?,
   * poseInRoot?, sizeDp?}`, mutating that session's held scene. Returns the freshly rendered
   * [StreamFrame].
   */
  public fun updatePanels(
    sessionId: String,
    panels: JsonArray,
    timeout: Duration = 60.seconds,
  ): StreamFrame {
    val params = buildJsonObject {
      put("sessionId", sessionId)
      put("panels", panels)
    }
    request("xr/updatePanels", params, timeout)
    return awaitFrame(sessionId, timeout)
  }

  /** Sends `xr/stop` to tear down [sessionId] on the server (notification; no ack awaited). */
  public fun stop(sessionId: String) {
    sendFrame(
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "xr/stop")
        put("params", buildJsonObject { put("sessionId", sessionId) })
      }
    )
    frames.remove(sessionId)
  }

  /** Whether the spawned child is still alive (always true for the stream-backed test client). */
  public fun isAlive(): Boolean = process?.isAlive ?: true

  /** Sends `exit`; the server ends its loop and the process terminates. */
  public fun exit() {
    sendFrame(
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "exit")
      }
    )
  }

  override fun close() {
    try {
      if (process?.isAlive == true) {
        // Graceful (exit) → SIGTERM (destroy) → SIGKILL (destroyForcibly), waiting at each rung so
        // a
        // hung native child (e.g. Filament stuck mid-render) can't survive close() and leak across
        // daemon sessions. Mirrors HarnessClient's shutdown ladder.
        exit()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
          process.destroy()
          if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
          }
        }
      }
    } catch (_: Exception) {
      process?.destroyForcibly()
    }
    reader.join(2_000)
  }

  // ---- internals ----

  private fun frameQueue(sessionId: String): LinkedBlockingQueue<StreamFrame> =
    frames.computeIfAbsent(sessionId) { LinkedBlockingQueue() }

  private fun awaitFrame(sessionId: String, timeout: Duration): StreamFrame =
    frameQueue(sessionId).poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      ?: throw XrServerException("no streamFrame for session $sessionId within $timeout")

  private fun request(method: String, params: JsonObject, timeout: Duration): JsonObject {
    val id = nextId.getAndIncrement()
    val slot = responseSlots.computeIfAbsent(id) { LinkedBlockingQueue() }
    sendFrame(
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
      }
    )
    val response =
      slot.poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        ?: run {
          responseSlots.remove(id)
          throw XrServerException("$method timed out after $timeout")
        }
    responseSlots.remove(id)
    response["error"]?.let { throw XrServerException("$method failed: $it") }
    return response["result"]?.jsonObject ?: JsonObject(emptyMap())
  }

  private fun sendFrame(message: JsonObject) {
    val payload = json.encodeToString(JsonObject.serializer(), message).toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    synchronized(output) {
      output.write(header)
      output.write(payload)
      output.flush()
    }
  }

  private fun runReader(input: InputStream) {
    try {
      while (true) {
        val frame = readFrame(input) ?: return
        val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
        val id = obj["id"]?.jsonPrimitive?.long
        if (id != null) {
          responseSlots.computeIfAbsent(id) { LinkedBlockingQueue() }.put(obj)
        } else if (obj["method"]?.jsonPrimitive?.content == "streamFrame") {
          obj["params"]?.jsonObject?.let { p ->
            val sid = p["sessionId"]?.jsonPrimitive?.content ?: "default"
            frameQueue(sid).put(parseFrame(p))
          }
        }
      }
    } catch (_: IOException) {
      // EOF / pipe closed — normal teardown.
    } catch (_: Throwable) {
      // Reader is best-effort; pending requests time out and surface the failure.
    }
  }

  private fun parseFrame(params: JsonObject): StreamFrame =
    StreamFrame(
      seq = params["seq"]?.jsonPrimitive?.long ?: 0,
      width = params["width"]?.jsonPrimitive?.int ?: 0,
      height = params["height"]?.jsonPrimitive?.int ?: 0,
      encoding = params["encoding"]?.jsonPrimitive?.content ?: "png",
      dataBase64 = params["data"]?.jsonPrimitive?.content ?: "",
    )

  public companion object {
    /** Spawn `xr-composite --serve` and wire a client to its stdio. Stderr inherits the JVM's. */
    public fun spawn(
      binary: File,
      materialsDir: File,
      width: Int = 1280,
      height: Int = 800,
    ): XrServerClient {
      val process =
        ProcessBuilder(
            binary.absolutePath,
            "--serve",
            "--materials",
            materialsDir.absolutePath,
            "--width",
            width.toString(),
            "--height",
            height.toString(),
          )
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start()
      return XrServerClient(process.outputStream, process.inputStream, process)
    }

    private fun readFrame(input: InputStream): ByteArray? {
      var contentLength = -1
      val headerBuf = ByteArrayOutputStream(64)
      var sawAny = false
      while (true) {
        val line = readHeaderLine(input, headerBuf) ?: return null
        if (line.isEmpty()) {
          if (sawAny) break else continue
        }
        sawAny = true
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        if (line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
          contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: -1
        }
      }
      if (contentLength < 0) return null
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
