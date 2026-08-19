package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.motion.MAX_INTERACTION_DURATION_MS
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.CRC32
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `@InteractionPreview` on the Robolectric backend (issue #4215).
 *
 * These assert on **pixels**, deliberately. A capture with the right frame count and a pointer that
 * went nowhere is the exact failure the feature exists to rule out: a recording of a component not
 * responding is indistinguishable from a component that cannot respond, and answering that question
 * is the artifact's whole job. So each test reads the colour of the cell the script addressed out
 * of the encoded file rather than trusting the frame count or the handler's return value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidInteractionCaptureTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var rootDir: File
  private var measured: IntSize? = null
  private var cells = 1

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("android-interaction-capture").toFile()
    System.setProperty("roborazzi.test.record", "true")
    rule.mainClock.autoAdvance = false
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `the tap lands on the addressed cell, not on index 0`() {
    setToggleRow(cells = 3)
    val out = File(rootDir, "addressed.apng")

    val handled = capture(out, script(targets = listOf(1)))

    assertTrue("the capture must claim the slot", handled)
    val frames = Apng.frames(out)
    val cell = cellWidth(frames.last())
    assertEquals("cell 0 was never addressed", OFF_ARGB, frames.last().cell(0, cell))
    assertEquals("cell 1 was the target", ON_ARGB, frames.last().cell(1, cell))
    assertEquals("cell 2 was never addressed", OFF_ARGB, frames.last().cell(2, cell))
  }

  @Test
  fun `tapping one cell twice returns to its start through a visibly different middle`() {
    setToggleRow(cells = 1)
    val out = File(rootDir, "toggle.apng")

    val handled = capture(out, script(targets = listOf(0, 0)))

    assertTrue(handled)
    val frames = Apng.frames(out)
    val cell = cellWidth(frames.first())
    assertEquals("the recording opens at rest", OFF_ARGB, frames.first().cell(0, cell))
    assertEquals(
      "and closes back at rest, so the loop point is clean",
      OFF_ARGB,
      frames.last().cell(0, cell),
    )
    // The state in between is the whole point — a file that only ever showed the resting colour
    // would pass every structural check while documenting nothing.
    assertTrue(
      "some frame between the two taps must show the flipped state",
      frames.any { it.cell(0, cell) == ON_ARGB },
    )
  }

  @Test
  fun `the APNG declares its frames and the exact 1 by 60 delay`() {
    setToggleRow(cells = 1)
    val out = File(rootDir, "timing.apng")

    capture(out, script(targets = listOf(0), frameIntervalMs = 16, leadInMs = 16, gapMs = 32))

    val declared = Apng.declaredFrameCount(out)
    assertEquals("acTL must count every encoded frame", Apng.frames(out).size, declared)
    // 16ms is 60fps, which no integer number of milliseconds names — `1/60` is what the author
    // meant and the only spelling that plays back at the authored rate.
    assertEquals(1.toShort() to 60.toShort(), Apng.delay(out))
  }

  @Test
  fun `a wrapped capture is framed like the still it sits beside`() {
    setToggleRow(cells = 3)
    val out = File(rootDir, "wrapped.apng")
    val content = measured!!

    capture(out, script(targets = listOf(0)))

    val frames = Apng.frames(out)
    assertTrue(
      "precondition: the sandbox the frames were captured on is wider than the row",
      rule.activity.resources.displayMetrics.widthPixels > content.width,
    )
    frames.forEach { frame ->
      assertEquals("frames are cropped to the measured row", content.width, frame.width)
      assertEquals(content.height, frame.height)
    }
  }

  @Test
  fun `a component that expands mid-gesture grows the canvas instead of being clipped`() {
    setGrowingBox()
    val out = File(rootDir, "growth.apng")
    val resting = measured!!

    capture(out, script(targets = listOf(0)))

    val frames = Apng.frames(out)
    val grown = measured!!
    assertTrue("precondition: the box really did expand", grown.width > resting.width)
    assertEquals("every frame is sized for the expansion", grown.width, frames.first().width)
    // The resting frames are padded, not stretched: a component that expands must not appear to
    // shrink as the canvas grows around it.
    assertEquals(PAD_ARGB, frames.first().getRGB(grown.width - 1, 0))
    assertNotEquals(PAD_ARGB, frames.last().getRGB(grown.width - 1, 0))
  }

  @Test
  fun `a frame interval coarser than the whole script still dispatches it`() {
    setToggleRow(cells = 1)
    val out = File(rootDir, "coarse.apng")

    // Flooring duration-by-interval alone would give a single sample at elapsed 0 — nothing
    // dispatched, and a "motion" capture of a component at rest.
    val handled = capture(out, script(targets = listOf(0), frameIntervalMs = 5000))

    assertTrue(handled)
    val frames = Apng.frames(out)
    assertTrue("the script must be sampled past its lead-in", frames.size >= 2)
    assertEquals(ON_ARGB, frames.last().cell(0, cellWidth(frames.last())))
  }

  @Test
  fun `a script the cap truncates between gestures releases nothing`() {
    setToggleRow(cells = 1)
    val out = File(rootDir, "capped.apng")

    // 11 taps at this timing run past MAX_INTERACTION_DURATION_MS, and the cap lands in a gap:
    // the last admitted event is a release, so the pointer is already up. Inferring "still held"
    // from the pending events would issue a second `up()`, which the injector rejects — failing an
    // otherwise complete recording at its very last step.
    val handled =
      capture(
        out,
        InteractionCapture(
          gesture = InteractionGesture.TAP,
          targets = List(11) { 0 },
          holdMs = 600,
          gapMs = 901,
          leadInMs = 100,
          frameIntervalMs = 500,
          format = MotionFormat.APNG,
        ),
      )

    assertTrue("a truncated script still publishes what it recorded", handled)
    assertTrue(Apng.frames(out).isNotEmpty())
    // The cap bounds the recording, not just the script: every frame is a full-size image in the
    // output, so a script running twice the window must not encode twice the frames. The two-frame
    // allowance is the response frame the sampler adds after the last admitted event.
    assertTrue(
      "a capped script must not record past its window",
      Apng.declaredFrameCount(out) <= MAX_INTERACTION_DURATION_MS / 500 + 2,
    )
  }

  @Test
  fun `a target index that resolves to nothing fails loudly`() {
    setToggleRow(cells = 3)
    val out = File(rootDir, "out-of-range.apng")

    val failure =
      runCatching { capture(out, script(targets = listOf(7))) }.exceptionOrNull()
        ?: error("an out-of-range target must fail the capture rather than record empty space")

    val message = failure.message.orEmpty()
    assertTrue(message, message.contains("out of range"))
    assertTrue(message, message.contains("3 clickable node(s)"))
    assertTrue("nothing may be published for a script that missed", !out.exists())
  }

  @Test
  fun `the GIF container is honoured when a consumer asks for it`() {
    setToggleRow(cells = 1)
    val out = File(rootDir, "toggle.gif")

    val handled = capture(out, script(targets = listOf(0), format = MotionFormat.GIF))

    assertTrue(handled)
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    reader.input = ImageIO.createImageInputStream(out)
    assertTrue("a GIF capture must carry more than one frame", reader.getNumImages(true) > 1)
    reader.dispose()
  }

  // ---------------------------------------------------------------- fixtures

  private fun script(
    targets: List<Int>,
    frameIntervalMs: Int = 50,
    leadInMs: Int = 50,
    gapMs: Int = 150,
    format: MotionFormat = MotionFormat.APNG,
  ) =
    InteractionCapture(
      gesture = InteractionGesture.TAP,
      targets = targets,
      holdMs = 200,
      gapMs = gapMs,
      leadInMs = leadInMs,
      frameIntervalMs = frameIntervalMs,
      format = format,
    )

  private fun capture(out: File, interaction: InteractionCapture): Boolean =
    handleInteractionCapture(
      rule = rule,
      interaction = interaction,
      previewId = "Test.${out.nameWithoutExtension}",
      isRound = false,
      outputFile = out,
      wrapWidth = true,
      wrapHeight = true,
      padArgb = PAD_ARGB,
      measuredContent = { measured },
    )

  /** A row of [cells] independently toggling squares — the addressing fixture. */
  private fun setToggleRow(cells: Int) {
    this.cells = cells
    rule.setContent { Measured { ToggleRow(cells) } }
    rule.mainClock.advanceTimeByFrame()
  }

  /** One square that doubles its width when tapped — the mid-recording growth fixture. */
  private fun setGrowingBox() {
    rule.setContent { Measured { GrowingBox() } }
    rule.mainClock.advanceTimeByFrame()
  }

  @Composable
  private fun Measured(content: @Composable () -> Unit) {
    Box(modifier = Modifier.onGloballyPositioned { measured = it.size }) { content() }
  }

  @Composable
  private fun ToggleRow(cells: Int) {
    Row {
      repeat(cells) {
        var on by remember { mutableStateOf(false) }
        Box(
          modifier =
            Modifier.size(CELL_DP.dp)
              .background(if (on) Color(ON_ARGB) else Color(OFF_ARGB))
              .clickable { on = !on }
        )
      }
    }
  }

  @Composable
  private fun GrowingBox() {
    var grown by remember { mutableStateOf(false) }
    Box(
      modifier =
        Modifier.size(width = if (grown) (CELL_DP * 2).dp else CELL_DP.dp, height = CELL_DP.dp)
          .background(Color(ON_ARGB))
          .clickable { grown = !grown }
    )
  }

  private fun cellWidth(frame: BufferedImage): Int = frame.width / cells

  /** The colour at the centre of cell [index], given a row of equal-width [cell] cells. */
  private fun BufferedImage.cell(index: Int, cell: Int): Int =
    getRGB(index * cell + cell / 2, height / 2)

  private companion object {
    const val CELL_DP = 24
    const val ON_ARGB = 0xFF00C853.toInt()
    const val OFF_ARGB = 0xFF212121.toInt()
    const val PAD_ARGB = 0xFF0000FF.toInt()
  }
}

