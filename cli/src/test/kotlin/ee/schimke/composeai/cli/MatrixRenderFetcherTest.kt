package ee.schimke.composeai.cli

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
import ee.schimke.composeai.mcp.MatrixCell
import ee.schimke.composeai.render.session.NotificationListener
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put

/**
 * Contract for [MatrixRenderFetcher]: with a fake [RenderSessionFactory] whose `renderNow` writes a
 * distinct PNG file per cell's overrides and emits the matching `renderFinished`, the fetcher
 * renders each cell serially and returns its bytes — null for rejected / unrendered cells
 * (issue #1788).
 */
class MatrixRenderFetcherTest {

  private fun newTempFolder(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

  private fun writeDescriptor(projectDir: File) {
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
  }

  @Test
  fun `renders one png per cell with distinct bytes`() {
    val projectDir = newTempFolder("matrix-module")
    writeDescriptor(projectDir)
    val fetcher = MatrixRenderFetcher(factory = FakeFactory())

    val cells = listOf(MatrixCell(uiMode = "light"), MatrixCell(uiMode = "dark"))
    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        moduleName = "sample",
        previewId = "com.example.Red",
        cells = cells,
      )

    assertTrue(outcome is MatrixRenderFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(2, outcome.cells.size)
    assertEquals(cells, outcome.cells.map { it.cell })
    val light = assertNotNull(outcome.cells[0].png)
    val dark = assertNotNull(outcome.cells[1].png)
    assertFalse(light.contentEquals(dark), "different overrides must render different bytes")
  }

  @Test
  fun `a rejected cell carries a null png while others render`() {
    val projectDir = newTempFolder("matrix-rejected")
    writeDescriptor(projectDir)
    // The fake rejects any cell whose device is "id:bad".
    val fetcher = MatrixRenderFetcher(factory = FakeFactory(rejectDevice = "id:bad"))

    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        moduleName = "sample",
        previewId = "com.example.Red",
        cells = listOf(MatrixCell(device = "id:pixel_5"), MatrixCell(device = "id:bad")),
      )

    assertTrue(outcome is MatrixRenderFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertNotNull(outcome.cells[0].png)
    assertNull(outcome.cells[1].png, "rejected cell must have no png")
  }

  @Test
  fun `identical overrides across cells produce identical bytes`() {
    val projectDir = newTempFolder("matrix-identical")
    writeDescriptor(projectDir)
    val fetcher = MatrixRenderFetcher(factory = FakeFactory())

    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        moduleName = "sample",
        previewId = "com.example.Red",
        cells = listOf(MatrixCell(locale = "en"), MatrixCell(locale = "en")),
      )

    assertTrue(outcome is MatrixRenderFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertContentEquals(outcome.cells[0].png, outcome.cells[1].png)
  }

  @Test
  fun `a renderNow failure fails the cell fast without waiting the render timeout`() {
    val projectDir = newTempFolder("matrix-render-throws")
    writeDescriptor(projectDir)
    val fetcher = MatrixRenderFetcher(factory = FakeFactory(throwOnRender = true))

    var outcome: MatrixRenderFetcher.Outcome? = null
    val elapsedMs =
      kotlin.system.measureTimeMillis {
        outcome =
          fetcher.fetch(
            projectDir = projectDir,
            moduleName = "sample",
            previewId = "com.example.Red",
            cells = listOf(MatrixCell(uiMode = "light")),
          )
      }

    // The render timeout is 180s; a renderNow that threw must not block on a render that never
    // started, so the call returns near-instantly rather than waiting it out.
    assertTrue(
      elapsedMs < 30_000,
      "renderNow failure must not wait the render timeout (took ${elapsedMs}ms)",
    )
    val ok = outcome
    assertTrue(ok is MatrixRenderFetcher.Outcome.Ok, "expected Ok, got $ok")
    assertNull(ok.cells.single().png, "a renderNow failure must yield a null png")
  }

  @Test
  fun `missing descriptor returns DescriptorMissing`() {
    val projectDir = newTempFolder("matrix-no-descriptor")
    val outcome =
      MatrixRenderFetcher(factory = FakeFactory())
        .fetch(
          projectDir = projectDir,
          moduleName = "sample",
          previewId = "com.example.Red",
          cells = listOf(MatrixCell(uiMode = "light")),
        )
    assertTrue(outcome is MatrixRenderFetcher.Outcome.DescriptorMissing)
  }

  @Test
  fun `open failure returns OpenFailed`() {
    val projectDir = newTempFolder("matrix-open-fails")
    writeDescriptor(projectDir)
    val outcome =
      MatrixRenderFetcher(factory = OpenFailFactory())
        .fetch(
          projectDir = projectDir,
          moduleName = "sample",
          previewId = "com.example.Red",
          cells = listOf(MatrixCell(uiMode = "light")),
        )
    assertTrue(outcome is MatrixRenderFetcher.Outcome.OpenFailed)
  }

  // -------------------------------------------------------------------------
  // Fake factory + session

  private class FakeFactory(
    private val rejectDevice: String? = null,
    private val throwOnRender: Boolean = false,
  ) : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun open(config: RenderSessionConfig): RenderSession =
      FakeSession(
        renderRoot = File(config.workspaceRoot, "build/compose-previews/renders"),
        rejectDevice = rejectDevice,
        throwOnRender = throwOnRender,
      )
  }

  private class OpenFailFactory : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun open(config: RenderSessionConfig): RenderSession =
      throw RenderSessionException("simulated daemon open failure")
  }

  /**
   * Minimal [RenderSession] modelling the daemon's async render: [renderNow] writes a PNG whose
   * bytes encode the cell's overrides (so distinct overrides yield distinct bytes, identical ones
   * yield identical bytes), then emits the `renderFinished` the fetcher waits on. A cell whose
   * device matches [rejectDevice] is rejected instead.
   */
  private class FakeSession(
    private val renderRoot: File,
    private val rejectDevice: String?,
    private val throwOnRender: Boolean = false,
  ) : RenderSession {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<NotificationListener>()
    private var counter = 0

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
      val id = previewIds.single()
      if (throwOnRender) throw RenderSessionException("simulated renderNow transport error")
      if (rejectDevice != null && overrides?.device == rejectDevice) {
        return RenderNowResult(
          queued = emptyList(),
          rejected = listOf(RejectedRender(id, "bad device")),
        )
      }
      // Bytes derived purely from the overrides, so identical cells share bytes and different cells
      // diverge — exactly the property the fetcher's per-cell hashing relies on.
      val content =
        "png:${overrides?.uiMode}:${overrides?.localeTag}:${overrides?.fontScale}:${overrides?.device}"
      renderRoot.mkdirs()
      val file =
        File(renderRoot, "$id-${counter++}.png").apply { writeBytes(content.toByteArray()) }
      val params =
        kotlinx.serialization.json.buildJsonObject {
          put("id", id)
          put("pngPath", file.absolutePath)
        }
      listeners.forEach { it.onNotification("renderFinished", params) }
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
    ): ExtensionsEnableResult = ExtensionsEnableResult(newlyEnabled = ids)

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
