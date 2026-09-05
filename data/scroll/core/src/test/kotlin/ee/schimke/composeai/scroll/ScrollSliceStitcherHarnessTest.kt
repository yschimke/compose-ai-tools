package ee.schimke.composeai.scroll

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Developer harness — re-stitches every `<id>_slices/` directory the renderer kept on disk
 * (`COMPOSEAI_KEEP_SCROLL_SLICES=1`) under `COMPOSEAI_STITCH_HARNESS_DIR`, so the matcher can be
 * iterated on without a Robolectric round-trip. Each directory holds `slice_N.png` files, an
 * optional `final_frame.png` and `scrolled.txt` (one cumulative scrolled-px value per slice, one
 * per line). Writes `restitched.png` beside them and prints every seam's verdict. Skipped unless
 * the variable is set.
 */
class ScrollSliceStitcherHarnessTest {
  @Test
  fun restitch() {
    val root = System.getenv("COMPOSEAI_STITCH_HARNESS_DIR")?.let(::File)
    assumeTrue(root != null && root.isDirectory)
    val dirs =
      root!!.listFiles { f -> f.isDirectory && f.name.endsWith("_slices") }!!.sortedBy { it.name }
    val measured = System.getenv("COMPOSEAI_STITCH_HARNESS_MEASURED") == "1"
    for (dir in dirs) {
      val sliceFiles =
        dir
          .listFiles { f -> f.name.startsWith("slice_") && f.name.endsWith(".png") }!!
          .sortedBy { it.name.removePrefix("slice_").removeSuffix(".png").toInt() }
      val scrolled =
        File(dir, "scrolled.txt").readLines().filter { it.isNotBlank() }.map { it.toFloat() }
      val slices = sliceFiles.mapIndexed { i, f -> SliceCapture(scrolled[i], f, measured) }
      val final = File(dir, "final_frame.png")
      val out = File(dir, "restitched.png")
      val viewportPx = ImageIO.read(sliceFiles.first()).height
      val seams = mutableListOf<ScrollSeam>()
      if (final.exists()) {
        stitchSlicesWithFinalFrame(slices, final, viewportPx, out, isRound = true) { seams += it }
      } else {
        stitchSlices(slices, viewportPx, out) { seams += it }
      }
      applyWearPillClip(out)
      println("== ${dir.name}: ${ImageIO.read(out).let { "${it.width}x${it.height}" }}")
      seams.forEach { println("   " + it.describe()) }
    }
  }
}
