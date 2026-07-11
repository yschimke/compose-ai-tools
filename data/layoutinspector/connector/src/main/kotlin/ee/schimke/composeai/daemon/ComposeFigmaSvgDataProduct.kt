package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.FigmaLayeredSvg
import ee.schimke.composeai.data.layoutinspector.FigmaSvgFontFace
import ee.schimke.composeai.data.layoutinspector.FigmaSvgLayer
import ee.schimke.composeai.data.layoutinspector.FigmaSvgModel
import ee.schimke.composeai.data.layoutinspector.FigmaSvgRasterTarget
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import ee.schimke.composeai.io.SystemFileSystem
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Producer for `compose/figma-svg` — the **layered, editable SVG** export. Backend-agnostic (pure
 * string from [FigmaLayeredSvg]) so it's written here regardless of backend, next to the
 * `compose/semantics-wireframe` SVG and from the same captured trees. Where the wireframe bakes the
 * *semantics* tree into a schematic, this bakes the *layout* tree — for the composable names and
 * container tokens — plus the semantics tree's text, into a design-fidelity artifact a designer
 * imports and edits in Figma.
 */
object ComposeFigmaSvgDataProducer {
  const val KIND: String = ComposeFigmaSvgProduct.KIND
  const val SCHEMA_VERSION: Int = ComposeFigmaSvgProduct.SCHEMA_VERSION
  const val FILE_SVG: String = ComposeFigmaSvgProduct.FILE_SVG

  /** Directory (under the preview dir) that holds the per-node opaque-component rasters. */
  const val RASTER_DIR: String = "figma-raster"

  /**
   * Writes `compose-figma.svg` under `<rootDir>/<previewId>/`.
   *
   * When [frameImage] is supplied (the render's already-captured frame PNG), the export runs in
   * **hybrid** mode: opaque components ([FigmaSvgModel.DEFAULT_RASTER_COMPONENTS] —
   * `Image`/`Icon`/`Canvas`/charts/…) are emitted as `<image>` placeholders and this producer crops
   * the referenced background-free raster out of the frame — cropping the composited pixels is
   * coordinate-correct for an opaque node (its bounds are fully painted) and reuses the frame every
   * backend already renders, so no isolated re-render is needed. Every emitted `<image>` therefore
   * has its PNG written before we return, so the SVG never references a raster that doesn't exist
   * (the reason the hybrid was previously opt-in). With no [frameImage] the export stays
   * vector-only.
   *
   * @param layout the layout-inspector tree (composable names + container tokens + nesting).
   * @param semantics optional semantics tree whose text nodes enrich matching layers with editable
   *   text + typography.
   * @param colorNames optional `#AARRGGBB` → theme-role-name map so named fills carry their
   *   variable.
   * @param density px-per-dp of the captured frame (dp/sp tokens are converted to px against it).
   * @param frameImage the captured frame PNG in root-pixel space; when present, enables hybrid
   *   raster export by cropping opaque-node rasters out of it.
   */
  fun writeSvg(
    rootDir: File,
    previewId: String,
    layout: LayoutInspectorPayload,
    semantics: ComposeSemanticsPayload? = null,
    colorNames: Map<String, String> = emptyMap(),
    density: Float = 1f,
    frameImage: File? = null,
    fontResolver: FigmaFontResolver? = null,
    roundClip: Boolean = false,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    val frame = frameImage?.takeIf { it.exists() }
    val model =
      FigmaSvgModel.from(
        layout = layout,
        semantics = semantics,
        colorNames = colorNames,
        density = density,
        rasterComponents =
          if (frame != null) FigmaSvgModel.DEFAULT_RASTER_COMPONENTS else emptySet(),
        // Hybrid mode also crops Canvas-drawn chrome (progress track, slider groove) the token
        // export can't see — only when a frame PNG exists to crop those pixels from.
        captureCanvasDraws = frame != null,
        // A round Wear device screen was masked to its inscribed circle by Roborazzi's device crop;
        // mask the export to the same circle so its square full-frame background doesn't paint the
        // corners the render leaves clear.
        roundClip = roundClip,
      )
    val fonts = fontResolver?.let { resolveFonts(model, it, fileSystem) }
    val svg =
      if (fonts == null || fonts.faces.isEmpty()) FigmaLayeredSvg.render(model)
      else
        FigmaLayeredSvg.render(
          model,
          FigmaLayeredSvg.Options(defaultFontFamily = DEFAULT_EMBED_FAMILY),
          fonts.faces,
          fonts.familyOverrides,
        )
    fileSystem.write(previewDir.resolve(FILE_SVG).path.toPath()) { writeUtf8(svg) }
    if (frame != null && model.rasterTargets.isNotEmpty()) {
      writeRasters(previewDir, frame, model.rasterTargets, fileSystem)
    }
  }

