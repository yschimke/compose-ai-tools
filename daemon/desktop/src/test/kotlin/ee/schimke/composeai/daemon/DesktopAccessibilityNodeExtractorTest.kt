@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.cli.AccessibilityNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the desktop Compose-semantics → [AccessibilityNode] extraction against a real
 * [ImageComposeScene], so the overlay-only a11y path's node list (label / role / states / merged)
 * matches what the AWT overlay groups and draws.
 *
 * Three scenarios mirror the Android `AccessibilityChecker` contract:
 * - a `Button { Text(...) }` produces a merged parent (role `Button`, `clickable`) whose inner
 *   `Text` is dropped (shadowed by the labelled merging ancestor);
 * - a `Checkbox` produces a `checked` / `unchecked` state chip from its `ToggleableState`;
 * - an unlabelled `Modifier.clickable` box with a role is kept (label empty, `clickable` state).
 */
class DesktopAccessibilityNodeExtractorTest {

  private fun extract(
    content: @androidx.compose.runtime.Composable () -> Unit
  ): List<AccessibilityNode> {
    val scene =
      ImageComposeScene(width = 300, height = 300, density = Density(1.0f), content = content)
    try {
      scene.render()
      val root: SemanticsNode = scene.semanticsOwners.first().unmergedRootSemanticsNode
      return DesktopAccessibilityNodeExtractor.extractNodes(root)
    } finally {
      scene.close()
    }
  }

  @Test
  fun button_with_text_is_merged_parent_and_inner_text_dropped() {
    val nodes = extract { Button(onClick = {}) { Text("Go") } }

    // The merging Button surfaces as a labelled, clickable parent with role Button; its inner Text
    // merges into it and is dropped (a screen reader reads only the button's announcement).
    val button = nodes.singleOrNull { it.label == "Go" }
    assertNotNull("expected a single merged Button node labelled 'Go', got $nodes", button)
    assertEquals("Button", button!!.role)
    assertTrue("Button must carry the clickable chip", "clickable" in button.states)
    assertTrue("Button must be a merged focus stop", button.merged)
    assertEquals("inner Text must be dropped — only the merged parent remains", 1, nodes.size)
  }

  @Test
  fun checkbox_emits_checked_or_unchecked_state() {
    val checked = extract { Checkbox(checked = true, onCheckedChange = {}) }
    val unchecked = extract { Checkbox(checked = false, onCheckedChange = {}) }

    assertTrue(
      "a checked Checkbox must carry the 'checked' chip, got $checked",
      checked.any { "checked" in it.states },
    )
    assertTrue(
      "an unchecked Checkbox must carry the 'unchecked' chip, got $unchecked",
      unchecked.any { "unchecked" in it.states },
    )
  }

  @Test
  fun unlabelled_clickable_is_kept_with_clickable_state() {
    // A sized, clickable Box with no text/contentDescription — kept because it's actionable, even
    // though its label is empty. (The overlay renders the role / "(unlabelled)" placeholder.)
    val nodes = extract { Box(modifier = Modifier.size(80.dp).clickable {}) }

    val clickable = nodes.firstOrNull { "clickable" in it.states }
    assertNotNull("an unlabelled clickable must still be kept, got $nodes", clickable)
    assertEquals("its label is empty", "", clickable!!.label)
  }
}
