package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.wear.compose.material3.AlertDialogContent
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reported case behind `ComposeSemanticsNode.placed` — yschimke/wear-m3-catalog#77, "what is
 * the second typography element here?".
 *
 * Wear's `AlertDialogContent` decides between a scrolling and a fixed layout by **subcomposing a
 * whole trial copy of the dialog** and measuring its unconstrained height. That copy is measured
 * and never placed, but it stays in the semantics tree — and an unplaced node reports its bounds at
 * the ORIGIN rather than out of frame, so every consumer that draws boxes off this tree drew a
 * second title stacked in the frame's top-left corner.
 *
 * The generic mechanism is pinned by `ComposeSemanticsCoreFieldsTest`'s own `SubcomposeLayout`
 * fixture; this pins the library shape that found it, because the trial measure is an
 * implementation detail of `AlertDialogContent` and nothing in the catalog asks for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearAlertDialogTrialMeasureTest {

  @Suppress("DEPRECATION") @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `the dialog's trial measure is reported unplaced, its real content placed`() {
    composeRule.setContent {
      MaterialTheme {
        AlertDialogContent(
          confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = {}) },
          dismissButton = { AlertDialogDefaults.DismissButton(onClick = {}) },
          title = { Text(TITLE) },
        )
      }
    }
    composeRule.waitForIdle()

    val root =
      ComposeSemanticsDataProducer.buildPayload(
          composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        )
        .root

    val titles = flatten(root).filter { it.text == TITLE }
    // Two copies of the title in the tree — the drawn one and the trial measure's — and exactly
    // one of them is on the frame.
    assertEquals(2, titles.size)
    assertEquals(1, titles.count { it.placed })
  }

  private fun flatten(node: ComposeSemanticsNode): List<ComposeSemanticsNode> =
    listOf(node) + node.children.flatMap { flatten(it) }

  private companion object {
    const val TITLE = "Dialog title one to three lines"
  }
}
