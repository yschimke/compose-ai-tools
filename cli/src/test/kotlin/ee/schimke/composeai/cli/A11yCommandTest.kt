package ee.schimke.composeai.cli

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
  fun `json output preserves a11yFindings for enabled module`() {
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
    val findings = previews[0].jsonObject["a11yFindings"]?.jsonArray ?: error("missing findings")
    assertEquals(2, findings.size)
    assertEquals("ERROR", findings[0].jsonObject["level"]?.jsonPrimitive?.contentOrNull)
    assertEquals("TouchTargetSize", findings[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
    assertEquals("WARNING", findings[1].jsonObject["level"]?.jsonPrimitive?.contentOrNull)
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
      a11yFindings = findings,
      a11yAnnotatedPath = null,
    )
  }
}
