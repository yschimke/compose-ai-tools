package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3 — `data/fetch` re-render-on-demand behaviour. See
 * [docs/daemon/DATA-PRODUCTS.md](../../../../../../../docs/daemon/DATA-PRODUCTS.md) § "Re-render
 * semantics" + § "Wire surface > data/fetch".
 *
 * The tests drive the JSON-RPC surface end-to-end against a [FakeProducer] [DataProductRegistry]
 * that swings between "needs a re-render" and "Ok with the payload" depending on whether a render
 * has been observed for the requested preview. That lets us pin every documented branch (happy-path
 * re-render -> payload, post-render-no-payload -> fetch-failed, budget-exceeded) without standing
 * up a real renderer-side producer (D2 / `renderer-android` lands on a separate branch).
 */
class DataFetchRerenderTest {

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Happy path. Producer reports "needs a11y mode re-render" on the first fetch, the daemon kicks a
   * re-render off the regular render-queue path, the watcher emits `renderStarted` /
   * `renderFinished`, the producer returns `Ok` on re-call, and the `data/fetch` response resolves
   * with the payload.
   */
  @Test(timeout = 30_000)
  fun rerender_happy_path_emits_render_notifications_then_payload_response() {
    val producer = FakeProducer(modeForKind = mapOf("a11y/hierarchy" to "a11y"))
    runWithServer(producer = producer) { rpc ->
      rpc.initialize()
      rpc.send(
        """
        {"jsonrpc":"2.0","id":42,"method":"data/fetch","params":{
          "previewId":"com.example.Foo_bar","kind":"a11y/hierarchy"
        }}
        """
          .trimIndent()
      )

      // Renderer-side notifications fire as for a regular renderNow - UI panel updates the PNG.
      val started = rpc.pollUntil { it["method"]?.jsonPrimitive?.contentOrNull == "renderStarted" }
      assertNotNull("renderStarted should fire for the fetch-driven re-render", started)
      assertEquals(
        "com.example.Foo_bar",
        started!!["params"]!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull,
      )

      val finished = rpc.pollUntil {
        it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished"
      }
      assertNotNull("renderFinished should fire for the fetch-driven re-render", finished)
      assertEquals(
        "com.example.Foo_bar",
        finished!!["params"]!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull,
      )

      // Then the data/fetch response - payload from the post-rerender producer call.
      val response = rpc.pollUntil { it["id"]?.jsonPrimitive?.intOrNull == 42 }
      assertNotNull("data/fetch should resolve once the re-render produces the payload", response)
      val result = response!!["result"]!!.jsonObject
      assertEquals("a11y/hierarchy", result["kind"]?.jsonPrimitive?.contentOrNull)
      assertEquals(1, result["schemaVersion"]?.jsonPrimitive?.intOrNull)
      assertNotNull("payload must be present on Ok", result["payload"])

      // The producer's render submit observed the propagated mode tag. Renderer-agnostic seam
      // stays string-typed: payload carries `mode=a11y` so D2's renderer-side producer can pick
      // the right pipeline.
      val payload = producer.lastRenderPayload
      assertNotNull("producer should observe the host payload", payload)
      assertTrue(
        "render payload should propagate `mode=a11y`, was: $payload",
        payload!!.contains("mode=a11y"),
      )
      assertTrue(
        "render payload should carry previewId, was: $payload",
        payload.contains("previewId=com.example.Foo_bar"),
      )
      assertEquals("producer should observe the completed render", 1, producer.onRenderCalls.get())
      assertEquals("com.example.Foo_bar", producer.lastOnRenderPreviewId)
      assertEquals(3L, producer.lastOnRenderMetrics?.get("tookMs"))
    }
  }

  /**
   * Budget exceeded. The producer never resolves its render (host blocks indefinitely), so the
   * fetch worker hits its budget timer. Per spec: budget tripped -> `DataProductBudgetExceeded`
   * (-32023); the render is not cancelled, the fetch just stops waiting.
   */
  @Test(timeout = 30_000)
  fun rerender_budget_exceeded_returns_minus_32023_and_does_not_cancel_render() {
    val producer = FakeProducer(modeForKind = mapOf("a11y/hierarchy" to "a11y"))
    runWithServer(
      producer = producer,
      // Block forever - anything past the budget below trips the timeout branch.
      blockHostSubmits = true,
      // Pin tiny so the test takes ~75ms in the timeout branch rather than the default 30s.
      dataFetchRerenderBudgetMs = 75,
    ) { rpc ->
      rpc.initialize()
      rpc.send(
        """
        {"jsonrpc":"2.0","id":7,"method":"data/fetch","params":{
          "previewId":"preview-X","kind":"a11y/hierarchy"
        }}
        """
          .trimIndent()
      )
      val response = rpc.pollUntil { it["id"]?.jsonPrimitive?.intOrNull == 7 }
      assertNotNull("data/fetch should resolve via the budget-timeout branch", response)
      assertNull("budget-timeout response carries no result", response!!["result"])
      val error = response["error"]?.jsonObject
      assertNotNull("budget-timeout response must carry a JSON-RPC error", error)
      assertEquals(
        "must surface DataProductBudgetExceeded (-32023)",
        -32023,
        error!!["code"]?.jsonPrimitive?.intOrNull,
      )
      // No `renderFailed` notification - the render is blocked, not cancelled. This confirms
      // we honour the no-mid-render-cancellation invariant from PROTOCOL.md section 8.
      val sawRenderFailed =
        rpc.history.toList().any { it["method"]?.jsonPrimitive?.contentOrNull == "renderFailed" }
      assertFalse(
        "budget timeout must not surface as a renderFailed notification - render is in-flight",
        sawRenderFailed,
      )
    }
  }

