package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Offline SVG regeneration for the wear catalog. The wear module is Android and can't build in this
 * environment (no SDK), but the figma-svg export is a pure JVM function of the committed
 * `layout-inspector.json` (+ `compose-semantics.json`) capture trees and the reference render PNG.
 * This walks those committed trees, re-runs the FULL export ([ComposeFigmaSvgDataProducer.writeSvg]
 * with the frame image so the hybrid raster path crops opaque/Canvas-drawn chrome exactly as CI
 * does) at wear density (2.0), and writes each preview's `compose-figma.svg` + `figma-raster/` to
 * the scratchpad so an external scorer can diff them against the reference renders.
 *
 * Gated behind env `WEAR_REGEN_OUT`/`WEAR_REGEN_IN` so it never runs in CI — it's a scoring aid.
 */
class WearSvgRegenHarness {
  @Test
  fun regenerate() {
    val outDir = System.getenv("WEAR_REGEN_OUT") ?: return
    val previews = File(System.getenv("WEAR_REGEN_IN") ?: return)
    val json = Json { ignoreUnknownKeys = true }
    val out = File(outDir).apply { mkdirs() }
    val dataRoot = previews.resolve("data")
    val rendersRoot = previews.resolve("renders")
    var n = 0
    dataRoot.listFiles()?.sorted()?.forEach { dir ->
      val li = dir.resolve("layout-inspector.json")
      if (!li.exists()) return@forEach
      val id = dir.name
      val render = rendersRoot.resolve("$id.png")
      if (!render.exists()) return@forEach
      val layout = json.decodeFromString(LayoutInspectorPayload.serializer(), li.readText())
      val sem =
        dir
          .resolve("compose-semantics.json")
          .takeIf { it.exists() }
          ?.let { json.decodeFromString(ComposeSemanticsPayload.serializer(), it.readText()) }
      val short = id.removePrefix("com.example.designcatalogwearm3.CatalogPreviewsKt.")
      // Full hybrid export with the frame so opaque/Canvas chrome is cropped exactly as CI ships,
      // and the Google-Fonts resolver so `<text>` embeds the real Roboto face (matching CI) rather
      // than a browser-substituted fallback — otherwise every text-heavy sticker scores
      // understated.
      val fontResolver =
        System.getenv("WEAR_REGEN_FONTCACHE")?.let {
          GoogleFontsWoff2Resolver(cacheDir = File(it).apply { mkdirs() })
        }
      ComposeFigmaSvgDataProducer.writeSvg(
        rootDir = out,
        previewId = short,
        layout = layout,
        semantics = sem,
        density = 2f,
        frameImage = render,
        fontResolver = fontResolver,
        // The production extension reads the authoritative `previewContext.device.isRound`; this
        // offline harness has only the committed frame, so it reads back Roborazzi's device crop
        // from the frame itself — corners transparent + cardinal edge-midpoints opaque is the
        // signature of a centred circular device crop (a round Wear device screen).
        roundClip = isDeviceCropped(render),
      )
      out.resolve(short).resolve("render.png").also { render.copyTo(it, overwrite = true) }
      n++
    }
    println("WEAR-REGEN wrote $n previews to $outDir")
  }

  /**
   * True when the frame is a centred circular device crop: transparent corners, opaque edge-mids.
   */
  private fun isDeviceCropped(png: File): Boolean {
    val img = javax.imageio.ImageIO.read(png) ?: return false
    if (!img.colorModel.hasAlpha()) return false
    val w = img.width
    val h = img.height
    fun a(x: Int, y: Int) = (img.getRGB(x, y) ushr 24) and 0xFF
    val corners = listOf(a(2, 2), a(w - 3, 2), a(2, h - 3), a(w - 3, h - 3))
    val edges = listOf(a(w / 2, 2), a(w / 2, h - 3), a(2, h / 2), a(w - 3, h / 2))
    return corners.all { it < 10 } && edges.all { it > 200 }
  }
}
