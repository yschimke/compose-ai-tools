package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitScriptCommandTest {
  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun tempDir(): File =
    Files.createTempDirectory("compose-preview-initscript-cmd-").toFile().also { tempDirs += it }

  @Test
  fun `default --path materialises and prints absolute path`() {
    val storage = tempDir()
    val out = StringBuilder()
    InitScriptCommand(
        args = listOf("--path"),
        pluginVersion = "9.9.9-test",
        storageDir = storage,
        stdout = { out.append(it) },
      )
      .run()
    val printed = out.toString().trim()
    val file = File(printed)
    assertTrue(file.isAbsolute, "expected an absolute path; got '$printed'")
    assertEquals(File(storage, INIT_SCRIPT_FILENAME).absolutePath, file.absolutePath)
    assertTrue(file.isFile, "expected the script to exist on disk at '$printed'")
    assertEquals(renderInitScript("9.9.9-test"), file.readText())
  }

  @Test
  fun `no flag defaults to --path behaviour`() {
    val storage = tempDir()
    val out = StringBuilder()
    InitScriptCommand(
        args = emptyList(),
        pluginVersion = "1.0.0",
        storageDir = storage,
        stdout = { out.append(it) },
      )
      .run()
    val printed = out.toString().trim()
    assertEquals(File(storage, INIT_SCRIPT_FILENAME).absolutePath, File(printed).absolutePath)
    assertTrue(File(printed).isFile)
  }

  @Test
  fun `--print emits the rendered init script body on stdout and does not write to disk`() {
    val storage = tempDir()
    val out = StringBuilder()
    InitScriptCommand(
        args = listOf("--print"),
        pluginVersion = "2.0.0",
        storageDir = storage,
        stdout = { out.append(it) },
      )
      .run()
    assertEquals(renderInitScript("2.0.0"), out.toString())
    // --print is a pipe-mode shortcut — it should not materialise to disk.
    assertTrue(
      !File(storage, INIT_SCRIPT_FILENAME).exists(),
      "expected --print to skip the disk write so callers can fully control where the body goes",
    )
  }
}
