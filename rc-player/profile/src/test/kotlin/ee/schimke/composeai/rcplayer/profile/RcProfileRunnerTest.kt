package ee.schimke.composeai.rcplayer.profile

import ee.schimke.composeai.rcplayer.trace.RcTrace
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real player headlessly, at a fraction of the profile's iteration counts.
 *
 * This is the test that would have caught the mistake the harness actually made: the first version
 * handed `RcComposePlayer` a bare `Modifier`, the raw draw path's canvas measured 0×0, and every
 * canvas-drawn text run laid out into nothing — while the timing tables carried on looking
 * perfectly healthy. Rendering the pixels and asserting they are not one flat colour is what makes
 * a silently empty profile fail.
 */
class RcProfileRunnerTest {
  @AfterTest
  fun clearRecorder() {
    RcTrace.recorder = null
  }

  @Test
  fun everyScenarioDrawsSomething() {
    val runner = RcProfileRunner(warmupLoads = 0, warmupFrames = 0)
    rcProfileScenarios(loads = 1, framesPerLoad = 4).forEach { scenario ->
      val png = runner.capture(scenario)
      assertTrue(png.size > 100, "${scenario.id} produced a ${png.size} B PNG")
      assertTrue(
        distinctColours(png) > 1,
        "${scenario.id} rendered a single flat colour — nothing was drawn",
      )
    }
  }

  @Test
  fun eachScenarioReportsTheSpansItsTablesAreBuiltFrom() {
    val results =
      RcProfileRunner(warmupLoads = 0, warmupFrames = 0)
        .run(rcProfileScenarios(loads = 1, framesPerLoad = 6))
    val byId = results.associateBy { it.scenario.id }

    results.forEach { result ->
      val names = result.sections.map { it.name }
      assertContains(names, "rc:decode", "${result.scenario.id}: $names")
      assertContains(names, "rc:link", "${result.scenario.id}: $names")
      assertContains(names, "rc:beginFrame", "${result.scenario.id}: $names")
    }

    // The canvas documents paint through the raw root pass and measure their own text.
    listOf("static-canvas", "animated-canvas").forEach { id ->
      val names = byId.getValue(id).sections.map { it.name }
      assertContains(names, "rc:drawRoot", "$id: $names")
      assertContains(names, "rc:measureText", "$id: $names")
    }

    // The animated document keeps requesting frames; the static one paints once and stops. That
    // difference is the whole reason the animated scenario is in the set, so it is worth pinning.
    val staticDraws = drawRootCount(byId.getValue("static-canvas"))
    val animatedDraws = drawRootCount(byId.getValue("animated-canvas"))
    assertEquals(1, staticDraws, "a static document should paint once per load")
    assertTrue(
      animatedDraws > staticDraws,
      "an animated document should repaint ($animatedDraws vs $staticDraws)",
    )

    // Taps reach the player's action dispatch rather than stopping at the hit test.
    val interactive = byId.getValue("interactive-button").sections.map { it.name }
    assertContains(interactive, "rc:actions", "interactive-button: $interactive")
  }

  private fun drawRootCount(result: RcProfileResult): Int =
    result.sections.firstOrNull { it.name == "rc:drawRoot" }?.count ?: 0

  /** Distinct pixel values in the PNG, decoded through skiko rather than a PNG parser. */
  private fun distinctColours(png: ByteArray): Int {
    val image = org.jetbrains.skia.Image.makeFromEncoded(png)
    val bitmap = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(image.width, image.height) }
    check(image.readPixels(bitmap))
    val seen = mutableSetOf<Int>()
    for (y in 0 until image.height step 4) {
      for (x in 0 until image.width step 4) {
        seen += bitmap.getColor(x, y)
        if (seen.size > 1) return seen.size
      }
    }
    return seen.size
  }
}
