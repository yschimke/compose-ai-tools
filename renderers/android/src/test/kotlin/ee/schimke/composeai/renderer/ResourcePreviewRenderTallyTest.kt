package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
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
  fun `an empty capture list tallies to zero`() {
    val (rendered, missing) =
      ResourcePreviewRenderTest.tallyRenders(emptyList<String>(), onError = { _, _ -> }) { true }
    assertEquals(0, rendered)
    assertEquals(0, missing)
  }
}
