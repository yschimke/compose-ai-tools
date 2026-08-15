package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers [renderInteractionPreview]: that the dispatched pointer is real and lands where the script
 * says, that the capture window is derived from the script, and that the APNG carries the exact
 * frame delay the format was chosen for.
 *
 * The assertions are all made on **pixels**, not on the renderer's own bookkeeping. A capture that
 * wrote the right number of frames while the pointer went nowhere is the failure this whole feature
 * exists to avoid, and only the pixels can tell the two apart.
 */
class DesktopInteractionRendererTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val fixtureClass = "ee.schimke.composeai.renderer.InteractionRenderTestFixturesKt"

  private fun spec(
    targets: List<Int>,
    gesture: InteractionGestureKind = InteractionGestureKind.TAP,
    holdMs: Int = 600,
    gapMs: Int = 200,
    leadInMs: Int = 100,
    frameIntervalMs: Int = 50,
    format: MotionFormatKind = MotionFormatKind.APNG,
  ) =
    InteractionSpec(
      gesture = gesture,
      targets = targets,
      holdMs = holdMs,
      gapMs = gapMs,
      leadInMs = leadInMs,
      frameIntervalMs = frameIntervalMs,
      format = format,
    )

  private fun render(
    function: String,
    spec: InteractionSpec,
    name: String,
    widthPx: Int = 90,
    heightPx: Int = 30,
    wrapWidth: Boolean = false,
    wrapHeight: Boolean = false,
  ): File {
    val outputFile = File(tempFolder.newFolder(name), "$name.${spec.format.name.lowercase()}")
    renderInteractionPreview(
      className = fixtureClass,
      functionName = function,
      widthPx = widthPx,
      heightPx = heightPx,
      density = 1.0f,
      showBackground = false,
      backgroundColor = 0L,
      outputFile = outputFile,
      wrapperClassName = null,
      previewArgs = emptyList(),
      localeTag = null,
      spec = spec,
      wrapWidth = wrapWidth,
      wrapHeight = wrapHeight,
    )
    return outputFile
  }

  @Test
  fun `the dispatched tap lands on the addressed cell, not the first one`() {
    // Target 2 only. If the renderer aimed at the root's centre, or at index 0, the third cell
    // would never light up — which is exactly the bug this addressing scheme has to rule out.
    val output = render("ThreeCellSelector", spec(targets = listOf(2)), "target-two")

    val frames = ApngFrames.read(output)
    val first = frames.first()
    val last = frames.last()

    assertTrue("cell 0 starts selected", first.isWhiteAt(15, 15))
    assertTrue("cell 2 starts unselected", !first.isWhiteAt(75, 15))
    assertTrue("cell 2 ends selected — the tap landed on it", last.isWhiteAt(75, 15))
    assertTrue("cell 0 ends unselected", !last.isWhiteAt(15, 15))
  }

  @Test
  fun `repeating a target repeats the gesture (the toggle idiom)`() {
    // `[2, 0]` is a two-step script: the capture has to pass THROUGH cell 2 being lit and come back
    // to cell 0. An implementation that only dispatched the last target would end in the same place
    // but never show the middle, so the mid-capture assertion is the one carrying the weight.
    val output = render("ThreeCellSelector", spec(targets = listOf(2, 0)), "toggle")

    val frames = ApngFrames.read(output)
    assertTrue("some frame shows cell 2 selected", frames.any { it.isWhiteAt(75, 15) })
    assertTrue("the last frame is back on cell 0", frames.last().isWhiteAt(15, 15))
  }

  @Test
  fun `press and hold keeps the pointer down for the declared dwell`() {
    // The fixture is white only WHILE held, so the count of white frames measures the dwell. At a
    // 50ms interval a 600ms hold is ~12 frames; a momentary tap would be at most 2. The assertion
    // is deliberately loose at the edges (frame boundaries land where they land) and tight enough
    // to fail a capture that released early or never pressed at all.
    val output =
      render(
        "HoldToLight",
        spec(targets = listOf(0), gesture = InteractionGestureKind.PRESS_AND_HOLD, holdMs = 600),
        "hold",
        widthPx = 30,
      )

    val held = ApngFrames.read(output).count { it.isWhiteAt(15, 15) }
    assertTrue("expected ~12 held frames for a 600ms hold at 50ms, got $held", held in 9..14)
  }

  @Test
  fun `a tap is momentary where a hold is not`() {
    val output = render("HoldToLight", spec(targets = listOf(0)), "tap-momentary", widthPx = 30)

    val held = ApngFrames.read(output).count { it.isWhiteAt(15, 15) }
    assertTrue("a 90ms tap should light at most a couple of frames, got $held", held <= 3)
    assertTrue("but it must still register as a press at all", held >= 1)
  }

  @Test
  fun `the capture window is derived from the script`() {
    // lead-in 100 + per target (90ms tap press + 200ms gap) = 100 + 2 * 290 = 680ms; at 50ms that
    // is 13 frames. Pinned because the duration is derived rather than declared — the point being
    // that adding a target lengthens the recording without anyone restating a duration.
    val output = render("ThreeCellSelector", spec(targets = listOf(1, 2)), "window")

    assertEquals(13, ApngFrames.read(output).size)
  }

  @Test
  fun `a 60fps capture carries an exact one-sixtieth frame delay`() {
    // The reason APNG is the default format at all: 16ms is 1/60 of a second only if the container
    // can express a rational delay. A GIF would quantise this to 20ms (50fps), unevenly.
    val output =
      render("ThreeCellSelector", spec(targets = listOf(1), frameIntervalMs = 16), "sixty-fps")

    assertEquals(1.toShort() to 60.toShort(), ApngFrames.firstDelay(output))
  }

  @Test
  fun `an out-of-range target fails the render rather than publishing a miss`() {
    val error = runCatching {
      render("ThreeCellSelector", spec(targets = listOf(7)), "out-of-range")
    }
      .exceptionOrNull()

    assertNotEquals("an unresolvable target must not render", null, error)
    assertTrue(
      "the message should name the count so the script can be fixed: ${error?.message}",
      error?.message?.contains("3 clickable node(s)") == true,
    )
  }

  @Test
  fun `a wrapped capture is cropped to the composable, not to the device sandbox`() {
    // The regression this whole capture-region change exists for. `ThreeCellSelector` is 90x30dp,
    // and at density 1 in a 400x300 sandbox a wrapped render must publish 90x30 — the same size the
    // single-frame path crops its sticker to. Before the wrap box reached this path every capture
    // came out sandbox-sized, so a 137x84 switch shipped as a 1050x2100 recording of a switch
    // adrift in empty space. Asserting the *dimensions* is the one-line check that would have
    // caught it; asserting only the pixels never would, because the pixels were fine — just lost
    // in a field of background.
    val output =
      render(
        "ThreeCellSelector",
        spec(targets = listOf(1)),
        "wrapped",
        widthPx = 400,
        heightPx = 300,
        wrapWidth = true,
        wrapHeight = true,
      )

    val frames = ApngFrames.read(output)
    assertTrue("expected frames, got none", frames.isNotEmpty())
    for (frame in frames) {
      assertEquals(90, frame.width)
      assertEquals(30, frame.height)
    }
  }

  @Test
  fun `an unwrapped capture keeps the requested frame`() {
    // The device-framed case still fills its sandbox — cropping is what a *wrapped* axis asks for,
    // and a `@Preview` that declared a device size means it.
    val output =
      render(
        "ThreeCellSelector",
        spec(targets = listOf(1)),
        "unwrapped",
        widthPx = 200,
        heightPx = 120,
      )

    val first = ApngFrames.read(output).first()
    assertEquals(200, first.width)
    assertEquals(120, first.height)
  }

  @Test
  fun `content that grows mid-recording is re-recorded at the size it grew to`() {
    // `ExpandOnTap` measures 30x30 at rest and 90x30 once tapped. Cropping to the resting
    // measurement would slice the expansion off at x=30 — the exact frame edge the revealed half
    // sits behind — so the recording has to notice it outgrew its crop and record again. The pixel
    // assertion at x=60 is the one that matters: the frames could be the right SIZE while the
    // revealed content was never captured.
    val output =
      render(
        "ExpandOnTap",
        spec(targets = listOf(0)),
        "growth",
        widthPx = 400,
        heightPx = 300,
        wrapWidth = true,
        wrapHeight = true,
      )

    val frames = ApngFrames.read(output)
    assertEquals("re-recorded at the grown width", 90, frames.first().width)
    assertEquals(30, frames.first().height)
    assertTrue("the revealed half is in the encoded pixels", frames.last().isWhiteAt(60, 15))
  }

  @Test
  fun `padding a frame moves and scales nothing`() {
    // The property that makes the fixed frame size safe. `ExpandOnTap` rests at 30x30 inside a
    // capture sized 90x30 for its later expansion, so its early frames are padded — and the whole
    // question is what happened to the component while that padding was added.
    //
    // Scaled up to fill, the cell would cover x=60. Re-centred, it would have left x=15. Neither
    // is true here: the cell is exactly where it composed, at its own size, and the space it has
    // not grown into yet is untouched padding.
    val output =
      render(
        "ExpandOnTap",
        spec(targets = listOf(0)),
        "padding",
        widthPx = 400,
        heightPx = 300,
        wrapWidth = true,
        wrapHeight = true,
      )

    val frames = ApngFrames.read(output)
    val resting = frames.first()
    assertEquals(90, resting.width)
    assertEquals("the cell did not scale up to fill the padded canvas", 0, resting.alphaAt(60, 15))
    assertEquals("the cell did not drift from the top-left", 255, resting.alphaAt(15, 15))
    assertEquals("nor did it grow rightwards into the padding", 0, resting.alphaAt(35, 15))
    // ...and once it genuinely expands, that same pixel is real content rather than padding.
    assertTrue("the expansion reaches the space the padding held", frames.last().isWhiteAt(60, 15))
  }

  @Test
  fun `motionCropSize takes the measured content only on a wrapped axis`() {
    val scene = androidx.compose.ui.unit.IntSize(400, 300)
    val measured = androidx.compose.ui.unit.IntSize(90, 30)

    assertEquals(
      androidx.compose.ui.unit.IntSize(90, 30),
      motionCropSize(measured, true, true, 400, 300, scene),
    )
    assertEquals(
      androidx.compose.ui.unit.IntSize(90, 300),
      motionCropSize(measured, true, false, 400, 300, scene),
    )
    assertEquals(
      androidx.compose.ui.unit.IntSize(400, 300),
      motionCropSize(measured, false, false, 400, 300, scene),
    )
    // Never larger than the scene that was actually rendered.
    assertEquals(
      androidx.compose.ui.unit.IntSize(400, 300),
      motionCropSize(androidx.compose.ui.unit.IntSize(9000, 9000), true, true, 400, 300, scene),
    )
  }

  @Test
  fun `apngDelayFor snaps the canonical rates and carries anything else literally`() {
    assertEquals(1.toShort() to 60.toShort(), apngDelayFor(16))
    assertEquals(1.toShort() to 60.toShort(), apngDelayFor(17))
    assertEquals(1.toShort() to 30.toShort(), apngDelayFor(33))
    assertEquals(1.toShort() to 50.toShort(), apngDelayFor(20))
    assertEquals(120.toShort() to 1000.toShort(), apngDelayFor(120))
  }
}

