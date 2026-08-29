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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
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
 * What `initialize` told us about the server we are attached to.
 *
 * The handshake used to be plumbed and then ignored — `capabilities` was carried as an untyped
 * `JsonObject` that nothing read. That was survivable while client and server were built from the
 * same commit; it is not, now that the compositor is provisioned at a pinned version that
 * deliberately lags (see the `xr-composite` pin). Version skew is the normal case here, so the
 * handshake has to be load-bearing: a mismatch must surface as a NAMED error, not as a composite
 * that silently never appears.
 */
public data class XrServerHandshake(
  /** `serverInfo.name` — `xr-composite` for the real server. Reported in errors, not enforced. */
  val serverName: String?,
  /** `serverInfo.version` — the RPC surface the server speaks. */
  val serviceVersion: Int,
  /** `capabilities.spatialSceneVersion` — the scene format it parses, or null if unreported. */
  val spatialSceneVersion: Int?,
  /** The raw `capabilities` object, for callers that want to inspect beyond the typed fields. */
  val capabilities: JsonObject,
) {
  /** Whether the server advertised [name] as a boolean-true capability. */
  public fun has(name: String): Boolean = capabilities[name]?.jsonPrimitive?.booleanOrNull == true

  public companion object {
    /**
     * Parse and VERIFY an `initialize` result. Pure, so the compatibility rules are unit-testable
     * without spawning a process.
     *
     * Accepts a server in `[MIN_SUPPORTED_XR_RENDER_SERVICE_VERSION, XR_RENDER_SERVICE_VERSION]`
     * and refuses one newer than this client, whose semantics we cannot know. Refusing a *newer*
     * server rather than an older one is deliberate: the pin means the server normally lags, so
     * requiring equality would make every service bump a flag day.
     */
    public fun parse(result: JsonObject): XrServerHandshake {
      val capabilities =
        result[XrRenderService.Result.CAPABILITIES]?.jsonObject
          ?: throw XrServerException("initialize: no capabilities in result ($result)")
      val serverInfo = result[XrRenderService.Result.SERVER_INFO]?.jsonObject
      val name = serverInfo?.get("name")?.jsonPrimitive?.contentOrNull
      val version =
        serverInfo?.get("version")?.jsonPrimitive?.intOrNull
          ?: throw XrServerException(
            "initialize: no serverInfo.version — not an ${XrRenderService.SERVER_NAME} render " +
              "server, or one too old to report its protocol version (result: $result)"
          )
      if (
        version > XrRenderService.XR_RENDER_SERVICE_VERSION ||
          version < XrRenderService.MIN_SUPPORTED_XR_RENDER_SERVICE_VERSION
      ) {
        throw XrServerException(
          "XR render service version mismatch: ${name ?: "server"} speaks v$version, this client " +
            "speaks v${XrRenderService.XR_RENDER_SERVICE_VERSION} and supports back to " +
            "v${XrRenderService.MIN_SUPPORTED_XR_RENDER_SERVICE_VERSION}. Update the " +
            "`xr-composite` pin to a release built from a compatible commit."
        )
      }
      return XrServerHandshake(
        serverName = name,
        serviceVersion = version,
        spatialSceneVersion =
          capabilities[XrRenderService.Capability.SPATIAL_SCENE_VERSION]?.jsonPrimitive?.intOrNull,
        capabilities = capabilities,
      )
    }
  }
}

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
  // The verified `initialize` result, retained so render/updatePanels can gate on what the server
  // actually advertised. Null until initialize() runs.
  @Volatile private var handshake: XrServerHandshake? = null
  // Sessions this client has rendered into and not yet stopped — the basis of the `multiSession`
  // gate. A single-session server silently reuses one scene for a second id, so opening one
  // without the capability corrupts both sessions rather than failing.
  private val openSessions = ConcurrentHashMap.newKeySet<String>()
  private val reader =
    Thread({ runReader(input) }, "xr-server-client-reader").apply {
      isDaemon = true
      start()
    }

  /**
   * Drives `initialize` and VERIFIES the result, throwing [XrServerException] on a protocol version
   * this client cannot speak. The handshake is retained so later calls can gate on the capabilities
   * the server actually advertised rather than assuming them.
   */
  public fun initialize(timeout: Duration = 60.seconds): XrServerHandshake {
    val result = request(XrRenderService.Method.INITIALIZE, buildJsonObject {}, timeout)
    val parsed = XrServerHandshake.parse(result)
    handshake = parsed
    return parsed
  }

  /**
   * Fail unless the server advertised [capability]. An absent capability means the server predates
   * the feature; calling anyway produces a confusing mid-stream error instead of a named one.
   */
  private fun requireCapability(capability: String, what: String) {
    val caps =
      handshake ?: throw XrServerException("$what before initialize: the handshake has not run yet")
    if (!caps.has(capability)) {
      throw XrServerException(
        "$what: server ${caps.serverName ?: "(unknown)"} v${caps.serviceVersion} does not " +
          "advertise the `$capability` capability"
      )
    }
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
    requireCapability(XrRenderService.Capability.RENDER, "render")
    checkSceneVersion(scene)
    if (!openSessions.contains(sessionId) && openSessions.isNotEmpty()) {
      requireCapability(
        XrRenderService.Capability.MULTI_SESSION,
        "render into a second session ($sessionId, alongside ${openSessions.joinToString()})",
      )
    }
    val params = buildJsonObject {
      put(XrRenderService.Param.SESSION_ID, sessionId)
      put(XrRenderService.Param.SCENE, scene)
      sceneDir?.let { put(XrRenderService.Param.SCENE_DIR, it) }
      environment?.let { put(XrRenderService.Param.ENVIRONMENT, it) }
      width?.let { put(XrRenderService.Param.WIDTH, it) }
      height?.let { put(XrRenderService.Param.HEIGHT, it) }
    }
    request(XrRenderService.Method.RENDER, params, timeout)
    openSessions.add(sessionId)
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
    requireCapability(XrRenderService.Capability.UPDATE_PANELS, "updatePanels")
    val params = buildJsonObject {
      put(XrRenderService.Param.SESSION_ID, sessionId)
      put(XrRenderService.Param.PANELS, panels)
    }
    request(XrRenderService.Method.UPDATE_PANELS, params, timeout)
    return awaitFrame(sessionId, timeout)
  }

  /** Sends `xr/stop` to tear down [sessionId] on the server (notification; no ack awaited). */
  public fun stop(sessionId: String) {
    sendFrame(
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", XrRenderService.Method.STOP)
        put("params", buildJsonObject { put(XrRenderService.Param.SESSION_ID, sessionId) })
      }
    )
    frames.remove(sessionId)
    openSessions.remove(sessionId)
  }

  /** Whether the spawned child is still alive (always true for the stream-backed test client). */
  public fun isAlive(): Boolean = process?.isAlive ?: true

  /** Sends `exit`; the server ends its loop and the process terminates. */
  public fun exit() {
    sendFrame(
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", XrRenderService.Method.EXIT)
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

  /**
   * Fail when the scene we are about to send declares a `version` the server said it cannot parse.
   *
   * Checks the actual payload against the server's advertised `spatialSceneVersion` rather than a
   * compile-time constant, which is both stricter and keeps this module free of a dependency on the
   * SpatialScene DTOs — its whole dependency list is kotlinx-serialization-json. Silent when either
   * side does not report a version: an unreported version is not evidence of a mismatch.
   */
  private fun checkSceneVersion(scene: JsonElement) {
    val serverVersion = handshake?.spatialSceneVersion ?: return
    val sceneVersion = (scene as? JsonObject)?.get("version")?.jsonPrimitive?.intOrNull ?: return
    if (sceneVersion != serverVersion) {
      throw XrServerException(
        "SpatialScene version mismatch: this scene is v$sceneVersion but the render server parses " +
          "v$serverVersion. The compositor is pinned separately from this repository " +
          "(`xr-composite` in gradle/libs.versions.toml); bump that pin to a release built " +
          "against scene v$sceneVersion."
      )
    }
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
        } else if (
          obj["method"]?.jsonPrimitive?.content == XrRenderService.Notification.STREAM_FRAME
        ) {
          obj["params"]?.jsonObject?.let { p ->
            val sid =
              p[XrRenderService.Param.SESSION_ID]?.jsonPrimitive?.content
                ?: XrRenderService.DEFAULT_SESSION_ID
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
      seq = params[XrRenderService.Param.SEQ]?.jsonPrimitive?.long ?: 0,
      width = params[XrRenderService.Param.WIDTH]?.jsonPrimitive?.int ?: 0,
      height = params[XrRenderService.Param.HEIGHT]?.jsonPrimitive?.int ?: 0,
      encoding = params[XrRenderService.Param.ENCODING]?.jsonPrimitive?.content ?: "png",
      dataBase64 = params[XrRenderService.Param.DATA]?.jsonPrimitive?.content ?: "",
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
