@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the resolved design-token extraction (issue #1897) against a real desktop
 * [ImageComposeScene]: `Modifier.background` → container colour, the background/clip shape → corner
 * radius, and `Modifier.padding` → per-edge insets. These are the tokens design-parity's
 * token-compliance check compares against — before this the desktop sidecar carried only the text
 * foreground colour + bounds, so colour/spacing/radius tokens degraded to "missing from candidate".
 */
class DesktopSemanticsTokensTest {

  private fun buildTree(content: @Composable () -> Unit): ComposeSemanticsNode {
    val scene =
      ImageComposeScene(width = 400, height = 400, density = Density(1.0f), content = content)
    try {
      scene.render()
      val root: SemanticsNode = scene.semanticsOwners.first().unmergedRootSemanticsNode
      return ComposeSemanticsDataProducer.buildPayload(root).root
    } finally {
      scene.close()
    }
  }

  private fun ComposeSemanticsNode.find(tag: String): ComposeSemanticsNode? {
    if (testTag == tag) return this
    return children.firstNotNullOfOrNull { it.find(tag) }
  }

  @Test
  fun resolves_background_colour_corner_radius_and_uniform_padding() {
    val root = buildTree {
      Box(
        Modifier.testTag("card")
          .background(Color(0xFF006A60), RoundedCornerShape(12.dp))
          .padding(16.dp)
      ) {
        Text("Card body")
      }
    }

    val card = root.find("card")
    assertNotNull("expected a node tagged 'card'", card)
    val tokens = card!!.tokens
    assertNotNull("card must carry resolved tokens", tokens)
    assertEquals("#FF006A60", tokens!!.backgroundColor)
    assertEquals("12.0dp", tokens.cornerRadius)
    assertEquals("16.0dp", tokens.padding?.start)
    assertEquals("16.0dp", tokens.padding?.top)
    assertEquals("16.0dp", tokens.padding?.end)
    assertEquals("16.0dp", tokens.padding?.bottom)
  }

  @Test
  fun resolves_per_edge_padding() {
    val root = buildTree {
      Box(Modifier.testTag("row").padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp))
    }

    val padding = root.find("row")?.tokens?.padding
    assertNotNull("row must carry resolved padding", padding)
    assertEquals("8.0dp", padding!!.start)
    assertEquals("4.0dp", padding.top)
    assertEquals("8.0dp", padding.end)
    assertEquals("4.0dp", padding.bottom)
  }

  @Test
  fun node_without_container_tokens_emits_null() {
    // Plain text carries text-layout fields (layoutForegroundColor etc.) but no container tokens.
    val root = buildTree { Text("just text", modifier = Modifier.testTag("label")) }

    val label = root.find("label")
    assertNotNull(label)
    assertNull("a node with no background / shape / padding must omit tokens", label!!.tokens)
  }
}
