package ee.schimke.composeai.daemon

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Smoke test for [FfmpegEncoder] — gated on `ffmpeg` being available on the test environment's
 * `PATH`. Tests synthesise a tiny PNG sequence (4 frames of solid colour, 32×32 px), shell out
 * through [FfmpegEncoder.encodeFromPngFrames] for both [FfmpegEncoder.RecordingFormatChoice.MP4]
 * and `WEBM`, and assert:
 *
 * 1. The encoder produces a non-empty file at the requested path.
 * 2. The file's leading bytes match the format's container magic — mp4 carries `ftyp` at byte 4,
 *    webm starts with the EBML `1A 45 DF A3` header. Cheap signature check; we deliberately don't
 *    decode the streams (that would couple the test to mp4/webm parsers we don't ship).
 *
 * The `assumeTrue(FfmpegEncoder.available())` skips the test cleanly on machines without ffmpeg —
 * no failure in CI environments that haven't installed it; full coverage on developer laptops and
 * the bench. The companion `DesktopRecordingSessionTest.mp4_format_round_trips_through_encoder`
 * does the same `assume` so the higher-level path stays portable.
 */
class FfmpegEncoderTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun mp4_encode_produces_valid_container() {
    assumeTrue("ffmpeg not on PATH; skipping mp4 smoke test", FfmpegEncoder.available())
    val framesDir = tempFolder.newFolder("frames-mp4")
    writeSolidColourFrames(framesDir, count = 4, side = 32)
    val out = tempFolder.newFile("out.mp4")

    FfmpegEncoder.encodeFromPngFrames(
      framesDir = framesDir,
      fps = 30,
      format = FfmpegEncoder.RecordingFormatChoice.MP4,
      out = out,
    )

    assertTrue("mp4 output must exist: ${out.absolutePath}", out.isFile)
    assertTrue("mp4 output must be non-empty", out.length() > 0)
    // `ftyp` box at bytes 4..7 — the standard mp4 container marker. We don't check the brand
    // (`isom` / `mp42` / `avc1` etc.); ffmpeg's `libx264` defaults to `isom` and we don't pin
    // that here.
    val head = out.readBytes().copyOf(12)
    assertTrue(
      "mp4 should carry 'ftyp' at bytes 4..7; got ${head.joinToString { "0x%02x".format(it) }}",
      head[4] == 'f'.code.toByte() &&
        head[5] == 't'.code.toByte() &&
        head[6] == 'y'.code.toByte() &&
        head[7] == 'p'.code.toByte(),
    )
  }

  @Test
  fun webm_encode_produces_valid_ebml_header() {
    assumeTrue("ffmpeg not on PATH; skipping webm smoke test", FfmpegEncoder.available())
    val framesDir = tempFolder.newFolder("frames-webm")
    writeSolidColourFrames(framesDir, count = 4, side = 32)
    val out = tempFolder.newFile("out.webm")

    FfmpegEncoder.encodeFromPngFrames(
      framesDir = framesDir,
      fps = 30,
      format = FfmpegEncoder.RecordingFormatChoice.WEBM,
      out = out,
    )

    assertTrue("webm output must exist: ${out.absolutePath}", out.isFile)
    assertTrue("webm output must be non-empty", out.length() > 0)
    // EBML magic — `1A 45 DF A3`. WebM and Matroska share this header.
    val head = out.readBytes().copyOf(4)
    val expected = byteArrayOf(0x1A, 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
    assertTrue(
      "webm should start with EBML magic 1A 45 DF A3; got ${head.joinToString { "0x%02x".format(it) }}",
      head.contentEquals(expected),
    )
  }

  @Test
  fun missing_frames_dir_throws() {
    assumeTrue("ffmpeg not on PATH; skipping", FfmpegEncoder.available())
    val nonExistent = File(tempFolder.root, "does-not-exist")
    val out = tempFolder.newFile("out.mp4")
    val thrown =
      runCatching {
          FfmpegEncoder.encodeFromPngFrames(
            framesDir = nonExistent,
            fps = 30,
            format = FfmpegEncoder.RecordingFormatChoice.MP4,
            out = out,
          )
        }
        .exceptionOrNull()
    assertTrue(
      "expected IllegalArgumentException for missing framesDir; got ${thrown?.javaClass}",
      thrown is IllegalArgumentException,
    )
  }

  /**
   * Write `count` PNG frames of solid colour into [framesDir], named `frame-NNNNN.png` matching the
   * `frame-%05d.png` pattern [FfmpegEncoder] hands ffmpeg. Cycles through R/G/B/Y so adjacent
   * frames differ — without that, libx264's CRF mode might encode the whole clip as a single
   * keyframe and ffmpeg would emit a very small file that some checkers reject as suspicious.
   */
  private fun writeSolidColourFrames(framesDir: File, count: Int, side: Int) {
    framesDir.mkdirs()
    val palette = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    for (i in 0 until count) {
      val img = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
      val g = img.createGraphics()
      try {
        g.color = palette[i % palette.size]
        g.fillRect(0, 0, side, side)
      } finally {
        g.dispose()
      }
      val out = File(framesDir, "frame-${"%05d".format(i)}.png")
      ImageIO.write(img, "png", out)
    }
  }
}
