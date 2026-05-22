package ee.schimke.composeai.cli.scripting

import ee.schimke.composeai.cli.CaptureResult
import ee.schimke.composeai.cli.PreviewResult
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Exercises the actual Kotlin scripting host end-to-end: a tiny `*.composepreview.kts` is written
 * to disk, compiled, evaluated, and the resulting [ScriptState] is asserted against. These tests
 * pay the full ~1–3 s compile cost — keep them few, but keep them.
 *
 * The host JARs (`kotlin-scripting-jvm-host` + `kotlin-compiler-embeddable`) sit on the JVM
 * classpath in this module via `implementation(libs.kotlin.scripting.*)`. If a future refactor
 * splits them onto a sidecar, this test moves with them.
 */
class ScriptRunnerTest {

  private val tempDir: File = Files.createTempDirectory("compose-preview-script-test").toFile()

  @AfterTest
  fun cleanup() {
    tempDir.deleteRecursively()
  }

  private fun writeScript(name: String, body: String): File {
    val file = File(tempDir, name)
    file.writeText(body)
    return file
  }

  @Test
  fun `script populates state with extensions and handlers`() {
    val script =
      writeScript(
        "simple.composepreview.kts",
        """
        extensions("a11y", "theme")
        filter { it.module == ":app" }
        onResult { result ->
          if (result.id.startsWith("Bad")) fail("bad preview: " + result.id)
        }
        """
          .trimIndent(),
      )

    val outcome = ScriptRunner.load(script)
    assertTrue(outcome is ScriptRunner.Outcome.Ok, "expected Ok, got $outcome")
    val state = outcome.state

    assertEquals(listOf("a11y", "theme"), state.extensions)
    assertEquals(1, state.filters.size)
    assertEquals(1, state.handlers.size)
    assertEquals(emptyList(), state.failures)

    // Verify the filter predicate sees `PreviewResult` from `:cli` correctly.
    val goodPreview = PreviewResult(id = "X", module = ":app", functionName = "f", className = "C")
    val otherPreview =
      PreviewResult(id = "Y", module = ":other", functionName = "f", className = "C")
    assertTrue(state.filters.single().invoke(goodPreview))
    assertTrue(!state.filters.single().invoke(otherPreview))
  }

  @Test
  fun `handler call back into fail accumulates failures on shared state`() {
    val script =
      writeScript(
        "fail.composepreview.kts",
        """
        onResult { result ->
          fail("preview " + result.id + " tripped")
        }
        """
          .trimIndent(),
      )

    val outcome = ScriptRunner.load(script)
    assertTrue(outcome is ScriptRunner.Outcome.Ok)
    val state = outcome.state

    val sample =
      PreviewResult(
        id = "HomeScreen",
        module = ":app",
        functionName = "HomeScreen",
        className = "com.app.HomeScreenKt",
        captures = listOf(CaptureResult(pngPath = null)),
      )
    state.handlers.single().invoke(sample)
    state.handlers.single().invoke(sample.copy(id = "Profile"))

    assertEquals(listOf("preview HomeScreen tripped", "preview Profile tripped"), state.failures)
  }

  @Test
  fun `compiler errors surface as Failed outcome with the script path`() {
    val script =
      writeScript(
        "broken.composepreview.kts",
        // `noSuchSymbol` is undefined — the compiler should reject this with an
        // unresolved-reference
        // diagnostic that mentions the source path.
        """
        noSuchSymbol("a11y")
        """
          .trimIndent(),
      )

    val outcome = ScriptRunner.load(script)
    val failed = outcome as? ScriptRunner.Outcome.Failed ?: fail("expected Failed, got $outcome")
    assertTrue(
      failed.message.contains("noSuchSymbol"),
      "expected symbol name in: ${failed.message}",
    )
  }
}
