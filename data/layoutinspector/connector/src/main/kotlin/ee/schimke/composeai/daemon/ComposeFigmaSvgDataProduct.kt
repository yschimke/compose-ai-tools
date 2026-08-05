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
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
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
   * @param fontScale the render's font-scale multiplier, used only as the **fallback** conversion
   *   (`sp × density × fontScale`) for captures older than `compose/semantics` v12. A v12+ capture
   *   carries the px the render resolved and that always wins: Compose resolves `sp` through the
   *   platform `FontScaleConverter` on API 34+, whose curve is non-linear in the font scale, so the
   *   linear form is only correct at 1.0 and over-sizes display text on a scaled render (#3024).
   *   Defaults to 1.0 (an un-scaled capture).
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
    fontScale: Float = 1f,
    frameImage: File? = null,
    fontResolver: FigmaFontResolver? = null,
    roundClip: Boolean = false,
    deviceBackground: String? = null,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    val frame = frameImage?.takeIf { it.exists() }
    // The frame PNG's pixel size is the exact area a maskless `showBackground` preview fills, so
    // hand it to the model — a thin/short child no longer shrink-wraps the background to itself
    // (issue #2974). Read via ImageIO's header (no full decode) through the injected FileSystem.
    val frameSize = frame?.let { readImageSize(it, fileSystem) }
    val model =
      FigmaSvgModel.from(
        layout = layout,
        semantics = semantics,
        colorNames = colorNames,
        density = density,
        fontScale = fontScale,
        frameWidthPx = frameSize?.first,
        frameHeightPx = frameSize?.second,
        rasterComponents =
          if (frame != null) FigmaSvgModel.DEFAULT_RASTER_COMPONENTS else emptySet(),
        // Hybrid mode also crops Canvas-drawn chrome (progress track, slider groove) the token
        // export can't see — only when a frame PNG exists to crop those pixels from. A node whose
        // draw the connector already captured in isolation (`drawRaster`, issue #2937) is exported
        // either way: those pixels come with the payload, not out of the frame.
        captureCanvasDraws = frame != null,
        // A round Wear device screen was masked to its inscribed circle by Roborazzi's device crop;
        // mask the export to the same circle so its square full-frame background doesn't paint the
        // corners the render leaves clear.
        roundClip = roundClip,
        // A device preview opts into painting its screen background (the black watch face) behind
        // the
        // tree, clipped to the device mask; component previews pass null and stay background-free.
        deviceBackground = deviceBackground,
      )
    val fonts = fontResolver?.let { resolveFonts(model, it, fileSystem) }
    // Everything this export can put a real name to: the faces it embedded, the captured→emitted
    // family map, and the families named straight on a `<text>` when nothing was embedded.
    val named = buildSet {
      fonts?.faces?.forEach { add(it.family) }
      fonts?.familyOverrides?.values?.forEach { add(it) }
      addAll(capturedFamilies(model.root))
    }
    val unnamed = FigmaSvgRenderedFonts.unnamedIn(named)
    val svg =
      when {
        // The render drew with a face this export can't name. Emitting the default here is what
        // shipped branded stickers as Roboto, so draw boxes instead: wrong-and-obvious beats
        // wrong-and-plausible.
        unnamed.isNotEmpty() -> {
          val tofu =
            FigmaSvgFontFace(
              family = TofuFont.FAMILY,
              weight = 400,
              italic = false,
              dataBase64 =
                Base64.getEncoder().encodeToString(TofuFont.build(codePoints(model.root))),
              format = "truetype",
            )
          System.err.println(
            "ComposeFigmaSvg: the render drew with ${unnamed.joinToString(", ")} but the export " +
              "could not name ${if (unnamed.size == 1) "it" else "them"} — text is exported as " +
              "missing-glyph boxes rather than silently substituting $DEFAULT_EMBED_FAMILY"
          )
          FigmaLayeredSvg.render(
            model,
            FigmaLayeredSvg.Options(defaultFontFamily = TofuFont.FAMILY),
            (fonts?.faces.orEmpty()) + tofu,
            fonts?.familyOverrides.orEmpty(),
          )
        }
        fonts == null || fonts.faces.isEmpty() -> FigmaLayeredSvg.render(model)
        else ->
          FigmaLayeredSvg.render(
            model,
            FigmaLayeredSvg.Options(defaultFontFamily = DEFAULT_EMBED_FAMILY),
            fonts.faces,
            fonts.familyOverrides,
          )
      }
    fileSystem.write(previewDir.resolve(FILE_SVG).path.toPath()) { writeUtf8(svg) }
    writeFontWarnings(previewDir, unnamed, named, fileSystem)
    // A target that carries its own bytes was captured by re-drawing the node, so it is written
    // whether or not a frame exists; the rest are still cropped out of the frame.
    val (captured, cropped) = model.rasterTargets.partition { it.pngBase64 != null }
    writeCapturedRasters(previewDir, captured, fileSystem)
    if (frame != null && cropped.isNotEmpty()) {
      writeRasters(previewDir, frame, cropped, fileSystem)
    }
  }

  /** The face a generic/absent family maps to — Compose's default Material typeface. */
  const val DEFAULT_EMBED_FAMILY: String = "Roboto"

  /** Sidecar naming the faces the render drew with that the export could not reproduce. */
  const val FILE_FONT_WARNINGS: String = "compose-figma-fonts.warnings.json"

  /** Every family named on a `<text>` in [layer]'s subtree. */
  private fun capturedFamilies(layer: FigmaSvgLayer): Set<String> = buildSet {
    fun walk(node: FigmaSvgLayer) {
      node.text?.let { text ->
        text.fontFamily?.takeIf { it.isNotBlank() }?.let { add(it) }
        text.spans?.forEach { span -> span.fontFamily?.takeIf { it.isNotBlank() }?.let { add(it) } }
      }
      node.children.forEach(::walk)
    }
    walk(layer)
  }

  /** Every code point drawn in [layer]'s subtree, so the tofu face maps exactly what is emitted. */
  private fun codePoints(layer: FigmaSvgLayer): Set<Int> = buildSet {
    fun walk(node: FigmaSvgLayer) {
      node.text?.let { t ->
        t.content.codePoints().toArray().forEach { add(it) }
        t.lines?.forEach { line -> line.content.codePoints().toArray().forEach { add(it) } }
      }
      node.children.forEach(::walk)
    }
    walk(layer)
  }

  /**
   * Records the unreproducible faces beside the SVG so a degraded export is auditable after the
   * fact — the boxes say *that* something is wrong, this says *which face*. Written only when there
   * is something to report, so a healthy export leaves no sidecar behind.
   */
  private fun writeFontWarnings(
    previewDir: File,
    unnamed: List<String>,
    named: Set<String>,
    fileSystem: FileSystem,
  ) {
    val path = previewDir.resolve(FILE_FONT_WARNINGS).path.toPath()
    if (unnamed.isEmpty()) {
      runCatching { fileSystem.delete(path) } // clear a stale warning from an earlier render
      return
    }
    val json = Json { encodeDefaults = false }
    val payload =
      FigmaSvgFontWarnings(
        unnamedRenderedFamilies = unnamed,
        namedFamilies = named.sorted(),
        tofuFamily = TofuFont.FAMILY,
      )
    fileSystem.write(path) {
      writeUtf8(json.encodeToString(FigmaSvgFontWarnings.serializer(), payload))
    }
  }

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
      val fontPath = captured?.let { fontFilePath(it, weight, italic, fileSystem) }
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
      val bytes = resolver.woff2(name, weight, italic)
      if (bytes == null) {
        // Fail loud: embedding was asked for (a resolver is present) but this concrete face didn't
        // resolve — the `<text>` degrades to a named generic and the SVG renders with a substitute
        // typeface (and, as an `<img>`, can't recover it). Surface it so a degraded bundle doesn't
        // publish silently; a deliberately-offline render prints the same line, which is fine.
        System.err.println(
          "ComposeFigmaSvg: could not embed font \"$name\" " +
            "(weight=$weight${if (italic) ", italic" else ""}) — the figma-svg text falls back to a " +
            "generic family and will render with a substitute typeface"
        )
        return
      }
      faces["$name|$weight|$italic|woff2"] =
        FigmaSvgFontFace(name, weight, italic, Base64.getEncoder().encodeToString(bytes))
      if (captured != null) overrides[captured] = name
    }
    // First gather the code points each face draws (across every `<text>` and wrapped `<tspan>`
    // that
    // uses it) so the subset carries exactly the glyphs the SVG shows and no more.
    val codePointsByFace = LinkedHashMap<Triple<String?, Int, Boolean>, MutableSet<Int>>()
    fun collect(layer: FigmaSvgLayer) {
      layer.text?.let { t ->
        val spans = t.spans
        if (spans.isNullOrEmpty()) {
          val cps =
            codePointsByFace.getOrPut(Triple(t.fontFamily, t.fontWeight ?: 400, t.italic)) {
              LinkedHashSet()
            }
          t.content.codePoints().toArray().forEach { cps.add(it) }
          t.lines?.forEach { line -> line.content.codePoints().toArray().forEach { cps.add(it) } }
        } else {
          spans.forEach { span ->
            val start = span.start.coerceIn(0, t.content.length)
            val end = span.end.coerceIn(start, t.content.length)
            val cps =
              codePointsByFace.getOrPut(
                Triple(
                  span.fontFamily ?: t.fontFamily,
                  span.fontWeight ?: t.fontWeight ?: 400,
                  span.italic,
                )
              ) {
                LinkedHashSet()
              }
            t.content.substring(start, end).codePoints().toArray().forEach { cps.add(it) }
          }
        }
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
  private fun fontFilePath(
    family: String,
    weight: Int,
    italic: Boolean,
    fileSystem: FileSystem,
  ): okio.Path? {
    // Two kinds of face reach the capture as a handle rather than a path, and both are published
    // here by the render side — the only place their bytes are reachable:
    //  - an Android resource face, as `res/font/<resId>` (issue #2886), and
    //  - a downloadable `GoogleFont`, as its bare family name (issue #2906), registered per
    //    weight/style because one family spans several files.
    // Consulting the registry first means the export embeds the exact bytes the render drew with,
    // instead of naming a family it provides no `@font-face` for.
    val candidate = FigmaResourceFonts.pathFor(family, weight, italic) ?: family
    val lower = candidate.lowercase()
    if (!(lower.endsWith(".ttf") || lower.endsWith(".otf"))) return null
    val path = candidate.toPath()
    return path.takeIf { runCatching { fileSystem.exists(it) }.getOrDefault(false) }
  }

  /**
   * The font's real family name (`"Lobster Two"`), read from the bytes; falls back to the stem.
   *
   * The **typographic** family (`name` ID 16) wins over the legacy one (ID 1, which is what AWT's
   * `Font.family` reports) whenever a face declares both. They differ exactly for the weights
   * outside the legacy regular/bold/italic quartet: `Montserrat-Medium.ttf` declares ID 1
   * `"Montserrat Medium"` and ID 16 `"Montserrat"`. Taking ID 1 made the export emit
   * `font-family="Montserrat Medium"` with `font-weight="500"` — self-consistent in the embedded
   * SVG (the `@font-face` declares the same made-up family) but wrong everywhere the name has to
   * mean something to someone else: the `?mode=web` rewrite asked Google Fonts for a
   * `Montserrat+Medium` family that doesn't exist, so every Medium run fell back to the default
   * face. ID 16 + `font-weight` is the pair CSS, Google Fonts and Figma all model, and it lets the
   * weights of one family share it instead of splitting into pseudo-families (issue #3024).
   */
  private fun fontFileFamily(bytes: ByteArray, path: okio.Path): String =
    typographicFamily(bytes)
      ?: runCatching {
          java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, ByteArrayInputStream(bytes)).family
        }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
      ?: path.name.substringBeforeLast('.')

  /**
   * `name` ID 16 (typographic/preferred family) from an sfnt, or null when the face declares none —
   * the common case for a plain Regular/Bold, where ID 1 already *is* the typographic family. Read
   * through FontBox (already the subsetter's parser) rather than by hand.
   */
  private fun typographicFamily(bytes: ByteArray): String? =
    runCatching {
        val naming =
          org.apache.fontbox.ttf
            .TTFParser(true)
            .parse(org.apache.pdfbox.io.RandomAccessReadBuffer(bytes))
            .naming ?: return@runCatching null
        naming.nameRecords
          .firstOrNull { it.nameId == NAME_ID_TYPOGRAPHIC_FAMILY }
          ?.string
          ?.trim()
          ?.takeIf { it.isNotBlank() }
      }
      .getOrNull()

  private const val NAME_ID_TYPOGRAPHIC_FAMILY = 16

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
   * Writes the rasters the connector already captured — an isolated re-draw of a node's own draw
   * lambda ([FigmaSvgRasterTarget.pngBase64], issue #2937) — to the hrefs their `<image>`s
   * reference. No frame is consulted: these pixels are the node's own paint, not a crop of the
   * composited render, which is exactly why a container that draws can carry one. A malformed
   * payload degrades the single placeholder rather than stranding the SVG, matching [writeRasters].
   */
  private fun writeCapturedRasters(
    previewDir: File,
    targets: List<FigmaSvgRasterTarget>,
    fileSystem: FileSystem,
  ) {
    for (target in targets) {
      val bytes =
        runCatching { Base64.getDecoder().decode(target.pngBase64) }.getOrNull() ?: continue
      val dest = previewDir.resolve(target.href).also { it.parentFile?.mkdirs() }
      runCatching { fileSystem.write(dest.path.toPath()) { write(bytes) } }
    }
  }

  /**
   * The [target] box cropped out of [frame], always at the target's **own** size; a degenerate box
   * → a 1×1 transparent pixel.
   *
   * The emitted `<image>` is placed at the target's full box (`x`/`y`/`width`/`height` from the
   * layer), so the PNG has to be that box — the part of it that falls outside the frame is
   * transparent, not missing. Returning the frame-clipped sub-image instead made the browser scale
   * a short bitmap up to the declared width: Jetsnack `Search/Categories` places its minimum-size
   * dessert image at 575..1270 under a `.clip(CategoryShape)` card, the crop clamped to the 1050px
   * frame produced a 475×695 PNG, and the `<image width="695">` stretched it 1.46× so nothing
   * inside the rounded card lined up with the PNG (issue #2852). The off-frame columns are pixels
   * the render never drew — and here also pixels the clip removes — so transparent is exactly
   * right.
   */
  private fun cropOrTransparent(frame: BufferedImage, target: FigmaSvgRasterTarget): BufferedImage {
    val fullW = target.right - target.left
    val fullH = target.bottom - target.top
    if (fullW <= 0 || fullH <= 0) return transparentPixel()
    val x = target.left.coerceIn(0, frame.width)
    val y = target.top.coerceIn(0, frame.height)
    val right = target.right.coerceIn(x, frame.width)
    val bottom = target.bottom.coerceIn(y, frame.height)
    val w = right - x
    val h = bottom - y
    if (w <= 0 || h <= 0) return transparentPixel()
    // Fully inside the frame: the sub-image already is the target box, no copy needed.
    if (w == fullW && h == fullH) return frame.getSubimage(x, y, w, h)
    val out = BufferedImage(fullW, fullH, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
      g.drawImage(frame.getSubimage(x, y, w, h), x - target.left, y - target.top, null)
    } finally {
      g.dispose()
    }
    return out
  }

  private fun transparentPixel(): BufferedImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

  /**
   * The `(width, height)` in pixels of the image at [file], read via `ImageIO`'s reader header
   * without decoding the pixels; null on any read/parse failure so a missing size degrades to the
   * extent-based background sizing rather than stranding the SVG. Bytes come through the injected
   * [fileSystem] so a fake/in-memory filesystem is honoured (docs/AGENTS.md).
   */
  private fun readImageSize(file: File, fileSystem: FileSystem): Pair<Int, Int>? =
    runCatching {
        val bytes = fileSystem.read(file.path.toPath()) { readByteArray() }
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
          val readers = ImageIO.getImageReaders(stream)
          if (!readers.hasNext()) return@runCatching null
          val reader = readers.next()
          try {
            reader.input = stream
            reader.getWidth(reader.minIndex) to reader.getHeight(reader.minIndex)
          } finally {
            reader.dispose()
          }
        }
      }
      .getOrNull()
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
  private val latestOutputBaseNameByPreviewId = ConcurrentHashMap<String, String>()

  override fun onRender(previewId: String, result: RenderResult) {
    // Mode-specific renders (for example figma-svg-long) do not replace the viewport export, so a
    // result without a concrete output name must leave the latest viewport mapping intact.
    result.outputBaseName?.let { latestOutputBaseNameByPreviewId[previewId] = it }
  }

  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == ComposeFigmaSvgDataProducer.KIND)
      rootDir
        .resolve(latestOutputBaseNameByPreviewId[previewId] ?: previewId)
        .resolve(ComposeFigmaSvgDataProducer.FILE_SVG)
    else null

  /** The SVG is not JSON — an `inline = true` fetch must still return the path, not parse it. */
  override fun allowInlineUpgrade(kind: String): Boolean = false
}

