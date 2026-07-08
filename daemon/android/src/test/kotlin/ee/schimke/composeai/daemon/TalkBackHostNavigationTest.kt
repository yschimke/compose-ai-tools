package ee.schimke.composeai.daemon

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies the TalkBack linear-navigation core (issue #1956) against a *real* Compose semantics
 * tree via a Robolectric compose rule — exercising the actual focus-stop extraction (which merged
 * nodes are stops, reading order, refs) and the cursor walk through [TalkBackHostNavigation.move],
 * the same code path `RobolectricHost.performTalkBackNavigation` drives during a recording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TalkBackHostNavigationTest {

  @get:Rule val composeRule = createComposeRule()

  /** Fetch the merged-tree semantics nodes the host would feed to [TalkBackHostNavigation]. */
  private fun mergedNodes() =
    composeRule
      .onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = false)
      .fetchSemanticsNodes(atLeastOneRootRequired = false)

  @Test
  fun `next walks the focus stops top to bottom and previous walks back, halting at boundaries`() {
    composeRule.setContent {
      Column {
        Text("Settings", modifier = Modifier.semantics { heading() })
        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Wi-Fi") }
        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Bluetooth") }
        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
      }
    }
    composeRule.waitForIdle()
    val nodes = mergedNodes()

    // Cold start: next enters at the first stop (the heading), then walks down.
    var cursor: String? = null
    var move = TalkBackHostNavigation.move(nodes, "next", cursor)
    assertTrue(move.matched)
    cursor = move.cursor

    val visited = mutableListOf<String?>()
    // Record the focused label at each stop by re-deriving from the focusTarget.
    visited += textOf(move)
    repeat(3) {
      move = TalkBackHostNavigation.move(nodes, "next", cursor)
      cursor = move.cursor
      if (move.matched) visited += textOf(move)
    }
    assertEquals(listOf("Settings", "Wi-Fi", "Bluetooth", "Sign out"), visited)

    // Past the last stop: end of screen, no wrap, cursor unchanged.
    val atEnd = TalkBackHostNavigation.move(nodes, "next", cursor)
    assertFalse("next past the last stop must not match (end of screen)", atEnd.matched)
    assertEquals(cursor, atEnd.cursor)

    // previous walks back up.
    move = TalkBackHostNavigation.move(nodes, "previous", cursor)
    assertEquals("Bluetooth", textOf(move))
    cursor = move.cursor
    move = TalkBackHostNavigation.move(nodes, "previous", cursor)
    assertEquals("Wi-Fi", textOf(move))
    cursor = move.cursor
    move = TalkBackHostNavigation.move(nodes, "previous", cursor)
    assertEquals("Settings", textOf(move))
    cursor = move.cursor

    // Before the first stop: end of screen.
    assertFalse(TalkBackHostNavigation.move(nodes, "previous", cursor).matched)
  }

  @Test
  fun `previous from cold start enters at the last stop`() {
    composeRule.setContent {
      Column {
        Button(onClick = {}) { Text("First") }
        Button(onClick = {}) { Text("Last") }
      }
    }
    composeRule.waitForIdle()
    val move = TalkBackHostNavigation.move(mergedNodes(), "previous", null)
    assertTrue(move.matched)
    assertEquals("Last", textOf(move))
  }

  @Test
  fun `an unlabeled editable node is a focus stop`() {
    // #1956 review: a node with no label and no OnClick is still a TalkBack stop when it carries
    // edit semantics. An empty BasicTextField (SetText + EditableText, no contentDescription) must
    // be walked — the old label-or-OnClick predicate dropped it.
    composeRule.setContent { BasicTextField(value = "", onValueChange = {}) }
    composeRule.waitForIdle()
    assertTrue(TalkBackHostNavigation.move(mergedNodes(), "next", null).matched)
  }

  @Test
  fun `a tree with no focus stops yields no move`() {
    composeRule.setContent { Column {} }
    composeRule.waitForIdle()
    val move = TalkBackHostNavigation.move(mergedNodes(), "next", null)
    assertFalse(move.matched)
  }

  private fun textOf(move: TalkBackHostNavigation.Move): String? =
    move.focusTarget?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
}
