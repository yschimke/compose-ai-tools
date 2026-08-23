package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A guttered preview's still and its motion products have to come out the same size on the Android
 * lane (issue #4467).
 *
 * The trap: a Robolectric resource qualifier is dp-only, so the hosting window grows by
 * `ceil(totalGutterPx / density)` dp — more pixels than the gutter actually resolves to whenever
 * the density is fractional. The still has always been trimmed back to the exact pixel target;
 * motion products encoded the qualifier-sized frame, so the same component published a PNG and a
 * GIF that disagreed about its own bounds by a pixel or two. Both now derive from
 * [fixedAxisTargetPx] — one number, so they cannot drift.
 */
class FixedAxisMotionTargetTest {

  /** The AS phone default, and the reason this bug exists at all. */
  private val fractionalDensity = 2.625f

  @Test
  fun `a fixed axis targets the declared frame plus the gutter's real pixels`() {
    // 120 dp at 2.625 is 315 px; two 4 dp edges round to 11 px each ⇒ 337.
    assertEquals(
      337,
      fixedAxisTargetPx(
        wrapped = false,
        hasDevice = false,
        declaredDp = 120,
        frameDp = 120,
        leadingGutterDp = 4,
        trailingGutterDp = 4,
        density = fractionalDensity,
      ),
    )
  }

  @Test
  fun `the target is not what the dp-only qualifier grew the window to`() {
    // This is the divergence being closed. The qualifier can only add whole dp, and it must not
    // round DOWN or the window ends up short of `component + gutter` and the crop eats a pixel of
    // shadow — so it adds `ceil(22 / 2.625)` = 9 dp, and the frame comes back wider than the
    // gutter resolves to. Encoding that straight into a GIF is what made a still and its motion
    // capture disagree.
    val qualifierDp = 120 + captureGutterAxisDp(4, 4, fractionalDensity)
    assertEquals(129, qualifierDp)
    val qualifierPx = Math.round(qualifierDp * fractionalDensity)
    assertEquals(339, qualifierPx)
    // Two pixels of transparent overshoot, which the trim removes.
    assertEquals(
      2,
      qualifierPx -
        fixedAxisTargetPx(
          wrapped = false,
          hasDevice = false,
          declaredDp = 120,
          frameDp = 120,
          leadingGutterDp = 4,
          trailingGutterDp = 4,
          density = fractionalDensity,
        )!!,
    )
  }

  @Test
  fun `an integral density has nothing to correct`() {
    assertEquals(
      120 * 2 + 16,
      fixedAxisTargetPx(
        wrapped = false,
        hasDevice = false,
        declaredDp = 120,
        frameDp = 120,
        leadingGutterDp = 4,
        trailingGutterDp = 4,
        density = 2.0f,
      ),
    )
  }

  @Test
  fun `wrapped axes, device frames and undeclared axes opt out`() {
    // A wrapped axis is cropped to the measured box, which already reports `child + gutter`.
    assertNull(target(wrapped = true))
    // A preview that names a device is asking for that device's frame, not for its own dp.
    assertNull(target(hasDevice = true))
    assertNull(target(declaredDp = null))
  }

  @Test
  fun `applying an empty target leaves the frame byte-identical`() {
    val file = png(40, 30)
    val before = file.readBytes()
    DialogWindowCapture.FixedAxisTarget().applyTo(file)
    assertEquals(before.size, file.readBytes().size)
    assertEquals(40 to 30, decode(file))
  }

  @Test
  fun `applying a target trims the overshoot off a captured frame`() {
    val file = png(339, 200)
    DialogWindowCapture.FixedAxisTarget(widthPx = 337, heightPx = 198).applyTo(file)
    assertEquals(337 to 198, decode(file))
  }

  @Test
  fun `a target only constrains the axis it names`() {
    val file = png(339, 200)
    DialogWindowCapture.FixedAxisTarget(widthPx = 337).applyTo(file)
    // The wrapped axis keeps whatever the measured crop gave it.
    assertEquals(337 to 200, decode(file))
  }

  private fun target(
    wrapped: Boolean = false,
    hasDevice: Boolean = false,
    declaredDp: Int? = 120,
  ): Int? =
    fixedAxisTargetPx(
      wrapped = wrapped,
      hasDevice = hasDevice,
      declaredDp = declaredDp,
      frameDp = 120,
      leadingGutterDp = 4,
      trailingGutterDp = 4,
      density = fractionalDensity,
    )

  private fun png(width: Int, height: Int): File {
    val file = Files.createTempFile("frame", ".png").toFile()
    file.deleteOnExit()
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "PNG", file)
    return file
  }

  private fun decode(file: File): Pair<Int, Int> {
    val img = ImageIO.read(file) ?: error("frame failed to decode")
    return img.width to img.height
  }
}
