package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * End-to-end verification for issue #3676: the two `@Preview`s over `PermissionGatedCameraScreen`
 * capture *different* branches.
 *
 * Before `@PermissionPreview` existed, the static build published two byte-identical "needs
 * permission" PNGs, one of them labelled "granted" — a wrong artefact, which is worse than a
 * missing one, because a reviewer reading the catalog has no way to tell. The granted preview now
 * carries `@PermissionPreview(grants = ["android.permission.CAMERA=granted"])`, which the renderer
 * turns into a `PermissionsOverrideExtension` seeding Robolectric's `ShadowApplication` grant set
 * before the first composition, so `ContextCompat.checkSelfPermission(...)` returns
 * `PERMISSION_GRANTED` and the screen takes its viewfinder branch.
 *
 * Reads the files produced by `:samples:android:composePreviewRenderAll` (wired into this module's
 * `test` task via `composePreview { renderBeforeUnitTests = true }`), mirroring `:samples:wear`'s
 * `GestureHintPreviewPixelTest` — the same "two renders of one screen must differ" assertion for
 * the same class of environment override.
 *
 * The renders are located by function-name prefix rather than by a hardcoded filename: the exact
 * stem is owned by discovery's filename normalisation (`docs/RENDER_FILENAMES.md`), which folds the
 * `@Preview(name = …)` variant suffix in and sanitises it, and pinning a copy of that here would
 * make this test fail for a reason that has nothing to do with permissions.
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
