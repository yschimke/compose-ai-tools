package ee.schimke.composeai.tui.e2e

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Builds a self-contained fixture project the e2e test points `compose-preview-tui --no-discovery
 * --module :sample` at. Everything the TUI needs lives on disk before kitty starts:
 *
 * * `<root>/gradlew` — empty marker so `findProjectRoot` succeeds.
 * * `<root>/sample/build/compose-previews/previews.json` — synthetic manifest with three previews
 *   so the list pane has something to scroll through.
 * * `<root>/sample/build/compose-previews/<id>.png` — three small PNGs the preview pane decodes
 *   into a Mosaic `Bitmap` and hands to the fork's `Image` composable. Filling them with distinct
 *   gradients makes the rendered output visibly different per preview, which is the easiest way to
 *   confirm at capture-review time that selection changes are actually re-reading the right file.
 * * `<root>/sample/build/compose-previews/accessibility.json` — one preview has a synthetic `ERROR`
 *   finding so the data pane shows non-empty content for one capture state.
 *
 * Returns the root path. The caller owns lifecycle — the test calls [build] once per run and lets
 * the JVM tempdir survive for inspection.
 */
object Fixtures {
  fun build(parent: File): File {
    val root = File(parent, "tui-fixture").apply { mkdirs() }
    File(root, "gradlew").writeText("#!/bin/sh\nexit 1\n")

    val moduleDir = File(root, "sample").apply { mkdirs() }
    val outDir = File(moduleDir, "build/compose-previews").apply { mkdirs() }

    val previewIds = listOf("ButtonPreview", "CardPreview", "DialogPreview")
    val manifest = buildString {
      append("{\n")
      append("  \"module\": \":sample\",\n")
      append("  \"variant\": \"debug\",\n")
      append("  \"previews\": [\n")
      previewIds.forEachIndexed { i, id ->
        append("    {\n")
        append("      \"id\": \"$id\",\n")
        append("      \"functionName\": \"$id\",\n")
        append("      \"className\": \"ee.schimke.fixture.Previews\",\n")
        append("      \"sourceFile\": \"src/main/kotlin/Previews.kt\"\n")
        append("    }")
        if (i != previewIds.lastIndex) append(",")
        append("\n")
      }
      append("  ]\n")
      append("}\n")
    }
    File(outDir, "previews.json").writeText(manifest)

    // Pre-render distinct gradients so the ASCII renderer outputs visibly different art per
    // preview. 320x480 keeps each PNG under a few KB but high enough that the half-block
    // sampler produces interesting variation.
    previewIds.forEachIndexed { i, id ->
      val png = renderGradient(width = 320, height = 480, hue = i * 0.27f, label = id)
      ImageIO.write(png, "png", File(outDir, "$id.png"))
    }

    File(outDir, "accessibility.json")
      .writeText(
        """
        {
          "module": ":sample",
          "entries": [
            {
              "previewId": "ButtonPreview",
              "findings": [
                {
                  "level": "ERROR",
                  "type": "TouchTargetSize",
                  "message": "View has a 24x24dp touch target; minimum is 48x48dp.",
                  "viewDescription": "Button[id=ok]"
                },
                {
                  "level": "WARNING",
                  "type": "TextContrast",
                  "message": "Foreground/background contrast ratio 3.1 falls below WCAG AA threshold 4.5."
                }
              ]
            },
            {
              "previewId": "CardPreview",
              "findings": []
            }
          ]
        }
        """
          .trimIndent()
      )

    return root
  }

  private fun renderGradient(width: Int, height: Int, hue: Float, label: String): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g: Graphics2D = img.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
      )
      for (y in 0 until height) {
        val t = y.toFloat() / height
        g.color = Color.getHSBColor(hue, 0.85f - 0.3f * t, 0.5f + 0.5f * t)
        g.drawLine(0, y, width, y)
      }
      g.color = Color.WHITE
      g.font = g.font.deriveFont(48f)
      val fm = g.fontMetrics
      val tx = (width - fm.stringWidth(label)) / 2
      val ty = height / 2 + fm.ascent / 2
      g.drawString(label, tx, ty)
    } finally {
      g.dispose()
    }
    return img
  }
}
