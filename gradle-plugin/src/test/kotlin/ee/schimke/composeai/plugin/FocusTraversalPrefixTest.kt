package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.FocusCapture
import ee.schimke.composeai.discovery.FocusDirection
import ee.schimke.composeai.discovery.PreviewInfo
import org.junit.Test

/**
 * Pins [focusTraversalPrefix] — the plugin-side half of desktop `@FocusedPreview(traverse = [...])`
 * support (issue #3672).
 *
 * The Android renderer keeps one composition alive across a preview's captures, so step N inherits
 * where step N-1 left focus. The desktop renderer runs one process per capture and has nothing to
 * inherit, so the plugin has to hand it the whole walk up to that step. Get this wrong and a
 * traversal sheet renders the same first-focusable frame under every step's filename — the exact
 * "the capture doesn't show what its name claims" defect the focus work exists to remove.
 */
class FocusTraversalPrefixTest {

  private fun preview(vararg focuses: FocusCapture) =
    PreviewInfo(
      id = "Traversal",
      functionName = "Traversal",
      className = "com.example.Traversal",
      captures = focuses.map { Capture(focus = it, renderOutput = "renders/x.png") },
    )

  private fun step(index: Int, direction: FocusDirection) =
    FocusCapture(direction = direction, step = index)

  @Test
  fun `traversal step carries every preceding direction in order`() {
    val steps =
      listOf(
        step(1, FocusDirection.Next),
        step(2, FocusDirection.Next),
        step(3, FocusDirection.Previous),
        step(4, FocusDirection.Next),
      )
    val info = preview(*steps.toTypedArray())

    assertThat(focusTraversalPrefix(info, steps[0])).containsExactly("Next").inOrder()
    assertThat(focusTraversalPrefix(info, steps[2]))
      .containsExactly("Next", "Next", "Previous")
      .inOrder()
    assertThat(focusTraversalPrefix(info, steps[3]))
      .containsExactly("Next", "Next", "Previous", "Next")
      .inOrder()
  }

  /**
   * `captures` is the cross product of the scroll / timing / focus fan-outs, so a traversal crossed
   * with another dimension carries every step once *per row*. The prefix must still be the walk —
   * one move per step — or step 2 of a two-timing traversal would dispatch four `Next` moves and
   * land past the element its filename names.
   */
  @Test
  fun `steps repeated across a cross-product fan-out are not replayed twice`() {
    val step1 = step(1, FocusDirection.Next)
    val step2 = step(2, FocusDirection.Next)
    // Two clock timings × two traversal steps = four capture rows, each carrying its focus state.
    val crossed = preview(step1, step2, step1, step2)
    assertThat(focusTraversalPrefix(crossed, step2)).containsExactly("Next", "Next").inOrder()
  }

  @Test
  fun `indexed captures carry no traversal prefix`() {
    val indexed = FocusCapture(tabIndex = 2)
    assertThat(focusTraversalPrefix(preview(indexed), indexed)).isEmpty()
  }

  @Test
  fun `a capture without focus intent carries no prefix`() {
    assertThat(focusTraversalPrefix(preview(), null)).isEmpty()
  }
}
