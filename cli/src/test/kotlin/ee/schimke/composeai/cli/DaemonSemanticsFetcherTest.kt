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
import ee.schimke.composeai.data.fonts.FigmaSvgFontWarningsSidecar
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Contract for [DaemonSemanticsFetcher]: with a fake [RenderSessionFactory] whose `renderNow`
 * writes `compose-semantics.json` sidecars, the fetcher returns each requested preview's sidecar
 * bytes keyed by preview id.
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
    // The daemon renders a `@PreviewParameter` preview's first provider value under the bare
    // function id, so its sidecar lands at `build/compose-previews/data/<id>/` like a plain
    // preview's; the fetcher must carry the id without per-value resolution.
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

    // Beta's composition throws, so the daemon emits only `renderFailed` (as a Glance composable
    // under the plain `@Preview` does); the fetcher must not wait out the whole budget on it.
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
    // A wide catalog takes far longer than one window in total while each render lands quickly (one
    // every 20ms across 100 previews, ~2s, vs a 1s window); every sidecar must still be collected.
    //
    // The two numbers are independent: total elapsed must exceed the window (the property), and the
    // stagger must stay far below it (the margin against CI scheduling stalls). Retune them
    // together without closing the gap.
    val projectDir = newTempFolder("semantics-inactivity")
    writeDescriptor(projectDir)

    val ids = (1..100).map { "Preview$it" }
    val produced = ids.associateWith { """{"root":{"nodeId":"$it","boundsInRoot":"0,0,4,8"}}""" }
    val logs = mutableListOf<String>()
    val fetcher =
      DaemonSemanticsFetcher(
        factory = FakeFactory(produced = produced, staggerMs = 20),
        onLog = { logs += it },
        renderTimeout = 1.seconds,
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
    assertEquals(2.seconds, factory.openedConfig?.shutdownTimeout)
  }

  @Test
  fun `short inactivity timeout does not reduce the daemon render deadline`() {
    // Review follow-up for #3198: released daemons start host.submit's deadline while a render
    // waits behind earlier previews (desktop times from dequeue only since compose-preview-daemon
    // #247). Forwarding a short inactivity window directly would turn it back into a batch-wide
    // cutoff for queued tail previews even when renders keep completing.
    val projectDir = newTempFolder("semantics-short-daemon-timeout")
    writeDescriptor(projectDir)
    val factory = FakeFactory(produced = emptyMap())

    DaemonSemanticsFetcher(factory = factory, renderTimeout = 150.milliseconds)
      .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("Preview"))

    assertEquals(300_000.milliseconds, factory.openedConfig?.maxRenderTime)
    assertEquals(2.seconds, factory.openedConfig?.shutdownTimeout)
  }

  @Test
  fun `renders are submitted in bounded windows, refilled as each finishes`() {
    // Released daemons start a render's deadline when it is queued, so a catalog handed over in
    // one renderNow shares one deadline and its tail fails together. Never more than the window may
    // be
    // queued at once, and every preview must still be rendered.
    val projectDir = newTempFolder("semantics-window")
    writeDescriptor(projectDir)
    val ids = (1..40).map { "Preview$it" }
    val factory =
      FakeFactory(
        produced = ids.associateWith { """{"root":{"nodeId":"$it"}}""" },
        staggerMs = 2,
      )

    val outcome =
      DaemonSemanticsFetcher(factory = factory, window = 3)
        .fetch(projectDir = projectDir, moduleName = "sample", previewIds = ids)

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(ids.toSet(), outcome.semanticsById.keys)
    val session = factory.sessions.single()
    assertTrue(session.batches.size > 1, "expected several renderNow calls, got ${session.batches}")
    assertTrue(
      session.batches.all { it.size <= 3 },
      "a batch exceeded the window: ${session.batches}",
    )
    assertTrue(
      session.maxOutstanding.get() <= 3,
      "at most the window may be queued at once, saw ${session.maxOutstanding.get()}",
    )
    // Submission order is input order: the window changes when, not what.
    assertEquals(ids, factory.submitted.toList())
  }

  @Test
  fun `a rejected render frees its window slot`() {
    val projectDir = newTempFolder("semantics-window-rejected")
    writeDescriptor(projectDir)
    val ids = listOf("A", "B", "C", "D")
    val factory =
      RejectingFactory(
        rejected = setOf("A", "B"),
        delegate = FakeFactory(produced = ids.associateWith { "{}" }),
      )
    val logs = mutableListOf<String>()

    val outcome =
      DaemonSemanticsFetcher(
          factory = factory,
          window = 2,
          onLog = { synchronized(logs) { logs += it } },
        )
        .fetch(projectDir = projectDir, moduleName = "sample", previewIds = ids)

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(setOf("C", "D"), outcome.semanticsById.keys)
    assertTrue(logs.any { "render rejected for 'A'" in it }, "got: $logs")
    assertTrue(logs.none { "timed out" in it }, "a rejection must not stall the window: $logs")
  }

  @Test
  fun `workers split the previews across daemons, each rendered exactly once`() {
    val projectDir = newTempFolder("semantics-workers")
    writeDescriptor(projectDir)
    val ids = (1..60).map { "Preview$it" }
    val produced = ids.associateWith { """{"root":{"nodeId":"$it"}}""" }
    val factory = FakeFactory(produced = produced, staggerMs = 1)
    val logs = java.util.Collections.synchronizedList(mutableListOf<String>())

    val outcome =
      DaemonSemanticsFetcher(factory = factory, workers = 3, window = 4, onLog = { logs += it })
        .fetch(projectDir = projectDir, moduleName = "sample", previewIds = ids)

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(3, factory.sessions.size)
    // Distinct workspace names keep each daemon's log attributable.
    assertEquals(
      setOf(
        "${projectDir.name}-semantics-1",
        "${projectDir.name}-semantics-2",
        "${projectDir.name}-semantics-3",
      ),
      factory.openedConfigs.map { it.workspaceName }.toSet(),
    )
    // Several daemons on one module must not share a history dir.
    assertTrue(
      factory.openedConfigs.all {
        it.systemPropertyOverrides["composeai.history.enabled"] == "false"
      }
    )
    // Every id rendered by exactly one daemon, so no two daemons race on one sidecar.
    assertEquals(ids.sorted(), factory.submitted.toList().sorted())
    // The result is in input order whichever daemon rendered each id: deterministic output.
    assertEquals(ids, outcome.semanticsById.keys.toList())
    assertEquals(
      produced.getValue("Preview17"),
      outcome.semanticsById.getValue("Preview17").toString(Charsets.UTF_8),
    )
    assertTrue(
      logs.any { it.startsWith("render timings: 60 finished, 0 failed") && "3 daemon(s)" in it },
      "got: $logs",
    )
  }

  @Test
  fun `a single worker keeps the module's workspace name and the daemon's history`() {
    val projectDir = newTempFolder("semantics-one-worker")
    writeDescriptor(projectDir)
    val factory = FakeFactory(produced = emptyMap())

    DaemonSemanticsFetcher(factory = factory)
      .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("A"))

    assertEquals(projectDir.name, factory.openedConfig?.workspaceName)
    assertEquals(
      null,
      factory.openedConfig?.systemPropertyOverrides?.get("composeai.history.enabled"),
    )
  }

  @Test
  fun `a stalled daemon gives up only its own renders, the others drain the queue`() {
    val projectDir = newTempFolder("semantics-worker-stall")
    writeDescriptor(projectDir)
    val ids = (1..30).map { "Preview$it" }
    val factory =
      FakeFactory(
        produced = ids.associateWith { "{}" },
        // Whichever daemon draws Preview1 never reports it, and goes quiet with it in flight.
        silentIds = setOf("Preview1"),
        staggerMs = 5,
      )
    val logs = java.util.Collections.synchronizedList(mutableListOf<String>())

    val outcome =
      DaemonSemanticsFetcher(
          factory = factory,
          workers = 2,
          window = 1,
          renderTimeout = 500.milliseconds,
          onLog = { logs += it },
        )
        .fetch(projectDir = projectDir, moduleName = "sample", previewIds = ids)

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals((ids - "Preview1").toSet(), outcome.semanticsById.keys)
    assertTrue(logs.any { "timed out" in it && "Preview1" in it }, "got: $logs")
    assertTrue(logs.any { "1 stalled" in it }, "the summary must count the stall, got: $logs")
  }

  @Test
  fun `a daemon that fails to open leaves its share to the others`() {
    val projectDir = newTempFolder("semantics-worker-open-fails")
    writeDescriptor(projectDir)
    val ids = (1..10).map { "Preview$it" }
    val factory = FakeFactory(produced = ids.associateWith { "{}" }, failingOpens = setOf(0))
    val logs = java.util.Collections.synchronizedList(mutableListOf<String>())

    val outcome =
      DaemonSemanticsFetcher(factory = factory, workers = 2, onLog = { logs += it })
        .fetch(projectDir = projectDir, moduleName = "sample", previewIds = ids)

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(ids.toSet(), outcome.semanticsById.keys)
    assertTrue(logs.any { "failed to open" in it }, "got: $logs")
  }

  @Test
  fun `every daemon failing to open is OpenFailed`() {
    val projectDir = newTempFolder("semantics-workers-all-fail")
    writeDescriptor(projectDir)
    val factory = FakeFactory(produced = emptyMap(), failingOpens = setOf(0, 1))

    val outcome =
      DaemonSemanticsFetcher(factory = factory, workers = 2)
        .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("A", "B"))

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.OpenFailed, "got $outcome")
  }

  @Test
  fun `the pack's data kinds are requested from the daemon`() {
    val projectDir = newTempFolder("semantics-kinds")
    writeDescriptor(projectDir)
    val factory = FakeFactory(produced = emptyMap())

    DaemonSemanticsFetcher(factory = factory)
      .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("A"))
    assertEquals(
      listOf("compose/semantics", "layout/inspector", "fonts/used", "compose/figma-svg"),
      factory.openedConfig?.attachDataProducts,
    )

    DaemonSemanticsFetcher(
        factory = factory,
        requestedKinds = DaemonSemanticsFetcher.kindsFor(figmaSvg = false),
      )
      .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("A"))
    assertEquals(
      listOf("compose/semantics", "layout/inspector", "fonts/used"),
      factory.openedConfig?.attachDataProducts,
    )
  }

  @Test
  fun `the desktop post-capture allowlist matches the requested kinds`() {
    // compose-preview-daemon#247: desktop gates post-capture work only on this launch property;
    // attachDataProducts alone leaves every extension running.
    val projectDir = newTempFolder("semantics-post-capture-kinds")
    writeDescriptor(projectDir)
    val factory = FakeFactory(produced = emptyMap())
    val prop = "composeai.daemon.postCaptureKinds"

    DaemonSemanticsFetcher(factory = factory)
      .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("A"))
    assertEquals(
      "compose/semantics,layout/inspector,fonts/used,compose/figma-svg",
      factory.openedConfig?.systemPropertyOverrides?.get(prop),
    )

    // --no-figma-svg drops the export from the allowlist, so desktop skips it.
    DaemonSemanticsFetcher(
        factory = factory,
        requestedKinds = DaemonSemanticsFetcher.kindsFor(figmaSvg = false),
      )
      .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("A"))
    assertEquals(
      "compose/semantics,layout/inspector,fonts/used",
      factory.openedConfig?.systemPropertyOverrides?.get(prop),
    )

    // Every parallel daemon gets it, alongside the history override.
    DaemonSemanticsFetcher(factory = factory, workers = 2)
      .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("A", "B"))
    val parallel = factory.openedConfigs.takeLast(2).map { it.systemPropertyOverrides }
    assertTrue(parallel.all { it[prop]?.endsWith("compose/figma-svg") == true }, "got $parallel")
    assertTrue(parallel.all { it["composeai.history.enabled"] == "false" }, "got $parallel")

    // No requested kinds: no allowlist, so the daemon keeps its default (run everything).
    DaemonSemanticsFetcher(factory = factory, requestedKinds = null)
      .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("A"))
    assertEquals(null, factory.openedConfig?.systemPropertyOverrides?.get(prop))
  }

  @Test
  fun `a stale figma font-warning sidecar never reaches a run without the export`() {
    // A previous run degraded and left `figma-fonts.warnings.json`; this run passes --no-figma-svg,
    // so nothing rewrites it. Reading it would fail the lost-font-family check on old data.
    val projectDir = newTempFolder("semantics-stale-font-warnings")
    writeDescriptor(projectDir)
    val dataDir = File(projectDir, "build/compose-previews/data/AlphaPreview").also { it.mkdirs() }
    val staleWarnings = File(dataDir, FigmaSvgFontWarningsSidecar.FILE)
    staleWarnings.writeText("""{"lostFamilies":["Stale"]}""")
    File(dataDir, "compose-figma.svg").writeText("<svg/>")

    val outcome =
      DaemonSemanticsFetcher(
          factory = FakeFactory(mapOf("AlphaPreview" to """{"root":{"nodeId":"1"}}""")),
          requestedKinds = DaemonSemanticsFetcher.kindsFor(figmaSvg = false),
        )
        .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("AlphaPreview"))

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertTrue(outcome.figmaFontWarningsById.isEmpty(), "stale warnings must not be carried")
    assertTrue(outcome.figmaSvgById.isEmpty(), "no figma-svg without the export requested")
    assertTrue(
      !staleWarnings.exists(),
      "the stale warnings sidecar should be cleared before render",
    )
  }

  @Test
  fun `figma artefacts are still carried when the export is requested`() {
    val projectDir = newTempFolder("semantics-figma-requested")
    writeDescriptor(projectDir)
    val factory =
      object : RenderSessionFactory {
        val inner = FakeFactory(mapOf("AlphaPreview" to """{"root":{"nodeId":"1"}}"""))
        override val backendKind = RenderSessionBackend.Subprocess

        override fun open(config: RenderSessionConfig): RenderSession {
          val session = inner.open(config)
          // The daemon writes the export (and, degraded, its warning) during this run's render.
          session.onNotification { method, _ ->
            if (method == "renderFinished") {
              val dir = File(projectDir, "build/compose-previews/data/AlphaPreview")
              File(dir, "compose-figma.svg").writeText("<svg/>")
              File(dir, FigmaSvgFontWarningsSidecar.FILE).writeText("""{"lostFamilies":["X"]}""")
            }
          }
          return session
        }
      }

    val outcome =
      DaemonSemanticsFetcher(factory = factory)
        .fetch(projectDir = projectDir, moduleName = "sample", previewIds = listOf("AlphaPreview"))

    assertTrue(outcome is DaemonSemanticsFetcher.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(setOf("AlphaPreview"), outcome.figmaSvgById.keys)
    assertEquals(setOf("AlphaPreview"), outcome.figmaFontWarningsById.keys)
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
    /** Open attempts (0-based, in call order) that throw, as a daemon failing to start would. */
    private val failingOpens: Set<Int> = emptySet(),
  ) : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess
    @Volatile var openedConfig: RenderSessionConfig? = null
    val openedConfigs = java.util.concurrent.CopyOnWriteArrayList<RenderSessionConfig>()
    val sessions = java.util.concurrent.CopyOnWriteArrayList<FakeSession>()
    /** Every id any session was asked to render, across sessions, in submission order. */
    val submitted = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val opens = java.util.concurrent.atomic.AtomicInteger()

    override fun open(config: RenderSessionConfig): RenderSession {
      if (opens.getAndIncrement() in failingOpens) {
        throw RenderSessionException("simulated daemon open failure")
      }
      openedConfig = config
      openedConfigs += config
      return FakeSession(
          workspaceRoot = config.workspaceRoot.absolutePath,
          produced = produced,
          fontsProduced = fontsProduced,
          failedIds = failedIds,
          silentIds = silentIds,
          staggerMs = staggerMs,
          dataRoot = File(config.workspaceRoot, "build/compose-previews/data"),
          submitted = submitted,
        )
        .also { sessions += it }
    }
  }

  /** Wraps [delegate]'s sessions so `renderNow` reports [rejected] ids as rejected, unrendered. */
  private class RejectingFactory(
    private val rejected: Set<String>,
    private val delegate: FakeFactory,
  ) : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun open(config: RenderSessionConfig): RenderSession {
      val inner = delegate.open(config)
      return object : RenderSession by inner {
        override fun renderNow(
          previewIds: List<String>,
          tier: RenderTier,
          reason: String?,
          overrides: PreviewOverrides?,
          timeout: kotlin.time.Duration,
        ): RenderNowResult {
          val accepted = previewIds.filterNot { it in rejected }
          val ack =
            if (accepted.isEmpty()) RenderNowResult(queued = emptyList(), rejected = emptyList())
            else inner.renderNow(accepted, tier, reason, overrides, timeout)
          return RenderNowResult(
            queued = ack.queued,
            rejected =
              previewIds.filter { it in rejected }.map { RejectedRender(it, "unknown preview") },
          )
        }
      }
    }
  }

  private class OpenFailFactory : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun open(config: RenderSessionConfig): RenderSession =
      throw RenderSessionException("simulated daemon open failure")
  }

  /**
   * Minimal [RenderSession] modelling the daemon's async render: [renderNow] writes the sidecar for
   * every canned preview, then emits one terminal notification per id (`renderFailed` for
   * [failedIds], `renderFinished` otherwise). Everything else throws.
   */
  private class FakeSession(
    override val workspaceRoot: String,
    private val produced: Map<String, String>,
    private val dataRoot: File,
    private val fontsProduced: Map<String, String> = emptyMap(),
    private val failedIds: Map<String, String> = emptyMap(),
    private val silentIds: Set<String> = emptySet(),
    private val staggerMs: Long = 0,
    private val submitted: java.util.Queue<String> = java.util.concurrent.ConcurrentLinkedQueue(),
  ) : RenderSession {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<NotificationListener>()

    /** Each `renderNow` call's ids, in call order. */
    val batches = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
    private val outstanding = java.util.concurrent.atomic.AtomicInteger()
    /** The most renders this session ever held between `renderNow` and a terminal event. */
    val maxOutstanding = java.util.concurrent.atomic.AtomicInteger()

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
      batches += previewIds
      submitted.addAll(previewIds)
      maxOutstanding.accumulateAndGet(outstanding.addAndGet(previewIds.size), ::maxOf)
      // With a stagger, model the real daemon: `renderNow` acks, then terminal notifications land
      // one at a time on a background thread, so total time can exceed the inactivity window while
      // each gap stays inside it.
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
      if (id in silentIds) return
      outstanding.decrementAndGet()
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
          put("tookMs", 100L + id.length)
          putJsonObject("workTrace") { putJsonObject("stepMs") { put("compose/semantics", 7L) } }
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
    ): ExtensionsEnableResult =
      ExtensionsEnableResult.Builder().also { it.newlyEnabled = ids }.build()

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
