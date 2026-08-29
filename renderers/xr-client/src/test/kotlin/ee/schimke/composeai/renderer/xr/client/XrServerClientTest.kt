package ee.schimke.composeai.renderer.xr.client

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Exercises [XrServerClient]'s framing + request/notification demultiplexing against an in-memory
 * fake server over piped streams — no native binary required (the real end-to-end is covered by
 * `renderers/xr-composite/test/serve_smoke.py`). Verifies that `render` / `xr/updatePanels` return
 * the `streamFrame` the server pushed before its ack, in order.
 */
class XrServerClientTest {

  private val json = Json { ignoreUnknownKeys = true }
  private lateinit var serverThread: Thread

  @AfterTest
  fun tearDown() {
    if (::serverThread.isInitialized) serverThread.join(2_000)
  }

  /**
   * A scripted fake server: reads each request and replies, pushing a streamFrame before the ack.
   */
  private fun startFakeServer(
    serverIn: InputStream,
    serverOut: OutputStream,
    capabilities: JsonObject = buildJsonObject {
      put("render", true)
      put("updatePanels", true)
      put("streamFrame", true)
      put("multiSession", true)
      put("spatialSceneVersion", 1)
    },
  ) {
    serverThread =
      Thread {
        var seq = 0L
        while (true) {
          val frame = readFrame(serverIn) ?: return@Thread
          val req = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
          val method = req["method"]?.jsonPrimitive?.content ?: continue
          val id = req["id"]?.jsonPrimitive?.long
          when (method) {
            "initialize" ->
              writeFrame(
                serverOut,
                result(
                  id!!,
                  buildJsonObject {
                    // Mirrors what the native server actually sends. This fake omitted
                    // `serverInfo` entirely until the handshake became load-bearing — an
                    // unfaithful fake nothing could detect, because nothing read the result.
                    put(
                      "serverInfo",
                      buildJsonObject {
                        put("name", XrRenderService.SERVER_NAME)
                        put("version", XrRenderService.XR_RENDER_SERVICE_VERSION)
                      },
                    )
                    put("capabilities", capabilities)
                  },
                ),
              )
            "render",
            "xr/updatePanels" -> {
              seq += 1
              val sid =
                req["params"]?.jsonObject?.get("sessionId")?.jsonPrimitive?.content ?: "default"
              // Push the frame first (as the native server does), tagged with the session, then
              // ack.
              writeFrame(serverOut, streamFrame(seq, "frame-$seq", sid))
              writeFrame(
                serverOut,
                result(
                  id!!,
                  buildJsonObject {
                    put("ok", true)
                    put("seq", seq)
                  },
                ),
              )
            }
            "exit" -> return@Thread
          }
        }
      }
        .apply {
          isDaemon = true
          start()
        }
  }

  @Test
  fun initializeRenderAndUpdatePanelsReturnFramesInOrder() {
    // client.output -> server.input ; server.output -> client.input
    val clientToServer = PipedOutputStream()
    val serverIn = PipedInputStream(clientToServer, 1 shl 16)
    val serverToClient = PipedOutputStream()
    val clientIn = PipedInputStream(serverToClient, 1 shl 16)
    startFakeServer(serverIn, serverToClient)

    val client = XrServerClient(clientToServer, clientIn, process = null)

    val handshake = client.initialize()
    assertEquals(XrRenderService.SERVER_NAME, handshake.serverName)
    assertEquals(XrRenderService.XR_RENDER_SERVICE_VERSION, handshake.serviceVersion)
    assertEquals(1, handshake.spatialSceneVersion)
    assertEquals(true, handshake.has(XrRenderService.Capability.RENDER))

    val scene = buildJsonObject {
      put("version", 1)
      put("units", "dp")
      put("camera", buildJsonObject { put("kind", "orbit") })
      put("panels", buildJsonArray {})
    }
    val f1 = client.render("s1", scene, sceneDir = ".")
    assertEquals(1L, f1.seq)
    assertEquals("png", f1.encoding)
    assertEquals("frame-1", f1.dataBase64)

    val f2 =
      client.updatePanels(
        "s1",
        buildJsonArray {
          add(
            buildJsonObject {
              put("id", "top")
              put(
                "poseInRoot",
                buildJsonObject {
                  put(
                    "translation",
                    buildJsonObject {
                      put("x", 1)
                      put("y", 2)
                      put("z", 3)
                    },
                  )
                  put(
                    "rotation",
                    buildJsonObject {
                      put("x", 0)
                      put("y", 0)
                      put("z", 0)
                      put("w", 1)
                    },
                  )
                },
              )
            }
          )
        },
      )
    assertEquals(2L, f2.seq)
    assertEquals("frame-2", f2.dataBase64)
    assertTrue(f2.seq > f1.seq)

    client.exit()
  }

  // ---- framing helpers (mirror the wire format the client speaks) ----