/**
 * Registry for `compose/figma-svg-long` — the full-page SVG export of a *scrolling* preview (see
 * [ComposeFigmaSvgProduct.KIND_LONG]). Unlike the viewport [ComposeFigmaSvgDataProductRegistry]
 * this is `requiresRerender = true`: the long SVG is produced on demand by an expanded-height
 * re-render, so a missing artefact resolves through the daemon's `data/fetch` re-render path in the
 * `figma-svg-long` render mode — the same contract [ScrollDataProductRegistry] uses for the PNG
 * `render/scroll/long`. The file lands next to the viewport export under `<previewId>/`.
 */
class ComposeFigmaSvgLongDataProductRegistry(
  private val rootDir: File,
  private val fileSystem: FileSystem = SystemFileSystem,
) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = ComposeFigmaSvgProduct.KIND_LONG,
          schemaVersion = ComposeFigmaSvgProduct.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = true,
          displayName = "Figma layered SVG (full page)",
          facets = listOf(DataProductFacet.ARTIFACT, DataProductFacet.IMAGE),
          mediaTypes = listOf(ComposeFigmaSvgProduct.MEDIA_TYPE_SVG),
          sampling = SamplingPolicy.End,
        )
      ),
    fileSystem = fileSystem,
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == ComposeFigmaSvgProduct.KIND_LONG)
      rootDir
        .resolve(previewId)
        .resolve(ComposeFigmaSvgProduct.LONG_SUBDIR)
        .resolve(ComposeFigmaSvgProduct.FILE_SVG_LONG)
    else null

  override fun missingOutcome(previewId: String, kind: String): DataProductRegistry.Outcome =
    if (kind == ComposeFigmaSvgProduct.KIND_LONG)
      DataProductRegistry.Outcome.RequiresRerender(ComposeFigmaSvgProduct.RENDER_MODE_LONG)
    else DataProductRegistry.Outcome.Unknown

  override fun renderModeFor(kind: String): String? =
    if (kind == ComposeFigmaSvgProduct.KIND_LONG) ComposeFigmaSvgProduct.RENDER_MODE_LONG else null

  /** The SVG is not JSON — an `inline = true` fetch must still return the path, not parse it. */
  override fun allowInlineUpgrade(kind: String): Boolean = false
}
