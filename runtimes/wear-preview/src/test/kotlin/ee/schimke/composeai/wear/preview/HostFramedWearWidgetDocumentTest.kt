@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.wear.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.RoundLargeWidgetPreviewParams
import androidx.glance.wear.tooling.preview.SquircleLargeWidgetPreviewParams
import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.rcembedded.player.ExperimentalRemoteDocumentPlayer
import ee.schimke.composeai.rcembedded.player.RemoteImageSupport
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [HostFramedWearWidgetDocument] names the host frame instead of baking it, and otherwise captures
 * what upstream's `WearWidgetDocument` does: played as captured, the two draw the same pixels at
 * every host shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostFramedWearWidgetDocumentTest {

  @get:Rule val rule = createComposeRule()

  private val squircle: WearWidgetParams =
    SquircleLargeWidgetPreviewParams().values.maxBy { it.widthDp }
  private val round: WearWidgetParams = RoundLargeWidgetPreviewParams().values.maxBy { it.widthDp }

  /** A content box that fills, in a colour of its own, so the inset shows. */
  private val content =
    @androidx.compose.remote.creation.compose.layout.RemoteComposable
    @androidx.compose.runtime.Composable {
      RemoteBox(modifier = RemoteModifier.fillMaxSize().background(Color.Yellow.rc))
    }

  private fun upstream(params: WearWidgetParams): ByteArray = runBlocking {
    WearWidgetDocument(WearWidgetBrush, content)
      .captureRawContent(RuntimeEnvironment.getApplication(), params, true)
      .rcDocument
  }

  private fun hostFramed(params: WearWidgetParams): ByteArray = runBlocking {
    HostFramedWearWidgetDocument(WearWidgetBrush, content)
      .captureRawContent(RuntimeEnvironment.getApplication(), params, true)
      .rcDocument
  }

  @Test
  fun `the host frame is declared by name`() {
    val text = String(hostFramed(squircle), Charsets.ISO_8859_1)
    for (frame in HostFramedWearWidgetDocument.HostFrame.entries) {
      assertThat(text).contains(frame.valueName)
    }
    assertThat(HostFramedWearWidgetDocument.HostFrame.valuesOf(round))
      .containsExactly(
        "wear.container.horizontal_padding",
        round.horizontalPaddingDp,
        "wear.container.vertical_padding",
        round.verticalPaddingDp,
        "wear.container.corner_radius",
        round.cornerRadiusDp,
      )
  }

  @Test
  fun `played as captured it draws what upstream draws at each host`() {
    var shown by mutableStateOf(squircle to upstream(squircle))
    rule.setContent {
      val (params, bytes) = shown
      key(shown) {
        val document =
          remember(bytes) {
            RemoteImageSupport.enableEncodedImageReferences()
            RemoteDocument(bytes)
          }
        Box(Modifier.testTag("widget")) {
          ExperimentalRemoteDocumentPlayer(
            document = document,
            modifier =
              Modifier.width((params.widthDp + 2f * params.horizontalPaddingDp).dp)
                .height((params.heightDp + 2f * params.verticalPaddingDp).dp),
          )
        }
      }
    }
    fun pixels(params: WearWidgetParams, bytes: ByteArray): PixelMap {
      rule.runOnIdle { shown = params to bytes }
      rule.waitForIdle()
      return rule.onNodeWithTag("widget").captureToImage().toPixelMap()
    }
    for (params in listOf(squircle, round)) {
      val expected = pixels(params, upstream(params))
      val actual = pixels(params, hostFramed(params))
      var differing = 0
      for (y in 0 until expected.height) for (x in 0 until expected.width) {
        if (expected[x, y] != actual[x, y]) differing++
      }
      assertThat(differing).isEqualTo(0)
      // The content box is drawn, and inset: the frame's corner is not the content's colour.
      assertThat(yellow(actual)).isGreaterThan(0)
      assertThat(actual[0, 0]).isNotEqualTo(Color.Yellow)
    }
    // What the device has to fix: a squircle capture keeps its 8dp insets in a round host's box,
    // so the content runs wider than the round host's own capture draws it.
    val roundOwn = pixels(round, upstream(round))
    val squircleInRound = pixels(round, hostFramed(squircle))
    assertThat(yellow(squircleInRound)).isGreaterThan(yellow(roundOwn))
  }

  private fun yellow(pixels: PixelMap): Int {
    var count = 0
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
      if (pixels[x, y] == Color.Yellow) count++
    }
    return count
  }
}
