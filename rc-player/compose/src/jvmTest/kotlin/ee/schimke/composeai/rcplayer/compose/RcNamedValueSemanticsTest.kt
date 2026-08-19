package ee.schimke.composeai.rcplayer.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcRootContentDescription
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import kotlin.test.Test

/**
 * A named text can back the root content description, which means a host edit has to reach the
 * *semantics* tree and not only the pixels.
 *
 * It nearly did not. `invalidationVersion` — the signal every non-action mutation raises — is read
 * during composition only inside the `layout != null` branch; everywhere else it is read inside a
 * `drawWithContent` lambda, which redraws without recomposing. So on a legacy canvas document the
 * label a screen reader announces stayed at the document's original string while the render showed
 * the new one. Silent, and invisible to a pixel test, which is why this one asserts through the
 * semantics tree instead.
 */
class RcNamedValueSemanticsTest {

  @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
  @Test
  fun aNamedTextChangeUpdatesTheRootContentDescription() =
    runSkikoComposeUiTest(size = Size(40f, 40f), density = Density(1f)) {
      val namedValues = mutableStateMapOf<String, RcNamedValue>()
      val document =
        RcDocument(
          RcHeader(RcVersion(1, 0, 0), legacyWidth = 40, legacyHeight = 40, modern = false),
          listOf(
            RcTextData(10, "Seventy-two degrees"),
            RcNamedVariable(10, RcNamedVariable.STRING_TYPE, "USER:summary"),
            RcRootContentDescription(10),
          ),
        )
      setContent { RcComposePlayer(document, Modifier.fillMaxSize(), namedValues = namedValues) }
      waitForIdle()
      onNodeWithContentDescription("Seventy-two degrees").assertExists()

      namedValues["USER:summary"] = RcNamedValue.Text("Sixty-four degrees")
      waitForIdle()

      onNodeWithContentDescription("Sixty-four degrees").assertExists()
      onNodeWithContentDescription("Seventy-two degrees").assertDoesNotExist()
    }

  /**
   * A parent may hand the same document a *different* holder — hoisting the map, or rebuilding it
   * from a new screen state. The collector has to follow the replacement rather than stay
   * subscribed to the detached one, and it has to do so without rebuilding `RcPlayerState`, which
   * is the entire point of the bridge.
   *
   * Both halves are asserted: the new holder's value lands, and a key only the *old* holder carried
   * is cleared rather than left applied — which only works because the effect tracks what the state
   * holds rather than re-reading whichever map it happens to be looking at.
   */
  @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
  @Test
  fun replacingTheHolderForTheSameDocumentIsFollowed() =
    runSkikoComposeUiTest(size = Size(40f, 40f), density = Density(1f)) {
      val first =
        mutableStateMapOf<String, RcNamedValue>(
          "USER:summary" to RcNamedValue.Text("From the first holder")
        )
      val second = mutableStateMapOf<String, RcNamedValue>()
      var holder by mutableStateOf(first)
      setContent { RcComposePlayer(document(), Modifier.fillMaxSize(), namedValues = holder) }
      waitForIdle()
      onNodeWithContentDescription("From the first holder").assertExists()

      holder = second
      waitForIdle()
      // `second` is empty, so the first holder's override must be cleared back to the document's
      // own text — not left in place, and not answered from the map nobody is holding any more.
      onNodeWithContentDescription("From the first holder").assertDoesNotExist()
      onNodeWithContentDescription("Seventy-two degrees").assertExists()

      second["USER:summary"] = RcNamedValue.Text("From the second holder")
      waitForIdle()
      onNodeWithContentDescription("From the second holder").assertExists()

      // Writes to the detached holder must not reach the player.
      first["USER:summary"] = RcNamedValue.Text("Detached")
      waitForIdle()
      onNodeWithContentDescription("Detached").assertDoesNotExist()
      onNodeWithContentDescription("From the second holder").assertExists()
    }

  private fun document(): RcDocument =
    RcDocument(
      RcHeader(RcVersion(1, 0, 0), legacyWidth = 40, legacyHeight = 40, modern = false),
      listOf(
        RcTextData(10, "Seventy-two degrees"),
        RcNamedVariable(10, RcNamedVariable.STRING_TYPE, "USER:summary"),
        RcRootContentDescription(10),
      ),
    )
}
