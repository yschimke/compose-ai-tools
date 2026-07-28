package ee.schimke.composeai.renderer

import ee.schimke.composeai.preview.lottie.lottieIntrinsicDurationMillis
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the animated Lottie capture: [renderLottieApng] sweeps a discovered asset's intrinsic
 * timeline into a looping APNG. The fixture `lottie/spin.json` is a rounded rectangle rotating
 * 0°→360° over 60 frames at 30fps, so its intrinsic duration is 2000ms.
 */
class DesktopLottieRendererTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun `intrinsic duration is durationFrames over frameRate`() {
    // 60 frames at 30fps → 2000ms.
    assertEquals(2000, lottieIntrinsicDurationMillis("lottie/spin.json"))
  }

  @Test
  fun `intrinsic duration falls back when the asset is unreadable`() {
    assertEquals(1234, lottieIntrinsicDurationMillis("lottie/does-not-exist.json", default = 1234))
  }

  @Test
  fun `renders a transparent anti-aliased apng for a discovered asset`() {
    // The discovered-asset path renders with no background (showBackground=false). renderLottieApng
    // keeps the transparent surface and encodes to APNG, whose 8-bit alpha carries the anti-aliased
    // edge — unlike GIF's 1-bit alpha, which crushed it into a churn-prone hard boundary.
    val outputFile = File(tempFolder.newFolder("renders"), "spin_animated.png")
    val written =
      renderLottieApng(
        assetPath = "lottie/spin.json",
        widthPx = 96,
        heightPx = 96,
        density = 1.0f,
        showBackground = false,
        backgroundColor = 0L,
        outputFile = outputFile,
        frameIntervalMs = 100,
      )

    assertTrue("encoder should report a written file", written != null)
    assertTrue(
      "rendered APNG must exist and be non-empty",
      outputFile.exists() && outputFile.length() > 0,
    )
    // 2000ms intrinsic / 100ms interval → 20 frames, recorded in the APNG acTL chunk.
    assertEquals(20, apngNumFrames(outputFile))

    // The default (frame 0 = base IDAT) decodes with alpha, and the corners are fully transparent —
    // proof the transparent background survived (GIF would have thresholded it to opaque/black).
    val firstFrame = ImageIO.read(ByteArrayInputStream(outputFile.readBytes()))
    assertTrue("APNG must carry an alpha channel", firstFrame.colorModel.hasAlpha())
    assertEquals("top-left corner must be fully transparent", 0, firstFrame.getRGB(0, 0) ushr 24)
    // The spinner edge keeps anti-aliased blends — many more than the two colours the transparent
    // GIF path collapsed to.
    val colors = HashSet<Int>()
    for (y in 0 until firstFrame.height) for (x in 0 until firstFrame.width) {
      colors.add(firstFrame.getRGB(x, y))
    }
    assertTrue(
      "expected anti-aliased edge blends (>2 colours), got ${colors.size}",
      colors.size > 2,
    )
  }

  @Test
  fun `caps the captured window at the max duration`() {
    val outputFile = File(tempFolder.newFolder("renders"), "spin-capped_animated.png")
    renderLottieApng(
      assetPath = "lottie/spin.json",
      widthPx = 32,
      heightPx = 32,
      density = 1.0f,
      showBackground = true,
      backgroundColor = 0L,
      outputFile = outputFile,
      durationMillisOverride = 60_000,
      frameIntervalMs = 100,
      maxDurationMillis = 1000,
    )
    // 1000ms cap / 100ms interval → 10 frames, not the 600 a 60s window would imply.
    assertEquals(10, apngNumFrames(outputFile))
  }

  @Test
  fun `sweeps a distinct frame per step and renders byte-identically twice`() {
    // Regression guard for the flapping baseline: Compottie's progress handoff lands *after* the
    // pass that applied the snapshot, and how many passes it needs races another thread — so any
    // fixed number of render passes per step now and then captured the previous step's pixels. The
    // duplicated frames fell on a different index each run (frame 35 in the CI render that prompted
    // issue #2868), so the committed APNG's bytes flipped between two states push after push and
    // the preview diff bot reported `lottie/spin.json` as changed on PRs that never touched it.
    // `renderSettledFrame` converges on the pixels instead. The spinner rotates continuously, so no
    // two adjacent steps may share pixels, and two renders of the same asset must agree byte for
    // byte.
    val renders = tempFolder.newFolder("renders")
    val first = File(renders, "spin-a_animated.png")
    val second = File(renders, "spin-b_animated.png")
    for (out in listOf(first, second)) {
      renderLottieApng(
        assetPath = "lottie/spin.json",
        widthPx = 96,
        heightPx = 96,
        density = 1.0f,
        showBackground = false,
        backgroundColor = 0L,
        outputFile = out,
        frameIntervalMs = 40,
      )
    }

    val frames = apngFramePayloads(first)
    assertEquals(50, frames.size)
    for (i in 1 until frames.size) {
      assertTrue(
        "frame $i is a stale duplicate of frame ${i - 1} — the progress step did not reach the " +
          "painter before the capture",
        !frames[i].contentEquals(frames[i - 1]),
      )
    }

    assertTrue(
      "two renders of the same asset must produce identical bytes",
      first.readBytes().contentEquals(second.readBytes()),
    )
  }

  /**
   * Per-frame compressed payloads of an APNG, in order: frame 0's `IDAT` chunks, then each later
   * frame's `fdAT` chunks with the 4-byte sequence number stripped. [ApngEncoder] copies Skiko's
   * `IDAT` bytes verbatim and Skia's PNG encoder is deterministic for identical pixels, so two
   * equal payloads mean two identical frames.
   */
  private fun apngFramePayloads(file: File): List<ByteArray> {
    val bytes = file.readBytes()
    val payloads = mutableListOf<ByteArray>()
    var current: MutableList<Byte>? = null
    var offset = 8 // skip the PNG signature
    while (offset + 12 <= bytes.size) {
      val length = ByteBuffer.wrap(bytes, offset, 4).int
      val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
      val dataStart = offset + 8
      when (type) {
        // `fcTL` opens a frame; flush whatever the previous one accumulated.
        "fcTL" -> {
          current?.let { payloads.add(it.toByteArray()) }
          current = mutableListOf()
        }
        "IDAT" -> current?.addAll(bytes.slice(dataStart until dataStart + length))
        // Drop the leading 4-byte sequence number so payloads are comparable with frame 0's IDAT.
        "fdAT" -> current?.addAll(bytes.slice(dataStart + 4 until dataStart + length))
      }
      offset = dataStart + length + 4 // + CRC
      if (type == "IEND") break
    }
    current?.let { payloads.add(it.toByteArray()) }
    return payloads
  }

  /** Read an APNG's `acTL` chunk and return its `numFrames` field. */
  private fun apngNumFrames(file: File): Int {
    val bytes = file.readBytes()
    val marker = "acTL".toByteArray(Charsets.US_ASCII)
    for (i in 8 until bytes.size - 8) {
      if (
        bytes[i] == marker[0] &&
          bytes[i + 1] == marker[1] &&
          bytes[i + 2] == marker[2] &&
          bytes[i + 3] == marker[3]
      ) {
        return ByteBuffer.wrap(bytes, i + 4, 4).int
      }
    }
    error("APNG has no acTL chunk: ${file.absolutePath}")
  }
}
