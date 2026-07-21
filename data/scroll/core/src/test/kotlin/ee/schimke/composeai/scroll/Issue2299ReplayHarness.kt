package ee.schimke.composeai.scroll

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DEBUG-ONLY throwaway harness for issue #2299. Replays the real long-scroll stitch from slices the
 * renderer captured under `COMPOSEAI_KEEP_SCROLL_SLICES=1`, so the pure-JVM stitcher can be iterated
 * in ~20s instead of a ~7-minute Robolectric render. Skips (assumeTrue) when no capture is present.
 * Deleted before the fix PR.
 */
class Issue2299ReplayHarness {
  private val slicesDir =
    File(
      "/home/user/compose-ai-tools/samples/wear/build/compose-previews/data/" +
        "render-scroll-long/SettingsMainScreenLongPreview_Devices_Large_Round_slices"
    )

  @Test
  fun replay() {
    val meta = File(slicesDir, "meta.txt")
    assumeTrue("no captured slices at $slicesDir", meta.exists())
    val lines = meta.readLines()
    val viewport = lines.first { it.startsWith("viewportLayoutPx=") }.substringAfter("=").trim().toInt()
    val isRound = lines.first { it.startsWith("isRound=") }.substringAfter("=").trim().toBoolean()
    val slices =
      lines
        .filter { it.startsWith("slice_") }
        .map {
          val parts = it.trim().split(" ")
          SliceCapture(parts[1].toFloat(), File(slicesDir, parts[0]))
        }
    println("REPLAY: ${slices.size} slices, viewport=$viewport, isRound=$isRound")
    val finalFrame = File(slicesDir, "final_frame.png")
    val out = File(slicesDir, "REPLAY_out.png")
    stitchSlicesWithFinalFrame(slices, finalFrame, viewport, out, isRound)
      ?: error("stitch returned null")
    if (isRound) applyWearPillClip(out)
    println("REPLAY wrote $out (${out.length()} bytes)")
  }
}
