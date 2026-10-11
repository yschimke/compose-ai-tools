package com.example.samplexrglimmer

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Checks the interactive XR menu GIFs: each `GlimmerXrMenu*` function's
 * `@FocusedPreview(indices = [0, 1, 2, 3], gif = true)` walks focus across four `ListItem`s and
 * stitches one `.gif`. Guards against broken stitching, per-step `_FOCUS_<n>.png` files also being
 * written, a renamed GIF path, and environment-backdrop drift (the four composited GIFs must differ
 * while their `.raw.gif` siblings stay identical).
 */
class GlimmerInteractiveMenuTest {

  private val rendersDir = File("build/compose-previews/renders")

  // `<functionName>_<previewName>` with disallowed characters collapsed (docs/RENDER_FILENAMES.md),
  // e.g. `GlimmerXrMenuLight_Light.gif`.
  private val envGifBasenames =
    listOf(
      "GlimmerXrMenuLight_Light",
      "GlimmerXrMenuDark_Dark",
      "GlimmerXrMenuBusy_Busy",
      "GlimmerXrMenuVeniceCanalCats_VeniceCanalCats",
    )

  @Test
  fun `every env variant lands as a non-empty GIF`() {
    envGifBasenames.forEach { base ->
      val gif = renderFile(rendersDir, base, ext = "gif")
      assertThat(gif.exists()).isTrue()
      assertThat(gif.length()).isGreaterThan(0L)
      val header = gif.inputStream().use { it.readNBytes(6).toString(Charsets.US_ASCII) }
      assertThat(header).isAnyOf("GIF87a", "GIF89a")
    }
  }

  @Test
  fun `gif flag collapses the per-step PNG fan-out for every env`() {
    // Four indices in the @FocusedPreview annotation → four virtual frames in each stitched
    // GIF. None of them should leak out as standalone PNGs, for any env.
    envGifBasenames.forEach { base ->
      (0..3).forEach { i ->
        val sibling = renderFile(rendersDir, base, "_FOCUS_$i")
        assertThat(sibling.exists()).isFalse()
      }
    }
  }

  /**
   * The four environment GIFs composite different backdrops with `BlendMode.Plus`, so their bytes
   * must differ (unlike `GlimmerCaptureAdditivePixelTest`'s uncomposited captures).
   */
  @Test
  fun `four env GIFs render visually distinct backdrops`() {
    val files = envGifBasenames.map { renderFile(rendersDir, it, ext = "gif") }
    files.forEach { assertThat(it.exists()).isTrue() }
    val hashes = files.map { it.readBytes().contentHashCode() }.toSet()
    assertThat(hashes).hasSize(files.size)
  }

  @Test
  fun `raw additive GIF remains available and identical across environments`() {
    val files = envGifBasenames.map { renderFile(rendersDir, it, suffix = ".raw", ext = "gif") }
    files.forEach { assertThat(it.exists()).isTrue() }
    assertThat(files.map { it.readBytes().contentHashCode() }.toSet()).hasSize(1)
  }

  @Test
  fun `animated Glimmer capture preserves raw GIF and composites its environment`() {
    val base = "GlimmerXrMenuAnimated_Animated_Light"
    val gif = renderFile(rendersDir, base, ext = "gif")
    val raw = renderFile(rendersDir, base, suffix = ".raw", ext = "gif")

    assertThat(gif.exists()).isTrue()
    assertThat(raw.exists()).isTrue()
    assertThat(gif.readBytes().contentEquals(raw.readBytes())).isFalse()
  }

  @Test
  fun `focus overlay and environment retain distinct raw PNG artifacts`() {
    val base = "GlimmerXrMenuOverlay_Overlay_Light"
    val composited = renderFile(rendersDir, base)
    val preOverlay = renderFile(rendersDir, base, suffix = ".raw")
    val preEnvironment = renderFile(rendersDir, base, suffix = ".glimmer.raw")

    assertThat(composited.exists()).isTrue()
    assertThat(preOverlay.exists()).isTrue()
    assertThat(preEnvironment.exists()).isTrue()
    assertThat(preOverlay.readBytes().contentEquals(preEnvironment.readBytes())).isFalse()
    assertThat(preEnvironment.readBytes().contentEquals(composited.readBytes())).isFalse()
  }
}
