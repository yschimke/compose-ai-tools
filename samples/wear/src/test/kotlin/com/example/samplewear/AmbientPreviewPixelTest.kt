package com.example.samplewear

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * End-to-end verification that `@AmbientPreview` actually drives `LocalAmbientModeManager` through
 * the renderer's Compose pipeline. Reads the files produced by `:samples:wear:renderAllPreviews`
 * (wired in via `composePreview { renderBeforeUnitTests = true }`) and pixel-asserts that the
 * Interactive vs Ambient renders differ.
 *
 * What this guards against:
 *
 * * Renderer-side regressions where `RenderPreviewCapture.ambient` isn't honoured — the
 *   `AmbientOverrideExtension` wouldn't wrap the composition and both PNGs would render the
 *   `Interactive` fallback (the bug PR #907 fixed: previously horologist's `AmbientAware` fell
 *   back to `Inactive` and produced identical "Inactive" captures).
 * * Discovery-side regressions where the `@AmbientPreview` annotation is dropped from
 *   `previews.json`, the `ambient` capture field arrives null at the renderer, and the override
 *   never fires.
 * * Sample-side regressions where the body stops reading from `LocalAmbientModeManager` (e.g. an
 *   accidental hard-coded `AmbientMode.Interactive` fallback).
 */
class AmbientPreviewPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  private val interactivePng =
    File(rendersDir, "AmbientPreviewsKt.AmbientStatusInteractivePreview_Ambient_body_interactive.png")

  private val ambientPng =
    File(rendersDir, "AmbientPreviewsKt.AmbientStatusAmbientPreview_Ambient_body_ambient.png")

  /**
   * Both PNGs must exist and differ — same composition body, the only difference is the
   * `@AmbientPreview` annotation on one of them. If they hash-match, the renderer didn't apply
   * the connector's `AmbientOverrideExtension` for the annotated variant and the body fell back
   * to `AmbientMode.Interactive` for both.
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