  private fun result(id: Long, result: JsonObject): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
  }

  private fun streamFrame(seq: Long, data: String, sessionId: String): JsonObject =
    buildJsonObject {
      put("jsonrpc", "2.0")
      put("method", "streamFrame")
      put(
        "params",
        buildJsonObject {
          put("encoding", "png")
          put("width", 64)
          put("height", 48)
          put("seq", seq)
          put("sessionId", sessionId)
          put("data", data)
        },
      )
    }

  private fun writeFrame(out: OutputStream, message: JsonObject) {
    val payload = json.encodeToString(JsonObject.serializer(), message).toByteArray(Charsets.UTF_8)
    synchronized(out) {
      out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
      out.write(payload)
      out.flush()
    }
  }

  private fun readFrame(input: InputStream): ByteArray? {
    var contentLength = -1
    val buf = ByteArrayOutputStream(64)
    var sawAny = false
    while (true) {
      val line = readHeaderLine(input, buf) ?: return null
      if (line.isEmpty()) {
        if (sawAny) break else continue
      }
      sawAny = true
      val colon = line.indexOf(':')
      if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", true)) {
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

  /** Wire a client to a fake server advertising [capabilities]. */
  private fun connect(capabilities: JsonObject? = null): XrServerClient {
    val clientToServer = PipedOutputStream()
    val serverIn = PipedInputStream(clientToServer, 1 shl 16)
    val serverToClient = PipedOutputStream()
    val clientIn = PipedInputStream(serverToClient, 1 shl 16)
    if (capabilities == null) startFakeServer(serverIn, serverToClient)
    else startFakeServer(serverIn, serverToClient, capabilities)
    return XrServerClient(clientToServer, clientIn, process = null)
  }

  private fun scene(version: Int = 1) = buildJsonObject {
    put("version", version)
    put("units", "dp")
    put("camera", buildJsonObject { put("kind", "orbit") })
    put("panels", buildJsonArray {})
  }

  @Test
  fun `a second session is refused when the server does not advertise multiSession`() {
    // A single-session server silently reuses one scene for a second id, so the two sessions
    // corrupt each other's frames rather than failing. Refuse before sending.
    val client =
      connect(
        buildJsonObject {
          put("render", true)
          put("updatePanels", true)
          put("streamFrame", true)
          put("multiSession", false)
          put("spatialSceneVersion", 1)
        }
      )
    client.initialize()
    client.render("s1", scene(), sceneDir = ".")
    val e = assertFailsWith<XrServerException> { client.render("s2", scene(), sceneDir = ".") }
    assertTrue(e.message!!.contains("multiSession"), e.message)
  }

  @Test
  fun `reusing the same session needs no multiSession capability`() {
    val client =
      connect(
        buildJsonObject {
          put("render", true)
          put("updatePanels", true)
          put("streamFrame", true)
          put("multiSession", false)
          put("spatialSceneVersion", 1)
        }
      )
    client.initialize()
    client.render("s1", scene(), sceneDir = ".")
    // Re-rendering the SAME session is single-session behaviour and must stay allowed.
    assertEquals(2L, client.render("s1", scene(), sceneDir = ".").seq)
  }

  @Test
  fun `a stopped session no longer counts against the multiSession gate`() {
    val client =
      connect(
        buildJsonObject {
          put("render", true)
          put("updatePanels", true)
          put("streamFrame", true)
          put("multiSession", false)
          put("spatialSceneVersion", 1)
        }
      )
    client.initialize()
    client.render("s1", scene(), sceneDir = ".")
    client.stop("s1")
    // One session at a time is exactly what a single-session server supports.
    assertEquals(2L, client.render("s2", scene(), sceneDir = ".").seq)
  }

  @Test
  fun `a scene whose version the server cannot parse is refused before sending`() {
    val client = connect()
    client.initialize()
    val e =
      assertFailsWith<XrServerException> { client.render("s1", scene(version = 2), sceneDir = ".") }
    assertTrue(e.message!!.contains("SpatialScene version mismatch"), e.message)
    assertTrue(e.message!!.contains("xr-composite"), e.message)
  }

  @Test
  fun `updatePanels is refused when the server does not advertise it`() {
    val client =
      connect(
        buildJsonObject {
          put("render", true)
          put("updatePanels", false)
          put("streamFrame", true)
          put("multiSession", true)
          put("spatialSceneVersion", 1)
        }
      )
    client.initialize()
    client.render("s1", scene(), sceneDir = ".")
    val e = assertFailsWith<XrServerException> { client.updatePanels("s1", buildJsonArray {}) }
    assertTrue(e.message!!.contains("updatePanels"), e.message)
  }

  @Test
  fun `render is refused when the server does not advertise streamFrame`() {
    // Both render and updatePanels RETURN a frame. Without `streamFrame` the request would be
    // accepted and then block for the full 60s timeout waiting for a notification the handshake
    // already said would never arrive.
    val client =
      connect(
        buildJsonObject {
          put("render", true)
          put("updatePanels", true)
          put("streamFrame", false)
          put("multiSession", true)
          put("spatialSceneVersion", 1)
        }
      )
    client.initialize()
    val e = assertFailsWith<XrServerException> { client.render("s1", scene(), sceneDir = ".") }
    assertTrue(e.message!!.contains("streamFrame"), e.message)
  }

  @Test
  fun `updatePanels is refused when the server does not advertise streamFrame`() {
    val client =
      connect(
        buildJsonObject {
          put("render", true)
          put("updatePanels", true)
          put("streamFrame", false)
          put("multiSession", true)
          put("spatialSceneVersion", 1)
        }
      )
    client.initialize()
    val e = assertFailsWith<XrServerException> { client.updatePanels("s1", buildJsonArray {}) }
    assertTrue(e.message!!.contains("streamFrame"), e.message)
  }

  @Test
  fun `calling render before initialize is a named error`() {
    val client = connect()
    val e = assertFailsWith<XrServerException> { client.render("s1", scene(), sceneDir = ".") }
    assertTrue(e.message!!.contains("before initialize"), e.message)
  }
}
