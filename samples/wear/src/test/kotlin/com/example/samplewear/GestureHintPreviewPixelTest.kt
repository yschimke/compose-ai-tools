package com.example.samplewear

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * End-to-end verification that a clean component can expose its public one-handed-gesture
 * indicator state for deterministic preview capture. Reads the files produced by
 * `:samples:wear:composePreviewRenderAll` (wired in via `composePreview { renderBeforeUnitTests =
 * true }`) and pixel-asserts that the hint-off vs hint-on renders differ.
 *
 * The two previews render the same `MediaGestureScreen`: one at rest and one with
 * `showIndicators = true`. Both paths use `OneHandedGestureClickIndicatorState.showIndicator()`;
 * there is no alternate hint UI to overlap the button content.
 */
class GestureHintPreviewPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  private val hintOffPng = File(rendersDir, "MediaGestureScreenPreview_Media_hints_off.png")

  private val hintOnPng =
    File(rendersDir, "MediaGestureScreenHintPreview_Media_hints_on_TIME_800ms.png")

  @Test
  fun `hint-off and hint-on renders differ`() {
    assertThat(hintOffPng.exists()).isTrue()
    assertThat(hintOnPng.exists()).isTrue()

    val offHash = hintOffPng.readBytes().contentHashCode()
    val onHash = hintOnPng.readBytes().contentHashCode()
    assertThat(offHash).isNotEqualTo(onHash)
  }
}
