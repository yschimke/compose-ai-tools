package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the [CliFlags] registry against drift. Every flag read through the shared
 * [flagValue]/[flagValuesAll] helpers must be classified in [CliFlags] — either as value-consuming
 * ([CliFlags.VALUE_FLAGS]) or attached/optional ([CliFlags.ATTACHED_OR_OPTIONAL_FLAGS]). A flag
 * that's read but unclassified silently breaks command detection (the value gets mistaken for the
 * subcommand), so we make it a compile-of-the-test failure instead of a runtime surprise.
 */
class CliFlagsRegistryTest {
  private val flagCallSite = Regex("""flagValue(?:sAll)?\(\s*"(--[a-z][a-z-]*)"""")

  @Test
  fun `every flagValue call site is classified in CliFlags`() {
    val classified = CliFlags.VALUE_FLAGS + CliFlags.ATTACHED_OR_OPTIONAL_FLAGS
    val used =
      mainSources()
        .flatMap { file -> flagCallSite.findAll(file.readText()).map { it.groupValues[1] } }
        .toSortedSet()

    assertTrue(used.isNotEmpty(), "expected to find flagValue call sites under $sourceDir")

    val unclassified = used - classified
    if (unclassified.isNotEmpty()) {
      fail(
        "Flags read via flagValue()/flagValuesAll() but missing from CliFlags: $unclassified. " +
          "Add each to CliFlags.VALUE_FLAGS (value is the next argv token, e.g. --foo bar) or " +
          "CliFlags.ATTACHED_OR_OPTIONAL_FLAGS (value attached/optional, e.g. --foo=bar)."
      )
    }
  }

  @Test
  fun `value and attached flag sets are disjoint`() {
    val overlap = CliFlags.VALUE_FLAGS intersect CliFlags.ATTACHED_OR_OPTIONAL_FLAGS
    assertEquals(emptySet(), overlap, "a flag cannot be both value-consuming and attached/optional")
  }

  @Test
  fun `findCommandIndex skips value flags and their values`() {
    assertEquals(0, CliFlags.findCommandIndex(arrayOf("show")))
    assertEquals(2, CliFlags.findCommandIndex(arrayOf("--module", ":app", "show")))
    // Regression: a global-position value flag that used to be unclassified mis-detected its value
    // as the command.
    assertEquals(2, CliFlags.findCommandIndex(arrayOf("--since", "2024", "history", "list")))
  }

  @Test
  fun `findCommandIndex treats attached and optional flags as non-consuming`() {
    // --images is optional-value: a bare token after it is the command, not its value.
    assertEquals(1, CliFlags.findCommandIndex(arrayOf("--images", "show")))
    assertEquals(1, CliFlags.findCommandIndex(arrayOf("--force=stale", "render")))
  }

  @Test
  fun `findCommandIndex returns -1 when argv is all flags`() {
    assertEquals(-1, CliFlags.findCommandIndex(arrayOf("--json")))
    assertEquals(-1, CliFlags.findCommandIndex(arrayOf("--module", ":app")))
  }

  private val sourceDir: File
    get() = File(repoRoot(), "cli/src/main/kotlin/ee/schimke/composeai/cli")

  private fun mainSources(): List<File> =
    sourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  /** Walk up from the test working dir (the `:cli` project dir) to the repo root. */
  private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("could not locate repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
  }
}
