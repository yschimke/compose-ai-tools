package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinCommandTest {
  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun tempDir(): File =
    Files.createTempDirectory("compose-preview-pin-cmd-").toFile().also { tempDirs += it }

  private class Sink {
    val out = mutableListOf<String>()
    val err = mutableListOf<String>()
  }

  private fun run(args: List<String>, root: File, cliVersion: String = "1.1.0"): Sink {
    val sink = Sink()
    PinCommand(
        args = args,
        projectRoot = root,
        cliVersion = cliVersion,
        env = { null },
        stdout = { sink.out += it },
        stderr = { sink.err += it },
      )
      .run()
    return sink
  }

  @Test
  fun `bare pin reports that nothing is pinned`() {
    val sink = run(emptyList(), tempDir())
    val text = sink.out.joinToString("\n")
    assertTrue(text.contains("No version pin"), text)
    assertTrue(text.contains("1.1.0"), text)
  }

  @Test
  fun `pin --cli writes the running CLI version`() {
    val root = tempDir()
    run(listOf("--cli"), root, cliVersion = "1.1.0")
    assertEquals("1.1.0", readGradlePropertiesPin(root))
  }

  @Test
  fun `pin with an explicit version writes it and strips a leading v`() {
    val root = tempDir()
    run(listOf("v1.0.5"), root)
    assertEquals("1.0.5", readGradlePropertiesPin(root))
  }

  @Test
  fun `bare pin reports the pin and its source`() {
    val root = tempDir()
    File(root, "gradle.properties").writeText("composePreview.version=1.0.5\n")
    val sink = run(emptyList(), root, cliVersion = "1.1.0")
    val text = sink.out.joinToString("\n")
    assertTrue(text.contains("1.0.5"), text)
    assertTrue(text.contains("gradle.properties"), text)
    // The CLI disagrees with the pin, so the report says so.
    assertTrue(sink.err.any { it.contains("1.0.5") && it.contains("1.1.0") }, "${sink.err}")
  }

  @Test
  fun `writing a pin does not also emit the skew warning`() {
    // The user just said which version they want; warning in the same breath reads as failure.
    val root = tempDir()
    val sink = run(listOf("1.0.5"), root, cliVersion = "1.1.0")
    assertTrue(sink.err.none { it.contains("compose-preview note") }, "${sink.err}")
    assertTrue(sink.err.none { it.contains("compose-preview warning") }, "${sink.err}")
  }

  @Test
  fun `pin --remove clears the pin`() {
    val root = tempDir()
    File(root, "gradle.properties").writeText("a=1\ncomposePreview.version=1.0.5\n")
    run(listOf("--remove"), root)
    assertEquals(null, readGradlePropertiesPin(root))
    assertTrue(File(root, "gradle.properties").readText().contains("a=1"))
  }

  @Test
  fun `--json reports the resolved state`() {
    val root = tempDir()
    File(root, "gradle.properties").writeText("composePreview.version=1.0.5\n")
    val sink = run(listOf("--json"), root, cliVersion = "1.1.0")
    val json = sink.out.joinToString("\n")
    assertTrue(json.contains("\"pinned\": true"), json)
    assertTrue(json.contains("\"version\": \"1.0.5\""), json)
    assertTrue(json.contains("\"cliVersion\": \"1.1.0\""), json)
    assertTrue(json.contains("\"matchesCli\": false"), json)
  }

  @Test
  fun `--json on an unpinned project reports nulls and a matching CLI`() {
    val sink = run(listOf("--json"), tempDir(), cliVersion = "1.1.0")
    val json = sink.out.joinToString("\n")
    assertTrue(json.contains("\"pinned\": false"), json)
    assertTrue(json.contains("\"version\": null"), json)
    // Nothing to disagree with, so an unpinned project is never "mismatched".
    assertTrue(json.contains("\"matchesCli\": true"), json)
  }

  @Test
  fun `a written pin is what auto-inject then injects`() {
    // The end-to-end promise of the feature: pin, then every Gradle invocation applies that plugin.
    val root = tempDir()
    val storage = tempDir()
    run(listOf("0.9.9"), root)
    val args =
      autoInjectInitScriptArgs(
        args = emptyList(),
        storageDir = storage,
        env = { null },
        projectRoot = root,
        stderr = {},
      )
    assertFalse(args.isEmpty(), "expected auto-inject to stay on")
    assertTrue(File(args[1]).readText().contains("val pluginVersion = \"0.9.9\""))
  }
}
