package ee.schimke.composeai.renderer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Probe values for the whole-object + shape sidecar test — declared at file scope so they reflect
// off this test file's synthetic `…CatalogTokenSidecarTestKt` class.
val ProbeScheme: ColorScheme = lightColorScheme(primary = Color(0xFF112233))
val ProbeShape: Shape = RoundedCornerShape(12.dp)
val ProbeShapes: Shapes = Shapes(small = RoundedCornerShape(4.dp))

/**
 * Exercises the resolved-token sidecar end to end (issue #2167): reflect a `@ColorCatalog` colour
 * and a `@TypographyCatalog` `TextStyle` — reusing the probe `val`s from the reflection probe tests
 * — and assert the emitted `data/catalog-tokens/<id>.catalog.json` carries their resolved values
 * (hex for the colour, size/weight metrics for the type style).
 */
class CatalogTokenSidecarTest {

  private val colorOwner = "ee.schimke.composeai.renderer.ColorValueReflectionProbeTestKt"
  private val textOwner = "ee.schimke.composeai.renderer.TextStyleValueReflectionProbeTestKt"

  @Test
  fun `writes resolved colour and type-style tokens to the sidecar`() {
    val renders = Files.createTempDirectory("catalog-sidecar").resolve("renders").toFile()
    val previous = System.getProperty("composeai.render.outputDir")
    System.setProperty("composeai.render.outputDir", renders.path)
    try {
      val tokens =
        listOf(
          // `ProbeOpaque = Color(0xFF3366CC)`, `ProbeAlpha = Color(0x80112233)`.
          CatalogToken(colorOwner, "ProbeOpaque", "Brand", CatalogTokenKind.COLOR),
          CatalogToken(colorOwner, "ProbeAlpha", "Scrim", CatalogTokenKind.COLOR),
          // `ProbeDisplay = TextStyle(fontSize = 57.sp, fontWeight = FontWeight.Medium)`.
          CatalogToken(textOwner, "ProbeDisplay", "Display", CatalogTokenKind.TEXT_STYLE),
        )
      CatalogTokenSidecar.write("mixed__all", tokens)

      val sidecar = CatalogTokenSidecar.pathFor(renders, "mixed__all")
      assertTrue("sidecar not written at ${sidecar.path}", sidecar.exists())
      val json = sidecar.readText()

      assertTrue(json.contains("\"schema\":\"${CatalogTokenSidecar.SCHEMA}\""))
      assertTrue(json.contains("\"previewId\":\"mixed__all\""))
      // Colour tokens: uppercase #AARRGGBB, alpha preserved.
      assertTrue(json.contains("\"kind\":\"COLOR\""))
      assertTrue(json.contains("\"hex\":\"#FF3366CC\""))
      assertTrue(json.contains("\"hex\":\"#80112233\""))
      // Type token: resolved size + weight.
      assertTrue(json.contains("\"kind\":\"TEXT_STYLE\""))
      assertTrue(json.contains("\"fontSizeSp\":57.0"))
      assertTrue(json.contains("\"fontWeight\":500"))
      assertTrue(json.contains("\"member\":\"ProbeDisplay\""))
    } finally {
      if (previous == null) System.clearProperty("composeai.render.outputDir")
      else System.setProperty("composeai.render.outputDir", previous)
    }
  }

  @Test
  fun `expands whole-object scales and shape geometry into resolved sidecar entries`() {
    val owner = "ee.schimke.composeai.renderer.CatalogTokenSidecarTestKt"
    val renders = Files.createTempDirectory("catalog-whole").resolve("renders").toFile()
    val previous = System.getProperty("composeai.render.outputDir")
    System.setProperty("composeai.render.outputDir", renders.path)
    try {
      val tokens =
        listOf(
          CatalogToken(owner, "ProbeScheme", "Brand", CatalogTokenKind.COLOR_SCHEME),
          CatalogToken(owner, "ProbeShape", "Card", CatalogTokenKind.SHAPE),
          CatalogToken(owner, "ProbeShapes", "Scale", CatalogTokenKind.SHAPES),
        )
      CatalogTokenSidecar.write("whole__all", tokens)

      val sidecar = CatalogTokenSidecar.pathFor(renders, "whole__all")
      assertTrue("sidecar not written at ${sidecar.path}", sidecar.exists())
      val json = sidecar.readText()

      // The whole ColorScheme expands to per-role COLOR entries with real hex values (not a marker).
      assertFalse("whole-object marker leaked", json.contains("\"whole\":true"))
      assertTrue(json.contains("\"label\":\"Brand · primary\""))
      assertTrue(json.contains("\"hex\":\"#FF112233\""))
      // The single Shape resolves corner geometry (12dp → 12px at the 48px reference square).
      assertTrue(json.contains("\"label\":\"Card\""))
      assertTrue(json.contains("\"type\":\"RoundedCornerShape\""))
      assertTrue(json.contains("\"topStart\":12.0"))
      // The whole Shapes expands to per-role SHAPE entries (small = 4dp → 4px).
      assertTrue(json.contains("\"label\":\"Scale · small\""))
      assertTrue(json.contains("\"topStart\":4.0"))
    } finally {
      if (previous == null) System.clearProperty("composeai.render.outputDir")
      else System.setProperty("composeai.render.outputDir", previous)
    }
  }

