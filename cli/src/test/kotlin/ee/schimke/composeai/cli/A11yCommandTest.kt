package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.UiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unit coverage for `compose-preview a11y`. The Gradle Tooling API path (`withGradle`, `runGradle`,
 * `resolveModules`) isn't exercised here — we hit the pure decision functions (`a11yExitCode`) and
 * the encoder/filter helpers exposed by [Command] via a thin test subclass. The CI-side end-to-end
 * coverage lives in `:gradle-plugin:functionalTest`.
 */
class A11yCommandTest {

  // ---------- exit-code matrix (a11yExitCode) ----------

  @Test
  fun `default failOn with successful build exits 0`() {
    assertEquals(0, a11yExitCode(buildOk = true, errorCount = 0, warnCount = 0, failOn = null))
  }

  @Test
  fun `default failOn with successful build ignores findings`() {
    // Without --fail-on, the CLI mirrors Gradle: findings alone don't trip the exit code.
    assertEquals(0, a11yExitCode(buildOk = true, errorCount = 5, warnCount = 5, failOn = null))
  }

  @Test
  fun `default failOn with failed build exits 2`() {
    assertEquals(2, a11yExitCode(buildOk = false, errorCount = 0, warnCount = 0, failOn = null))
  }

  @Test
  fun `failOn errors with errors trips exit 2`() {
    assertEquals(2, a11yExitCode(buildOk = true, errorCount = 1, warnCount = 0, failOn = "errors"))
  }

  @Test
  fun `failOn errors with only warnings exits 0`() {
    assertEquals(0, a11yExitCode(buildOk = true, errorCount = 0, warnCount = 3, failOn = "errors"))
  }

  @Test
  fun `failOn warnings with warnings trips exit 2`() {
    assertEquals(
      2,
      a11yExitCode(buildOk = true, errorCount = 0, warnCount = 1, failOn = "warnings"),
    )
  }

  @Test
  fun `failOn warnings with errors trips exit 2`() {
    assertEquals(
      2,
      a11yExitCode(buildOk = true, errorCount = 1, warnCount = 0, failOn = "warnings"),
    )
  }

  @Test
  fun `failOn none never trips even with errors and warnings`() {
    assertEquals(0, a11yExitCode(buildOk = true, errorCount = 5, warnCount = 5, failOn = "none"))
  }

  @Test
  fun `failOn none with failed build still exits 2`() {
    // `--fail-on none` only suppresses CLI-side threshold tripping, not the underlying build
    // failure — Gradle exit codes still propagate.
    assertEquals(2, a11yExitCode(buildOk = false, errorCount = 0, warnCount = 0, failOn = "none"))
  }

  @Test
  fun `unknown failOn returns sentinel for caller error message`() {
    assertEquals(
      EXIT_UNKNOWN_FAIL_ON,
      a11yExitCode(buildOk = true, errorCount = 0, warnCount = 0, failOn = "anything-else"),
    )
  }

  // ---------- JSON shape ----------

  @Test
  fun `json output for no enabled modules emits empty previews with schema pin`() {
    // Mirrors A11yCommand's "no module has a11y enabled" branch which calls
    // `encodeResponse(emptyList(), countsScope = null)`.
    val cmd = TestableCommand(listOf("--json"))
    val payload = Json.parseToJsonElement(cmd.encodeResponseFor(emptyList())).jsonObject
    assertEquals(JsonPrimitive(SHOW_LIST_SCHEMA), payload["schema"])
    assertEquals(JsonArray(emptyList()), payload["previews"])
    // countsScope = null → no counts block emitted (or emitted as null).
    val counts = payload["counts"]
    assertTrue(
      counts == null || counts is kotlinx.serialization.json.JsonNull,
      "expected no counts",
    )
  }