  /** The face a generic/absent family maps to — Compose's default Material typeface. */
  const val DEFAULT_EMBED_FAMILY: String = "Roboto"

  /**
   * Process-wide cache of file-embedded faces, keyed on font path + weight/italic + the exact
   * subset code points. A catalog render asks every Latin sticker for the same (base-ASCII) subset
   * of the same face, so the subset + base64 is computed once and reused for the rest — subsetting
   * is a few ms per face, so this keeps a whole-catalog render's font work to one pass per face.
   */
  private val fontFaceCache = java.util.concurrent.ConcurrentHashMap<String, FigmaSvgFontFace>()

  /** The `@font-face`s to embed plus the captured-family → emitted-family name map. */
  private class FontPlan(
    val faces: List<FigmaSvgFontFace>,
    val familyOverrides: Map<String, String>,
  )

  /**
   * Resolves the faces the export's `<text>` needs. Two paths per captured family:
   * - **The render loaded a real single-face font file** (the capture recorded an absolute
   *   `.ttf`/`.otf` path — a downloaded Google font, a bundled/custom face, a variable font). Embed
   *   *that file's* bytes and name the face by its real family (read from the font), so the export
   *   reproduces the exact face the render drew — no name guessing.
   * - **A generic / absent family** — mapped to a concrete embeddable face by
   *   [FigmaLayeredSvg.embedFamily] (`sans-serif` → [DEFAULT_EMBED_FAMILY], `serif` → Noto Serif,
   *   `monospace` → Roboto Mono) and fetched from [resolver] (Google Fonts) by that name. A generic
   *   with no concrete stand-in (`cursive` / `fantasy`) embeds nothing and falls back to the name.
   *
   * Also records, per captured family, the family name to emit on the `<text>` so it matches the
   * `@font-face`. Faces that can't be produced are skipped (the text falls back to the named
   * family).
   */
  private fun resolveFonts(
    model: FigmaSvgModel,
    resolver: FigmaFontResolver,
    fileSystem: FileSystem,
  ): FontPlan {
    val faces = LinkedHashMap<String, FigmaSvgFontFace>()
    val overrides = HashMap<String, String>()
    fun add(captured: String?, weight: Int, italic: Boolean, codePoints: Set<Int>) {
      val fontPath = captured?.let { fontFilePath(it, fileSystem) }
      if (fontPath != null) {
        // Subset to a stable base charset (printable ASCII) plus whatever this face actually draws,
        // so every Latin sticker asks for the *same* subset — computed once, cached process-wide,
        // and
        // reused across the whole catalog. Note the embedded face drives *browser*-based rendering
        // (the fidelity harness, the bundle's web embed): Figma itself resolves fonts by the
        // family name on the `<text>` (matched against its own font library — Roboto/Noto/… are all
        // there), not from the SVG's `@font-face`, so it renders and edits with the full font
        // regardless of the subset. The subset keeps the *self-contained* SVG small and exact.
        val cps = FontSubsetter.PRINTABLE_ASCII + codePoints
        val cacheKey = "$fontPath|$weight|$italic|${cps.toSortedSet().joinToString(",")}"
        val face =
          fontFaceCache[cacheKey]
            ?: run {
              // Read through the injected FileSystem (Okio, per docs/AGENTS.md) — a caller using a
              // fake / in-memory FileSystem must see the bytes it exposed, not the host disk.
              val bytes =
                runCatching { fileSystem.read(fontPath) { readByteArray() } }
                  .getOrNull()
                  ?.takeIf { it.isNotEmpty() } ?: return
              val family = fontFileFamily(bytes, fontPath)
              // Embed only the subset glyphs — the exact outlines the render loaded, minus the
              // layout/hinting tables static text doesn't need — so a ~300 KB font rides along at a
              // few KB. Falls back to the full file when it can't be subset (CFF `.otf`, parse
              // fail).
              val subset = FontSubsetter.subset(bytes, cps)
              val embedBytes = subset ?: bytes
              val format =
                when {
                  subset != null -> "truetype" // the subset output is always a glyf-flavoured sfnt
                  fontPath.name.endsWith(".otf", ignoreCase = true) -> "opentype"
                  else -> "truetype"
                }
              FigmaSvgFontFace(
                  family,
                  weight,
                  italic,
                  Base64.getEncoder().encodeToString(embedBytes),
                  format,
                )
                .also { fontFaceCache[cacheKey] = it }
            }
        faces["${face.family}|$weight|$italic|${face.format}"] = face
        overrides[captured] = face.family
        return
      }
      // A meaningful generic (serif/monospace) maps to a concrete embeddable family; a bare
      // cursive/fantasy has none, so skip embedding and let the text fall back to the generic.
      val name = FigmaLayeredSvg.embedFamily(captured, DEFAULT_EMBED_FAMILY) ?: return
      resolver.woff2(name, weight, italic)?.let {
        faces["$name|$weight|$italic|woff2"] =
          FigmaSvgFontFace(name, weight, italic, Base64.getEncoder().encodeToString(it))
        if (captured != null) overrides[captured] = name
      }
    }
    // First gather the code points each face draws (across every `<text>` and wrapped `<tspan>`
    // that
    // uses it) so the subset carries exactly the glyphs the SVG shows and no more.
    val codePointsByFace = LinkedHashMap<Triple<String?, Int, Boolean>, MutableSet<Int>>()
    fun collect(layer: FigmaSvgLayer) {
      layer.text?.let { t ->
        val cps =
          codePointsByFace.getOrPut(Triple(t.fontFamily, t.fontWeight ?: 400, t.italic)) {
            LinkedHashSet()
          }
        t.content.codePoints().toArray().forEach { cps.add(it) }
        t.lines?.forEach { line -> line.content.codePoints().toArray().forEach { cps.add(it) } }
      }
      layer.children.forEach(::collect)
    }
    collect(model.root)
    for ((key, cps) in codePointsByFace) add(key.first, key.second, key.third, cps)
    return FontPlan(faces.values.toList(), overrides)
  }

