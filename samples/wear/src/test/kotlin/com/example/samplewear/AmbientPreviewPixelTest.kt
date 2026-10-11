package com.example.samplewear

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * `@AmbientPreview` must drive `LocalAmbientModeManager` through the renderer: the Interactive and
 * Ambient renders must differ. Catches the override not being honoured, the annotation being
 * dropped by discovery, or the sample no longer reading the manager.
 */
class AmbientPreviewPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  private val interactivePng =
    renderFile(rendersDir, "AmbientStatusInteractivePreview_Ambient_body_interactive")

  private val ambientPng =
    renderFile(rendersDir, "AmbientStatusAmbientPreview_Ambient_body_ambient")

  /**
   * Both PNGs must exist and differ — same composition body, the only difference is the
   * `@AmbientPreview` annotation on one of them. If they hash-match, the renderer didn't apply the
   * connector's `AmbientOverrideExtension` for the annotated variant and the body fell back to
   * `AmbientMode.Interactive` for both.
   */
  @Test
  fun `Interactive and Ambient renders differ`() {
    assertThat(interactivePng.exists()).isTrue()
    assertThat(ambientPng.exists()).isTrue()

    val interactiveHash = interactivePng.readBytes().contentHashCode()
    val ambientHash = ambientPng.readBytes().contentHashCode()
    assertThat(interactiveHash).isNotEqualTo(ambientHash)
  }
}
