package ee.schimke.composeai.daemon

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.io.SystemFileSystem
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat

/**
 * Desktop half of the **figma-svg fidelity harness**: rasterises a preview's `compose-figma.svg`,
 * aligns it to the padding-free render PNG, and scores how faithfully the layered export reproduces
 * the render via the pure [FigmaFidelity] engine — writing a `render | figma-svg | diff` composite
 * and a score sidecar.
 *
 * It runs only where the renderer runs and only when asked ([enabled]) so the default render path
 * stays free — the harness is a QA/CI pass, not a per-render cost. It is the measurement Task-2
 * wants: a structural score that says whether the vector export drifted from the render.
 *
 * ### Rasteriser
 * The SVG is rasterised with **headless Chromium** when a browser is discoverable — the same engine
 * Figma/browsers use to interpret an imported SVG, so `<text>`, gradients, and the `figma-raster/`
 * `<image>` layers all render, and the score measures the *export's* fidelity rather than a
 * decoder's gaps. When no browser is present it falls back to Skia [loadSvgPainter] (shapes/paths
 * only — no SVG text), and records which engine was used in the score sidecar so a shapes-only
 * score isn't mistaken for a text-inclusive one.
 *
 * ### Alignment
 * The export adds [FigmaSvgModel.DEFAULT_PADDING] (16px) of transparent margin and draws the tree
 * under a `translate(tx, ty)` (`tx = padding - minX`). The render PNG is padding-free with content
 * at `(0,0)`. So we rasterise the SVG at its native `viewBox` size (1 unit = 1px, because the model
 * already converted dp→px at the render density), then place the SVG's content-space origin at the
 * aligned origin by drawing it offset by `(-tx, -ty)` onto a render-sized canvas. `tx`/`ty` are
 * read from the emitted SVG so a non-root-origin extent aligns correctly too.
 */
object FigmaSvgFidelity {

  const val FILE_COMPOSITE: String = "compose-figma-fidelity.png"
  const val FILE_SCORE: String = "compose-figma-fidelity.json"

  /** Gated on `-Dcomposeai.figma.fidelity=true` (or the env var) so it's opt-in for CI runs. */
  fun enabled(): Boolean =
    System.getProperty("composeai.figma.fidelity")?.equals("true", ignoreCase = true) == true ||
      System.getenv("COMPOSEAI_FIGMA_FIDELITY")?.equals("true", ignoreCase = true) == true

  /**
   * Rasterises [svgFile], diffs it against [renderPng], and writes the composite + score into
   * [previewDir]. Best-effort: any failure (unreadable file, undecodable SVG, size mismatch) is
   * swallowed so a fidelity hiccup never strands the render outputs. Returns the
   * [FigmaFidelity.Result] when it succeeded, else null.
   */
  fun write(
    previewDir: File,
    svgFile: File,
    renderPng: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): FigmaFidelity.Result? {
    return try {
      if (!svgFile.exists() || !renderPng.exists()) return null
      val svgText = fileSystem.read(svgFile.path.toPath()) { readUtf8() }
      val render = ImageIO.read(ByteArrayInputStream(renderPng.readBytes())) ?: return null
      val w = intAttr(svgText, "width") ?: return null
      val h = intAttr(svgText, "height") ?: return null
      if (w <= 0 || h <= 0) return null
      val (svgRaster, rasterizer) =
        rasterizeWithBrowser(previewDir, svgText, w, h)?.let { it to "chromium" }
          ?: (rasterizeWithSkia(svgText, w, h)?.let { it to "skia" } ?: return null)
      val aligned = alignToRender(svgRaster, svgText, render.width, render.height)
      val result = FigmaFidelity.compare(render, aligned)

      val compositeBytes = encodePng(result.composite)
      fileSystem.write(previewDir.resolve(FILE_COMPOSITE).path.toPath()) { write(compositeBytes) }
      fileSystem.write(previewDir.resolve(FILE_SCORE).path.toPath()) {
        writeUtf8(scoreJson(result, rasterizer))
      }
      result
    } catch (t: Throwable) {
      System.err.println(
        "FigmaSvgFidelity: fidelity write failed for ${previewDir.name}: " +
          "${t.javaClass.simpleName}: ${t.message}"
      )
      null
    }
  }

