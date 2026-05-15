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
import ee.schimke.composeai.render.session.RenderSessionFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Aggregation contract for [DaemonA11yFetcher]: with a fake [RenderSessionFactory] returning canned
 * a11y/atf payloads, the fetcher writes `accessibility.json` in the shape [A11yReportRenderer]
 * reads — one entry per preview, in input order, findings preserved.
 */
class DaemonA11yFetcherTest {

  private fun newTempFolder(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

  @Test
  fun `writes accessibility report with one entry per preview`() {
    val projectDir = newTempFolder("module")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    val payloads =
      mapOf(
        "AlphaPreview" to atfPayload(listOf(finding("ERROR", "TouchTargetSize", "too small"))),
        "BetaPreview" to atfPayload(emptyList()),
      )
    val fetcher = DaemonA11yFetcher(factory = FakeFactory(payloads))

    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previewIds = listOf("AlphaPreview", "BetaPreview"),
      )

    assertTrue(outcome is DaemonA11yFetcher.Outcome.Ok, "expected Ok, got $outcome")
    val report =
      Companion.json.decodeFromString(
        AccessibilityReport.serializer(),
        File(projectDir, "build/compose-previews/accessibility.json").readText(),
      )
    assertEquals("sample", report.module)
    assertEquals(listOf("AlphaPreview", "BetaPreview"), report.entries.map { it.previewId })
    assertEquals(1, report.entries[0].findings.size)
    assertEquals("ERROR", report.entries[0].findings.first().level)
    assertEquals("TouchTargetSize", report.entries[0].findings.first().type)
    assertEquals(0, report.entries[1].findings.size)
  }

  @Test
  fun `returns DescriptorMissing when daemon-launch_json is absent`() {
    val projectDir = newTempFolder("module-no-descriptor")
    val fetcher = DaemonA11yFetcher(factory = FakeFactory(emptyMap()))

    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previewIds = listOf("AlphaPreview"),
      )

    assertTrue(outcome is DaemonA11yFetcher.Outcome.DescriptorMissing)
  }

  // -------------------------------------------------------------------------
  // Fake factory + session

  private class FakeFactory(private val payloads: Map<String, JsonElement>) : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun open(config: RenderSessionConfig): RenderSession =
      FakeSession(
        workspaceRoot = config.workspaceRoot.absolutePath,
        modulePath = ":sample",
        payloads = payloads,
      )
  }

  /**
   * Minimal [RenderSession] that returns canned a11y/atf payloads. Every other method throws — the
   * fetcher only calls fetchData and close.
   */
  private class FakeSession(
    override val workspaceRoot: String,
    override val modulePath: String,
    private val payloads: Map<String, JsonElement>,
  ) : RenderSession {
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

    override fun setVisible(previewIds: List<String>) = error("unused")

    override fun setFocus(previewIds: List<String>) = error("unused")

    override fun fileChanged(path: String, kind: FileKind, changeType: ChangeType) = error("unused")

    override fun renderNow(
      previewIds: List<String>,
      tier: RenderTier,
      reason: String?,
      overrides: PreviewOverrides?,
      timeout: kotlin.time.Duration,
    ): RenderNowResult = error("unused")

    override fun fetchData(
      previewId: String,
      kind: String,
      inline: Boolean,
      params: JsonElement?,
      timeout: kotlin.time.Duration,
    ): DataFetchResult =
      DataFetchResult(kind = kind, schemaVersion = 1, payload = payloads[previewId])

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

    override fun onNotification(listener: NotificationListener): AutoCloseable = AutoCloseable {}

    override fun close() = Unit
  }

  private fun atfPayload(findings: List<AccessibilityFinding>): JsonElement = buildJsonObject {
    put(
      "findings",
      kotlinx.serialization.json.JsonArray(
        findings.map {
          buildJsonObject {
            put("level", it.level)
            put("type", it.type)
            put("message", it.message)
          }
        }
      ),
    )
  }

  private fun finding(level: String, type: String, message: String) =
    AccessibilityFinding(level = level, type = type, message = message)

  companion object {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
  }
}
