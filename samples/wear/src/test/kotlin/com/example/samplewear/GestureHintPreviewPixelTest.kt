package com.example.samplewear

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * End-to-end verification that `@GestureHintPreview` actually force-shows the one-handed-gesture hint
 * through the renderer's Compose pipeline. Reads the files produced by
 * `:samples:wear:composePreviewRenderAll` (wired in via `composePreview { renderBeforeUnitTests =
 * true }`) and pixel-asserts that the hint-off vs hint-on renders differ.
 *
 * The two previews render the *same* `MediaGestureScreen()` — ordinary app code with no preview-only
 * flags. The only difference is the `@GestureHintPreview` annotation on one of them. If they
 * hash-match, the override didn't reach `GestureHint`:
 * - discovery dropped the annotation from `previews.json` (the `gestureHint` capture arrives null), or
 * - the renderer didn't wrap the composition with `:data-gestures-connector`'s
 *   `GestureOverrideExtension`, or
 * - `GestureHint`'s force-show path stopped compositing the indicator drawable (the interactive
 *   `OneHandedGestureIndicator` alone settles to hidden in a still, so both would render plain).
 */
class GestureHintPreviewPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  private val hintOffPng = File(rendersDir, "MediaGestureScreenPreview_Media_hints_off.png")

  private val hintOnPng = File(rendersDir, "MediaGestureScreenHintPreview_Media_hints_on.png")

  @Test
  fun `hint-off and hint-on renders differ`() {
    assertThat(hintOffPng.exists()).isTrue()
    assertThat(hintOnPng.exists()).isTrue()

    val offHash = hintOffPng.readBytes().contentHashCode()
    val onHash = hintOnPng.readBytes().contentHashCode()
    assertThat(offHash).isNotEqualTo(onHash)
  }
}
