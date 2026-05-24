package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.bta.BtaCompileService
import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
import ee.schimke.composeai.daemon.protocol.SourceChangeSet
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the stage-2 `compileSources` JSON-RPC method on [JsonRpcServer]. Wraps the server
 * over piped streams (same pattern as [JsonRpcServerIntegrationTest]); the backend is a trivial
 * fake [BtaCompileService] whose return value the test controls.
 *
 * Three outcomes are exercised, mirroring [BtaCompileService.Outcome]:
 *
 * 1. **null service → fallback.** Daemons whose launch descriptor lacks `btaCompilerClasspath` end
 *    up here. The handler returns `result="fallback"` with no swap side-effects, and the editor
 *    will retry through stage-1 / stage-0.
 * 2. **service returns `Ok` → ok + classloader swap fires.** Same code path
 *    `fileChanged({kind:"source"})` triggers, so any in-flight render keeps its already-resolved
 *    `Class<?>` and the next render reads the freshly-compiled bytecode off disk via the new child
 *    classloader.
 * 3. **service returns `CompileError` → compileError, no swap.** Diagnostics flow through to the
 *    editor's existing compile-error banner; the classloader is NOT rotated, so a subsequent render
 *    (manual refresh, focus change) still uses the last-good bytecode.
 *
 * The handler's invalid-params and empty-sources paths get separate quick checks at the bottom.
 */
class CompileSourcesTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 10_000)
  fun `compileSources returns fallback when no BTA service is wired`() {
    val rig = startServer(service = null)
    try {
      rig.handshake()
      val resp = rig.compileSources(sources = listOf("/tmp/Hi.kt"))
      assertEquals("fallback", resp.resultKind)
      assertEquals(0, rig.host.swapCount.get())
    } finally {
      rig.shutdown()
    }
  }

  @Test(timeout = 10_000)
  fun `compileSources returns ok and swaps the user classloader on success`() {
    val service = StubService(outcome = { BtaCompileService.Outcome.Ok })
    val rig = startServer(service = service)
    try {
      rig.handshake()
      val resp = rig.compileSources(sources = listOf("/tmp/Hi.kt", "/tmp/There.kt"))
      assertEquals("ok", resp.resultKind)
      assertEquals(1, rig.host.swapCount.get())
      assertTrue("durationMs should be non-negative; got ${resp.durationMs}", resp.durationMs >= 0)
      // Service saw both sources.
      assertEquals(listOf("/tmp/Hi.kt", "/tmp/There.kt"), service.lastSources.map { it.toString() })
    } finally {
      rig.shutdown()
    }
  }

  @Test(timeout = 10_000)
  fun `compileSources returns compileError without swapping on diagnostic failure`() {
    val service =
      StubService(
        outcome = {
          BtaCompileService.Outcome.CompileError(
            listOf(
              CompileErrorDetail(
                file = "/tmp/Hi.kt",
                line = 4,
                column = 17,
                message = "Unresolved reference 'R'",
              )
            )
          )
        }
      )
    val rig = startServer(service = service)
    try {
      rig.handshake()
      val resp = rig.compileSources(sources = listOf("/tmp/Hi.kt"))
      assertEquals("compileError", resp.resultKind)
      assertEquals(1, resp.errors.size)
      assertEquals("/tmp/Hi.kt", resp.errors[0].file)
      assertEquals(4, resp.errors[0].line)
      assertEquals(17, resp.errors[0].column)
      // No swap on compile failure — stale-but-loadable bytecode keeps the last render visible.
      assertEquals(0, rig.host.swapCount.get())
    } finally {
      rig.shutdown()
    }
  }

  @Test(timeout = 10_000)
  fun `compileSources forwards the editor's known dirty set to the service`() {
    val service = StubService(outcome = { BtaCompileService.Outcome.Ok })
    val rig = startServer(service = service)
    try {
      rig.handshake()
      rig.compileSources(
        sources = listOf("/tmp/Hi.kt"),
        changes = SourceChangeSet(modified = listOf("/tmp/Hi.kt"), removed = emptyList()),
      )
      assertEquals(SourceChangeSet(modified = listOf("/tmp/Hi.kt")), service.lastChanges)
    } finally {
      rig.shutdown()
    }
  }

  @Test(timeout = 10_000)
  fun `compileSources rejects an empty sources list with invalid-params`() {
    val rig = startServer(service = null)
    try {
      rig.handshake()
      rig.writeFrame(
        """{"jsonrpc":"2.0","id":42,"method":"compileSources","params":{"sources":[]}}"""
      )
      val resp = rig.pollUntil { it["id"]?.jsonPrimitive?.intOrNull == 42 }
      assertNotNull("compileSources response should arrive", resp)
      val error = resp!!["error"]?.jsonObject
      assertNotNull("expected error envelope", error)
      assertEquals(-32602, error!!["code"]?.jsonPrimitive?.intOrNull)
      // No swap, no service call.
      assertEquals(0, rig.host.swapCount.get())
    } finally {
      rig.shutdown()
    }
  }

  @Test(timeout = 10_000)
  fun `compileSources treats a throwing service as fallback`() {
    val service = StubService(outcome = { error("BTA bootstrap exploded") })
    val rig = startServer(service = service)
    try {
      rig.handshake()
      val resp = rig.compileSources(sources = listOf("/tmp/Hi.kt"))
      assertEquals("fallback", resp.resultKind)
      assertEquals(0, rig.host.swapCount.get())
    } finally {
      rig.shutdown()
    }
  }

  // --- harness ------------------------------------------------------------------------------

  private class StubService(val outcome: () -> BtaCompileService.Outcome) : BtaCompileService {
    var lastSources: List<Path> = emptyList()
    var lastChanges: SourceChangeSet? = null

    override fun compile(
      sources: List<Path>,
      changes: SourceChangeSet?,
    ): BtaCompileService.Outcome {
      lastSources = sources
      lastChanges = changes
      return outcome()
    }
  }

  private class SwapCountingHost : RenderHost {
    val swapCount = AtomicInteger(0)

    override fun start() {
      /* no-op */
    }

    override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult =
      error("renderNow path is not exercised by this test")

    override fun shutdown(timeoutMs: Long) {
      /* no-op */
    }

    override fun swapUserClassLoaders() {
      swapCount.incrementAndGet()
    }
  }

  private data class CompileSourcesResponseDto(
    val resultKind: String,
    val errors: List<CompileErrorDetail>,
    val durationMs: Long,
  )

  private inner class Rig(
    val server: JsonRpcServer,
    val serverThread: Thread,
    val host: SwapCountingHost,
    private val clientToServerOut: PipedOutputStream,
    private val received: LinkedBlockingQueue<JsonObject>,
  ) {
    private var nextId = 100L

    fun writeFrame(json: String) {
      val payload = json.toByteArray(Charsets.UTF_8)
      clientToServerOut.write(
        "Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
      )
      clientToServerOut.write(payload)
      clientToServerOut.flush()
    }

    fun pollUntil(timeoutMs: Long = 5_000, matcher: (JsonObject) -> Boolean): JsonObject? {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (System.currentTimeMillis() < deadline) {
        val msg =
          received.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: return null
        if (matcher(msg)) return msg
      }
      return null
    }

    fun handshake() {
      writeFrame(
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":2,
          "clientVersion":"test",
          "workspaceRoot":"/tmp",
          "moduleId":":test",
          "moduleProjectDir":"/tmp",
          "capabilities":{"visibility":true,"metrics":false}
        }}
        """
          .trimIndent()
      )
      val init = pollUntil { it["id"]?.jsonPrimitive?.intOrNull == 1 }
      assertNotNull("initialize response should arrive", init)
      writeFrame("""{"jsonrpc":"2.0","method":"initialized","params":{}}""")
    }

    fun compileSources(
      sources: List<String>,
      changes: SourceChangeSet? = null,
    ): CompileSourcesResponseDto {
      val id = nextId++
      val sourcesJson = sources.joinToString(",") { "\"$it\"" }
      val changesJson =
        changes?.let { c ->
          val m = c.modified.joinToString(",") { "\"$it\"" }
          val r = c.removed.joinToString(",") { "\"$it\"" }
          ""","changes":{"modified":[$m],"removed":[$r]}"""
        } ?: ""
      writeFrame(
        """{"jsonrpc":"2.0","id":$id,"method":"compileSources","params":{"sources":[$sourcesJson]$changesJson}}"""
      )
      val resp = pollUntil { it["id"]?.jsonPrimitive?.longOrNull == id }
      assertNotNull("compileSources response (id=$id) should arrive", resp)
      assertNull("no error envelope expected", resp!!["error"])
      val result = resp["result"]!!.jsonObject
      val kind = result["result"]?.jsonPrimitive?.contentOrNull ?: error("missing result kind")
      val errors =
        result["errors"]?.jsonArray.orEmpty().map { entry ->
          val obj = entry.jsonObject
          CompileErrorDetail(
            file = obj["file"]!!.jsonPrimitive.content,
            line = obj["line"]!!.jsonPrimitive.content.toInt(),
            column = obj["column"]!!.jsonPrimitive.content.toInt(),
            message = obj["message"]!!.jsonPrimitive.content,
          )
        }
      val durationMs = result["durationMs"]?.jsonPrimitive?.longOrNull ?: error("missing duration")
      return CompileSourcesResponseDto(kind, errors, durationMs)
    }

    fun shutdown() {
      try {
        writeFrame("""{"jsonrpc":"2.0","id":999,"method":"shutdown","params":null}""")
      } catch (_: Throwable) {
        /* server may have already exited */
      }
      serverThread.join(2_000)
      if (serverThread.isAlive) serverThread.interrupt()
    }
  }

  private fun startServer(service: BtaCompileService?): Rig {
    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)
    val host = SwapCountingHost()
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        btaCompileService = service,
        onExit = { /* test owns lifecycle */ },
      )
    val received = LinkedBlockingQueue<JsonObject>()
    Thread(
        {
          val framer = ContentLengthFramer(serverToClientIn)
          try {
            while (true) {
              val frame = framer.readFrame() ?: break
              received.put(json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject)
            }
          } catch (_: Throwable) {
            /* expected on shutdown */
          }
        },
        "compile-sources-test-reader",
      )
      .apply { isDaemon = true }
      .start()
    val serverThread =
      Thread({ server.run() }, "compile-sources-test-server").apply { isDaemon = true }
    serverThread.start()
    return Rig(server, serverThread, host, clientToServerOut, received)
  }
}

/** kotlinx.serialization helper. */
private val kotlinx.serialization.json.JsonPrimitive.longOrNull: Long?
  get() = content.toLongOrNull()