  /**
   * The captured family as an on-disk single-face font ([fileSystem]-visible `.ttf`/`.otf`), else
   * null. `.ttc` collections are deliberately excluded: an SVG `@font-face` would need
   * `format('collection')` plus a face selection (the collection carries several faces), which we
   * don't emit — so a bare `truetype` src would be skipped and the text would silently fall back.
   */
  private fun fontFilePath(family: String, fileSystem: FileSystem): okio.Path? {
    val lower = family.lowercase()
    if (!(lower.endsWith(".ttf") || lower.endsWith(".otf"))) return null
    val path = family.toPath()
    return path.takeIf { runCatching { fileSystem.exists(it) }.getOrDefault(false) }
  }

  /** The font's real family name (`"Lobster Two"`), read from the bytes; falls back to the stem. */
  private fun fontFileFamily(bytes: ByteArray, path: okio.Path): String =
    runCatching {
        java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, ByteArrayInputStream(bytes)).family
      }
      .getOrNull()
      ?.takeIf { it.isNotBlank() } ?: path.name.substringBeforeLast('.')

  /**
   * Crops each opaque-node [FigmaSvgRasterTarget] out of the captured [frameImage] and writes it to
   * the [FigmaSvgRasterTarget.href] the SVG's `<image>` references (a `figma-raster/<node>.png`
   * under the preview dir). The node bounds are absolute-to-root px — the same space the frame PNG
   * is drawn in — so a straight sub-image crop lands the component's pixels. Bounds are intersected
   * with the frame so a node measured partly off-canvas still yields a valid PNG; a degenerate
   * intersection falls back to a 1×1 transparent pixel so the `<image>` reference always resolves.
   * Decode/encode failures are swallowed per target — a missing raster degrades one placeholder, it
   * never strands the SVG.
   */
  private fun writeRasters(
    previewDir: File,
    frameImage: File,
    targets: List<FigmaSvgRasterTarget>,
    fileSystem: FileSystem,
  ) {
    val frame =
      try {
        val bytes = fileSystem.read(frameImage.path.toPath()) { readByteArray() }
        ImageIO.read(ByteArrayInputStream(bytes))
      } catch (t: Throwable) {
        null
      } ?: return
    for (target in targets) {
      val dest = previewDir.resolve(target.href).also { it.parentFile?.mkdirs() }
      val crop =
        try {
          cropOrTransparent(frame, target)
        } catch (t: Throwable) {
          transparentPixel()
        }
      try {
        val out = ByteArrayOutputStream()
        ImageIO.write(crop, "png", out)
        fileSystem.write(dest.path.toPath()) { write(out.toByteArray()) }
      } catch (t: Throwable) {
        // Leave the placeholder unwritten rather than fail the whole export; better a single
        // dangling ref than no SVG. In practice write only fails on IO the caller controls.
      }
    }
  }

  /**
   * The [target] bounds clipped to [frame], cropped; a degenerate box → a 1×1 transparent pixel.
   */
  private fun cropOrTransparent(frame: BufferedImage, target: FigmaSvgRasterTarget): BufferedImage {
    val x = target.left.coerceIn(0, frame.width)
    val y = target.top.coerceIn(0, frame.height)
    val right = target.right.coerceIn(x, frame.width)
    val bottom = target.bottom.coerceIn(y, frame.height)
    val w = right - x
    val h = bottom - y
    return if (w > 0 && h > 0) frame.getSubimage(x, y, w, h) else transparentPixel()
  }

  private fun transparentPixel(): BufferedImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
}

