package ee.schimke.composeai.scripting

import ee.schimke.composeai.cli.A11Y_PAYLOAD_SCHEMA_V1
import ee.schimke.composeai.cli.AccessibilityEntry
import ee.schimke.composeai.cli.AccessibilityFinding
import ee.schimke.composeai.cli.CaptureResult
import ee.schimke.composeai.cli.ExtensionPayload
import ee.schimke.composeai.cli.PreviewResult
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json

/**
 * Exercises the actual Kotlin scripting host end-to-end against the contrib-reference scripting
 * binary: a tiny `*.composepreview.kts` is written to disk, compiled, evaluated against a
 * pre-populated [ScriptState], and the resulting failure list is asserted against. These tests pay
 * the full ~1–3 s compile cost — keep them few, but keep them.
 */
class ScriptRunnerTest {

  private val tempDir: File = Files.createTempDirectory("compose-preview-scripting-test").toFile()
  private val json = Json { ignoreUnknownKeys = true }

  @AfterTest
  fun cleanup() {
    tempDir.deleteRecursively()
  }

  private fun writeScript(name: String, body: String): File {
    val file = File(tempDir, name)
    file.writeText(body)
    return file
  }

  /**
   * Build a [RenderedPreview] via the new `dataExtensions["a11y"]` carrier — the same path the
   * `Main.kt` reference uses. Exercises the decode-on-the-script-side flow end-to-end. `null`
   * [a11yFindings] means "ATF didn't run for this module" (no `dataExtensions["a11y"]` entry);
   * empty list means "checks ran, nothing tripped."
   */
  private fun preview(
    id: String,
    module: String = ":app",
    a11yFindings: List<AccessibilityFinding>? = null,
    pngPath: String? = "/tmp/$id.png",
  ): RenderedPreview {
    val dataExtensions =
      if (a11yFindings == null) {
        emptyMap()
      } else {
        mapOf(
          "a11y" to
            ExtensionPayload(
              schema = A11Y_PAYLOAD_SCHEMA_V1,
              payload =
                json.encodeToJsonElement(
                  AccessibilityEntry.serializer(),
                  AccessibilityEntry(previewId = id, findings = a11yFindings),
                ),
            )
        )
      }
    return RenderedPreview(
      PreviewResult(
        id = id,
        module = module,
        functionName = id,
        className = "com.app.${id}Kt",
        captures = listOf(CaptureResult(pngPath = pngPath)),
        pngPath = pngPath,
        dataExtensions = dataExtensions,
      )
    )
  }

  @Test
  fun `show returns the requested preview and fail accumulates`() {
    val state = ScriptState()
    state.results["HomeScreen"] = preview("HomeScreen")
    state.results["Settings"] = preview("Settings")

    val script =
      writeScript(
        "show.composepreview.kts",
        """
        val home = show("HomeScreen")
        fail("checked " + home.id + " in " + home.module)
        """
          .trimIndent(),
      )

    val outcome = ScriptRunner.evaluate(script, state)
    assertTrue(outcome is ScriptRunner.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(listOf("checked HomeScreen in :app"), state.failures)
  }

  @Test
  fun `previews returns every preview in id-sorted order`() {
    val state = ScriptState()
    state.results["Zeta"] = preview("Zeta")
    state.results["Alpha"] = preview("Alpha")

    val script =
      writeScript(
        "list.composepreview.kts",
        """
        for (ui in previews()) fail(ui.id)
        """
          .trimIndent(),
      )

    val outcome = ScriptRunner.evaluate(script, state)
    assertTrue(outcome is ScriptRunner.Outcome.Ok)
    assertEquals(listOf("Alpha", "Zeta"), state.failures)
  }

  @Test
  fun `show on unknown id surfaces a Failed outcome with available ids in the message`() {
    val state = ScriptState()
    state.results["HomeScreen"] = preview("HomeScreen")

    val script =
      writeScript(
        "missing.composepreview.kts",
        """
        show("Nope")
        """
          .trimIndent(),
      )

    val outcome = ScriptRunner.evaluate(script, state)
    val failed = outcome as? ScriptRunner.Outcome.Failed ?: fail("expected Failed, got $outcome")
    assertTrue(
      failed.message.contains("preview not found: 'Nope'"),
      "expected lookup miss in: ${failed.message}",
    )
    assertTrue(
      failed.message.contains("HomeScreen"),
      "expected available ids listed in: ${failed.message}",
    )
  }

  /**
   * The viability bar from issue #1084 + `docs/AGENTS.md` ("Built-in scripts"): a user script
   * should be able to express what `compose-preview a11y --filter Home --fail-on errors` does
   * today, without needing to be hardcoded into the CLI's command list. This test exercises the
   * contrib-reference path — no `:cli` dependency, decode through `dataExtensions["a11y"]` against
   * the published `:preview-data-api` shapes.
   */
  @Test
  fun `a11y audit example from the issue translates cleanly to the shape-C DSL`() {
    val errorFinding =
      AccessibilityFinding(
        level = "ERROR",
        type = "TouchTargetSize",
        message = "View is smaller than 48dp",
      )
    val warningFinding =
      AccessibilityFinding(
        level = "WARNING",
        type = "TextContrast",
        message = "Contrast below 4.5:1",
      )

    val state = ScriptState()
    state.results["HomeScreen"] = preview("HomeScreen", a11yFindings = listOf(errorFinding))
    state.results["HomeSecondary"] = preview("HomeSecondary", a11yFindings = listOf(warningFinding))
    state.results["Profile"] = preview("Profile", a11yFindings = emptyList())
    state.results["HomeOther"] =
      preview("HomeOther", module = ":other", a11yFindings = listOf(errorFinding))

    val script =
      writeScript(
        "a11y-audit.composepreview.kts",
        """
        for (ui in previews().filter { it.module == ":app" && it.id.startsWith("Home") }) {
          if (ui.a11y.hasErrors) {
            fail("a11y errors on " + ui.id + ": " + ui.a11y.errors.size)
          }
        }
        """
          .trimIndent(),
      )

    val outcome = ScriptRunner.evaluate(script, state)
    assertTrue(outcome is ScriptRunner.Outcome.Ok, "expected Ok, got $outcome")
    assertEquals(listOf("a11y errors on HomeScreen: 1"), state.failures)
  }

  @Test
  fun `compiler errors surface as Failed outcome with the script path`() {
    val script =
      writeScript(
        "broken.composepreview.kts",
        """
        noSuchSymbol("a11y")
        """
          .trimIndent(),
      )

    val outcome = ScriptRunner.evaluate(script, ScriptState())
    val failed = outcome as? ScriptRunner.Outcome.Failed ?: fail("expected Failed, got $outcome")
    assertTrue(
      failed.message.contains("noSuchSymbol"),
      "expected symbol name in: ${failed.message}",
    )
  }
}