/** Minimal APNG reader — enough to count frames, read the first delay, and sample pixels. */
private object ApngFrames {

  // The JVM's stock ImageIO decodes only the FIRST frame of an APNG, so frames are recovered from
  // the chunk stream directly: each fcTL starts a frame and its pixels live in the IDAT / fdAT that
  // follow. Every frame these fixtures produce is full-size and non-disposing, so rebuilding one as
  // a standalone PNG is a chunk copy rather than a composite.
  fun read(file: File): List<BufferedImage> = ApngSplitter(file.readBytes()).frames()

  fun firstDelay(file: File): Pair<Short, Short> = ApngSplitter(file.readBytes()).firstDelay()
}

private class ApngSplitter(private val bytes: ByteArray) {

  private data class Chunk(val type: String, val start: Int, val length: Int)

  private fun chunks(): List<Chunk> {
    val out = mutableListOf<Chunk>()
    var i = 8
    while (i + 8 <= bytes.size) {
      val length = readInt(i)
      val type = String(bytes, i + 4, 4, Charsets.ISO_8859_1)
      out += Chunk(type, i, length)
      i += 12 + length
    }
    return out
  }

  fun firstDelay(): Pair<Short, Short> {
    val fctl = chunks().first { it.type == "fcTL" }
    val num = readShort(fctl.start + 8 + 20)
    val den = readShort(fctl.start + 8 + 22)
    return num to den
  }