  /**
   * Producer raises `FetchFailed` *after* the re-render lands (e.g. its projection step blew up).
   * Dispatcher must surface `DataProductFetchFailed` (-32022) to the client, not silently retry.
   */
  @Test(timeout = 30_000)
  fun rerender_then_producer_failure_returns_minus_32022() {
    val producer =
      FakeProducer(
        modeForKind = mapOf("a11y/hierarchy" to "a11y"),
        postRerenderOutcome =
          DataProductRegistry.Outcome.FetchFailed(
            message = "projection blew up",
            errorKind = "projection",
          ),
      )
    runWithServer(producer = producer) { rpc ->
      rpc.initialize()
      rpc.send(
        """
        {"jsonrpc":"2.0","id":11,"method":"data/fetch","params":{
          "previewId":"preview-Y","kind":"a11y/hierarchy"
        }}
        """
          .trimIndent()
      )
      val response = rpc.pollUntil { it["id"]?.jsonPrimitive?.intOrNull == 11 }
      assertNotNull(response)
      val error = response!!["error"]?.jsonObject
      assertNotNull("post-rerender producer failure must surface as JSON-RPC error", error)
      assertEquals(
        "must surface DataProductFetchFailed (-32022)",
        -32022,
        error!!["code"]?.jsonPrimitive?.intOrNull,
      )
    }
  }

  /**
   * Producer returns `RequiresRerender` *again* after the re-render lands. The dispatcher must NOT
   * recurse infinitely - surface a `DataProductFetchFailed` once and stop.
   */
  @Test(timeout = 30_000)
  fun rerender_then_second_requires_rerender_does_not_loop() {
    val producer =
      FakeProducer(
        modeForKind = mapOf("a11y/hierarchy" to "a11y"),
        postRerenderOutcome = DataProductRegistry.Outcome.RequiresRerender("a11y"),
      )
    runWithServer(producer = producer) { rpc ->
      rpc.initialize()
      rpc.send(
        """
        {"jsonrpc":"2.0","id":31,"method":"data/fetch","params":{
          "previewId":"preview-Z","kind":"a11y/hierarchy"
        }}
        """
          .trimIndent()
      )
      val response = rpc.pollUntil { it["id"]?.jsonPrimitive?.intOrNull == 31 }
      assertNotNull(response)
      val error = response!!["error"]?.jsonObject
      assertNotNull(error)
      assertEquals(-32022, error!!["code"]?.jsonPrimitive?.intOrNull)
    }
  }

  /**
   * Pre-render-on-demand fast paths still work - `Outcome.Ok` returned directly skips the worker
   * entirely. Pins that we haven't accidentally regressed the D1 "kind already produced" path by
   * routing it through the new D3 worker.
   */
  @Test(timeout = 15_000)
  fun ok_outcome_short_circuits_without_a_rerender() {
    val producer =
      FakeProducer(
        // No re-render mode configured - first fetch returns Ok directly.
        modeForKind = emptyMap(),
        immediateOk =
          DataFetchResult(
            kind = "a11y/atf",
            schemaVersion = 1,
            payload = buildJsonObject { /* empty payload is fine for the test */ },
          ),
      )
    runWithServer(producer = producer) { rpc ->
      rpc.initialize()
      rpc.send(
        """
        {"jsonrpc":"2.0","id":99,"method":"data/fetch","params":{
          "previewId":"preview-Q","kind":"a11y/atf"
        }}
        """
          .trimIndent()
      )
      val response = rpc.pollUntil { it["id"]?.jsonPrimitive?.intOrNull == 99 }
      assertNotNull(response)
      val result = response!!["result"]!!.jsonObject
      assertEquals("a11y/atf", result["kind"]?.jsonPrimitive?.contentOrNull)

      // Critically: no render notifications were emitted - D1 fast path bypasses the queue.
      val sawRender =
        rpc.history.toList().any {
          val method = it["method"]?.jsonPrimitive?.contentOrNull
          method == "renderStarted" || method == "renderFinished"
        }
      assertFalse("Ok-shortcut must not trigger a render - only RequiresRerender does", sawRender)
      assertEquals("Ok shortcut must not call the host", 0, producer.renderSubmits.get())
    }
  }

