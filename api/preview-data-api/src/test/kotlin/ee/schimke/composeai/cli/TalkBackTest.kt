package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the JVM `cli` TalkBack helpers (issue #1956) to the same behaviour as the `:data-a11y-core`
 * originals — the field-for-field mirror is only safe if both sides agree on these exact strings
 * and boundaries. Mirrors the a11y-core suites' key cases.
 */
class TalkBackTest {

  private fun node(
    label: String = "",
    ref: String? = null,
    role: String? = null,
    states: List<String> = emptyList(),
    merged: Boolean = true,
  ) =
    AccessibilityNode(
      label = label,
      ref = ref,
      role = role,
      states = states,
      merged = merged,
      boundsInScreen = "0,0,10,10",
    )

  @Test
  fun utterance_button() {
    assertEquals(
      "Buy now, button, double-tap to activate",
      TalkBackUtterance.compose(
        node(label = "Buy now", role = "Button", states = listOf("clickable"))
      ),
    )
  }

  @Test
  fun utterance_checkboxUncheckedAndDisabledAndSlider() {
    assertEquals(
      "Remember me, checkbox, not checked, double-tap to toggle",
      TalkBackUtterance.compose(
        node(label = "Remember me", role = "CheckBox", states = listOf("unchecked", "clickable"))
      ),
    )
    assertEquals(
      "Submit, button, disabled",
      TalkBackUtterance.compose(
        node(label = "Submit", role = "Button", states = listOf("clickable", "disabled"))
      ),
    )
    assertEquals(
      "Volume, seekbar, 70%",
      TalkBackUtterance.compose(node(label = "Volume", role = "SeekBar", states = listOf("70%"))),
    )
  }

  private val tree =
    listOf(
      node(label = "h", ref = "a/Header[0]", merged = true),
      node(label = "c", ref = "a/Card[0]", merged = true),
      node(label = "t", ref = "a/Text[0]", merged = false),
      node(label = "b", ref = "a/Button[0]", merged = true),
    )

  @Test
  fun traversal_focusStopsAndMoves() {
    assertEquals(
      listOf("a/Header[0]", "a/Card[0]", "a/Button[0]"),
      TalkBackTraversal.focusStops(tree).map { it.ref },
    )
    assertEquals("a/Header[0]", TalkBackTraversal.next(tree, null)?.ref)
    assertEquals("a/Button[0]", TalkBackTraversal.next(tree, "a/Card[0]")?.ref)
    assertNull(TalkBackTraversal.next(tree, "a/Button[0]"))
    assertEquals("a/Button[0]", TalkBackTraversal.previous(tree, null)?.ref)
    assertNull(TalkBackTraversal.previous(tree, "a/Header[0]"))
  }

  @Test
  fun frames_dwellAndClamp() {
    assertEquals(0, TalkBackOverlayFrames.focusedStopForFrame(0, 30, stopCount = 3, dwellMs = 900L))
    assertEquals(
      1,
      TalkBackOverlayFrames.focusedStopForFrame(27, 30, stopCount = 3, dwellMs = 900L),
    )
    assertEquals(2, TalkBackOverlayFrames.focusedStopForFrame(10_000, 30, stopCount = 3))
    assertEquals(-1, TalkBackOverlayFrames.focusedStopForFrame(0, 30, stopCount = 0))
    assertEquals(82, TalkBackOverlayFrames.totalFrames(30, stopCount = 3, dwellMs = 900L))
  }
}
