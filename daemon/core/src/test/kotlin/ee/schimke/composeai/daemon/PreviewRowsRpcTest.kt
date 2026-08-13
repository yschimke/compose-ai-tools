package ee.schimke.composeai.daemon

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire coverage for `preview/rows` — the enumeration half of issue #3749.
 *
 * Row *addressing* (`renderNow` on `<baseId>_PARAM_4`) shipped first and left discovery to a probe:
 * ask for a row past the end and read the provider's row list out of the error. This is the direct
 * answer, and it exists on the daemon because nothing upstream can produce it — `previews.json`
 * carries base ids only, since discovery reads bytecode and cannot instantiate a provider.
 *
 * Scope is the wire contract — shapes and error codes. The metadata **gate** (an ordinary preview
 * answers "no rows" without touching a classloader or, on Android, the render sandbox) lives in the
 * hosts, so it's asserted there: `PreviewManifestRowEnumerationTest` in `:daemon:desktop` and
 * `RobolectricRowEnumerationTest` in `:daemon:android`.
 */
class PreviewRowsRpcTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val toClose = mutableListOf<AutoCloseable>()

  @After
  fun teardown() {
    toClose.reversed().forEach { runCatching { it.close() } }
  }

  @Test(timeout = 30_000)
  fun `a parameterized preview enumerates its rows as addressable ids`() {
    val host =
      RowsFakeHost(
        rows = mapOf("Screen" to listOf("Crimson", "Teal", "PARAM_2"), "Plain" to emptyList())
      )
    val h = bringUp(host)

    val result = rowsCall(h, id = 10, previewId = "Screen")!!["result"]!!.jsonObject
    assertEquals("Screen", result["previewId"]?.jsonPrimitive?.contentOrNull)
    val rows = result["rows"]!!.jsonArray.map { it.jsonObject }
    assertEquals(3, rows.size)
    assertEquals(listOf(0, 1, 2), rows.map { it["index"]!!.jsonPrimitive.int })
    assertEquals(
      listOf("Crimson", "Teal", "PARAM_2"),
      rows.map { it["label"]!!.jsonPrimitive.contentOrNull },
    )
    // The ids are what makes this useful: they go straight back to `renderNow` untouched.
    assertEquals(
      listOf("Screen_Crimson", "Screen_Teal", "Screen_PARAM_2"),
      rows.map { it["id"]!!.jsonPrimitive.contentOrNull },
    )
  }

  /**
   * "No rows" is a normal answer, not an error — a client lists rows for everything it displays and
   * renders the bare id when the list is empty.
   */
  @Test(timeout = 30_000)
  fun `an ordinary preview answers with an empty row list`() {
    val h = bringUp(RowsFakeHost(rows = mapOf("Plain" to emptyList())))

    val result = rowsCall(h, id = 11, previewId = "Plain")!!["result"]!!.jsonObject
    assertEquals(0, result["rows"]!!.jsonArray.size)
  }

  @Test(timeout = 30_000)
  fun `an unknown previewId is the caller's mistake, not an internal error`() {
    val h = bringUp(RowsFakeHost(rows = mapOf("Screen" to listOf("Crimson"))))

    val error = rowsCall(h, id = 12, previewId = "Nope")!!["error"]!!.jsonObject
    assertEquals(JsonRpcServer.ERR_INVALID_PARAMS, error["code"]!!.jsonPrimitive.int)
    assertTrue(
      "error should name the id it couldn't resolve; got ${error["message"]}",
      error["message"]!!.jsonPrimitive.contentOrNull!!.contains("Nope"),
    )
  }

  /**
   * A host with no enumeration at all (the [RenderHost] default) replies method-not-found, the code
   * clients already treat as "this daemon can't do that" and degrade past.
   */
  @Test(timeout = 30_000)
  fun `a host that cannot enumerate replies method not found`() {
    val h = bringUp(NonEnumeratingFakeHost())

    val error = rowsCall(h, id = 13, previewId = "Screen")!!["error"]!!.jsonObject
    assertEquals(JsonRpcServer.ERR_METHOD_NOT_FOUND, error["code"]!!.jsonPrimitive.int)
  }

  // ---------------------------------------------------------------------------------------------
  // Harness
  // ---------------------------------------------------------------------------------------------

  private class Harness(
    val out: PipedOutputStream,
    val received: LinkedBlockingQueue<JsonObject>,
    val thread: Thread,
  )

  private fun bringUp(host: RenderHost): Harness {
    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)
    toClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        onExit = { _ -> },
      )
    val thread = Thread({ server.run() }, "preview-rows-rpc-server").apply { isDaemon = true }
    thread.start()

    val reader = ContentLengthFramer(serverToClientIn)
    val received = LinkedBlockingQueue<JsonObject>()
    Thread(
        {
          try {
            while (true) {
              val frame = reader.readFrame() ?: break
              received.put(json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject)
            }
          } catch (_: Throwable) {}
        },
        "preview-rows-rpc-reader",
      )
      .apply { isDaemon = true }
      .start()

    val h = Harness(clientToServerOut, received, thread)
    writeFrame(
      h.out,
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":2,"clientVersion":"test","workspaceRoot":"/tmp",
        "moduleId":":test","moduleProjectDir":"/tmp",
        "capabilities":{"visibility":true,"metrics":false}}}""",
    )
    assertNotNull(
      "initialize response should arrive",
      pollUntil(h.received) { it["id"]?.jsonPrimitive?.intOrNull == 1 },
    )
    writeFrame(h.out, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")
    return h
  }

  private fun rowsCall(h: Harness, id: Int, previewId: String): JsonObject? {
    writeFrame(
      h.out,
      """{"jsonrpc":"2.0","id":$id,"method":"preview/rows",""" +
        """"params":{"previewId":"$previewId"}}""",
    )
    return pollUntil(h.received) { it["id"]?.jsonPrimitive?.intOrNull == id }
  }

  private fun writeFrame(out: PipedOutputStream, payloadJson: String) {
    val payload = payloadJson.toByteArray(Charsets.UTF_8)
    out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
    out.write(payload)
    out.flush()
  }

  private fun pollUntil(
    queue: LinkedBlockingQueue<JsonObject>,
    timeoutMs: Long = 5_000,
    matcher: (JsonObject) -> Boolean,
  ): JsonObject? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
      val msg = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
      if (matcher(msg)) return msg
    }
    return null
  }
}

/**
 * Minimal [RenderHost] that only knows how to answer `preview/rows`. [rows] doubles as the
 * manifest: a missing key is an unknown previewId, an empty list is a preview with no provider.
 */
private class RowsFakeHost(private val rows: Map<String, List<String>>) : RenderHost {
  override fun start() {}

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult =
    error("RowsFakeHost renders nothing")

  override fun shutdown(timeoutMs: Long) {}

  override fun previewParameterRows(previewId: String): List<PreviewParameterRow> {
    val labels =
      rows[previewId] ?: throw IllegalArgumentException("no manifest entry for '$previewId'")
    return labels.mapIndexed { index, label ->
      PreviewParameterRow(
        index = index,
        label = label,
        id = PreviewRowAddress.rowId(previewId, label),
      )
    }
  }
}

/** A host that never overrode [RenderHost.previewParameterRows] — the pre-#3749 shape. */
private class NonEnumeratingFakeHost : RenderHost {
  override fun start() {}

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult =
    error("NonEnumeratingFakeHost renders nothing")

  override fun shutdown(timeoutMs: Long) {}
}