  @Test
  fun `json output emits findings on the v2 dataExtensions a11y carrier`() {
    val cmd = TestableCommand(listOf("--json"))
    val results =
      listOf(
        previewResult(
          id = "Foo",
          findings =
            listOf(
              AccessibilityFinding(
                level = "ERROR",
                type = "TouchTargetSize",
                message = "Touch target 24x24 below 48dp.",
                viewDescription = "Button",
              ),
              AccessibilityFinding(
                level = "WARNING",
                type = "TextContrast",
                message = "Contrast 3.8:1 below 4.5:1.",
              ),
            ),
        )
      )

    val payload = Json.parseToJsonElement(cmd.encodeResponseFor(results)).jsonObject

    assertEquals(JsonPrimitive(SHOW_LIST_SCHEMA), payload["schema"])
    val previews = payload["previews"]?.jsonArray ?: error("missing previews")
    assertEquals(1, previews.size)
    // v2 wire format: findings live on `dataExtensions["a11y"].payload.findings`, not as a
    // top-level `a11yFindings` array. Asserting both the schema pin and the body shape so a
    // regression in either is caught here.
    val dataExtensions =
      previews[0].jsonObject["dataExtensions"]?.jsonObject ?: error("missing dataExtensions")
    val a11y = dataExtensions["a11y"]?.jsonObject ?: error("missing dataExtensions[\"a11y\"]")
    assertEquals(
      JsonPrimitive(A11Y_PAYLOAD_SCHEMA_V1),
      a11y["schema"],
      "expected a11y payload schema pinned to v1",
    )
    val findings =
      a11y["payload"]?.jsonObject?.get("findings")?.jsonArray ?: error("missing findings")
    assertEquals(2, findings.size)
    assertEquals("ERROR", findings[0].jsonObject["level"]?.jsonPrimitive?.contentOrNull)
    assertEquals("TouchTargetSize", findings[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
    assertEquals("WARNING", findings[1].jsonObject["level"]?.jsonPrimitive?.contentOrNull)
    // The dropped v1 top-level field must be absent.
    assertNull(previews[0].jsonObject["a11yFindings"])
    assertNull(previews[0].jsonObject["a11yAnnotatedPath"])
  }

  @Test
  fun `json output with countsScope emits counts block`() {
    // `compose-preview a11y` itself passes countsScope=null, but ShowCommand's path uses the
    // same encoder with counts populated — pin the shape for back-compat agents.
    val cmd = TestableCommand(listOf("--json"))
    val results =
      listOf(
        previewResult(id = "A", changed = true, png = "/tmp/a.png"),
        previewResult(id = "B", changed = false, png = "/tmp/b.png"),
      )

    val payload =
      Json.parseToJsonElement(cmd.encodeResponseFor(results, countsScope = results)).jsonObject
    val counts = payload["counts"]?.jsonObject ?: error("expected counts block")
    assertEquals(2, counts["total"]?.jsonPrimitive?.intOrNull)
    assertEquals(1, counts["changed"]?.jsonPrimitive?.intOrNull)
    assertEquals(1, counts["unchanged"]?.jsonPrimitive?.intOrNull)
    assertEquals(0, counts["missing"]?.jsonPrimitive?.intOrNull)
  }

  // ---------- --changed-only filter ----------

  @Test
  fun `changedOnly drops previews with no changed capture`() {
    val cmd = TestableCommand(listOf("--changed-only"))
    val all =
      listOf(
        previewResult(id = "Changed", changed = true, png = "/tmp/c.png"),
        previewResult(id = "Unchanged", changed = false, png = "/tmp/u.png"),
      )

    val filtered = cmd.applyFiltersFor(all)

    assertEquals(listOf("Changed"), filtered.map { it.id })
  }

  @Test
  fun `without changedOnly all previews pass through`() {
    val cmd = TestableCommand(emptyList())
    val all =
      listOf(
        previewResult(id = "Changed", changed = true, png = "/tmp/c.png"),
        previewResult(id = "Unchanged", changed = false, png = "/tmp/u.png"),
      )

    val filtered = cmd.applyFiltersFor(all)

    assertEquals(listOf("Changed", "Unchanged"), filtered.map { it.id })
  }

  @Test
  fun `id filter narrows to a single preview`() {
    val cmd = TestableCommand(listOf("--id", "Bar"))
    val all =
      listOf(previewResult(id = "Foo"), previewResult(id = "Bar"), previewResult(id = "Baz"))

    val filtered = cmd.applyFiltersFor(all)

    assertEquals(listOf("Bar"), filtered.map { it.id })
    assertFalse(filtered.any { it.id == "Foo" })
    assertNull(filtered.singleOrNull()?.takeIf { it.id != "Bar" })
  }

  // ---------- per-preview data-product fan-out (#3742) ----------

  @Test
  fun `unfiltered run asks about every preview in the module`() {
    val cmd = TestableReportCommand(emptyList())

    val requests = cmd.requestsFor(listOf(manifest("app", "Alpha", "Beta", "Gamma")))

    val request = requests.single()
    assertEquals(listOf("Alpha", "Beta", "Gamma"), request.previews.map { it.entryId })
    assertFalse(request.narrowed, "a full module is not a partial report")
  }

  @Test
  fun `filter narrows the per-preview fan-out and marks the report partial`() {
    // The bug: `a11y --filter Alpha` used to fetch ATF for all three previews — a per-preview
    // daemon render each — to print one row. The render half was narrowed in #3734; this is the
    // daemon half.
    val cmd = TestableReportCommand(listOf("--filter", "alpha"))

    val request = cmd.requestsFor(listOf(manifest("app", "Alpha", "Beta", "Gamma"))).single()

    assertEquals(listOf("Alpha"), request.previews.map { it.entryId })
    assertTrue(request.narrowed, "a subset of the module must merge into the module's report")
  }

  @Test
  fun `a request that selects every preview is not treated as partial`() {
    // `renderedIds` is null both here and for an unfiltered run, which is why the fan-out asks the
    // request rather than the render what it covers.
    val cmd = TestableReportCommand(listOf("--filter", "Preview"))

    val request = cmd.requestsFor(listOf(manifest("app", "AlphaPreview", "BetaPreview"))).single()

    assertEquals(listOf("AlphaPreview", "BetaPreview"), request.previews.map { it.entryId })
    assertFalse(request.narrowed)
  }

  @Test
  fun `an exact permutation id is addressed as its declared preview, with overrides`() {
    // `--permutations accessibility` synthesises `Foo_dark` client-side; the daemon's PreviewIndex
    // only knows the ids the plugin discovered and resolves them exactly, so asking it for
    // `Foo_dark` gets "unknown preview". The request names `Foo` and carries the dark-mode
    // configuration instead — and files the result under the id the user asked about (#3762).
    val cmd = TestableReportCommand(listOf("--id", "Foo_dark", "--permutations", "accessibility"))

    val request = cmd.requestsFor(listOf(manifest("app", "Foo", "Bar"))).single()

    val requested = request.previews.single()
    assertEquals("Foo", requested.previewId, "the daemon-addressable id")
    assertEquals("Foo_dark", requested.entryId, "the id a consumer looks up")
    assertEquals(UiMode.DARK, requested.overrides?.uiMode)
    assertTrue(requested.isPermutation)
    assertTrue(request.narrowed, "one of eight expanded previews")
  }

  @Test
  fun `each accessibility permutation carries the configuration it stands for`() {
    val cmd = TestableReportCommand(listOf("--filter", "Foo", "--permutations", "accessibility"))

    val byEntry =
      cmd.requestsFor(listOf(manifest("app", "Foo"))).single().previews.associateBy { it.entryId }

    assertEquals(null, byEntry.getValue("Foo").overrides, "the declared preview renders as itself")
    assertEquals(UiMode.DARK, byEntry.getValue("Foo_dark").overrides?.uiMode)
    assertEquals("ar-XB", byEntry.getValue("Foo_rtl").overrides?.localeTag)
    assertEquals(2.0f, byEntry.getValue("Foo_fontscale-2x").overrides?.fontScale)
    // Every fetch still addresses the one preview the plugin discovered.
    assertEquals(setOf("Foo"), byEntry.values.map { it.previewId }.toSet())
  }

  @Test
  fun `coverage is measured against the ids a consumer will look up`() {
    // A one-preview module: the request covers one of the four ids the results will carry, so the
    // report must call itself partial rather than let the renderer synthesise clean rows for the
    // three permutations nobody asked for.
    val cmd = TestableReportCommand(listOf("--id", "Foo_dark", "--permutations", "accessibility"))

    val request = cmd.requestsFor(listOf(manifest("app", "Foo"))).single()

    assertEquals(listOf("Foo_dark"), request.previews.map { it.entryId })
    assertEquals(
      listOf("Foo", "Foo_dark", "Foo_rtl", "Foo_fontscale-2x"),
      request.consumerPreviewIds,
    )
    assertTrue(request.narrowed)
  }

  @Test
  fun `without permutations the two id spaces are the same`() {
    val cmd = TestableReportCommand(emptyList())

    val request = cmd.requestsFor(listOf(manifest("app", "Foo", "Bar"))).single()

    assertEquals(request.previews.map { it.entryId }, request.consumerPreviewIds)
  }

  @Test
  fun `a permutation request no longer warns about substitution`() {
    // It used to say the permutation row would carry no data, because the run fetched the declared
    // preview at its own parameters instead. It now fetches the permutation's configuration, so
    // there is nothing to apologise for.
    val cmd = TestableReportCommand(listOf("--id", "Foo_dark", "--permutations", "accessibility"))

    val err = captureStderr { cmd.requestsFor(listOf(manifest("app", "Foo", "Bar"))) }

    assertEquals("", err.trim())
  }

  @Test
  fun `a filter under permutations fetches every matching permutation`() {
    // Each expanded row is its own render at its own configuration, and checking a11y across those
    // configurations is the point of `--permutations accessibility` — dark contrast, RTL layout,
    // 2x font scale. Deduplicating them back to the declared preview would report one result four
    // times over.
    val cmd = TestableReportCommand(listOf("--filter", "Foo", "--permutations", "accessibility"))

    val request = cmd.requestsFor(listOf(manifest("app", "Foo", "Bar"))).single()

    assertEquals(
      listOf("Foo", "Foo_dark", "Foo_rtl", "Foo_fontscale-2x"),
      request.previews.map { it.entryId },
    )
  }

  @Test
  fun `modules the request does not touch drop out of the work list`() {
    val cmd = TestableReportCommand(listOf("--id", "Alpha"))

    val requests =
      cmd.requestsFor(listOf(manifest("app", "Alpha", "Beta"), manifest("wear", "TilePreview")))

    // No session opened, no daemon started, and no `accessibility.json` written for `:wear` — the
    // module isn't attempted, so it can't be reported as ATF-unavailable either.
    assertEquals(listOf(":app"), requests.map { it.module.gradlePath })
    assertEquals(listOf("Alpha"), requests.single().previews.map { it.entryId })
  }

  @Test
  fun `a module with no previews is never attempted`() {
    val cmd = TestableReportCommand(emptyList())

    assertEquals(emptyList(), cmd.requestsFor(listOf(manifest("app"))))
  }

  // ---------- helpers ----------

  /**
   * Test-only [Command] subclass: re-exposes `protected` helpers so the encoder + filter logic can
   * be exercised without booting the Gradle Tooling API. `run()` is unused.
   */
  private class TestableCommand(args: List<String>) : Command(args) {
    override fun run() = Unit

    fun encodeResponseFor(
      results: List<PreviewResult>,
      countsScope: List<PreviewResult>? = null,
    ): String = encodeResponse(results, countsScope)

    fun applyFiltersFor(results: List<PreviewResult>): List<PreviewResult> = applyFilters(results)
  }

  /**
   * Test-only [ReportCommand] subclass exposing the per-module work list its
   * `produceAdditionalDataProducts` hook receives, so the `--id` / `--filter` narrowing can be
   * exercised without a Gradle build or a daemon. `run()` is never called.
   */
  private class TestableReportCommand(args: List<String>) : ReportCommand(args, "a11y") {
    fun requestsFor(
      manifests: List<Pair<PreviewModule, PreviewManifest>>
    ): List<DataProductRequest> = dataProductRequests(manifests)
  }

  private fun captureStderr(block: () -> Unit): String {
    val buffer = java.io.ByteArrayOutputStream()
    val saved = System.err
    System.setErr(java.io.PrintStream(buffer))
    try {
      block()
    } finally {
      System.setErr(saved)
    }
    return buffer.toString()
  }

  private fun manifest(path: String, vararg ids: String): Pair<PreviewModule, PreviewManifest> {
    val module = PreviewModule(":$path", java.io.File("/tmp/compose-preview-test/$path"))
    return module to
      PreviewManifest(
        module = module.gradlePath,
        variant = "debug",
        previews =
          ids.map { id ->
            PreviewInfo(
              id = id,
              functionName = id,
              className = "com.example.PreviewsKt",
              params = PreviewParams(kind = "COMPOSE"),
            )
          },
      )
  }

  private fun previewResult(
    id: String,
    changed: Boolean? = null,
    png: String? = null,
    findings: List<AccessibilityFinding>? = null,
  ): PreviewResult {
    val capture =
      CaptureResult(
        advanceTimeMillis = null,
        scroll = null,
        pngPath = png,
        sha256 = null,
        changed = changed,
      )
    val dataExtensions =
      if (findings == null) {
        emptyMap()
      } else {
        // Mirror what `A11yReportRenderer.annotate` writes in production: encode an
        // `AccessibilityEntry` into the `dataExtensions["a11y"]` carrier with the v1 schema
        // pin. `null` findings means "ATF didn't run for the module" — no carrier entry.
        val entry = AccessibilityEntry(previewId = id, findings = findings)
        mapOf(
          "a11y" to
            ExtensionPayload(
              schema = A11Y_PAYLOAD_SCHEMA_V1,
              payload = Json.encodeToJsonElement(AccessibilityEntry.serializer(), entry),
            )
        )
      }
    return PreviewResult(
      id = id,
      module = ":sample",
      functionName = id,
      className = "com.example.${id}Kt",
      sourceFile = "src/main/kotlin/com/example/${id}.kt",
      params = PreviewParams(),
      captures = listOf(capture),
      pngPath = png,
      sha256 = null,
      changed = changed,
      dataExtensions = dataExtensions,
    )
  }
}