  fun frames(): List<BufferedImage> {
    val all = chunks()
    val ihdr = all.first { it.type == "IHDR" }
    val header = bytes.copyOfRange(0, 8) + slice(ihdr)
    val tail = crcChunk("IEND", ByteArray(0))

    val out = mutableListOf<BufferedImage>()
    var pending: ByteArray? = null
    for (chunk in all) {
      when (chunk.type) {
        "fcTL" -> {
          pending?.let { out += decode(header + it + tail) }
          pending = ByteArray(0)
        }
        "IDAT" -> if (pending != null) pending += slice(chunk)
        // fdAT is an IDAT whose payload is prefixed by a 4-byte sequence number.
        "fdAT" ->
          if (pending != null) {
            val payload = bytes.copyOfRange(chunk.start + 12, chunk.start + 8 + chunk.length)
            pending += crcChunk("IDAT", payload)
          }
      }
    }
    pending?.let { out += decode(header + it + tail) }
    return out
  }

  private fun decode(png: ByteArray): BufferedImage =
    javax.imageio.ImageIO.read(png.inputStream()) ?: error("APNG frame failed to decode")

  private fun slice(chunk: Chunk): ByteArray =
    bytes.copyOfRange(chunk.start, chunk.start + 12 + chunk.length)

  private fun crcChunk(type: String, payload: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(intBytes(payload.size))
    val typed = type.toByteArray(Charsets.ISO_8859_1) + payload
    out.write(typed)
    val crc = java.util.zip.CRC32().apply { update(typed) }.value
    out.write(intBytes(crc.toInt()))
    return out.toByteArray()
  }

  private fun intBytes(value: Int) =
    byteArrayOf(
      (value ushr 24).toByte(),
      (value ushr 16).toByte(),
      (value ushr 8).toByte(),
      value.toByte(),
    )

  private fun readInt(at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 24) or
      ((bytes[at + 1].toInt() and 0xFF) shl 16) or
      ((bytes[at + 2].toInt() and 0xFF) shl 8) or
      (bytes[at + 3].toInt() and 0xFF)

  private fun readShort(at: Int): Short =
    (((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)).toShort()
}

/** The pixel's alpha — `0` is padding the renderer added, `255` is something the component drew. */
private fun BufferedImage.alphaAt(x: Int, y: Int): Int = (getRGB(x, y) shr 24) and 0xFF

/** True when the pixel is (near-)white — the fixtures' "this is the active one" signal. */
private fun BufferedImage.isWhiteAt(x: Int, y: Int): Boolean {
  val rgb = getRGB(x, y)
  val r = (rgb shr 16) and 0xFF
  val g = (rgb shr 8) and 0xFF
  val b = rgb and 0xFF
  return r > 200 && g > 200 && b > 200
}
