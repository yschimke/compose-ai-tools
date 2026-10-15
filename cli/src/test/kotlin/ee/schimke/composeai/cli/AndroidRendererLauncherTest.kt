package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `bundle render`'s Android subprocess starts at [BundleRenderer.ANDROID_RENDERER_LAUNCHER], which
 * must exit once the renderer's main returns even though a non-daemon thread lingers, and must run
 * on the renderer's classpath with nothing from the CLI but its own class file.
 */
class AndroidRendererLauncherTest {
  private fun launch(vararg args: String): Pair<Int, String> {
    val work = Files.createTempDirectory("android-launcher").toFile()
    try {
      val launcher = BundleRenderer.androidRendererLauncher(work)
      // The launcher class dir, then the test classpath standing in for the renderer's (it holds
      // the fake `AndroidRendererMainKt` and the Kotlin standard library).
      val classpath = launcher.path + File.pathSeparator + System.getProperty("java.class.path")
      val java = File(System.getProperty("java.home"), "bin/java").path
      val process =
        ProcessBuilder(java, "-cp", classpath, BundleRenderer.ANDROID_RENDERER_LAUNCHER, *args)
          .redirectErrorStream(true)
          .start()
      val exited = process.waitFor(60, TimeUnit.SECONDS)
      if (!exited) process.destroyForcibly()
      assertTrue(exited, "the launcher must exit after the renderer's main returns")
      return process.exitValue() to process.inputStream.bufferedReader().readText()
    } finally {
      work.deleteRecursively()
    }
  }

  @Test
  fun `exits zero once the renderer returns despite a lingering non-daemon thread`() {
    val (status, output) = launch()
    assertEquals(0, status, output)
    assertTrue("rendered" in output, output)
  }

  @Test
  fun `a renderer failure exits non-zero`() {
    val (status, output) = launch("--fail")
    assertEquals(1, status, output)
    assertTrue("render failed" in output, output)
  }

  @Test
  fun `only the launcher's own class file is copied`() {
    val work = Files.createTempDirectory("android-launcher").toFile()
    try {
      val root = BundleRenderer.androidRendererLauncher(work)
      val files = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList()
      assertEquals(
        listOf(BundleRenderer.ANDROID_RENDERER_LAUNCHER.replace('.', '/') + ".class"),
        files,
      )
      // It names no other CLI class, so it links on the renderer's classpath.
      val constants = root.resolve(files.single()).readBytes().toString(Charsets.ISO_8859_1)
      val cliReferences =
        Regex("ee/schimke/composeai/cli/[A-Za-z0-9_/$]+").findAll(constants).map { it.value }
      assertEquals(
        setOf(BundleRenderer.ANDROID_RENDERER_LAUNCHER.replace('.', '/')),
        cliReferences.toSet(),
      )
    } finally {
      work.deleteRecursively()
    }
  }
}