  @Test
  fun `writes resolved theme tokens keyed by theme name`() {
    val renders = Files.createTempDirectory("theme-sidecar").resolve("renders").toFile()
    val previous = System.getProperty("composeai.render.outputDir")
    System.setProperty("composeai.render.outputDir", renders.path)
    try {
      val tokens =
        listOf(
          CatalogTokenSidecar.ResolvedToken.Colour("primary", Color(0xFFFF6F61)),
          CatalogTokenSidecar.ResolvedToken.Colour("surface", Color(0x80112233)),
          CatalogTokenSidecar.ResolvedToken.Type(
            "displaySmall",
            TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Medium),
          ),
        )
      CatalogTokenSidecar.writeResolved("themecatalog__Brand_Light", "Brand Light", tokens)

      val sidecar = CatalogTokenSidecar.pathFor(renders, "themecatalog__Brand_Light")
      assertTrue("sidecar not written at ${sidecar.path}", sidecar.exists())
      val json = sidecar.readText()

      assertTrue(json.contains("\"schema\":\"${CatalogTokenSidecar.SCHEMA}\""))
      assertTrue(json.contains("\"previewId\":\"themecatalog__Brand_Light\""))
      // The theme key is the per-theme axis design-parity maps onto a Figma variable mode.
      assertTrue(json.contains("\"theme\":\"Brand Light\""))
      // Live resolved role → hex, alpha preserved, keyed by the M3 role label.
      assertTrue(json.contains("\"label\":\"primary\""))
      assertTrue(json.contains("\"kind\":\"COLOR\""))
      assertTrue(json.contains("\"hex\":\"#FFFF6F61\""))
      assertTrue(json.contains("\"hex\":\"#80112233\""))
      // Live resolved type role → metrics.
      assertTrue(json.contains("\"label\":\"displaySmall\""))
      assertTrue(json.contains("\"kind\":\"TEXT_STYLE\""))
      assertTrue(json.contains("\"fontSizeSp\":36.0"))
      assertTrue(json.contains("\"fontWeight\":500"))
    } finally {
      if (previous == null) System.clearProperty("composeai.render.outputDir")
      else System.setProperty("composeai.render.outputDir", previous)
    }
  }

  @Test
  fun `theme sidecar no-ops for empty tokens`() {
    val renders = Files.createTempDirectory("theme-sidecar-empty").resolve("renders").toFile()
    val previous = System.getProperty("composeai.render.outputDir")
    System.setProperty("composeai.render.outputDir", renders.path)
    try {
      CatalogTokenSidecar.writeResolved("themecatalog__empty", "Empty", emptyList())
      assertFalse(CatalogTokenSidecar.pathFor(renders, "themecatalog__empty").exists())
    } finally {
      if (previous == null) System.clearProperty("composeai.render.outputDir")
      else System.setProperty("composeai.render.outputDir", previous)
    }
  }

  @Test
  fun `no-ops for empty tokens`() {
    val renders = Files.createTempDirectory("catalog-sidecar-empty").resolve("renders").toFile()
    val previous = System.getProperty("composeai.render.outputDir")
    System.setProperty("composeai.render.outputDir", renders.path)
    try {
      CatalogTokenSidecar.write("empty__all", emptyList())
      assertFalse(CatalogTokenSidecar.pathFor(renders, "empty__all").exists())
    } finally {
      if (previous == null) System.clearProperty("composeai.render.outputDir")
      else System.setProperty("composeai.render.outputDir", previous)
    }
  }
}