/**
 * Just enough APNG reading to assert on a capture: the declared frame count, the frame delay, and
 * the frames themselves as ordinary `BufferedImage`s.
 *
 * `ImageIO` decodes an APNG's first frame and stops — every later frame lives in `fdAT` chunks it
 * has no reader for — so a test that wants to see whether the pointer actually moved anything has
 * to split the container itself. Each frame is reassembled as a standalone PNG (the shared `IHDR`,
 * that frame's image data, `IEND`) and handed to `ImageIO`, which mirrors how the encoder built it.
 */
private object Apng {

  private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

  fun declaredFrameCount(file: File): Int {
    val bytes = file.readBytes()
    val acTl = chunks(bytes).first { it.type == "acTL" }
    return ByteBuffer.wrap(bytes, acTl.dataOffset, 4).int
  }

  fun delay(file: File): Pair<Short, Short> {
    val bytes = file.readBytes()
    val fcTl = chunks(bytes).first { it.type == "fcTL" }
    // fcTL: seq(4) w(4) h(4) xOff(4) yOff(4) delayNum(2) delayDen(2) …
    val buf = ByteBuffer.wrap(bytes, fcTl.dataOffset + 20, 4)
    return buf.short to buf.short
  }

  fun frames(file: File): List<BufferedImage> {
    val bytes = file.readBytes()
    val all = chunks(bytes)
    val ihdr = all.first { it.type == "IHDR" }
    val header = bytes.copyOfRange(ihdr.offset, ihdr.offset + 12 + ihdr.length)

    val frames = mutableListOf<ByteArray>()
    var pending: java.io.ByteArrayOutputStream? = null
    for (c in all) {
      when (c.type) {
        "fcTL" -> {
          pending?.let { frames += it.toByteArray() }
          pending = java.io.ByteArrayOutputStream()
        }
        "IDAT" -> pending?.write(bytes, c.offset, 12 + c.length)
        // An `fdAT` payload is an `IDAT` payload behind a 4-byte sequence number.
        "fdAT" ->
          pending?.write(
            pngChunk("IDAT", bytes.copyOfRange(c.dataOffset + 4, c.dataOffset + c.length))
          )
      }
    }
    pending?.let { frames += it.toByteArray() }

    val iend = pngChunk("IEND", ByteArray(0))
    return frames.map { data ->
      val png = SIGNATURE + header + data + iend
      ByteArrayInputStream(png).use { ImageIO.read(it) }
        ?: error("reassembled APNG frame did not decode")
    }
  }

  private data class Chunk(val type: String, val offset: Int, val length: Int) {
    /** Where this chunk's payload starts — past the 4-byte length and the 4-byte type. */
    val dataOffset: Int
      get() = offset + 8
  }

  private fun chunks(bytes: ByteArray): List<Chunk> {
    val out = mutableListOf<Chunk>()
    var i = SIGNATURE.size
    while (i + 8 <= bytes.size) {
      val length = ByteBuffer.wrap(bytes, i, 4).int
      val type = String(bytes, i + 4, 4, Charsets.US_ASCII)
      out += Chunk(type, i, length)
      i += 12 + length
    }
    return out
  }

  private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc =
      CRC32().apply {
        update(typeBytes)
        update(data)
      }
    return ByteBuffer.allocate(12 + data.size)
      .putInt(data.size)
      .put(typeBytes)
      .put(data)
      .putInt(crc.value.toInt())
      .array()
  }
}
