package com.example.samplexrglimmer

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Asserts the interactive XR menu navigation GIF produced by
 * `:samples:xr-glimmer:composePreviewRenderAll` lands at the right path with the right shape.
 *
 * `GlimmerXrMenuNavigation` carries `@FocusedPreview(indices = [0, 1, 2, 3], gif = true)`, so the
 * renderer drives focus across four Glimmer `ListItem`s (one `moveFocus(Enter)` on the first
 * capture, then `moveFocus(Next)` to walk forward) and stitches the four captures into a single
 * `.gif`. The PNG fan-out (`_FOCUS_0.png` etc.) gets swapped for the stitched output and the
 * sibling PNGs must NOT be written — the whole point of the `gif = true` flag is to collapse the
 * fan-out. Together these two guards catch:
 *
 *  - GIF stitching breakages (the file disappears or shrinks to zero / loses its magic header).
 *  - Regressions where the renderer writes both the GIF *and* the per-step PNGs (a duplicate-
 *    output mode would silently double the `:samples:xr-glimmer` render budget).
 *  - A future renderer change that renames the GIF (e.g. dropping the trailing `_FOCUS` suffix
 *    on the GIF path) — the new filename would land outside the assertion below and we'd hear
 *    about it loudly here rather than in a downstream skill that hard-codes the same path.
 */
class GlimmerInteractiveMenuTest {

  private val rendersDir = File("build/compose-previews/renders")

  // Filename mirrors the renderer's sanitisation rules (docs/RENDER_FILENAMES.md): the middle
  // dot and surrounding spaces in `Glimmer · XR Menu · Navigation` collapse to underscores,
  // consecutive underscores compact to one — landing at `Glimmer_XR_Menu_Navigation`. Function
  // name prefix `GlimmerXrMenuNavigation_` is the standard form (the common package prefix
  // `com.example.samplexrglimmer.GlimmerInteractiveMenuPreviewsKt.` is stripped per the
  // discovery rule).
  private val gifBasename = "GlimmerXrMenuNavigation_Glimmer_XR_Menu_Navigation"

  @Test
  fun `interactive menu navigation lands as a single non-empty GIF`() {
    val gif = File(rendersDir, "$gifBasename.gif")
    assertThat(gif.exists()).isTrue()
    assertThat(gif.length()).isGreaterThan(0L)
    val header = gif.inputStream().use { it.readNBytes(6).toString(Charsets.US_ASCII) }
    assertThat(header).isAnyOf("GIF87a", "GIF89a")
  }

  @Test
  fun `gif flag collapses the per-step PNG fan-out`() {
    // Four indices in the @FocusedPreview annotation → four virtual frames in the stitched
    // GIF. None of them should leak out as standalone PNGs.
    (0..3).forEach { i ->
      val sibling = File(rendersDir, "${gifBasename}_FOCUS_$i.png")
      assertThat(sibling.exists()).isFalse()
    }
  }
}
