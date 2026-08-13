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
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    assertTrue(outcome.atfAvailable, "expected atfAvailable=true on successful fetches")
    val report =
      Companion.json.decodeFromString(
        AccessibilityReport.serializer(),
        File(projectDir, "build/compose-previews/accessibility.json").readText(),
      )
    assertEquals("sample", report.module)
    assertNull(report.status, "successful run should not stamp a status")
    assertEquals(listOf("AlphaPreview", "BetaPreview"), report.entries.map { it.previewId })
    assertEquals(1, report.entries[0].findings.size)
    assertEquals("ERROR", report.entries[0].findings.first().level)
    assertEquals("TouchTargetSize", report.entries[0].findings.first().type)
    assertEquals(0, report.entries[1].findings.size)
  }

  @Test
  fun `preserves per-node ref from a11y-hierarchy into the aggregated report`() {
    // #1784 — the aggregate decodes a11y-hierarchy.json through this module's AccessibilityNode
    // mirror and re-encodes it into accessibility.json. The mirror must carry `ref` or the stable
    // handles silently drop out of `compose-preview a11y` output.
    val projectDir = newTempFolder("module-ref")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    val dataDir = File(projectDir, "build/compose-previews/data/AlphaPreview")
    dataDir.mkdirs()
    File(dataDir, "a11y-hierarchy.json")
      .writeText(
        """{"nodes":[{"label":"Submit","ref":"a/role:Button[0]","role":"Button","boundsInScreen":"0,0,10,10"}]}"""
      )

    val fetcher =
      DaemonA11yFetcher(factory = FakeFactory(mapOf("AlphaPreview" to atfPayload(emptyList()))))
    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previewIds = listOf("AlphaPreview"),
      )

    assertTrue(outcome is DaemonA11yFetcher.Outcome.Ok, "expected Ok, got $outcome")
    val report =
      Companion.json.decodeFromString(
        AccessibilityReport.serializer(),
        File(projectDir, "build/compose-previews/accessibility.json").readText(),
      )
    assertEquals("a/role:Button[0]", report.entries.single().nodes.single().ref)
  }

  @Test
  fun `returns DescriptorMissing when daemon-launch_json is absent and writes atf-unavailable sidecar`() {
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
    // The python PR-comment helper reads accessibility.json directly, so we stamp a sidecar
    // even when the session can't open — otherwise the workflow can't tell apart "no module
    // tried" from "the daemon descriptor was missing."
    val reportFile = File(projectDir, "build/compose-previews/accessibility.json")
    assertTrue(reportFile.exists(), "expected an atf-unavailable sidecar")
    val report =
      Companion.json.decodeFromString(AccessibilityReport.serializer(), reportFile.readText())
    assertEquals(A11Y_REPORT_STATUS_ATF_UNAVAILABLE, report.status)
    assertEquals(emptyList(), report.entries.map { it.previewId })
  }

  @Test
  fun `stamps atf-unavailable status when every per-preview fetch errors`() {
    val projectDir = newTempFolder("module-fetches-fail")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    // Null payload simulates a fetch that errored (the fake session below returns null payloads
    // verbatim; the production fetcher wraps DataProductException / RenderSessionException to
    // the same `null` here).
    val fetcher =
      DaemonA11yFetcher(factory = FakeFactory(mapOf("AlphaPreview" to null, "BetaPreview" to null)))

    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previewIds = listOf("AlphaPreview", "BetaPreview"),
      )

    assertTrue(outcome is DaemonA11yFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertFalse(outcome.atfAvailable, "no fetches succeeded → atfAvailable should be false")
    val report =
      Companion.json.decodeFromString(
        AccessibilityReport.serializer(),
        File(projectDir, "build/compose-previews/accessibility.json").readText(),
      )
    assertEquals(A11Y_REPORT_STATUS_ATF_UNAVAILABLE, report.status)
  }

  @Test
  fun `partial fetch success leaves status null`() {
    val projectDir = newTempFolder("module-partial")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    val fetcher =
      DaemonA11yFetcher(
        factory =
          FakeFactory(mapOf("AlphaPreview" to atfPayload(emptyList()), "BetaPreview" to null))
      )

    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previewIds = listOf("AlphaPreview", "BetaPreview"),
      )

    assertTrue(outcome is DaemonA11yFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertTrue(outcome.atfAvailable, "one successful fetch is enough to keep atfAvailable=true")
    val report =
      Companion.json.decodeFromString(
        AccessibilityReport.serializer(),
        File(projectDir, "build/compose-previews/accessibility.json").readText(),
      )
    assertNull(report.status, "partial success should not stamp a status")
  }

  // ---------- narrowed runs merge rather than clobber (#3742) ----------

  @Test
  fun `a narrowed fetch carries forward the entries it did not refetch`() {
    val projectDir = newTempFolder("module-merge")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    writeExistingReport(
      projectDir,
      entry("AlphaPreview", finding("ERROR", "TouchTargetSize", "stale")),
      entry("BetaPreview", finding("WARNING", "TextContrast", "keep me")),
    )

    val fetcher =
      DaemonA11yFetcher(
        factory =
          FakeFactory(
            mapOf(
              "AlphaPreview" to atfPayload(listOf(finding("ERROR", "TouchTargetSize", "fresh")))
            )
          )
      )
    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previewIds = listOf("AlphaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
      )

    assertTrue(outcome is DaemonA11yFetcher.Outcome.Ok, "expected Ok, got $outcome")
    val report = readReport(projectDir)
    // `accessibility.json` is a per-module report: `a11y --id AlphaPreview` must not delete the
    // module's other findings on its way to printing one row.
    assertEquals(listOf("AlphaPreview", "BetaPreview"), report.entries.map { it.previewId })
    assertEquals("fresh", report.entries[0].findings.single().message)
    assertEquals("keep me", report.entries[1].findings.single().message)
    // Every declared preview ended up covered, so the report speaks for the whole module again.
    assertFalse(report.partial, "merged entries cover the module")
  }

  @Test
  fun `a first-ever narrowed fetch marks the report partial`() {
    val projectDir = newTempFolder("module-partial-flag")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    val fetcher =
      DaemonA11yFetcher(factory = FakeFactory(mapOf("AlphaPreview" to atfPayload(emptyList()))))
    fetcher.fetch(
      projectDir = projectDir,
      modulePath = "",
      moduleName = "sample",
      previewIds = listOf("AlphaPreview"),
      modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
    )

    // Nothing on disk to merge with, so the report genuinely covers one of two previews. Without
    // the flag, a consumer reads `BetaPreview`'s absence as "checked, found nothing" — the module
    // -wide report would quietly certify a preview ATF never looked at.
    val report = readReport(projectDir)
    assertEquals(listOf("AlphaPreview"), report.entries.map { it.previewId })
    assertTrue(report.partial, "a report covering 1 of 2 previews is partial")
  }

  @Test
  fun `a full fetch clears the partial flag`() {
    val projectDir = newTempFolder("module-partial-cleared")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    val fetcher =
      DaemonA11yFetcher(
        factory =
          FakeFactory(
            mapOf(
              "AlphaPreview" to atfPayload(emptyList()),
              "BetaPreview" to atfPayload(emptyList()),
            )
          )
      )
    fetcher.fetch(
      projectDir = projectDir,
      modulePath = "",
      moduleName = "sample",
      previewIds = listOf("AlphaPreview", "BetaPreview"),
    )

    assertFalse(readReport(projectDir).partial)
  }

  @Test
  fun `an empty entry from an unavailable report is not carried forward as clean`() {
    val projectDir = newTempFolder("module-merge-status")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    writeExistingReport(
      projectDir,
      status = A11Y_REPORT_STATUS_ATF_UNAVAILABLE,
      entries = arrayOf(entry("AlphaPreview"), entry("BetaPreview")),
    )

    DaemonA11yFetcher(factory = FakeFactory(mapOf("AlphaPreview" to atfPayload(emptyList()))))
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previewIds = listOf("AlphaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
      )

    // `BetaPreview`'s entry records a fetch that produced nothing under a stamp saying nothing ran.
    // Republishing it now that this run's stamp is gone would present it as "checked, found
    // nothing" — so it's dropped, and `partial` says out loud that Beta is uncovered.
    val report = readReport(projectDir)
    assertEquals(listOf("AlphaPreview"), report.entries.map { it.previewId })
    assertNull(report.status)
    assertTrue(report.partial, "Beta is uncovered once its uninformative entry is dropped")
  }

  @Test
  fun `hierarchy nodes are not proof that ATF ran`() {
    val projectDir = newTempFolder("module-merge-nodes")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    // `readNodes` populates `nodes` off `a11y-hierarchy.json` for every preview whether or not its
    // ATF fetch succeeded, and that file survives from earlier renders — so a failed entry can
    // carry a full node list. Treating that as evidence would launder it into a clean row.
    writeExistingReport(
      projectDir,
      status = A11Y_REPORT_STATUS_ATF_UNAVAILABLE,
      entries =
        arrayOf(
          entry("AlphaPreview"),
          AccessibilityEntry(
            previewId = "BetaPreview",
            findings = emptyList(),
            nodes = listOf(AccessibilityNode(label = "Submit", boundsInScreen = "0,0,10,10")),
          ),
        ),
    )

    DaemonA11yFetcher(factory = FakeFactory(mapOf("AlphaPreview" to atfPayload(emptyList()))))
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previewIds = listOf("AlphaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
      )

    val report = readReport(projectDir)
    assertEquals(listOf("AlphaPreview"), report.entries.map { it.previewId })
    assertTrue(report.partial, "Beta is uncovered — its node list proves nothing about ATF")
  }

  @Test
  fun `real findings under an unavailable stamp still carry forward`() {
    val projectDir = newTempFolder("module-merge-status-real")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    // A stamp can land on top of a previous run's genuine results — a failed session open stamps
    // the report without touching the entries it carried. Those findings are data; only the empty
    // ones are suspect.
    writeExistingReport(
      projectDir,
      status = A11Y_REPORT_STATUS_ATF_UNAVAILABLE,
      entries =
        arrayOf(entry("AlphaPreview"), entry("BetaPreview", finding("ERROR", "Contrast", "real"))),
    )

    DaemonA11yFetcher(factory = FakeFactory(mapOf("AlphaPreview" to atfPayload(emptyList()))))
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previewIds = listOf("AlphaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
      )

    val report = readReport(projectDir)
    // Alpha's own empty entry was the uninformative kind, so it drops out of the carried set and
    // comes back from this run's fetch — hence the order. Beta's findings survive untouched.
    assertEquals(setOf("AlphaPreview", "BetaPreview"), report.entries.map { it.previewId }.toSet())
    assertEquals(
      "real",
      report.entries.single { it.previewId == "BetaPreview" }.findings.single().message,
    )
    assertFalse(report.partial, "both previews are covered")
  }

  @Test
  fun `a full fetch still replaces the report wholesale`() {
    val projectDir = newTempFolder("module-no-merge")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    writeExistingReport(projectDir, entry("GonePreview", finding("ERROR", "Stale", "deleted")))

    val fetcher =
      DaemonA11yFetcher(factory = FakeFactory(mapOf("AlphaPreview" to atfPayload(emptyList()))))
    fetcher.fetch(
      projectDir = projectDir,
      modulePath = "",
      moduleName = "sample",
      previewIds = listOf("AlphaPreview"),
    )

    // The unnarrowed run is the only thing that ever evicts an entry for a preview that no longer
    // exists — so it keeps clobbering.
    assertEquals(listOf("AlphaPreview"), readReport(projectDir).entries.map { it.previewId })
  }

  @Test
  fun `a narrowed run that cannot open the session keeps the existing entries and flags itself`() {
    val projectDir = newTempFolder("module-merge-open-fails")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    writeExistingReport(
      projectDir,
      entry("BetaPreview", finding("WARNING", "TextContrast", "keep")),
    )

    val outcome =
      DaemonA11yFetcher(factory = OpenFailFactory())
        .fetch(
          projectDir = projectDir,
          modulePath = "",
          moduleName = "sample",
          previewIds = listOf("AlphaPreview"),
          modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
        )

    assertTrue(outcome is DaemonA11yFetcher.Outcome.OpenFailed)
    val report = readReport(projectDir)
    // Failing to reach the daemon teaches us nothing about the previews we weren't going to fetch,
    // so they survive — but the run still stamps itself unavailable so the CLI exits 2.
    assertEquals(listOf("BetaPreview"), report.entries.map { it.previewId })
    assertEquals(A11Y_REPORT_STATUS_ATF_UNAVAILABLE, report.status)
  }

  @Test
  fun `an unreadable existing report degrades to a plain write`() {
    val projectDir = newTempFolder("module-merge-corrupt")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    File(projectDir, "build/compose-previews/accessibility.json").writeText("{ not json")

    val fetcher =
      DaemonA11yFetcher(factory = FakeFactory(mapOf("AlphaPreview" to atfPayload(emptyList()))))
    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previewIds = listOf("AlphaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
      )

    assertTrue(outcome is DaemonA11yFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(listOf("AlphaPreview"), readReport(projectDir).entries.map { it.previewId })
  }

  @Test
  fun `OpenFailed writes atf-unavailable sidecar`() {
    val projectDir = newTempFolder("module-open-fails")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    val fetcher = DaemonA11yFetcher(factory = OpenFailFactory())

    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previewIds = listOf("AlphaPreview"),
      )

    assertTrue(outcome is DaemonA11yFetcher.Outcome.OpenFailed)
    val reportFile = File(projectDir, "build/compose-previews/accessibility.json")
    assertTrue(reportFile.exists())
    val report =
      Companion.json.decodeFromString(AccessibilityReport.serializer(), reportFile.readText())
    assertEquals(A11Y_REPORT_STATUS_ATF_UNAVAILABLE, report.status)
  }

  // -------------------------------------------------------------------------
  // Fake factory + session

  private class FakeFactory(private val payloads: Map<String, JsonElement?>) :
    RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun open(config: RenderSessionConfig): RenderSession =
      FakeSession(
        workspaceRoot = config.workspaceRoot.absolutePath,
        modulePath = ":sample",
        payloads = payloads,
      )
  }

  /**
   * Factory that always fails `open()` — drives the [DaemonA11yFetcher.Outcome.OpenFailed] branch.
   */
  private class OpenFailFactory : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun open(config: RenderSessionConfig): RenderSession =
      throw RenderSessionException("simulated daemon open failure")
  }

  /**
   * Minimal [RenderSession] that returns canned a11y/atf payloads. Every other method throws — the
   * fetcher only calls fetchData and close.
   */
  private class FakeSession(
    override val workspaceRoot: String,
    override val modulePath: String,
    private val payloads: Map<String, JsonElement?>,
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

  private fun entry(previewId: String, vararg findings: AccessibilityFinding) =
    AccessibilityEntry(previewId = previewId, findings = findings.toList())

  private fun writeExistingReport(
    projectDir: File,
    vararg entries: AccessibilityEntry,
    status: String? = null,
  ) {
    File(projectDir, "build/compose-previews/accessibility.json")
      .writeText(
        Companion.json.encodeToString(
          AccessibilityReport.serializer(),
          AccessibilityReport(module = "sample", entries = entries.toList(), status = status),
        )
      )
  }

  private fun readReport(projectDir: File): AccessibilityReport =
    Companion.json.decodeFromString(
      AccessibilityReport.serializer(),
      File(projectDir, "build/compose-previews/accessibility.json").readText(),
    )

  companion object {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
  }
}
