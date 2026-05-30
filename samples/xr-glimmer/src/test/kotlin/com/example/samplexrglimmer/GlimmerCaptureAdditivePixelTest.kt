package com.example.samplexrglimmer

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Asserts the additive-RGB capture contract that `:samples:xr-glimmer:composePreviewRenderAll`
 * is supposed to deliver — the renderer mirror of
 * `:samples:android`'s `TransparentBackgroundPreviewPixelTest`, on the other channel.
 *
 * Glimmer's display model is additive: pure black pixels render as 100% transparent on-device,
 * so the design (`docs/design/GLIMMER_PREVIEW.md` § "Capture encoding") picks Encoding B —
 * opaque RGB on a `Color.Black` background, then `ADD`-blend onto an environment image to
 * recover what a wearer sees. The contract this test guards:
 *
 *  - The captured PNG carries an alpha plane (i.e. it's loaded as RGBA, not RGB-flattened).
 *  - Alpha is fully opaque in every pixel (`0xFF`) — Encoding B is NOT a transparent capture.
 *  - The four outer corners read `RGB == (0, 0, 0)` — additive-zero. These pixels sit well
 *    outside the centred title chip / card, so any drift away from black would mean either
 *    (a) the background-fill path lost the explicit `backgroundColor = 0xFF000000` from
 *    `@Preview`, or (b) a future env compositor mutated the original capture in place
 *    (it must write a sibling file instead).
 *
 * Without this test, Encoding-B drift slips through silently: an opaque-grey background still
 * looks "fine" in a manual review, but breaks the eventual `ADD`-blend env compositor because
 * grey + scene > 0 everywhere and the scene gets washed out across every pixel.
 */
class GlimmerCaptureAdditivePixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  // Captures discovered for `NowPlayingCard` — one per `@Preview` env name. Filenames are
  // the renderer's sanitised form: the middle dot in `Glimmer · Light` lands outside the
  // `[A-Za-z0-9._-]` allowlist defined by `docs/RENDER_FILENAMES.md` and gets stripped,
  // with the surrounding spaces compacted to a single underscore, so the on-disk file is
  // `NowPlayingCard_Glimmer_Light.png` (not `_·_`).
  private val nowPlayingCaptures =
    listOf(
      "NowPlayingCard_Glimmer_Light.png",
      "NowPlayingCard_Glimmer_Dark.png",
      "NowPlayingCard_Glimmer_Busy.png",
      "NowPlayingCard_Glimmer_VeniceCanalCats.png",
    )

  private val focusableMenuCapture = "FocusableMenu_Glimmer_Input.png"

  @Test
  fun `every Glimmer capture is opaque RGB with additive-zero corners`() {
    val files = (nowPlayingCaptures + focusableMenuCapture).map { File(rendersDir, it) }
    files.forEach { file ->
      assertThat(file.exists()).isTrue()
      val img = ImageIO.read(file)
      assertThat(img.colorModel.hasAlpha()).isTrue()
      assertThat(img.width).isAtLeast(40)
      assertThat(img.height).isAtLeast(40)

      val (w, h) = img.width to img.height
      // The card and chip sit centred in a 640×480 canvas with 24-dp insets from the
      // background `Box`, so the four absolute corner pixels are well outside any
      // composable that could paint over the background-fill layer.
      listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1).forEach { (x, y) ->
        val argb = img.getRGB(x, y)
        val alpha = (argb ushr 24) and 0xff
        val r = (argb ushr 16) and 0xff
        val g = (argb ushr 8) and 0xff
        val b = argb and 0xff
        assertThat(alpha).isEqualTo(0xff)
        assertThat(Triple(r, g, b)).isEqualTo(Triple(0, 0, 0))
      }
    }
  }

  /**
   * The four `NowPlayingCard` captures are pixel-identical today — Encoding B doesn't see
   * the env name at render time; the future `:data-glimmer-environment-connector` is what
   * differentiates them by writing a sibling composited PNG. If a regression starts varying
   * the captures across env names (e.g. the renderer accidentally reads `Preview.name` and
   * synthesises something env-specific in-process), this test catches it.
   */
  @Test
  fun `four NowPlayingCard env variants land at pixel-identical captures`() {
    val files = nowPlayingCaptures.map { File(rendersDir, it) }
    files.forEach { assertThat(it.exists()).isTrue() }
    val hashes = files.map { it.readBytes().contentHashCode() }.toSet()
    assertThat(hashes).hasSize(1)
  }
}
