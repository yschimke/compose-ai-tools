package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.DataFetchParams
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Aggregation contract for [DaemonA11yFetcher]: with a fake [RenderSessionFactory] returning canned
 * a11y/atf payloads, the fetcher writes `accessibility.json` in the shape [A11yReportRenderer]
 * reads — one entry per preview, in input order, findings preserved.
 */
class DaemonA11yFetcherTest {

  /** Plain declared previews — no `--permutations` in play, so entry id == fetch id. */
  private fun declared(vararg ids: String): List<ReportCommand.RequestedPreview> = ids.map {
    ReportCommand.RequestedPreview(previewId = it, entryId = it)
  }

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
        previews = declared("AlphaPreview", "BetaPreview"),
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
        previews = declared("AlphaPreview"),
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
        previews = declared("AlphaPreview"),
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
        previews = declared("AlphaPreview", "BetaPreview"),
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
        previews = declared("AlphaPreview", "BetaPreview"),
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

  // ---------- permutations are fetched at their own overrides (#3762) ----------

  @Test
  fun `a permutation is addressed as its declared preview and filed under its own id`() {
    val projectDir = newTempFolder("module-permutation")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    val factory = FakeFactory(mapOf("Foo" to atfPayload(emptyList())))
    val outcome =
      DaemonA11yFetcher(factory = factory)
        .fetch(
          projectDir = projectDir,
          modulePath = "",
          moduleName = "sample",
          previews =
            listOf(
              ReportCommand.RequestedPreview("Foo", "Foo"),
              ReportCommand.RequestedPreview(
                "Foo",
                "Foo_dark",
                PreviewOverrides(uiMode = ee.schimke.composeai.daemon.protocol.UiMode.DARK),
              ),
            ),
        )

    assertTrue(outcome is DaemonA11yFetcher.Outcome.Ok, "expected Ok, got $outcome")
    // The daemon only knows `Foo` — `PreviewIndex.byId` is an exact lookup — so both fetches
    // address it, and the permutation carries its configuration in the params bag instead.
    assertEquals(listOf("Foo", "Foo"), factory.calls.map { it.previewId })
    val overrideParams = factory.calls.first { it.params != null }.params!!.jsonObject
    assertEquals(
      "dark",
      overrideParams[DataFetchParams.PARAM_OVERRIDES]
        ?.jsonObject
        ?.get("uiMode")
        ?.jsonPrimitive
        ?.content,
    )
    // ...and the artefact is forced fresh, since the shared per-preview file may hold the other
    // configuration's render.
    assertEquals(
      true,
      overrideParams[DataFetchParams.PARAM_FORCE_RERENDER]?.jsonPrimitive?.content?.toBoolean(),
    )
    // The report is keyed by what a consumer looks up.
    assertEquals(
      setOf("Foo", "Foo_dark"),
      readReport(projectDir).entries.map { it.previewId }.toSet(),
    )
  }

  @Test
  fun `the declared preview is fetched last so its artefacts are the ones left on disk`() {
    val projectDir = newTempFolder("module-permutation-order")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    val dataDir = File(projectDir, "build/compose-previews/data/Foo")

    // Stand in for the daemon: every render writes the overlay to `data/<previewId>/`, keyed by the
    // id it was asked for rather than by the overrides — which is exactly why they collide.
    val factory =
      FakeFactory(
        payloads = mapOf("Foo" to atfPayload(emptyList())),
        onFetch = { _, ordinal ->
          dataDir.mkdirs()
          File(dataDir, "a11y-overlay.png").writeBytes("render-$ordinal".toByteArray())
        },
      )

    DaemonA11yFetcher(factory = factory)
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previews =
          listOf(
            ReportCommand.RequestedPreview("Foo", "Foo"),
            ReportCommand.RequestedPreview("Foo", "Foo_dark", PreviewOverrides(fontScale = 2.0f)),
          ),
      )

