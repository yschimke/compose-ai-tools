package com.example.samplexrglimmer

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Asserts the interactive XR menu navigation GIFs produced by
 * `:samples:xr-glimmer:composePreviewRenderAll` land at the right paths with the right shape.
 *
 * `GlimmerXrMenuNavigation` carries four stacked `@Preview` annotations (one per Light / Dark /
 * Busy / VeniceCanalCats env, matching the per-env naming convention in
 * `docs/design/GLIMMER_PREVIEW.md`) plus `@FocusedPreview(indices = [0, 1, 2, 3], gif = true)`,
 * so the renderer replays the focus walk per env and stitches each into a `.gif`. Per-env
 * captures collapse the per-step PNG fan-out the same way the single-env predecessor did;
 * sibling `_FOCUS_<n>.png` files must NOT be written — the whole point of `gif = true` is to
 * collapse the fan-out. Together these guards catch:
 *
 *  - GIF stitching breakages (a file disappears or shrinks to zero / loses its magic header).
 *  - Regressions where the renderer writes both the GIF *and* the per-step PNGs (a duplicate-
 *    output mode would silently quadruple the `:samples:xr-glimmer` render budget here).
 *  - A future renderer change that renames the GIF (e.g. dropping the trailing `_FOCUS` suffix
 *    on the GIF path) — the new filename would land outside the assertion below.
 *  - Encoding-B drift across envs: the four GIFs are byte-identical *today* because the
 *    `:data-glimmer-environment-connector` (which will paint each env onto its backdrop) does
 *    not exist yet; if the renderer started reading `Preview.name` and synthesising something
 *    env-specific in-process, the hashes would diverge and this test would surface it before a
 *    downstream skill hard-codes the same identity assumption.
 */
class GlimmerInteractiveMenuTest {

  private val rendersDir = File("build/compose-previews/renders")

  // Filenames mirror the renderer's sanitisation rules (docs/RENDER_FILENAMES.md): the middle
  // dot and surrounding spaces in `Glimmer XR Menu · <Env>` collapse to underscores, consecutive
  // underscores compact to one — landing at `Glimmer_XR_Menu_<Env>`. Function-name prefix
  // `GlimmerXrMenuNavigation_` is the standard form (the common package prefix
  // `com.example.samplexrglimmer.GlimmerInteractiveMenuPreviewsKt.` is stripped per the
  // discovery rule).
  private val envGifBasenames =
    listOf(
      "GlimmerXrMenuNavigation_Glimmer_XR_Menu_Light",
      "GlimmerXrMenuNavigation_Glimmer_XR_Menu_Dark",
      "GlimmerXrMenuNavigation_Glimmer_XR_Menu_Busy",
      "GlimmerXrMenuNavigation_Glimmer_XR_Menu_VeniceCanalCats",
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
   * The four env variants are byte-identical today — Encoding B doesn't see the env name at
   * render time; the future `:data-glimmer-environment-connector` is what differentiates them
   * by writing a sibling composited output per env. If a regression starts varying the captures
   * across env names (e.g. the renderer accidentally reads `Preview.name` and synthesises
   * something env-specific in-process), this test catches it. Mirrors the same contract
   * `GlimmerCaptureAdditivePixelTest` enforces for the `NowPlayingCard` PNG fan-out.
   */
  @Test
  fun `four env GIF variants land at byte-identical captures`() {
    val files = envGifBasenames.map { File(rendersDir, "$it.gif") }
    files.forEach { assertThat(it.exists()).isTrue() }
    val hashes = files.map { it.readBytes().contentHashCode() }.toSet()
    assertThat(hashes).hasSize(1)
  }
}
