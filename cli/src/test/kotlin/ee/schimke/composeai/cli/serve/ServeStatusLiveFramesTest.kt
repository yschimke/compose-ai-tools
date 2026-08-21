package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * End-to-end wiring for `/status.json`'s `liveFrames` (#4281): a real socket on a real embedded
 * server, frames pushed by a modelled daemon, and the counters read back off the status route.
 *
 * The unit-level arithmetic lives in `LiveFramePerfStatsTest`; what this pins is the part that was
 * missing rather than wrong — the live lane's frames reaching the status snapshot at all. They
 * never passed through `ServeRenderHost.render`, so `renderStats` could not see them and the only
 * live-lane number on the page was how many sockets were open.
 */
class ServeStatusLiveFramesTest {

  private val previewId = "com.example.Red"
  private val client = OkHttpClient()
  private val registry = ServeSessionRegistry(open = { null })
  private var server: ServeHttpServer? = null
  private var socket: WebSocket? = null

  @AfterTest
  fun tearDown() {
    socket?.close(1000, "done")
    server?.stop()
    registry.close()
  }

  private fun renderRoot(): File =
    Files.createTempDirectory("status-live").toFile().also { it.deleteOnExit() }

  @Test
  fun `a live socket's frames are reported on status_json`() {
    val session = FakeRenderSession(renderRoot(), streaming = true)
    val host =
      ServeRenderHost(session, listOf(ServePreview(previewId, "Red")), renderTimeoutSeconds = 30)
    registry.register("live-mod", host = host, pinned = false)
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "live-mod",
          isPublic = true,
        )
        .also { it.start() }
    this.server = server

    val opened = CountDownLatch(1)
    socket =
      client.newWebSocket(
        Request.Builder()
          .url("ws://127.0.0.1:${server.port}/ws/$previewId?session=live-mod")
          .build(),
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            opened.countDown()
          }
        },
      )
    assertTrue(opened.await(10, TimeUnit.SECONDS), "socket never opened")
    val streamId = awaitStreamId(session)

    // Two painted frames and one `unchanged` heartbeat, as a resting-then-touched preview produces.
    val payload = Base64.getEncoder().encodeToString(ByteArray(120))
    session.emitStreamFrame(streamId, seq = 1, payloadBase64 = payload)
    session.emitStreamFrame(streamId, seq = 2, payloadBase64 = payload)
    session.emitStreamFrame(streamId, seq = 3, payloadBase64 = null)

    val live = awaitLiveFrames(server) { it["frames"]?.jsonPrimitive?.long == 2L }
    assertEquals(2L, live.getValue("frames").jsonPrimitive.long)
    assertEquals(1L, live.getValue("heartbeats").jsonPrimitive.long)
    assertEquals(1, live.getValue("openSockets").jsonPrimitive.int)
    assertEquals(payload.length * 2L, live.getValue("payloadBytes").jsonPrimitive.long)
    val stream = live.getValue("streams").let { assertNotNull(it) }.toString()
    assertTrue(stream.contains(previewId), "the open socket names what it is watching: $stream")
    // The HTML page carries the same reading, so an operator sees it without parsing JSON.
    assertTrue(get(server, "/status").second.contains("Live frames"))
  }

  /** The daemon-side stream id, once the socket's live session has subscribed. */
  private fun awaitStreamId(session: FakeRenderSession): String {
    repeat(100) {
      session.lastFrameStreamId?.let {
        return it
      }
      Thread.sleep(50)
    }
    error("the socket never opened a daemon stream")
  }

  /** `/status.json`'s `liveFrames`, polled until [ready] — frames cross threads to get there. */
  private fun awaitLiveFrames(
    server: ServeHttpServer,
    ready: (kotlinx.serialization.json.JsonObject) -> Boolean,
  ): kotlinx.serialization.json.JsonObject {
    var last: kotlinx.serialization.json.JsonObject? = null
    repeat(100) {
      val (code, body) = get(server, "/status.json")
      assertEquals(200, code, body)
      val live = Json.parseToJsonElement(body).jsonObject["liveFrames"]?.jsonObject
      if (live != null) {
        last = live
        if (ready(live)) return live
      }
      Thread.sleep(50)
    }
    error("liveFrames never reported the expected frames: $last")
  }

  private fun get(server: ServeHttpServer, path: String): Pair<Int, String> =
    client
      .newCall(Request.Builder().url("http://127.0.0.1:${server.port}$path").build())
      .execute()
      .use { it.code to (it.body?.string() ?: "") }
}