  // -------------------------------------------------------------------------
  // Test infrastructure
  // -------------------------------------------------------------------------

  /**
   * In-memory [DataProductRegistry] driven by per-test config:
   * - [modeForKind] tells the first fetch for a given kind to return `RequiresRerender(<mode>)`.
   * - After the dispatcher's re-render lands ([renderObserved] is set), subsequent fetches for that
   *   kind return [postRerenderOutcome] (defaults to a synthetic `Ok` payload).
   * - [immediateOk] lets a test inject "Ok on first call, no re-render needed" for the D1 fast-path
   *   regression coverage.
   */
  private class FakeProducer(
    private val modeForKind: Map<String, String>,
    private val postRerenderOutcome: DataProductRegistry.Outcome? = null,
    private val immediateOk: DataFetchResult? = null,
  ) : DataProductRegistry {

    val renderSubmits = AtomicInteger(0)
    @Volatile var lastRenderPayload: String? = null
    @Volatile var renderObserved: Boolean = false
    val onRenderCalls = AtomicInteger(0)
    @Volatile var lastOnRenderPreviewId: String? = null
    @Volatile var lastOnRenderMetrics: Map<String, Long>? = null

    override val capabilities: List<DataProductCapability> =
      modeForKind.keys
        .map { kind ->
          DataProductCapability(
            kind = kind,
            schemaVersion = 1,
            transport = DataProductTransport.INLINE,
            attachable = true,
            fetchable = true,
            requiresRerender = true,
          )
        }
        .ifEmpty {
          immediateOk?.let { ok ->
            listOf(
              DataProductCapability(
                kind = ok.kind,
                schemaVersion = ok.schemaVersion,
                transport = DataProductTransport.INLINE,
                attachable = false,
                fetchable = true,
                requiresRerender = false,
              )
            )
          } ?: emptyList()
        }

    override fun fetch(
      previewId: String,
      kind: String,
      params: JsonElement?,
      inline: Boolean,
    ): DataProductRegistry.Outcome {
      if (immediateOk != null && kind == immediateOk.kind) {
        return DataProductRegistry.Outcome.Ok(immediateOk)
      }
      val mode = modeForKind[kind] ?: return DataProductRegistry.Outcome.Unknown
      if (!renderObserved) {
        return DataProductRegistry.Outcome.RequiresRerender(mode)
      }
      return postRerenderOutcome
        ?: DataProductRegistry.Outcome.Ok(
          DataFetchResult(
            kind = kind,
            schemaVersion = 1,
            payload = buildJsonObject { /* deterministic empty payload */ },
          )
        )
    }

    override fun attachmentsFor(previewId: String, kinds: Set<String>) =
      emptyList<ee.schimke.composeai.daemon.protocol.DataProductAttachment>()

    override fun onRender(previewId: String, result: RenderResult) {
      onRenderCalls.incrementAndGet()
      lastOnRenderPreviewId = previewId
      lastOnRenderMetrics = result.metrics
    }

    /** Called by [TestRenderHost] when the dispatcher submits a render. */
    fun observeRenderSubmit(payload: String) {
      renderSubmits.incrementAndGet()
      lastRenderPayload = payload
      renderObserved = true
    }
  }

  /**
   * [RenderHost] for the data-fetch tests. Either completes renders instantly (writing the payload
   * back through the producer so the next `fetch` returns `Ok`) or blocks forever (driving the
   * budget-timeout branch).
   */
  private class TestRenderHost(
    private val producer: FakeProducer,
    private val blockSubmits: Boolean,
  ) : RenderHost {
    private val queue = LinkedBlockingQueue<RenderRequest>()
    private val results =
      java.util.concurrent.ConcurrentHashMap<Long, LinkedBlockingQueue<RenderResult>>()
    @Volatile private var stopped = false
    val interruptCount = AtomicInteger(0)
    private val worker =
      Thread(
          {
            while (!stopped) {
              val req =
                try {
                  queue.poll(50, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                  interruptCount.incrementAndGet()
                  Thread.currentThread().interrupt()
                  return@Thread
                } ?: continue
              when (req) {
                is RenderRequest.Render -> {
                  producer.observeRenderSubmit(req.payload)
                  if (blockSubmits) {
                    // Hold the render forever - the dispatcher's budget timer is what trips
                    // the test's data/fetch response, and we want to confirm the render is
                    // not cancelled out from under us.
                    continue
                  }
                  val result =
                    RenderResult(
                      id = req.id,
                      classLoaderHashCode = 0,
                      classLoaderName = "test",
                      metrics = mapOf("tookMs" to 3L),
                    )
                  results.computeIfAbsent(req.id) { LinkedBlockingQueue() }.put(result)
                }
                RenderRequest.Shutdown -> return@Thread
              }
            }
          },
          "data-fetch-test-render-host",
        )
        .apply { isDaemon = true }

    override fun start() {
      worker.start()
    }

    override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
      require(request is RenderRequest.Render)
      queue.put(request)
      val q = results.computeIfAbsent(request.id) { LinkedBlockingQueue() }
      val result = q.poll(timeoutMs, TimeUnit.MILLISECONDS)
      return result ?: error("TestRenderHost.submit($request) timed out")
    }

    override fun shutdown(timeoutMs: Long) {
      stopped = true
      queue.put(RenderRequest.Shutdown)
      worker.join(timeoutMs)
    }
  }

