package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import ee.schimke.composeai.overrides.LocalPreviewOverrideHost
import ee.schimke.composeai.overrides.PreviewOverrideController
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `--knob` bake seam: [parsePreviewOverrideKnobSeed] decodes the Base64 `key=value`-per-line
 * blob the gradle plugin forwards, and seeding it into [PreviewOverrideController] before a render
 * makes the in-composition `previewOverride*` lookups (the ones `CatalogSticker` performs) return
 * the override — so a batch bake re-themes exactly like the live daemon path. This pins both the
 * decode and that a seeded value actually flips the rendered pixels.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class PreviewOverrideKnobSeedTest {

  private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

  @Test
  fun `decodes the base64 key=value blob, splitting each line on its first equals`() {
    // A serialized `scheme:` value carries `,;:=` — the split-on-FIRST-`=` keeps it intact.
    val blob = "theme.colors=scheme:l=primary:FF00695C;d=primary:FF4DD0E1\ntheme.font=Roboto Flex"
    val seed = parsePreviewOverrideKnobSeed(b64(blob))
    assertEquals(2, seed.size)
    assertEquals(
      PreviewOverrideValue.StringValue("scheme:l=primary:FF00695C;d=primary:FF4DD0E1"),
      seed["theme.colors"],
    )
    assertEquals(PreviewOverrideValue.StringValue("Roboto Flex"), seed["theme.font"])
  }

  @Test
  fun `blank, null, or malformed base64 decodes to an empty seed (a plain stock render)`() {
    assertEquals(emptyMap<String, PreviewOverrideValue>(), parsePreviewOverrideKnobSeed(null))
    assertEquals(emptyMap<String, PreviewOverrideValue>(), parsePreviewOverrideKnobSeed("   "))
    assertEquals(
      emptyMap<String, PreviewOverrideValue>(),
      parsePreviewOverrideKnobSeed("!!! not base64 !!!"),
    )
    // A line with no `=` (or an empty key) is skipped rather than mis-parsed.
    assertEquals(
      emptyMap<String, PreviewOverrideValue>(),
      parsePreviewOverrideKnobSeed(b64("junk")),
    )
  }

  /** Center pixel after rendering a fill driven by the seeded `theme.colors` knob. */
  private fun centerColor(seed: Map<String, PreviewOverrideValue>): Triple<Int, Int, Int> {
    PreviewOverrideController.set(seed)
    val scene = ImageComposeScene(width = 16, height = 16, density = Density(1f))
    try {
      scene.setContent {
        // The exact read a catalog sticker performs: the default host
        // (ControllerPreviewOverrideHost)
        // resolves the seeded value, else the author default.
        val themed = LocalPreviewOverrideHost.current.string("theme.colors", "M3", null) == "SEEDED"
        val fill = if (themed) Color.Green else Color.Red
        Layout(modifier = Modifier.fillMaxSize().background(fill)) { _, c ->
          layout(c.maxWidth, c.maxHeight) {}
        }
      }
      scene.render()
      val image = scene.render()
      val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
      val bmp = ByteArrayInputStream(png.bytes).use { ImageIO.read(it) }
      val rgb = bmp.getRGB(8, 8)
      return Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    } finally {
      scene.close()
      PreviewOverrideController.resetForNewSession()
    }
  }

  @Test
  fun `a decoded seed re-themes the render the override host drives`() {
    // Seeding the knob (the renderPreview `set()` path) makes the in-composition lookup return the
    // seeded value — green. Without a seed it stays the author default — red. Same mechanism the
    // batch bake uses to re-skin every compose-m3 sticker under an app theme.
    val (r, g, b) = centerColor(parsePreviewOverrideKnobSeed(b64("theme.colors=SEEDED")))
    assertTrue("expected green (seeded) but was ($r,$g,$b)", g > 150 && r < 100 && b < 100)
    val (r2, g2, b2) = centerColor(emptyMap())
    assertTrue("expected red (stock) but was ($r2,$g2,$b2)", r2 > 150 && g2 < 100 && b2 < 100)
  }
}
