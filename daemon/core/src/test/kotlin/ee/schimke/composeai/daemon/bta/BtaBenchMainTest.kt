@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import java.io.File
import java.nio.file.Path
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure helpers in [BtaBenchMain] — the sysprop → [BenchConfig] parse, the output
 * record formatting (the tab-separated protocol the bench Gradle task parses), and the marker-swap
 * edit. The actual `compileIncremental` drive needs the BTA impl JARs + a real module, so it lives
 * on the reference machine via `benchCompileStages`, not in this unit test.
 */
class BtaBenchMainTest {

  private fun props(map: Map<String, String>): (String) -> String? = { map[it] }

  private val complete =
    mapOf(
      DefaultBtaCompileService.SYSPROP_IMPL_CLASSPATH to "/a.jar${File.pathSeparator}/b.jar",
      DefaultBtaCompileService.SYSPROP_COMPILE_CLASSPATH to "/c.jar",
      DefaultBtaCompileService.SYSPROP_COMPILER_PLUGINS to "/compose.jar",
      DefaultBtaCompileService.SYSPROP_MODULE_NAME to "desktop-daemon-bench",
      DefaultBtaCompileService.SYSPROP_OUTPUT_DIR to "/out",
      DefaultBtaCompileService.SYSPROP_IC_WORKING_DIR to "/ic",
      BenchConfig.SYSPROP_SOURCES to "/src/BenchPreviews.kt",
      BenchConfig.SYSPROP_EDIT_FILE to "/src/BenchPreviews.kt",
    )

  @Test
  fun `fromSysprops parses a complete btaCompile block`() {
    val cfg = BenchConfig.fromSysprops(props(complete))!!
    assertEquals(listOf(Path.of("/a.jar"), Path.of("/b.jar")), cfg.implClasspath)
    assertEquals(listOf(Path.of("/c.jar")), cfg.compileClasspath)
    assertEquals(listOf(Path.of("/compose.jar")), cfg.compilerPlugins)
    assertEquals("desktop-daemon-bench", cfg.moduleName)
    assertEquals(Path.of("/out"), cfg.outputDir)
    assertEquals(Path.of("/ic"), cfg.icWorkingDir)
    assertEquals(listOf(Path.of("/src/BenchPreviews.kt")), cfg.sources)
    // Defaults.
    assertEquals("\"three\"", cfg.editMarker)
    assertEquals(2, cfg.warmups)
    assertEquals(5, cfg.runs)
  }

  @Test
  fun `fromSysprops returns null when the btaCompile block is incomplete`() {
    val required =
      listOf(
        DefaultBtaCompileService.SYSPROP_IMPL_CLASSPATH,
        DefaultBtaCompileService.SYSPROP_MODULE_NAME,
        DefaultBtaCompileService.SYSPROP_OUTPUT_DIR,
        DefaultBtaCompileService.SYSPROP_IC_WORKING_DIR,
        BenchConfig.SYSPROP_SOURCES,
        BenchConfig.SYSPROP_EDIT_FILE,
      )
    for (missing in required) {
      val cfg = BenchConfig.fromSysprops(props(complete - missing))
      assertNull("missing $missing should yield null", cfg)
    }
  }

  @Test
  fun `fromSysprops honours overridden warmups and runs`() {
    val overrides =
      complete + mapOf(BenchConfig.SYSPROP_WARMUPS to "0", BenchConfig.SYSPROP_RUNS to "9")
    val cfg = BenchConfig.fromSysprops(props(overrides))!!
    assertEquals(0, cfg.warmups)
    assertEquals(9, cfg.runs)
  }

  @Test
  fun `parsePathList ignores empty entries and a null input`() {
    val sep = File.pathSeparator
    assertEquals(emptyList<Path>(), parsePathList(null))
    assertEquals(emptyList<Path>(), parsePathList(""))
    assertEquals(listOf(Path.of("/x")), parsePathList("$sep/x$sep"))
  }

  @Test
  fun `mutateMarker swaps the first literal for a unique one and stays a string literal`() {
    val src = """Text("three")"""
    val edited = mutateMarker(src, "\"three\"")
    assertNotEquals(src, edited)
    assertTrue("edit keeps it a quoted literal: $edited", edited.contains(Regex("\"three-\\d+\"")))
  }

  @Test
  fun `row and note follow the tab-separated wire protocol`() {
    assertEquals(
      "BENCHROW\tcompile\tstage-2-warm-after-1-line-edit\t3\t312\tBtaCompileSession.compileIncremental()",
      row(
        "compile",
        "stage-2-warm-after-1-line-edit",
        3,
        312,
        "BtaCompileSession.compileIncremental()",
      ),
    )
    // Tabs inside notes would corrupt the parse — they're flattened to spaces.
    assertEquals("BENCHNOTE\ta b", note("a\tb"))
  }
}
