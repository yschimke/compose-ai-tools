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
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import ee.schimke.composeai.render.session.NotificationListener
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put

/**
 * Contract for [DaemonSemanticsFetcher]: with a fake [RenderSessionFactory] whose `renderNow`
 * writes `compose-semantics.json` sidecars (standing in for the daemon's always-on
 * ComposeSemanticsExtension), the fetcher renders the requested previews and returns each one's
 * sidecar bytes keyed by preview id (issue #1843).
 */
class DaemonSemanticsFetcherTest {

  private fun newTempFolder(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

  private fun writeDescriptor(projectDir: File) {
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
  }

  @Test
  fun `renders previews and returns their semantics sidecars`() {
    val projectDir = newTempFolder("semantics-module")
    writeDescriptor(projectDir)

    val produced =
      mapOf(
        "AlphaPreview" to """{"root":{"nodeId":"1","boundsInRoot":"0,0,4,8"}}""",
        "BetaPreview" to """{"root":{"nodeId":"2","boundsInRoot":"0,0,6,6"}}""",
      )
    val fetcher = DaemonSemanticsFetcher(factory = FakeFactory(produced))

    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        moduleName = "sample",
        previewIds = listOf("AlphaPreview", "BetaPreview"),
      )

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(setOf("AlphaPreview", "BetaPreview"), outcome.semanticsById.keys)
    assertEquals(
      produced["AlphaPreview"],
      outcome.semanticsById.getValue("AlphaPreview").toString(Charsets.UTF_8),
    )
    assertEquals(
      produced["BetaPreview"],
      outcome.semanticsById.getValue("BetaPreview").toString(Charsets.UTF_8),
    )
  }

  @Test
  fun `carries the fonts-used sidecar for previews whose backend records it`() {
    val projectDir = newTempFolder("semantics-fonts")
    writeDescriptor(projectDir)

    val fonts = mapOf("AlphaPreview" to """{"fonts":[{"requestedFamily":"serif","weight":400}]}""")
    val fetcher =
      DaemonSemanticsFetcher(
        factory =
          FakeFactory(
            produced =
              mapOf(
                "AlphaPreview" to """{"root":{"nodeId":"1","boundsInRoot":"0,0,4,8"}}""",
                "BetaPreview" to """{"root":{"nodeId":"2","boundsInRoot":"0,0,6,6"}}""",
              ),
            fontsProduced = fonts,
          )
      )

    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        moduleName = "sample",
        previewIds = listOf("AlphaPreview", "BetaPreview"),
      )

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    // Only Alpha's backend recorded font usage; Beta simply has no fonts entry.
    assertEquals(setOf("AlphaPreview"), outcome.fontsById.keys)
    assertEquals(
      fonts.getValue("AlphaPreview"),
      outcome.fontsById.getValue("AlphaPreview").toString(Charsets.UTF_8),
    )
  }

  @Test
  fun `previews whose sidecar never materialised are simply absent`() {
    val projectDir = newTempFolder("semantics-partial")
    writeDescriptor(projectDir)

    // Only Alpha produces a sidecar; Beta is rendered but writes nothing (e.g. an unsupported
    // capture). The fetcher carries what it got and omits the rest.
    val fetcher =
      DaemonSemanticsFetcher(
        factory =
          FakeFactory(
            mapOf("AlphaPreview" to """{"root":{"nodeId":"1","boundsInRoot":"0,0,1,1"}}""")
          )
      )

    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        moduleName = "sample",
        previewIds = listOf("AlphaPreview", "BetaPreview"),
      )

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(setOf("AlphaPreview"), outcome.semanticsById.keys)
  }

  @Test
  fun `clears a stale sidecar so a render that produces nothing carries no cross-run data`() {
    val projectDir = newTempFolder("semantics-stale")
    writeDescriptor(projectDir)
    // A stale sidecar from a previous run that this render does NOT reproduce (no canned content).
    val staleDir = File(projectDir, "build/compose-previews/data/BetaPreview").also { it.mkdirs() }
    File(staleDir, "compose-semantics.json")
      .writeText("""{"root":{"nodeId":"STALE","boundsInRoot":"0,0,1,1"}}""")

    val fetcher = DaemonSemanticsFetcher(factory = FakeFactory(emptyMap()))
    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        moduleName = "sample",
        previewIds = listOf("BetaPreview"),
      )

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertTrue(outcome.semanticsById.isEmpty(), "stale sidecar must not be carried")
    assertTrue(
      !File(staleDir, "compose-semantics.json").exists(),
      "stale sidecar should have been deleted before the render",
    )
  }

  @Test
  fun `missing descriptor returns DescriptorMissing`() {
    val projectDir = newTempFolder("semantics-no-descriptor")
    val fetcher = DaemonSemanticsFetcher(factory = FakeFactory(emptyMap()))

    val outcome =
      fetcher.fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("Alpha"))

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.DescriptorMissing)
  }

  @Test
  fun `open failure returns OpenFailed`() {
    val projectDir = newTempFolder("semantics-open-fails")
    writeDescriptor(projectDir)
    val fetcher = DaemonSemanticsFetcher(factory = OpenFailFactory())

    val outcome =
      fetcher.fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("Alpha"))

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.OpenFailed)
  }

  @Test
  fun `empty preview list short-circuits to an empty Ok`() {
    val projectDir = newTempFolder("semantics-empty")
    val fetcher = DaemonSemanticsFetcher(factory = OpenFailFactory())

    val outcome =
      fetcher.fetch(projectDir = projectDir, moduleName = "sample", previewIds = emptyList())

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok)
    assertTrue(outcome.semanticsById.isEmpty())
  }

  // -------------------------------------------------------------------------
  // Fake factory + session

  private class FakeFactory(
    private val produced: Map<String, String>,
    private val fontsProduced: Map<String, String> = emptyMap(),
  ) : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun open(config: RenderSessionConfig): RenderSession =
      FakeSession(
        workspaceRoot = config.workspaceRoot.absolutePath,
        produced = produced,
        fontsProduced = fontsProduced,
        dataRoot = File(config.workspaceRoot, "build/compose-previews/data"),
      )
  }

  private class OpenFailFactory : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun open(config: RenderSessionConfig): RenderSession =
      throw RenderSessionException("simulated daemon open failure")
  }

  /**
   * Minimal [RenderSession] modelling the real daemon's async render: [renderNow] writes the
   * always-on `compose-semantics.json` sidecar for every preview it has canned content for, then
   * emits a `renderFinished` notification per requested id (the signal the fetcher waits on before
   * reading the files). Every other method throws.
   */
  private class FakeSession(
    override val workspaceRoot: String,
    private val produced: Map<String, String>,
    private val dataRoot: File,
    private val fontsProduced: Map<String, String> = emptyMap(),
  ) : RenderSession {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<NotificationListener>()

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
      for (id in previewIds) {
        produced[id]?.let { content ->
          val dir = File(dataRoot, id).also { it.mkdirs() }
          File(dir, "compose-semantics.json").writeText(content)
        }
        fontsProduced[id]?.let { content ->
          val dir = File(dataRoot, id).also { it.mkdirs() }
          File(dir, "fonts-used.json").writeText(content)
        }
        // Emit renderFinished for *every* requested id — including ones that wrote no sidecar — so
        // the fetcher's wait completes (a failed render still finishes) rather than timing out.
        val params =
          kotlinx.serialization.json.buildJsonObject {
            put("id", id)
            put("pngPath", "$id.png")
          }
        listeners.forEach { it.onNotification("renderFinished", params) }
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
