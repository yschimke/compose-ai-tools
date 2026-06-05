package ee.schimke.composeai.renderer.xr.client

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
  private fun startFakeServer(serverIn: InputStream, serverOut: OutputStream) {
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
                      put(
                        "capabilities",
                        buildJsonObject {
                          put("render", true)
                          put("updatePanels", true)
                          put("streamFrame", true)
                        },
                      )
                    },
                  ),
                )
              "render",
              "xr/updatePanels" -> {
                seq += 1
                // Push the frame first (as the native server does), then the ack.
                writeFrame(serverOut, streamFrame(seq, "frame-$seq"))
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

    val caps = client.initialize()
    assertEquals(true, caps["render"]?.jsonPrimitive?.content?.toBoolean())

    val scene = buildJsonObject {
      put("version", 1)
      put("units", "dp")
      put("camera", buildJsonObject { put("kind", "orbit") })
      put("panels", buildJsonArray {})
    }
    val f1 = client.render(scene, sceneDir = ".")
    assertEquals(1L, f1.seq)
    assertEquals("png", f1.encoding)
    assertEquals("frame-1", f1.dataBase64)

    val f2 =
      client.updatePanels(
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
        }
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

  private fun streamFrame(seq: Long, data: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("method", "streamFrame")
    put(
      "params",
      buildJsonObject {
        put("encoding", "png")
        put("width", 64)
        put("height", 48)
        put("seq", seq)
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
}
