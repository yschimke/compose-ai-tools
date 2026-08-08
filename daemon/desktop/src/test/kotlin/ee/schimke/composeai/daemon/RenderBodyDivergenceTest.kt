package ee.schimke.composeai.daemon

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Measures whether the **two duplicated desktop render bodies still agree pixel-for-pixel**.
 *
 * [RenderEngine]'s KDoc records that it is a deliberate copy of `:renderer-desktop`'s
 * `renderPreview`, kept in sync by hand until the v2 reconciliation extracts a shared helper
 * ([DESIGN.md §
 * 7](../../../../../../docs/daemon/DESIGN.md#7-sharing-strategy--what-crosses-the-boundary)), and
 * that "the bench + CI pixel-diff will catch divergence". Nothing in the ordinary test suite
 * actually renders the *same* preview down *both* paths and compares, so that claim has never been
 * checked directly — this does.
 *
 * Why it matters beyond hygiene: the only remaining fork-per-render cost in the tree is
 * `composePreviewRender`, which spawns one `javaexec` per capture (measured at 2.15 s/preview by
 * m3-catalog, ~43 min for its 1095-preview catalog). Routing it at the daemon instead would
 * amortise that the way `RcJvmWorkerPool` did for the cmp-jvm lane — but only if the daemon draws
 * the same pixels the standalone renderer does. If these two have already drifted, every baked
 * catalog PNG would move on the switch, and the drift is a bug to fix first rather than a diff to
 * accept.
 *
 * Scope is deliberately the subset both bodies claim to support: a single frame of a simple
 * composable. `@PreviewParameter` fan-out, scroll/animation/GIF data products and the LOTTIE path
 * are documented as standalone-renderer-only, so they are out of scope here and are the reason the
 * routing change needs the v2 extraction first — see the report on this branch.
 */
class RenderBodyDivergenceTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun theDaemonAndStandaloneRenderBodiesProduceTheSamePng() {
    val daemonPng = renderViaDaemon()
    val standalonePng = renderViaStandaloneRenderer()

    assertTrue("daemon render produced no bytes", daemonPng.isNotEmpty())
    assertTrue("standalone render produced no bytes", standalonePng.isNotEmpty())

    if (!daemonPng.contentEquals(standalonePng)) {
      System.err.println(
        "RENDER BODY DIVERGENCE: daemon ${daemonPng.size} bytes vs standalone " +
          "${standalonePng.size} bytes; first difference at byte " +
          firstDifference(daemonPng, standalonePng)
      )
    }
    assertArrayEquals(
      "the daemon's duplicated render body must draw the same pixels as :renderer-desktop's " +
        "renderPreview — they are hand-synced copies, and any drift moves every baked PNG the " +
        "moment a caller is routed from one to the other",
      standalonePng,
      daemonPng,
    )
  }

  private fun renderViaDaemon(): ByteArray {
    val outputDir = tempFolder.newFolder("daemon-renders")
    val host = DesktopHost(engine = RenderEngine(outputDir = outputDir))
    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(
            payload =
              "className=$FIXTURE_CLASS;" +
                "functionName=$FIXTURE_FUNCTION;" +
                "widthPx=$WIDTH;heightPx=$HEIGHT;density=$DENSITY;" +
                "showBackground=true;" +
                "outputBaseName=divergence-daemon"
          ),
          timeoutMs = 120_000,
        )
      val path = requireNotNull(result.pngPath) { "daemon render returned no pngPath" }
      return File(path).readBytes()
    } finally {
      host.shutdown(timeoutMs = 30_000)
    }
  }

  /**
   * Drive `:renderer-desktop`'s `main()` in-process. It is the same entry point
   * `RenderPreviewsTask.invokeRenderer` spawns per capture, and on the success path it only writes
   * the file and returns — `exitProcess` is reached solely on argument/render failure, which would
   * fail this test loudly rather than silently killing the JVM.
   */
  private fun renderViaStandaloneRenderer(): ByteArray {
    val outputFile = File(tempFolder.newFolder("standalone-renders"), "divergence-standalone.png")
    ee.schimke.composeai.renderer.main(
      arrayOf(
        FIXTURE_CLASS,
        FIXTURE_FUNCTION,
        WIDTH.toString(),
        HEIGHT.toString(),
        DENSITY.toString(),
        /* showBackground = */ "true",
        /* backgroundColor = */ "0",
        outputFile.absolutePath,
      )
    )
    assertTrue("standalone renderer wrote no file", outputFile.isFile)
    return outputFile.readBytes()
  }

  private fun firstDifference(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) if (a[i] != b[i]) return i
    return n
  }

  private companion object {
    const val FIXTURE_CLASS = "ee.schimke.composeai.daemon.RedFixturePreviewsKt"
    const val FIXTURE_FUNCTION = "RedSquare"
    const val WIDTH = 64
    const val HEIGHT = 64
    const val DENSITY = 1.0f
  }
}