  /**
   * Rasterise the SVG with headless Chromium (the browser engine an imported SVG is interpreted
   * by), so text/gradients/`<image>` layers all render. Returns null when no browser is
   * discoverable or the screenshot fails, so the caller can fall back to Skia. The temp HTML
   * wrapper lives in [previewDir] so the SVG's relative `figma-raster/` hrefs resolve.
   */
  private fun rasterizeWithBrowser(
    previewDir: File,
    svgText: String,
    w: Int,
    h: Int,
  ): BufferedImage? {
    val chrome = findChromium() ?: return null
    val html = File(previewDir, ".compose-figma-fidelity.html")
    val out = File(previewDir, ".compose-figma-fidelity-raster.png")
    return try {
      html.writeText(
        "<!doctype html><meta charset=utf-8>" +
          "<style>html,body{margin:0;padding:0;background:transparent}svg{display:block}</style>" +
          svgText
      )
      val proc =
        ProcessBuilder(
            chrome,
            "--headless",
            "--disable-gpu",
            "--no-sandbox",
            "--hide-scrollbars",
            "--force-device-scale-factor=1",
            "--default-background-color=00000000",
            "--window-size=$w,$h",
            "--screenshot=${out.absolutePath}",
            html.toURI().toString(),
          )
          .redirectErrorStream(true)
          .start()
      if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
        proc.destroyForcibly()
        return null
      }
      if (!out.exists()) null else ImageIO.read(out)
    } catch (t: Throwable) {
      null
    } finally {
      html.delete()
      out.delete()
    }
  }

  /** Locate a headless-Chromium binary from the Playwright browser dir, or null if absent. */
  private fun findChromium(): String? {
    val root = System.getenv("PLAYWRIGHT_BROWSERS_PATH")?.let { File(it) } ?: return null
    if (!root.isDirectory) return null
    val candidates =
      root
        .listFiles { f -> f.isDirectory && f.name.startsWith("chromium") }
        ?.sortedByDescending { it.name }
        .orEmpty()
    for (dir in candidates) {
      for (rel in listOf("chrome-linux/headless_shell", "chrome-linux/chrome")) {
        val bin = File(dir, rel)
        if (bin.canExecute()) return bin.absolutePath
      }
    }
    return null
  }

  /** Skia fallback: renders shapes/paths (no SVG text) at native size via [loadSvgPainter]. */
  private fun rasterizeWithSkia(svgText: String, w: Int, h: Int): BufferedImage? {
    val density = Density(1f)
    val painter = loadSvgPainter(ByteArrayInputStream(svgText.toByteArray()), density)
    val scene = ImageComposeScene(width = w, height = h, density = density)
    return try {
      scene.setContent {
        Box(modifier = Modifier.fillMaxSize()) {
          Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
          )
        }
      }
      scene.render()
      val image = scene.render()
      val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: return null
      ImageIO.read(ByteArrayInputStream(bytes))
    } finally {
      scene.close()
    }
  }

  /**
   * Places the SVG's content-space origin at `(0,0)` by drawing [svgRaster] offset by `(-tx, -ty)`
   * onto a [renderW]×[renderH] canvas — cropping the export's padding and any extent offset so the
   * result lines up pixel-for-pixel with the render.
   */
  private fun alignToRender(
    svgRaster: BufferedImage,
    svgText: String,
    renderW: Int,
    renderH: Int,
  ): BufferedImage {
    val (tx, ty) = translateOf(svgText)
    val out = BufferedImage(renderW, renderH, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.drawImage(svgRaster, -tx, -ty, null)
    g.dispose()
    return out
  }

  /** `<svg … width="96" …>` → 96. */
  private fun intAttr(svg: String, name: String): Int? =
    Regex("<svg\\b[^>]*\\b$name\\s*=\\s*\"(-?\\d+)\"", RegexOption.IGNORE_CASE)
      .find(svg)
      ?.groupValues
      ?.get(1)
      ?.toIntOrNull()

  /** `translate(16, 16)` → (16, 16); defaults to (0,0) if absent. */
  private fun translateOf(svg: String): Pair<Int, Int> {
    val m = Regex("translate\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)").find(svg) ?: return 0 to 0
    return (m.groupValues[1].toIntOrNull() ?: 0) to (m.groupValues[2].toIntOrNull() ?: 0)
  }

  private fun encodePng(image: BufferedImage): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
  }

  private fun scoreJson(r: FigmaFidelity.Result, rasterizer: String): String =
    """{"score":${"%.4f".format(r.score)},""" +
      """"scorePercent":${"%.2f".format(r.scorePercent)},""" +
      """"meanAbsError":${"%.3f".format(r.meanAbsError)},""" +
      """"rasterizer":"$rasterizer",""" +
      """"width":${r.width},"height":${r.height}}"""
}