/**
 * Registry for `compose/figma-svg`. The path-transported deliverable is the SVG; in hybrid mode the
 * producer also writes sibling `figma-raster/<node>.png` crops the SVG's `<image>` layers
 * reference, but those travel with the SVG (relative hrefs) rather than as separately-fetchable
 * products. Mirrors [ComposeSemanticsWireframeDataProductRegistry] otherwise.
 */
class ComposeFigmaSvgDataProductRegistry(
  private val rootDir: File,
  private val fileSystem: FileSystem = SystemFileSystem,
) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = ComposeFigmaSvgDataProducer.KIND,
          schemaVersion = ComposeFigmaSvgDataProducer.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = false,
          displayName = "Figma layered SVG",
          facets = listOf(DataProductFacet.ARTIFACT, DataProductFacet.IMAGE),
          mediaTypes = listOf(ComposeFigmaSvgProduct.MEDIA_TYPE_SVG),
          sampling = SamplingPolicy.End,
        )
      ),
    fileSystem = fileSystem,
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == ComposeFigmaSvgDataProducer.KIND)
      rootDir.resolve(previewId).resolve(ComposeFigmaSvgDataProducer.FILE_SVG)
    else null

  /** The SVG is not JSON — an `inline = true` fetch must still return the path, not parse it. */
  override fun allowInlineUpgrade(kind: String): Boolean = false
}