    // The permutation kept a copy of its own render...
    assertEquals(
      "render-1",
      File(projectDir, "build/compose-previews/data/Foo_dark/a11y-overlay.png").readText(),
    )
    // ...and `data/Foo/` ends up holding the declared preview's, because it was fetched last.
    assertEquals("render-2", File(dataDir, "a11y-overlay.png").readText())
    val report = readReport(projectDir)
    assertEquals(
      "data/Foo_dark/a11y-overlay.png",
      report.entries.single { it.previewId == "Foo_dark" }.annotatedPath,
    )
    assertEquals(
      "data/Foo/a11y-overlay.png",
      report.entries.single { it.previewId == "Foo" }.annotatedPath,
    )
  }

  @Test
  fun `each permutation keeps the findings of its own render`() {
    val projectDir = newTempFolder("module-permutation-findings")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    // Findings come back inline in the payload, so they are per-render even though every fetch
    // addresses the same preview. Call 1 is the permutation (fetched first), call 2 the base.
    val factory =
      FakeFactory(
        payloads = emptyMap(),
        payloadByCall =
          mapOf(
            1 to atfPayload(listOf(finding("ERROR", "TextContrast", "dark-only"))),
            2 to atfPayload(emptyList()),
          ),
      )

    DaemonA11yFetcher(factory = factory)
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previews =
          listOf(
            ReportCommand.RequestedPreview("Foo", "Foo"),
            ReportCommand.RequestedPreview(
              "Foo",
              "Foo_dark",
              PreviewOverrides(uiMode = ee.schimke.composeai.daemon.protocol.UiMode.DARK),
            ),
          ),
      )

    val report = readReport(projectDir)
    assertEquals(
      "dark-only",
      report.entries.single { it.previewId == "Foo_dark" }.findings.single().message,
    )
    assertEquals(
      emptyList(),
      report.entries.single { it.previewId == "Foo" }.findings,
      "the declared preview's own render was clean",
    )
  }

  @Test
  fun `the declared preview is re-rendered at its own configuration after a permutation`() {
    val projectDir = newTempFolder("module-permutation-force-base")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    val factory = FakeFactory(mapOf("Foo" to atfPayload(emptyList())))
    DaemonA11yFetcher(factory = factory)
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previews =
          listOf(
            ReportCommand.RequestedPreview("Foo", "Foo"),
            ReportCommand.RequestedPreview("Foo", "Foo_dark", PreviewOverrides(fontScale = 2.0f)),
          ),
      )

    // The permutation was rendered *as* `Foo`, so it left its data product at `Foo`'s own cache
    // path. `FileBackedDataProductRegistry` only queues a re-render when that file is *missing*, so
    // an unforced base fetch would serve the permutation's artefact and file it as the base.
    val baseParams = factory.calls.last().params?.jsonObject
    assertEquals(
      true,
      baseParams?.get(DataFetchParams.PARAM_FORCE_RERENDER)?.jsonPrimitive?.content?.toBoolean(),
      "the base fetch after a permutation must force a re-render",
    )
    assertNull(
      baseParams?.get(DataFetchParams.PARAM_OVERRIDES),
      "...at default overrides, which is what makes it the base",
    )
  }

  @Test
  fun `a permutation-only request still restores the declared preview's render`() {
    val projectDir = newTempFolder("module-permutation-only")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    val factory = FakeFactory(mapOf("Foo" to atfPayload(emptyList())))
    DaemonA11yFetcher(factory = factory)
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previews =
          listOf(
            ReportCommand.RequestedPreview("Foo", "Foo_dark", PreviewOverrides(fontScale = 2.0f))
          ),
        modulePreviewIds = listOf("Foo", "Foo_dark"),
        narrowed = true,
      )

    // `--id Foo_dark` asks for one entry, but the render that produced it overwrote
    // `renders/Foo.png` — which `buildResults` hashes as the declared preview. So the restore runs
    // even though it has no entry to file.
    assertEquals(2, factory.calls.size, "expected the permutation plus a restoring base fetch")
    assertEquals(
      true,
      factory.calls
        .last()
        .params
        ?.jsonObject
        ?.get(DataFetchParams.PARAM_FORCE_RERENDER)
        ?.jsonPrimitive
        ?.content
        ?.toBoolean(),
    )
    assertEquals(
      listOf("Foo_dark"),
      readReport(projectDir).entries.map { it.previewId },
      "the restore is a side effect, not a result — it files no entry",
    )
  }

  @Test
  fun `a failed permutation does not inherit the previous permutation's artefacts`() {
    val projectDir = newTempFolder("module-permutation-failed-snapshot")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    val dataDir = File(projectDir, "build/compose-previews/data/Foo")

    // Only the first fetch renders; the second errors, so `data/Foo/` still holds the *first*
    // permutation's overlay when it returns.
    val factory =
      FakeFactory(
        payloads = emptyMap(),
        payloadByCall =
          mapOf(1 to atfPayload(emptyList()), 2 to null, 3 to atfPayload(emptyList())),
        onFetch = { _, ordinal ->
          if (ordinal != 2) {
            dataDir.mkdirs()
            File(dataDir, "a11y-overlay.png").writeBytes("render-$ordinal".toByteArray())
          }
        },
      )

    DaemonA11yFetcher(factory = factory)
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previews =
          listOf(
            ReportCommand.RequestedPreview("Foo", "Foo_dark", PreviewOverrides(fontScale = 2.0f)),
            ReportCommand.RequestedPreview("Foo", "Foo_rtl", PreviewOverrides(localeTag = "ar-XB")),
          ),
        modulePreviewIds = listOf("Foo", "Foo_dark", "Foo_rtl"),
        narrowed = true,
      )

    assertEquals(
      "render-1",
      File(projectDir, "build/compose-previews/data/Foo_dark/a11y-overlay.png").readText(),
    )
    assertFalse(
      File(projectDir, "build/compose-previews/data/Foo_rtl/a11y-overlay.png").exists(),
      "a failed fetch has no artefacts of its own, and must not adopt the last one's",
    )
    assertNull(readReport(projectDir).entries.single { it.previewId == "Foo_rtl" }.annotatedPath)
  }

  @Test
  fun `a snapshot drops a stale artefact the latest render did not produce`() {
    val projectDir = newTempFolder("module-permutation-stale-snapshot")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    val dataDir = File(projectDir, "build/compose-previews/data/Foo")
    val darkDir = File(projectDir, "build/compose-previews/data/Foo_dark")
    darkDir.mkdirs()
    File(darkDir, "a11y-overlay.png").writeText("from-an-earlier-run")

    // This render produces a hierarchy but no overlay (the overlay-only path is optional). The copy
    // loop has nothing to overwrite the earlier run's PNG with, so it must remove it — otherwise
    // `annotatedPath` points a reader at pixels from a render that isn't this one.
    val factory =
      FakeFactory(
        payloads = mapOf("Foo" to atfPayload(emptyList())),
        onFetch = { _, _ ->
          dataDir.mkdirs()
          File(dataDir, "a11y-hierarchy.json").writeText("""{"nodes":[]}""")
        },
      )

    DaemonA11yFetcher(factory = factory)
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previews =
          listOf(
            ReportCommand.RequestedPreview("Foo", "Foo_dark", PreviewOverrides(fontScale = 2.0f))
          ),
        modulePreviewIds = listOf("Foo", "Foo_dark"),
        narrowed = true,
      )

    assertFalse(File(darkDir, "a11y-overlay.png").exists(), "stale overlay should be dropped")
    assertNull(readReport(projectDir).entries.single().annotatedPath)
  }

  @Test
  fun `a permutation whose overrides collapse to the base is still forced`() {
    val projectDir = newTempFolder("module-permutation-no-op")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    // `@Preview(fontScale = 2.0f)` expands to a `_fontscale-2x` variant whose params equal the
    // base's, so `overridesFor` hands back null. Unforced, that fetch would serve whatever the
    // preceding RTL render left at the shared cache path and file it under the 2× id.
    val factory = FakeFactory(mapOf("Foo" to atfPayload(emptyList())))
    DaemonA11yFetcher(factory = factory)
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previews =
          listOf(
            ReportCommand.RequestedPreview("Foo", "Foo_rtl", PreviewOverrides(localeTag = "ar-XB")),
            ReportCommand.RequestedPreview("Foo", "Foo_fontscale-2x", overrides = null),
          ),
        modulePreviewIds = listOf("Foo", "Foo_rtl", "Foo_fontscale-2x"),
        narrowed = true,
      )

    val noOp = factory.calls[1].params?.jsonObject
    assertEquals(
      true,
      noOp?.get(DataFetchParams.PARAM_FORCE_RERENDER)?.jsonPrimitive?.content?.toBoolean(),
      "a permutation with an empty override bag still needs its own render",
    )
    assertNull(noOp?.get(DataFetchParams.PARAM_OVERRIDES), "...at the base configuration")
  }

  @Test
  fun `a failed restore discards the permutation's artefacts instead of filing them as the base`() {
    val projectDir = newTempFolder("module-permutation-restore-fails")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    val dataDir = File(projectDir, "build/compose-previews/data/Foo")

    // The permutation renders as `Foo` and leaves its artefacts — including the cached
    // `a11y-atf.json` — under `Foo`. Then the restoring fetch errors, so nothing replaces them.
    val factory =
      FakeFactory(
        payloads = emptyMap(),
        payloadByCall = mapOf(1 to atfPayload(emptyList()), 2 to null),
        onFetch = { _, ordinal ->
          if (ordinal == 1) {
            dataDir.mkdirs()
            File(dataDir, "a11y-overlay.png").writeText("dark-pixels")
            File(dataDir, "a11y-hierarchy.json").writeText("""{"nodes":[]}""")
            File(dataDir, "a11y-atf.json").writeText("""{"findings":[]}""")
          }
        },
      )

    DaemonA11yFetcher(factory = factory)
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previews =
          listOf(
            ReportCommand.RequestedPreview("Foo", "Foo"),
            ReportCommand.RequestedPreview("Foo", "Foo_dark", PreviewOverrides(fontScale = 2.0f)),
          ),
      )

    val base = readReport(projectDir).entries.single { it.previewId == "Foo" }
    assertNull(base.annotatedPath, "the overlay left under `Foo` is the permutation's")
    assertEquals(emptyList(), base.nodes, "so is the hierarchy")
    // And the cached data product goes too: `FileBackedDataProductRegistry` only re-renders when
    // the file is missing, so leaving it would serve the permutation's findings to the next run.
    assertFalse(File(dataDir, "a11y-atf.json").exists())
    assertFalse(File(dataDir, "a11y-overlay.png").exists())
    // The permutation's own snapshot, taken before the failure, is untouched.
    assertEquals(
      "dark-pixels",
      File(projectDir, "build/compose-previews/data/Foo_dark/a11y-overlay.png").readText(),
    )
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
        previews = declared("AlphaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
        narrowed = true,
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
  fun `a partial-but-unnarrowed run still rewrites wholesale`() {
    val projectDir = newTempFolder("module-partial-unnarrowed")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    writeExistingReport(projectDir, entry("GonePreview", finding("ERROR", "Stale", "deleted")))

    // The `--permutations` shape: every *declared* preview is fetched (so nothing was skipped and
    // there is nothing to carry forward), but the consumer id space also contains synthetic ids the
    // daemon can't address, so the report is still partial. Deriving "should I merge?" from that
    // coverage gap would keep a deleted preview's findings on disk forever.
    DaemonA11yFetcher(factory = FakeFactory(mapOf("AlphaPreview" to atfPayload(emptyList()))))
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previews = declared("AlphaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "AlphaPreview_dark"),
        narrowed = false,
      )

    val report = readReport(projectDir)
    assertEquals(listOf("AlphaPreview"), report.entries.map { it.previewId })
    assertTrue(report.partial, "the synthetic id is still uncovered")
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
      previews = declared("AlphaPreview"),
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
      previews = declared("AlphaPreview", "BetaPreview"),
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
        previews = declared("AlphaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
        narrowed = true,
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
  fun `a failed refetch keeps what the last run found`() {
    val projectDir = newTempFolder("module-refetch-fails")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")
    writeExistingReport(
      projectDir,
      entry("AlphaPreview", finding("ERROR", "TouchTargetSize", "observed")),
      entry("BetaPreview"),
    )

    // Alpha is requested again and its fetch errors; Gamma succeeds, so the run as a whole is
    // "available" and stamps no status. Letting Alpha's empty entry win would delete a real
    // finding and republish the preview as checked-and-clean in the same move.
    DaemonA11yFetcher(
        factory =
          FakeFactory(mapOf("AlphaPreview" to null, "GammaPreview" to atfPayload(emptyList())))
      )
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previews = declared("AlphaPreview", "GammaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "BetaPreview", "GammaPreview"),
        narrowed = true,
      )

    val report = readReport(projectDir)
    assertNull(report.status, "one fetch succeeded, so the run is not unavailable")
    assertEquals(
      "observed",
      report.entries.single { it.previewId == "AlphaPreview" }.findings.single().message,
    )
  }

  @Test
  fun `a failed first fetch still records the attempt`() {
    val projectDir = newTempFolder("module-first-fetch-fails")
    File(projectDir, "build/compose-previews").mkdirs()
    File(projectDir, "build/compose-previews/daemon-launch.json").writeText("{}")

    // Nothing on disk to protect, so #1453's "the preview was attempted" entry still lands.
    DaemonA11yFetcher(factory = FakeFactory(mapOf("AlphaPreview" to null)))
      .fetch(
        projectDir = projectDir,
        modulePath = "",
        moduleName = "sample",
        previews = declared("AlphaPreview"),
      )

    val report = readReport(projectDir)
    assertEquals(listOf("AlphaPreview"), report.entries.map { it.previewId })
    assertEquals(A11Y_REPORT_STATUS_ATF_UNAVAILABLE, report.status)
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
        previews = declared("AlphaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
        narrowed = true,
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
        previews = declared("AlphaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
        narrowed = true,
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
      previews = declared("AlphaPreview"),
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
          previews = declared("AlphaPreview"),
          modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
          narrowed = true,
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
        previews = declared("AlphaPreview"),
        modulePreviewIds = listOf("AlphaPreview", "BetaPreview"),
        narrowed = true,
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
        previews = declared("AlphaPreview"),
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

  /** One `data/fetch` the fetcher made: the id it addressed and the params bag it sent. */
  data class FetchCall(val previewId: String, val params: JsonElement?)

  private class FakeFactory(
    private val payloads: Map<String, JsonElement?>,
    /** Records every fetch, in order, so a test can assert on ids, order and params. */
    val calls: MutableList<FetchCall> = mutableListOf(),
    /** Runs before each fetch returns — lets a test simulate the daemon writing artefacts. */
    private val onFetch: (String, Int) -> Unit = { _, _ -> },
    /** Payload override by call ordinal, for asserting per-permutation results differ. */
    private val payloadByCall: Map<Int, JsonElement?> = emptyMap(),
  ) : RenderSessionFactory {
    override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

    override fun open(config: RenderSessionConfig): RenderSession =
      FakeSession(
        workspaceRoot = config.workspaceRoot.absolutePath,
        modulePath = ":sample",
        payloads = payloads,
        calls = calls,
        onFetch = onFetch,
        payloadByCall = payloadByCall,
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
    val calls: MutableList<FetchCall> = mutableListOf(),
    private val onFetch: (String, Int) -> Unit = { _, _ -> },
    private val payloadByCall: Map<Int, JsonElement?> = emptyMap(),
  ) : RenderSession {
    fun payloadFor(previewId: String, ordinal: Int): JsonElement? =
      if (payloadByCall.containsKey(ordinal)) payloadByCall[ordinal] else payloads[previewId]

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
    ): DataFetchResult {
      calls += FetchCall(previewId, params)
      onFetch(previewId, calls.size)
      return DataFetchResult(
        kind = kind,
        schemaVersion = 1,
        payload = payloadFor(previewId, calls.size),
      )
    }

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
