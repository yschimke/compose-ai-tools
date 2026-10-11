package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The two `@Preview`s over `PermissionGatedCameraScreen` must capture different branches: the
 * granted one's `@PermissionPreview(grants = ["android.permission.CAMERA=granted"])` seeds
 * Robolectric's grants so the viewfinder branch renders. Like `:samples:wear`'s
 * `GestureHintPreviewPixelTest`.
 *
 * Renders are found by function-name prefix, since discovery owns the exact stem
 * (`docs/RENDER_FILENAMES.md`).
 */
class PermissionPreviewPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  private fun renderFor(functionName: String): File {
    val matches =
      rendersDir
        .listFiles { file -> file.name.startsWith(functionName) && file.name.endsWith(".png") }
        ?.sortedBy { it.name }
        .orEmpty()
    assertThat(matches).hasSize(1)
    return matches.single()
  }

  @Test
  fun `denied and granted permission previews render different pixels`() {
    val denied = renderFor("CameraPermissionDeniedPreview")
    val granted = renderFor("CameraPermissionGrantedPreview")

    assertThat(denied.length()).isGreaterThan(0L)
    assertThat(granted.length()).isGreaterThan(0L)
    // Byte comparison rather than a per-pixel walk: the two branches differ in body copy and in
    // whether a button is present at all, so any difference at all is the signal, and an identical
    // pair is exactly the regression.
    assertThat(granted.readBytes().contentHashCode())
      .isNotEqualTo(denied.readBytes().contentHashCode())
  }
}
