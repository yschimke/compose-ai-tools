package com.example.samplexrglimmer

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Asserts the interactive XR menu navigation GIFs produced by
 * `:samples:xr-glimmer:composePreviewRenderAll` land at the right paths with the right shape, and
 * that each env actually composites a visibly different backdrop.
 *
 * Each top-level function (`GlimmerXrMenuLight` etc.) carries `@FocusedPreview(indices =
 * [0, 1, 2, 3], gif = true)`, so the renderer drives focus across four Glimmer `ListItem`s (one
 * `moveFocus(Enter)` on the first capture, then `moveFocus(Next)` per subsequent step) and stitches
 * each function's four captures into a single `.gif`. The per-step PNG fan-out (`_FOCUS_0.png`
 * etc.) must NOT be written — `gif = true` is supposed to collapse it. Together the guards catch:
 *
 * - GIF stitching breakages (a file disappears or shrinks to zero / loses its magic header).
 * - Regressions where the renderer writes both the GIF *and* the per-step PNGs (a duplicate- output
 *   mode would silently quadruple the `:samples:xr-glimmer` render budget here).
 * - A future renderer change that renames the GIF (e.g. dropping the trailing `_FOCUS` suffix on
 *   the GIF path) — the new filename would land outside the assertion below.
 * - **Env-backdrop drift.** Each env composites a different procedurally-drawn backdrop with the
 *   Glimmer UI additive-blended on top, so the four GIFs must be byte-distinct. If a regression
 *   accidentally drops the env backdrop layer or the `BlendMode.Plus` wrapper, all four GIFs
 *   collapse to identical opaque-black captures and this test surfaces it — that's exactly the
 *   failure mode that motivated this iteration of the demo.
 */
class GlimmerInteractiveMenuTest {

  private val rendersDir = File("build/compose-previews/renders")

  // Filenames are `<functionName>_<previewName>` per the discovery rule, with non-allowlisted
  // characters collapsed to underscores (docs/RENDER_FILENAMES.md). Function name carries the
  // env; the preview name is the short env label — so e.g. the Light GIF lands at
  // `GlimmerXrMenuLight_Light.gif`.
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
      val gif = File(rendersDir, "$base.gif")
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
        val sibling = File(rendersDir, "${base}_FOCUS_$i.png")
        assertThat(sibling.exists()).isFalse()
      }
    }
  }

  /**
   * The four env GIFs must be visually distinct because each composites a different
   * procedurally-drawn backdrop. Byte-comparing the GIFs is a strict-enough proxy — two different
   * env backdrops painted with `BlendMode.Plus` on top of the same Glimmer UI produce different
   * pixel data, which produces different GIF bytes after the renderer's stitcher quantises. Inverse
   * of the assertion `GlimmerCaptureAdditivePixelTest` enforces on the `NowPlayingCard` PNG fan-out
   * (which intentionally stays byte-identical across env names because that sample still uses
   * Encoding B without inline compositing).
   */
  @Test
  fun `four env GIFs render visually distinct backdrops`() {
    val files = envGifBasenames.map { File(rendersDir, "$it.gif") }
    files.forEach { assertThat(it.exists()).isTrue() }
    val hashes = files.map { it.readBytes().contentHashCode() }.toSet()
    assertThat(hashes).hasSize(files.size)
  }
}
