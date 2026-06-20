package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataSubscribeResult
import ee.schimke.composeai.daemon.protocol.ExtensionsDisableResult
import ee.schimke.composeai.daemon.protocol.ExtensionsEnableResult
import ee.schimke.composeai.daemon.protocol.ExtensionsListResult
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.HistoryDiffMode
import ee.schimke.composeai.daemon.protocol.HistoryDiffResult
import ee.schimke.composeai.daemon.protocol.HistoryListParams
import ee.schimke.composeai.daemon.protocol.HistoryListResult
import ee.schimke.composeai.daemon.protocol.HistoryReadResultDto
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.Manifest
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingEncodeResult
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingStartResult
import ee.schimke.composeai.daemon.protocol.RecordingStopResult
import ee.schimke.composeai.daemon.protocol.RejectedRender
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.render.session.NotificationListener
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ServeRenderHostTest {

  private val previewId = "com.example.Red"

  private fun newRenderRoot(): File =
    java.nio.file.Files.createTempDirectory("serve-host").toFile().also { it.deleteOnExit() }

  private fun host(session: RenderSession): ServeRenderHost =
    ServeRenderHost(
      session = session,
      previews = listOf(ServePreview(previewId, "Red")),
      renderTimeoutSeconds = 30,
    )

  @Test
  fun `identical requests are served from cache after one render`() {
    val session = FakeSession(newRenderRoot())
    host(session).use { h ->
      val first = h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      val second = h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertTrue(first is RenderOutcome.Ok)
      assertTrue(second is RenderOutcome.Ok)
      assertContentEquals(first.png, second.png)
      assertEquals(1, session.renderCount.get(), "second identical request must hit the cache")
    }
  }

  @Test
  fun `different overrides each render`() {
    val session = FakeSession(newRenderRoot())
    host(session).use { h ->
      h.render(previewId, PreviewOverrides(uiMode = UiMode.LIGHT))
      h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
      assertEquals(2, session.renderCount.get())
    }
  }

  @Test
  fun `concurrent identical requests coalesce to a single render`() {
    val session = FakeSession(newRenderRoot())
    host(session).use { h ->
      val threads = 16
      val pool = Executors.newFixedThreadPool(threads)
      val start = CountDownLatch(1)
      val results = CopyOnWriteArrayList<RenderOutcome>()
      repeat(threads) {
        pool.submit {
          start.await()
          results.add(h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK)))
        }
      }
      start.countDown()
      pool.shutdown()
      assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "renders did not finish")

      assertEquals(threads, results.size)
      assertTrue(results.all { it is RenderOutcome.Ok })
      assertEquals(1, session.renderCount.get(), "identical concurrent renders must coalesce")
    }
  }

  @Test
  fun `unknown preview id is NotFound without rendering`() {
    val session = FakeSession(newRenderRoot())
    host(session).use { h ->
      assertEquals(RenderOutcome.NotFound, h.render("com.example.Missing", PreviewOverrides()))
      assertEquals(0, session.renderCount.get())
    }
  }

  @Test
  fun `a late renderFinished from a timed-out render does not corrupt the next render`() {
    // Render 1 emits nothing → it times out (the daemon still owes a late renderFinished). Render 2
    // (the daemon catching up) emits the timed-out render's STALE event first, then its own FRESH
    // event. The stale one must be drained, not cached/served under render 2's override key.
    val session =
      FakeSession(
        newRenderRoot(),
        renderHook = { call, emit ->
          if (call == 2) {
            emit("STALE".toByteArray())
            emit("FRESH".toByteArray())
          }
          // call 1: emit nothing → render times out
        },
      )
    ServeRenderHost(
        session = session,
        previews = listOf(ServePreview(previewId, "Red")),
        renderTimeoutSeconds = 1,
      )
      .use { h ->
        val first = h.render(previewId, PreviewOverrides(uiMode = UiMode.LIGHT))
        assertTrue(first is RenderOutcome.Failed, "first render should time out, got $first")

        val second = h.render(previewId, PreviewOverrides(uiMode = UiMode.DARK))
        assertTrue(second is RenderOutcome.Ok, "second render should succeed, got $second")
        assertEquals(
          "FRESH",
          second.png.decodeToString(),
          "stale event from the timed-out render must not be served for the new overrides",
        )
      }
  }

  @Test
  fun `a rejected render surfaces as Failed`() {
    val session = FakeSession(newRenderRoot(), rejectAll = true)
    host(session).use { h ->
      val outcome = h.render(previewId, PreviewOverrides())
      assertTrue(outcome is RenderOutcome.Failed, "expected Failed, got $outcome")
    }
  }

  // -------------------------------------------------------------------------

  /**
   * Minimal [RenderSession]: writes a PNG whose bytes encode the overrides, emits renderFinished.
   *
   * [renderHook] (when set) overrides the default emit-immediately behaviour: it receives the
   * 1-based call index and an `emit` that writes given bytes to a fresh PNG and fires
   * `renderFinished` for the preview. A hook that emits nothing models a render that times out (the
   * daemon owes a late event), letting tests drive the stale-event path.
   */
  private class FakeSession(
    private val renderRoot: File,
    private val rejectAll: Boolean = false,
    private val renderHook: ((call: Int, emit: (ByteArray) -> Unit) -> Unit)? = null,
  ) : RenderSession {
    val renderCount = AtomicInteger(0)
    private val listeners = CopyOnWriteArrayList<NotificationListener>()
    private val counter = AtomicInteger(0)

    private fun emitFinished(id: String, bytes: ByteArray) {
      renderRoot.mkdirs()
      val file =
        File(renderRoot, "$id-${counter.incrementAndGet()}.png").apply { writeBytes(bytes) }
      val params = buildJsonObject {
        put("id", id)
        put("pngPath", file.absolutePath)
      }
      listeners.forEach { it.onNotification("renderFinished", params) }
    }

    override val workspaceRoot: String = renderRoot.absolutePath
    override val modulePath: String = ":sample"
    override val initializeResult: InitializeResult =
      InitializeResult(
        protocolVersion = 2,
        daemonVersion = "fake",
        pid = 0,
        capabilities =
          ServerCapabilities(
            incrementalDiscovery = false,
            sandboxRecycle = false,
            leakDetection = emptyList(),
          ),
        classpathFingerprint = "",
        manifest = Manifest(path = "", previewCount = 0),
      )
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun setVisible(previewIds: List<String>) = Unit

    override fun setFocus(previewIds: List<String>) = Unit

    override fun fileChanged(path: String, kind: FileKind, changeType: ChangeType) = Unit

    override fun renderNow(
      previewIds: List<String>,
      tier: RenderTier,
      reason: String?,
      overrides: PreviewOverrides?,
      timeout: kotlin.time.Duration,
    ): RenderNowResult {
      val call = renderCount.incrementAndGet()
      val id = previewIds.single()
      if (rejectAll) {
        return RenderNowResult(queued = emptyList(), rejected = listOf(RejectedRender(id, "nope")))
      }
      val hook = renderHook
      if (hook != null) {
        hook(call) { bytes -> emitFinished(id, bytes) }
      } else {
        val content = "png:${overrides?.uiMode}:${overrides?.localeTag}:${overrides?.device}"
        emitFinished(id, content.toByteArray())
      }
      return RenderNowResult(queued = previewIds, rejected = emptyList())
    }

    override fun fetchData(
      previewId: String,
      kind: String,
      inline: Boolean,
      params: JsonElement?,
      timeout: kotlin.time.Duration,
    ): DataFetchResult = error("unused")

    override fun subscribeData(
      previewId: String,
      kind: String,
      params: JsonElement?,
      timeout: kotlin.time.Duration,
    ): DataSubscribeResult = error("unused")

    override fun unsubscribeData(
      previewId: String,
      kind: String,
      timeout: kotlin.time.Duration,
    ): DataSubscribeResult = error("unused")

    override fun listExtensions(timeout: kotlin.time.Duration): ExtensionsListResult =
      error("unused")

    override fun enableExtensions(
      ids: List<String>,
      timeout: kotlin.time.Duration,
    ): ExtensionsEnableResult = error("unused")

    override fun disableExtensions(
      ids: List<String>,
      timeout: kotlin.time.Duration,
    ): ExtensionsDisableResult = error("unused")

    override fun historyList(
      params: HistoryListParams,
      timeout: kotlin.time.Duration,
    ): HistoryListResult = error("unused")

    override fun historyRead(
      entryId: String,
      inline: Boolean,
      timeout: kotlin.time.Duration,
    ): HistoryReadResultDto = error("unused")

    override fun historyDiff(
      fromId: String,
      toId: String,
      mode: HistoryDiffMode,
      timeout: kotlin.time.Duration,
    ): HistoryDiffResult = error("unused")

    override fun recordingStart(
      previewId: String,
      fps: Int?,
      scale: Float?,
      overrides: PreviewOverrides?,
      timeout: kotlin.time.Duration,
    ): RecordingStartResult = error("unused")

    override fun recordingScript(recordingId: String, events: List<RecordingScriptEvent>) =
      error("unused")

    override fun recordingStop(
      recordingId: String,
      timeout: kotlin.time.Duration,
    ): RecordingStopResult = error("unused")

    override fun recordingEncode(
      recordingId: String,
      format: RecordingFormat,
      timeout: kotlin.time.Duration,
    ): RecordingEncodeResult = error("unused")

    override fun onNotification(listener: NotificationListener): AutoCloseable {
      listeners.add(listener)
      return AutoCloseable { listeners.remove(listener) }
    }

    override fun close() = Unit
  }
}
