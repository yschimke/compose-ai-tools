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
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
  fun `carries a PreviewParameter fan-out's semantics from the bare id`() {
    // Issue #3049: `bundle pack --with-semantics` dropped every `@PreviewParameter` preview's a11y
    // tree on a build predating the daemon's `@PreviewParameter` support — the parameterless
    // `(Composer, int)` lookup threw `NoSuchMethodException`, so no sidecar was written at all.
    // Once the daemon resolves the annotation it renders one frame of the provider's *first* value
    // under the **bare** function id (it does not fan out per value — that stays with the
    // standalone
    // renderer), so the sidecar lands at `build/compose-previews/data/<id>/` exactly like a plain
    // preview's. This guards that contract from the fetcher's side: a fan-out id is carried with no
    // per-value directory resolution, because none is written.
    val projectDir = newTempFolder("semantics-fanout")
    writeDescriptor(projectDir)

    val logs = mutableListOf<String>()
    val fanoutTree = """{"root":{"nodeId":"searchbar","boundsInRoot":"0,0,48,12"}}"""
    val fetcher =
      DaemonSemanticsFetcher(
        // The daemon writes the first value's sidecar under the bare id, keyed just like any other
        // preview — no `SearchBarPreview_Light` / `_PARAM_0` leaf is ever produced.
        factory = FakeFactory(mapOf("SearchBarPreview" to fanoutTree)),
        onLog = { logs += it },
      )

    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        moduleName = "sample",
        previewIds = listOf("SearchBarPreview"),
      )

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(setOf("SearchBarPreview"), outcome.semanticsById.keys)
    assertEquals(
      fanoutTree,
      outcome.semanticsById.getValue("SearchBarPreview").toString(Charsets.UTF_8),
    )
    assertTrue(
      logs.none { "no compose-semantics.json for 'SearchBarPreview'" in it },
      "a fan-out's bare-id sidecar must be carried, not reported missing, got: $logs",
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
  fun `a renderFailed preview releases the wait instead of burning the render budget`() {
    val projectDir = newTempFolder("semantics-render-failed")
    writeDescriptor(projectDir)

    // Alpha renders; Beta's composition throws, so the daemon emits `renderFailed` — the *other*
    // terminal event — and never a `renderFinished`. This is what a Glance composable reached
    // through the plain `androidx.compose.ui.tooling.preview.Preview` annotation does (jetchat's
    // MessagesWidget previews): it dies in the Compose applier within seconds. The fetcher used to
    // wait only on `renderFinished`, so one such preview sat out the entire 180s batch budget and
    // failed the whole catalog pack.
    val logs = mutableListOf<String>()
    val fetcher =
      DaemonSemanticsFetcher(
        factory =
          FakeFactory(
            produced =
              mapOf("AlphaPreview" to """{"root":{"nodeId":"1","boundsInRoot":"0,0,4,8"}}"""),
            failedIds = mapOf("BetaPreview" to "java.lang.ClassCastException: GlanceNode"),
          ),
        onLog = { logs += it },
      )

    val startedAt = System.nanoTime()
    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        moduleName = "sample",
        previewIds = listOf("AlphaPreview", "BetaPreview"),
      )
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(setOf("AlphaPreview"), outcome.semanticsById.keys)
    // The failure must be reported, not swallowed into a generic timeout.
    assertTrue(
      logs.any { "render failed for 'BetaPreview'" in it && "GlanceNode" in it },
      "the daemon's failure message must be surfaced, got: $logs",
    )
    assertTrue(
      logs.none { "timed out" in it },
      "a reported failure must not degrade into a timeout, got: $logs",
    )
    // Guard the regression directly: with the bug, this fetch blocks for the full 180s budget.
    assertTrue(elapsedMs < 30_000, "fetch should return promptly, took ${elapsedMs}ms")
  }

  @Test
  fun `uses the configured render timeout while waiting for terminal notifications`() {
    val projectDir = newTempFolder("semantics-timeout")
    writeDescriptor(projectDir)

    val logs = mutableListOf<String>()
    val fetcher =
      DaemonSemanticsFetcher(
        factory = FakeFactory(produced = emptyMap(), silentIds = setOf("SlowPreview")),
        onLog = { logs += it },
        renderTimeout = 10.milliseconds,
      )

    val startedAt = System.nanoTime()
    val outcome =
      fetcher.fetch(
        projectDir = projectDir,
        moduleName = "sample",
        previewIds = listOf("SlowPreview"),
      )
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertTrue(
      logs.any { "timed out after 10ms" in it && "SlowPreview" in it },
      "configured timeout and pending render should be logged, got: $logs",
    )
    assertTrue(elapsedMs < 5_000, "configured timeout should return promptly, took ${elapsedMs}ms")
  }

  @Test
  fun `render timeout is an inactivity window, not a batch-wide budget`() {
    // Issue #2948: a wide catalog (a component library fanning out across many themes) renders far
    // more previews than fit inside a single `--timeout`, yet each individual render still lands
    // quickly. The daemon here completes one render every 20ms across 15 previews (~300ms total),
    // well past the 150ms window — but never idle for that long. Every semantics sidecar must still
    // be collected: the old batch-wide budget cut the batch off mid-flight and silently dropped
    // roughly a third of the catalog's semantics.
    val projectDir = newTempFolder("semantics-inactivity")
    writeDescriptor(projectDir)

    val ids = (1..15).map { "Preview$it" }
    val produced = ids.associateWith { """{"root":{"nodeId":"$it","boundsInRoot":"0,0,4,8"}}""" }
    val logs = mutableListOf<String>()
    val fetcher =
      DaemonSemanticsFetcher(
        factory = FakeFactory(produced = produced, staggerMs = 20),
        onLog = { logs += it },
        renderTimeout = 150.milliseconds,
      )

    val outcome = fetcher.fetch(projectDir = projectDir, moduleName = "sample", previewIds = ids)

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(
      ids.toSet(),
      outcome.semanticsById.keys,
      "every render lands within the inactivity window, so no semantics may be dropped; logs: $logs",
    )
    assertTrue(
      logs.none { "timed out" in it },
      "steady progress must not trip the inactivity timeout, got: $logs",
    )
  }

  @Test
  fun `long render timeout is forwarded to the daemon session`() {
    // Issue #3197: the fetcher honored --timeout while waiting for notifications, but the daemon
    // still used its fixed 300-second host.submit deadline. Tail previews in a bounded sandbox pool
    // therefore failed while queued even when the command was run with --timeout 600.
    val projectDir = newTempFolder("semantics-daemon-timeout")
    writeDescriptor(projectDir)
    val factory = FakeFactory(produced = emptyMap())

    DaemonSemanticsFetcher(factory = factory, renderTimeout = 600_000.milliseconds)
      .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("Preview"))

    assertEquals(600_000.milliseconds, factory.openedConfig?.maxRenderTime)
  }

  @Test
  fun `short inactivity timeout does not reduce the daemon render deadline`() {
    // Review follow-up for #3198: host.submit starts its deadline while a render waits behind
    // earlier previews. Forwarding a short inactivity window directly would therefore turn it
    // back into a batch-wide cutoff for queued tail previews even when renders keep completing.
    val projectDir = newTempFolder("semantics-short-daemon-timeout")
    writeDescriptor(projectDir)
    val factory = FakeFactory(produced = emptyMap())

    DaemonSemanticsFetcher(factory = factory, renderTimeout = 150.milliseconds)
      .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("Preview"))

    assertEquals(300_000.milliseconds, factory.openedConfig?.maxRenderTime)
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
    private val failedIds: Map<String, String> = emptyMap(),
    private val silentIds: Set<String> = emptySet(),
    private val staggerMs: Long = 0,
  ) : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess
    var openedConfig: RenderSessionConfig? = null

    override fun open(config: RenderSessionConfig): RenderSession {
      openedConfig = config
      return FakeSession(
        workspaceRoot = config.workspaceRoot.absolutePath,
        produced = produced,
        fontsProduced = fontsProduced,
        failedIds = failedIds,
        silentIds = silentIds,
        staggerMs = staggerMs,
        dataRoot = File(config.workspaceRoot, "build/compose-previews/data"),
      )
    }
  }

  private class OpenFailFactory : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun open(config: RenderSessionConfig): RenderSession =
      throw RenderSessionException("simulated daemon open failure")
  }

  /**
   * Minimal [RenderSession] modelling the real daemon's async render: [renderNow] writes the
   * always-on `compose-semantics.json` sidecar for every preview it has canned content for, then
   * emits one terminal notification per requested id — `renderFailed` for ids in [failedIds],
   * `renderFinished` otherwise. Both are signals the fetcher must stop waiting on. Every other
   * method throws.
   */
  private class FakeSession(
    override val workspaceRoot: String,
    private val produced: Map<String, String>,
    private val dataRoot: File,
    private val fontsProduced: Map<String, String> = emptyMap(),
    private val failedIds: Map<String, String> = emptyMap(),
    private val silentIds: Set<String> = emptySet(),
    private val staggerMs: Long = 0,
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
      // With a stagger, model the real daemon: `renderNow` only queues and acks, then the terminal
      // notifications land one at a time on a background thread as each render actually completes.
      // This lets a test drive the total render past the fetcher's inactivity window while keeping
      // each individual gap inside it (issue #2948).
      if (staggerMs > 0) {
        Thread {
            for (id in previewIds) {
              Thread.sleep(staggerMs)
              emitTerminal(id)
            }
          }
          .apply { isDaemon = true }
          .start()
        return RenderNowResult(queued = previewIds, rejected = emptyList())
      }
      for (id in previewIds) emitTerminal(id)
      return RenderNowResult(queued = previewIds, rejected = emptyList())
    }

    private fun emitTerminal(id: String) {
      val failure = failedIds[id]
      if (failure != null) {
        // A render whose composition throws emits `renderFailed` and *no* `renderFinished` — the
        // daemon owes exactly one terminal event per queued render.
        val params =
          kotlinx.serialization.json.buildJsonObject {
            put("id", id)
            putJsonObject("error") { put("message", failure) }
          }
        listeners.forEach { it.onNotification("renderFailed", params) }
        return
      }
      if (id in silentIds) return
      produced[id]?.let { content ->
        val dir = File(dataRoot, id).also { it.mkdirs() }
        File(dir, "compose-semantics.json").writeText(content)
      }
      fontsProduced[id]?.let { content ->
        val dir = File(dataRoot, id).also { it.mkdirs() }
        File(dir, "fonts-used.json").writeText(content)
      }
      // Emit renderFinished for every remaining id — including ones that wrote no sidecar — so
      // the fetcher's wait completes rather than timing out.
      val params =
        kotlinx.serialization.json.buildJsonObject {
          put("id", id)
          put("pngPath", "$id.png")
        }
      listeners.forEach { it.onNotification("renderFinished", params) }
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