  private fun runWithServer(
    producer: FakeProducer,
    blockHostSubmits: Boolean = false,
    dataFetchRerenderBudgetMs: Long = JsonRpcServer.DEFAULT_DATA_FETCH_RERENDER_BUDGET_MS,
    block: (RpcDriver) -> Unit,
  ) {
    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = TestRenderHost(producer, blockSubmits = blockHostSubmits)
    val exitCode = AtomicInteger(-1)
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        idleTimeoutMs = 100L,
        dataProducts = producer,
        dataFetchRerenderBudgetMs = dataFetchRerenderBudgetMs,
        onExit = { code ->
          exitCode.set(code)
          exitLatch.countDown()
        },
      )
    val serverThread =
      Thread({ server.run() }, "data-fetch-rerender-server").apply { isDaemon = true }
    serverThread.start()

    val received = LinkedBlockingQueue<JsonObject>()
    val history = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
    val readerThread =
      Thread(
          {
            try {
              val reader = ContentLengthFramer(serverToClientIn)
              while (true) {
                val frame = reader.readFrame() ?: break
                val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
                history.add(obj)
                received.put(obj)
              }
            } catch (_: Throwable) {}
          },
          "data-fetch-rerender-reader",
        )
        .apply { isDaemon = true }
    readerThread.start()

    val driver =
      RpcDriver(clientToServerOut = clientToServerOut, received = received, history = history)
    try {
      block(driver)
      // Tear down - drop subscriptions / let renders drain. Skip shutdown for the
      // budget-blocked test (the host worker would block forever); fall back to closing pipes.
      if (!blockHostSubmits) {
        driver.send("""{"jsonrpc":"2.0","id":9999,"method":"shutdown"}""")
        driver.pollUntil { it["id"]?.jsonPrimitive?.intOrNull == 9999 }
        driver.send("""{"jsonrpc":"2.0","method":"exit"}""")
        assertTrue("server should exit cleanly within 10s", exitLatch.await(10, TimeUnit.SECONDS))
      }
      // Render thread interrupt invariant - fetch-driven re-renders must not interrupt the host.
      assertEquals(
        "fetch-driven re-renders must not interrupt the render thread",
        0,
        host.interruptCount.get(),
      )
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      serverThread.join(5_000)
    }
  }

  private class RpcDriver(
    private val clientToServerOut: PipedOutputStream,
    val received: LinkedBlockingQueue<JsonObject>,
    /**
     * Append-only snapshot of every message the reader has parsed. `pollUntil` drains [received]
     * but [history] retains the full timeline for "did we ever see X" assertions.
     */
    val history: List<JsonObject>,
  ) {
    fun send(text: String) {
      val payload = text.toByteArray(Charsets.UTF_8)
      clientToServerOut.write(
        "Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
      )
      clientToServerOut.write(payload)
      clientToServerOut.flush()
    }

    fun initialize() {
      send(
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
          "protocolVersion":1,
          "clientVersion":"test",
          "workspaceRoot":"/tmp",
          "moduleId":":test",
          "moduleProjectDir":"/tmp",
          "capabilities":{"visibility":true,"metrics":false}
        }}
        """
          .trimIndent()
      )
      pollUntil { it["id"]?.jsonPrimitive?.intOrNull == 1 }
        ?: error("initialize response should arrive")
      send("""{"jsonrpc":"2.0","method":"initialized","params":{}}""")
    }

    fun pollUntil(timeoutMs: Long = 10_000, matcher: (JsonObject) -> Boolean): JsonObject? {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (System.currentTimeMillis() < deadline) {
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
        val msg = received.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
        if (matcher(msg)) return msg
      }
      return null
    }
  }
}
