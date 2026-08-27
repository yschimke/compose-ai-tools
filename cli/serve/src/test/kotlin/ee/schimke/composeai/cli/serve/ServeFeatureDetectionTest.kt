package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.Capture
import ee.schimke.composeai.previewdata.PreviewInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * [detectedFeaturesOf] folds per-capture `@FocusedPreview` / `@GestureHintPreview` presence markers
 * into the two booleans the viewer gates its detected-feature controls on.
 */
class ServeFeatureDetectionTest {

  private fun preview(vararg captures: Capture) =
    PreviewInfo(
      id = "com.example.P",
      functionName = "P",
      className = "com.example.PKt",
      captures = captures.toList(),
    )

  @Test
  fun `a focus capture flags focus support only`() {
    val (focus, gestures) = detectedFeaturesOf(preview(Capture(focus = buildJsonObject {})))
    assertEquals(true to false, focus to gestures)
  }

  @Test
  fun `a focusGif capture also flags focus support`() {
    val (focus, _) = detectedFeaturesOf(preview(Capture(focusGif = JsonPrimitive("x"))))
    assertEquals(true, focus)
  }

  @Test
  fun `a gestureHint capture flags gesture support only`() {
    val (focus, gestures) = detectedFeaturesOf(preview(Capture(gestureHint = buildJsonObject {})))
    assertEquals(false to true, focus to gestures)
  }

  @Test
  fun `an ordinary preview supports neither`() {
    val (focus, gestures) = detectedFeaturesOf(preview(Capture()))
    assertEquals(false to false, focus to gestures)
  }

  @Test
  fun `support is folded across all captures`() {
    // A preview whose first capture is plain but a later one carries the annotation still counts.
    val (focus, _) = detectedFeaturesOf(preview(Capture(), Capture(focus = buildJsonObject {})))
    assertEquals(true, focus)
  }
}
