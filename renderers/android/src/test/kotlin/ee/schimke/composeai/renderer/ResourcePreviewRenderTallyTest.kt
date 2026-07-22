package ee.schimke.composeai.renderer

import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [ResourcePreviewRenderTest.tallyRenders] — the per-capture isolation that
 * keeps one un-rasterisable resource (an adaptive launcher icon / a vector Robolectric's NATIVE
 * graphics can't draw) from aborting the whole `composePreviewRenderAndroidResources` batch and
 * turning into a hard `rc=2` before the CLI's `--missing-renders` policy can downgrade it (issue
 * #2589). No Robolectric needed: the isolation contract is exercised with a plain rendering lambda.
 */
class ResourcePreviewRenderTallyTest {

  @Test
  fun `a throwing render is caught, counted missing, and the batch keeps going`() {
    val errors = mutableListOf<String>()
    val (rendered, missing) =
      ResourcePreviewRenderTest.tallyRenders(
        listOf("a", "boom", "c"),
        onError = { item, t -> errors.add("$item:${t.message}") },
      ) { item ->
        if (item == "boom") throw RuntimeException("cannot rasterise") else true
      }

    // "a" and "c" still rendered even though "boom" threw in the middle.
    assertEquals(2, rendered)
    assertEquals(1, missing)
    assertEquals(listOf("boom:cannot rasterise"), errors)
  }

  @Test
  fun `a false return counts as a deliberately-skipped capture without an error`() {
    val errors = mutableListOf<String>()
    val (rendered, missing) =
      ResourcePreviewRenderTest.tallyRenders(
        listOf(1, 2, 3, 4),
        onError = { _, _ -> errors.add("err") },
      ) { it % 2 == 0 } // even → rendered, odd → skipped (false)

    assertEquals(2, rendered)
    assertEquals(2, missing)
    assertTrue("onError must not fire for a clean false", errors.isEmpty())
  }

  @Test
  fun `every capture rendering yields zero missing`() {
    val (rendered, missing) =
      ResourcePreviewRenderTest.tallyRenders(listOf("x", "y", "z"), onError = { _, _ -> }) { true }
    assertEquals(3, rendered)
    assertEquals(0, missing)
  }

  @Test
  fun `a fatal (output) failure is re-thrown, not downgraded to missing`() {
    // Codex review, PR #2638: an I/O / output failure (ENOSPC, unwritable dir, a failed PNG write)
    // must still fail the task rather than be swallowed as a missing render — otherwise CI can pass
    // green under `--missing-renders warn` while the renderer couldn't write its output.
    val errors = mutableListOf<String>()
    val boom = IOException("No space left on device")
    val thrown =
      assertThrows(IOException::class.java) {
        ResourcePreviewRenderTest.tallyRenders(
          listOf("ok", "write-fails"),
          fatal = ResourcePreviewRenderTest::isOutputFailure,
          onError = { item, t -> errors.add("$item:${t.message}") },
        ) { item ->
          if (item == "write-fails") throw boom else true
        }
      }
    assertEquals(boom, thrown)
    // A fatal failure propagates immediately — it is NOT routed through onError as a missing render.
    assertTrue("fatal failures must not be reported as missing", errors.isEmpty())
  }

  @Test
  fun `isOutputFailure sees an IOException wrapped in a runtime exception`() {
    assertTrue(ResourcePreviewRenderTest.isOutputFailure(IOException("boom")))
    assertTrue(
      ResourcePreviewRenderTest.isOutputFailure(RuntimeException("wrap", IOException("disk full")))
    )
    // A pure rasterisation failure (no IOException anywhere in the chain) is NOT fatal.
    assertTrue(!ResourcePreviewRenderTest.isOutputFailure(UnsupportedOperationException("no draw")))
  }

  @Test
  fun `render-errors sidecar report round-trips through JSON`() {
    // The sidecar is the on-disk contract the CLI / preview server / VS Code read to surface why a
    // resource render is missing — pin its shape so a rename/field change is a deliberate break.
    val report =
      RenderErrorReport(
        entries =
          listOf(
            RenderErrorEntry(
              id = "mipmap/ic_launcher",
              renderOutput = "renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle.png",
              status = "failed",
              message = "RuntimeException: can't rasterise",
            ),
            RenderErrorEntry(
              id = "drawable/foo",
              renderOutput = "renders/resources/drawable/foo.png",
              status = "skipped",
              message = "no <monochrome> layer for THEMED_LIGHT",
            ),
          )
      )
    val json = Json { ignoreUnknownKeys = true }
    val decoded = json.decodeFromString<RenderErrorReport>(json.encodeToString(report))
    assertEquals(report, decoded)
    assertEquals("failed", decoded.entries[0].status)
    assertEquals("mipmap/ic_launcher", decoded.entries[0].id)
  }

  @Test
  fun `an empty render-errors report is the clean-run signal`() {
    val json = Json { ignoreUnknownKeys = true }
    val decoded = json.decodeFromString<RenderErrorReport>(json.encodeToString(RenderErrorReport()))
    assertTrue(decoded.entries.isEmpty())
  }

  @Test
  fun `an empty capture list tallies to zero`() {
    val (rendered, missing) =
      ResourcePreviewRenderTest.tallyRenders(emptyList<String>(), onError = { _, _ -> }) { true }
    assertEquals(0, rendered)
    assertEquals(0, missing)
  }
}
