package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.referencesMovingSystemVariable
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap

/**
 * The `remote-m3` indeterminate circular progress indicator, rendered from the exact bytes the
 * published catalog ships (#4264).
 *
 * It draws nothing that a synthetic document would have caught: the indicator's whole geometry —
 * the static track as much as the moving arc — is derived from one float expression over the
 * player-supplied continuous clock, so a player that leaves that id unloaded resolves it to raw
 * `NaN` and Skia silently discards every shape built from it. The parity lane saw that as "the
 * player drew nothing in 5015 ms", five seconds of an empty viewport with no error anywhere, while
 * the AndroidX and TypeScript players animated the same document.
 *
 * Asserted as *ink*, deliberately: a pixel comparison against a reference would be asserting the
 * clock phase this frame happened to catch, which is exactly the thing that legitimately varies.
 */
class RcIndeterminateProgressRenderTest {

  @Test
  fun theIndicatorDrawsAtTheClockPhaseItIsRenderedAt() {
    val document = fixture()
    assertTrue(
      document.referencesMovingSystemVariable(),
      "the fixture no longer animates off the player clock, so it no longer covers #4264",
    )
    assertTrue(inkPixels(document) > 0, "the indicator rendered as an empty frame")
  }

  private fun fixture(): RcDocument {
    val bytes =
      checkNotNull(javaClass.getResourceAsStream("/rc-fixtures/$FIXTURE")) {
          "missing fixture /rc-fixtures/$FIXTURE"
        }
        .use { it.readBytes() }
    return RcDocumentCodec.decode(bytes)
  }

  private fun inkPixels(document: RcDocument): Int {
    val scene =
      ImageComposeScene(width = SIZE, height = SIZE, density = Density(2f)) {
        RcComposePlayer(document)
      }
    try {
      val bitmap = Bitmap().apply { allocN32Pixels(SIZE, SIZE) }
      check(scene.render(0L).readPixels(bitmap))
      return (0 until SIZE).sumOf { x -> (0 until SIZE).count { y -> bitmap.getColor(x, y) != 0 } }
    } finally {
      scene.close()
    }
  }

  private companion object {
    const val FIXTURE = "IndeterminateCircularProgress-400x400.rc"
    const val SIZE = 400
  }
}
