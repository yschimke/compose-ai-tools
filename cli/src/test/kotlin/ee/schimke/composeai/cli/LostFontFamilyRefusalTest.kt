package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gate that stopped a sheet of missing-glyph boxes from publishing again.
 *
 * A `compose/figma-svg` export that cannot name a font family the render drew falls back to boxes
 * and writes `compose-figma-fonts.warnings.json` beside the SVG. For a year nothing read that file:
 * it stayed in the render's data dir, the boxes published, and the defect was found by eye in a
 * live catalog days later. The sidecar now travels with the sticker, and its presence refuses the
 * pack.
 */
class LostFontFamilyRefusalTest {

  @Test
  fun `a healthy pack is not refused`() {
    // No sidecar means no degraded preview — the export writes one only when it degrades.
    assertNull(lostFontFamilyRefusal(emptyList(), allowed = false, bundlePath = "b.png"))
  }

  @Test
  fun `a degraded pack is refused and names every preview and the sidecar`() {
    val message =
      lostFontFamilyRefusal(
        listOf("AppCard_ideal", "Chip_default"),
        allowed = false,
        bundlePath = "out/wear-m3-bundle.png",
      )

    assertNotNull(message)
    // The message has to carry what a publisher needs to act: how many, which, and where the
    // machine-readable reason lives.
    assertTrue(message.contains("2 preview(s)"), message)
    assertTrue(message.contains("AppCard_ideal"), message)
    assertTrue(message.contains("Chip_default"), message)
    assertTrue(message.contains(".figma-fonts.warnings.json"), message)
    assertTrue(message.contains("out/wear-m3-bundle.png"), message)
    assertTrue(message.contains("--allow-lost-font-families"), message)
  }

  @Test
  fun `the override publishes the boxes deliberately`() {
    // Same posture as the render's own -Dcomposeai.fonts.failOnFallback: refusing is the default,
    // and shipping the degraded artefact stays possible but has to be asked for.
    assertNull(lostFontFamilyRefusal(listOf("AppCard_ideal"), allowed = true, bundlePath = "b.png"))
  }
}
