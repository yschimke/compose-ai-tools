package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BUNDLE_FIGMA_FONT_WARNINGS_SUFFIX
import ee.schimke.composeai.bundle.BUNDLE_FIGMA_RASTER_DIR_SUFFIX
import ee.schimke.composeai.bundle.BUNDLE_FIGMA_SVG_SUFFIX
import ee.schimke.composeai.bundle.BUNDLE_FONTS_SUFFIX
import ee.schimke.composeai.bundle.BUNDLE_LAYOUT_SUFFIX
import ee.schimke.composeai.bundle.BUNDLE_PREVIEWS_DIR
import ee.schimke.composeai.bundle.BUNDLE_SEMANTICS_SUFFIX
import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.ZIP_DOS_EPOCH_MS
import ee.schimke.composeai.imagecrop.clampTo
import ee.schimke.composeai.imagecrop.contentBoxFillsRender
import ee.schimke.composeai.imagecrop.pngAlphaBounds
import ee.schimke.composeai.imagecrop.svgContentBox
import ee.schimke.composeai.imagecrop.union
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `compose-preview bundle split <sheet.png> -o <dir>`: turn a sheet bundle (many previews) into one
 * self-contained bundle per preview (`<dir>/<id>.png`). Pure repackaging — each output copies that
 * preview's baked PNG and captured sidecars from the sheet, with no daemon or re-render (unlike
 * `pack --per-preview`).
 *
 * [SplitMode.FULL] copies the shared re-render classpath into every bundle, so output grows N× with
 * preview count. [SplitMode.FULL_SHARED_CLASSPATH] keeps the live lane but publishes
 * `classes/app.jar` once to a content-addressed pool, referenced as a hash-verified
 * `externalClasspath`. [SplitMode.VIEW_ONLY] drops the classpath: a baked sticker of tens of KB,
 * right for delivery branches that never re-render.
 *
 * Every split measures and reports the carriage ([SplitCarriageSummary]); `--carriage-report
 * <file.json>` writes the numbers for publishers that gate on them.
 */
internal enum class SplitMode {
  FULL,
  FULL_SHARED_CLASSPATH,
  VIEW_ONLY,
}

/** One whole bundle entry published once into the split output's content-addressed pool. */
internal data class SharedClasspathEntry(val path: String, val sha256: String, val size: Long)

/** One entry of the shared re-render carriage, sized as it sits in the source sheet. */
internal data class SplitCarriageEntry(val path: String, val bytes: Long)

/**
 * The shared re-render payload a live split copies into every bundle (`classes/app.jar` unless
 * pooled, `libs/`, `report.json`, `android/`). [bytesPerBundle] is the deflated, as-written size.
 */
internal data class SplitCarriage(
  val bytesPerBundle: Long,
  val entries: List<SplitCarriageEntry>,
) {
  companion object {
    val NONE = SplitCarriage(0L, emptyList())
  }
}

/** A zip with no entries: end-of-central-directory record only. */
private const val EMPTY_ZIP_BYTES = 22L

/** One decimal place, locale-independent (never `97,8`). */
private fun formatPercent(value: Double): String =
  String.format(java.util.Locale.ROOT, "%.1f", value)

/**
 * Report the carriage once it is at least this share of everything written. Each catalog edit
 * rewrites `classes/app.jar`, so a FULL split's repeated copies grow a delivery branch without
 * bound.
 */
internal const val SPLIT_CARRIAGE_REPORT_PERCENT = 50.0

/** What the shared carriage cost against what the split wrote: one payload or N copies. */
internal class SplitCarriageSummary(
  val mode: SplitMode,
  val carriage: SplitCarriage,
  val bundles: Int,
  val totalBytes: Long,
) {
  /** Bytes of the output that are the same payload repeated. */
  val repeatedBytes: Long = carriage.bytesPerBundle * bundles

  /** [repeatedBytes] as a percentage of [totalBytes], 0 when nothing was written. */
  val sharePercent: Double = if (totalBytes <= 0L) 0.0 else repeatedBytes * 100.0 / totalBytes

  /** True once the carriage dominates the output enough to be worth saying out loud. */
  val dominates: Boolean = bundles > 1 && sharePercent >= SPLIT_CARRIAGE_REPORT_PERCENT

  /**
   * The warning line, or null when the carriage isn't the story; names a remedy that fits this mode
   * (not `--view-only` for a live tier).
   */
  fun warning(): String? {
    if (!dominates) return null
    val largest = carriage.entries.firstOrNull()
    val remedy =
      when (mode) {
        SplitMode.FULL ->
          "publish it once with --shared-classpath-out <pool-dir> — each bundle keeps a " +
            "hash-verified externalClasspath, so the live re-render lane survives — or " +
            "--view-only if this tier never re-renders"
        SplitMode.FULL_SHARED_CLASSPATH ->
          "classes/app.jar is already pooled; what repeats is libs/ + android/, which only " +
            "--view-only drops (at the cost of the live re-render lane)"
        // VIEW_ONLY carries nothing, so this is unreachable; kept for exhaustiveness.
        SplitMode.VIEW_ONLY -> "--view-only already carries nothing"
      }
    return "bundle split: shared carriage is ${carriage.bytesPerBundle} bytes in each of " +
      "$bundles bundle(s) — $repeatedBytes of $totalBytes total bytes " +
      "(${formatPercent(sharePercent)}%) is the same payload repeated; $remedy." +
      (largest?.let { " Largest carried entry: ${it.path} (${it.bytes} bytes)." } ?: "")
  }

  /** Machine-readable form, for a publisher that gates on the measurement. */
  fun toJson(): String =
    SPLIT_JSON.encodeToString(
      JsonObject.serializer(),
      buildJsonObject {
        put(
          "mode",
          JsonPrimitive(
            when (mode) {
              SplitMode.VIEW_ONLY -> "view-only"
              SplitMode.FULL -> "full"
              SplitMode.FULL_SHARED_CLASSPATH -> "full-shared-classpath"
            }
          ),
        )
        put("bundles", JsonPrimitive(bundles))
        put("carriageBytesPerBundle", JsonPrimitive(carriage.bytesPerBundle))
        put("repeatedBytes", JsonPrimitive(repeatedBytes))
        put("totalBytes", JsonPrimitive(totalBytes))
        put("sharePercent", JsonPrimitive(kotlin.math.round(sharePercent * 10.0) / 10.0))
        put("reportThresholdPercent", JsonPrimitive(SPLIT_CARRIAGE_REPORT_PERCENT))
        put("dominates", JsonPrimitive(dominates))
        put(
          "carriageEntries",
          buildJsonArray {
            for (entry in carriage.entries) {
              add(
                buildJsonObject {
                  put("path", JsonPrimitive(entry.path))
                  put("bytes", JsonPrimitive(entry.bytes))
                }
              )
            }
          },
        )
      },
    )
}

/** One split output: preview id, cover PNG (the polyglot's leading bytes), and the appended zip. */
internal class SplitPreview(val id: String, val coverPng: ByteArray, val zipBytes: ByteArray) {
  /** The complete PNG+ZIP polyglot: cover PNG followed by the appended zip. */
  fun polyglot(): ByteArray = coverPng + zipBytes
}

// Sidecar suffixes copied into a split bundle; explicit so unknown future sidecars aren't
// misattributed. `.figma-raster/<node>.png` crops are matched by prefix separately.
private val SPLIT_SIDECAR_SUFFIXES =
  listOf(
    ".png",
    BUNDLE_SEMANTICS_SUFFIX,
    BUNDLE_LAYOUT_SUFFIX,
    BUNDLE_FONTS_SUFFIX,
    BUNDLE_FIGMA_SVG_SUFFIX,
    // Present only for previews whose text exported as missing-glyph boxes; must travel with the
    // sticker.
    BUNDLE_FIGMA_FONT_WARNINGS_SUFFIX,
    ".overrides.json",
    ".catalog.json",
  )

private val SPLIT_JSON = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

/**
 * Split a sheet's [sheetZip] (the appended zip, not the polyglot) into one [SplitPreview] per
 * manifest preview, skipping previews with no baked PNG. Pure and deterministic.
 */
internal fun splitBundleZip(
  sheetZip: ByteArray,
  mode: SplitMode,
  crop: Boolean = true,
): List<SplitPreview> {
  val result = ArrayList<SplitPreview>()
  forEachSplitPreview(sheetZip, mode, crop) { result += it }
  return result
}

/**
 * Streaming [splitBundleZip]: hands each [SplitPreview] to [onPreview] without retaining it, and
 * returns the count. Each FULL bundle carries the shared payload, so accumulating them OOMs on
 * large catalogs; peak memory is now the source plus one output. The list form is kept for tests.
 */
internal fun forEachSplitPreview(
  sheetZip: ByteArray,
  mode: SplitMode,
  crop: Boolean = true,
  onSharedClasspath: (SharedClasspathEntry, ByteArray) -> Unit = { _, _ -> },
  onCarriage: (SplitCarriage) -> Unit = {},
  onPreview: (SplitPreview) -> Unit,
): Int {
  val entries = readZipEntries(sheetZip)
  val bundleJsonBytes =
    entries["bundle.json"]
      ?: throw IllegalArgumentException(
        "not a bundle: missing bundle.json (is this a packed sheet?)"
      )
  val previewsJsonBytes =
    entries["previews.json"]
      ?: throw IllegalArgumentException("not a bundle: missing previews.json")
  val manifest = SPLIT_JSON.parseToJsonElement(bundleJsonBytes.decodeToString()).jsonObject
  val previews = SPLIT_JSON.parseToJsonElement(previewsJsonBytes.decodeToString()).jsonObject
  val ids =
    manifest["previewIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }?.distinct()
      ?: emptyList()
  val previewsArray = previews["previews"]?.jsonArray ?: JsonArray(emptyList())

  // Shared re-render carriage for FULL bundles: the classpath plus the Android app-resource payload
  // under `android/` (`resources.ap_`, manifest, `r-classes.jar`), without which a detached
  // re-render shows `⟦res 0x7f…⟧` placeholders for app resources.
  val sharedClasspath =
    if (mode == SplitMode.FULL_SHARED_CLASSPATH) {
      entries["classes/app.jar"]?.let { bytes ->
        SharedClasspathEntry(
            path = "classes/app.jar",
            sha256 =
              MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
              },
            size = bytes.size.toLong(),
          )
          .also { onSharedClasspath(it, bytes) }
      }
    } else {
      null
    }
  val shared =
    entries
      .filterKeys {
        it == "classes/app.jar" ||
          it.startsWith("libs/") ||
          it == "report.json" ||
          it.startsWith("android/")
      }
      .let { carriage ->
        if (mode == SplitMode.FULL_SHARED_CLASSPATH) carriage - "classes/app.jar" else carriage
      }
  val fullMode = mode != SplitMode.VIEW_ONLY

  // Measure the per-bundle carriage, deflated as it will land, before writing anything; zero for
  // VIEW_ONLY.
  onCarriage(
    if (fullMode) {
      SplitCarriage(
        bytesPerBundle = writeDeterministicZip(shared).size.toLong() - EMPTY_ZIP_BYTES,
        entries =
          shared
            .map { (path, bytes) -> SplitCarriageEntry(path, bytes.size.toLong()) }
            .sortedByDescending { it.bytes },
      )
    } else {
      SplitCarriage.NONE
    }
  )

  var emitted = 0
  for (id in ids) {
    val rawCover =
      entries["$BUNDLE_PREVIEWS_DIR/$id.png"] ?: continue // no image → nothing to address
    // Crop the cover to its component content box (from the figma-svg), so e.g. a Wear sticker
    // isn't a speck on a 454² canvas. No-op when cropping is off, there's no figma-svg, or the box
    // fills the render. Coordinate sidecars are re-based below to stay consistent.
    val cropped: CroppedCover? =
      if (crop) {
        entries["$BUNDLE_PREVIEWS_DIR/$id$BUNDLE_FIGMA_SVG_SUFFIX"]?.decodeToString()?.let {
          cropPngToContentBox(rawCover, it)
        }
      } else {
        null
      }
    val cover = cropped?.png ?: rawCover

    val out = LinkedHashMap<String, ByteArray>()
    if (fullMode) out.putAll(shared)
    for (suffix in SPLIT_SIDECAR_SUFFIXES) {
      val key = "$BUNDLE_PREVIEWS_DIR/$id$suffix"
      entries[key]?.let { out[key] = it }
    }
    // Replace the uncropped `.png` the sidecar copy added, so it matches the polyglot cover.
    out["$BUNDLE_PREVIEWS_DIR/$id.png"] = cover
    // Re-base `semantics` / `layout` bounds into the cropped image's space.
    if (cropped != null && (cropped.cropX != 0 || cropped.cropY != 0)) {
      for (suffix in listOf(BUNDLE_SEMANTICS_SUFFIX, BUNDLE_LAYOUT_SUFFIX)) {
        val key = "$BUNDLE_PREVIEWS_DIR/$id$suffix"
        out[key]?.let { out[key] = rebaseSidecarCoords(it, cropped.cropX, cropped.cropY) }
      }
    }
    val rasterPrefix = "$BUNDLE_PREVIEWS_DIR/$id$BUNDLE_FIGMA_RASTER_DIR_SUFFIX/"
    val irPrefix = "ir/$id."
    for ((name, bytes) in entries) {
      if (name.startsWith(rasterPrefix)) out[name] = bytes
      if (fullMode && name.startsWith(irPrefix)) out[name] = bytes
    }

    out["bundle.json"] =
      SPLIT_JSON.encodeToString(
          JsonObject.serializer(),
          perPreviewManifest(manifest, id, fullMode, sharedClasspath),
        )
        .encodeToByteArray()
    out["previews.json"] =
      SPLIT_JSON.encodeToString(
          JsonObject.serializer(),
          perPreviewPreviews(previews, previewsArray, id),
        )
        .encodeToByteArray()

    onPreview(SplitPreview(id, cover, writeDeterministicZip(out)))
    emitted++
  }
  return emitted
}

/** Rewrite the sheet manifest for a single preview: cover + previewIds = [id], IR filtered, etc. */
private fun perPreviewManifest(
  manifest: JsonObject,
  id: String,
  fullMode: Boolean,
  sharedClasspath: SharedClasspathEntry? = null,
): JsonObject = buildJsonObject {
  for ((key, value) in manifest) {
    when (key) {
      "previewIds" -> put(key, buildJsonArray { add(JsonPrimitive(id)) })
      // Keep the raw-id list parallel to previewIds, falling back to the bundle id for older
      // bundles.
      "rawPreviewIds" -> {
        val bundleIds =
          manifest["previewIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val raws = value.jsonArray.map { it.jsonPrimitive.content }
        val raw = bundleIds.indexOf(id).takeIf { it in raws.indices }?.let { raws[it] } ?: id
        put(key, buildJsonArray { add(JsonPrimitive(raw)) })
      }
      "coverPreviewId" -> put(key, JsonPrimitive(id))
      // View-only carries no re-render classpath, so record that honestly.
      "classpath" -> put(key, if (fullMode) value else JsonArray(emptyList()))
      // (v9) Repositories only serve classpath re-resolution, so only FULL splits keep them.
      "repositories" -> if (fullMode) put(key, value)
      "resolution" -> put(key, if (fullMode) value else JsonPrimitive("view-only"))
      // The `android/` carriage only rides FULL bundles; drop the pointer for VIEW_ONLY.
      "androidResources" -> if (fullMode) put(key, value)
      // Keep only this preview's intermediate representation.
      "intermediateRepresentations" ->
        put(
          key,
          buildJsonArray {
            value.jsonArray
              .filter { it.jsonObject["previewId"]?.jsonPrimitive?.content == id }
              .forEach { add(it) }
          },
        )
      // Sheet data-extension reports describe the sheet's cover, not this preview; drop them.
      "dataExtensions" -> {}
      else -> put(key, value)
    }
  }
  // Ensure view-only fields exist even if the sheet manifest omitted them.
  if (!fullMode) {
    if ("classpath" !in manifest) put("classpath", JsonArray(emptyList()))
    if ("resolution" !in manifest) put("resolution", JsonPrimitive("view-only"))
  }
  if (sharedClasspath != null) {
    put(
      "externalClasspath",
      buildJsonArray {
        add(
          buildJsonObject {
            put("path", JsonPrimitive(sharedClasspath.path))
            put("sha256", JsonPrimitive(sharedClasspath.sha256))
            put("size", JsonPrimitive(sharedClasspath.size))
          }
        )
      },
    )
  }
}

/** Filter `previews.json` down to the single preview [id]. */
private fun perPreviewPreviews(
  previews: JsonObject,
  previewsArray: JsonArray,
  id: String,
): JsonObject = buildJsonObject {
  for ((key, value) in previews) {
    if (key == "previews") {
      put(
        key,
        buildJsonArray {
          previewsArray
            .filter { it.jsonObject["id"]?.jsonPrimitive?.content == id }
            .forEach { add(it) }
        },
      )
    } else {
      put(key, value)
    }
  }
}

/**
 * Crop [pngBytes] to the content box from its figma-svg [svgText], or null (keep the full image)
 * when there's no box, the PNG can't be decoded, or the box fills the render
 * ([contentBoxFillsRender]). The box is clamped to the image; [CroppedCover.cropX]/[cropY] are the
 * clamped origin for re-basing sidecars. Deterministic, so splits stay byte-reproducible.
 */
internal fun cropPngToContentBox(pngBytes: ByteArray, svgText: String): CroppedCover? {
  val svgBox = svgContentBox(svgText) ?: return null
  val src = runCatching { ImageIO.read(ByteArrayInputStream(pngBytes)) }.getOrNull() ?: return null
  val rw = src.width
  val rh = src.height
  if (rw <= 0 || rh <= 0) return null
  // Union in the PNG's opaque extent so focus rings or outlines outside the layout box aren't
  // clipped.
  val box = (pngAlphaBounds(pngBytes)?.let { svgBox.union(it) } ?: svgBox).clampTo(rw, rh)
  if (contentBoxFillsRender(box, rw, rh)) return null
  val x = box.x
  val y = box.y
  val w = box.w
  val h = box.h
  val cropped = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
  val g = cropped.createGraphics()
  try {
    // Draw the render shifted up-left by the box origin, so only the component lands in the frame.
    g.drawImage(src, -x, -y, null)
  } finally {
    g.dispose()
  }
  val baos = ByteArrayOutputStream()
  return if (ImageIO.write(cropped, "png", baos)) CroppedCover(baos.toByteArray(), x, y) else null
}

/** A cropped sticker cover: re-encoded PNG plus the crop origin in full-render pixels. */
internal class CroppedCover(val png: ByteArray, val cropX: Int, val cropY: Int)

/**
 * Re-base a `.semantics.json` / `.layout.json` sidecar into the cropped image's space by
 * subtracting ([dx],[dy]) from absolute coordinates. A whole-tree JSON transform, so unrelated
 * fields survive verbatim. Absolute fields:
 * - `boundsInRoot` — semantics `"left,top,right,bottom"` string;
 * - `bounds` — layout `{left,top,right,bottom}` object;
 * - `centerXPx` / `centerYPx` — Wear curved-text arc centre.
 *
 * Everything else is position-independent. Malformed values pass through; a zero origin is a no-op.
 */
internal fun rebaseSidecarCoords(jsonBytes: ByteArray, dx: Int, dy: Int): ByteArray {
  if (dx == 0 && dy == 0) return jsonBytes
  val root =
    runCatching { SPLIT_JSON.parseToJsonElement(jsonBytes.decodeToString()) }.getOrNull()
      ?: return jsonBytes
  val shifted = rebaseCoordsTree(root, dx, dy)
  return SPLIT_JSON.encodeToString(JsonElement.serializer(), shifted).encodeToByteArray()
}

private fun rebaseCoordsTree(el: JsonElement, dx: Int, dy: Int): JsonElement =
  when (el) {
    is JsonObject ->
      buildJsonObject {
        for ((k, v) in el) {
          when {
            k == "boundsInRoot" && v is JsonPrimitive && v.isString ->
              put(k, JsonPrimitive(shiftBoundsCsv(v.content, dx, dy)))
            k == "bounds" && v is JsonObject && "left" in v -> put(k, shiftBoundsObject(v, dx, dy))
            k == "centerXPx" && v is JsonPrimitive -> put(k, shiftNumber(v, dx))
            k == "centerYPx" && v is JsonPrimitive -> put(k, shiftNumber(v, dy))
            else -> put(k, rebaseCoordsTree(v, dx, dy))
          }
        }
      }
    is JsonArray -> JsonArray(el.map { rebaseCoordsTree(it, dx, dy) })
    else -> el
  }

/** Shift a `"left,top,right,bottom"` string; anything other than 4 ints passes through. */
private fun shiftBoundsCsv(csv: String, dx: Int, dy: Int): String {
  val n = csv.split(",").map { it.trim().toIntOrNull() }
  if (n.size != 4 || n.any { it == null }) return csv
  return "${n[0]!! - dx},${n[1]!! - dy},${n[2]!! - dx},${n[3]!! - dy}"
}

/** Shift a `{left,top,right,bottom, …}` object by (`dx`,`dy`), preserving any other keys. */
private fun shiftBoundsObject(o: JsonObject, dx: Int, dy: Int): JsonObject = buildJsonObject {
  for ((k, v) in o) {
    val d =
      when (k) {
        "left",
        "right" -> dx
        "top",
        "bottom" -> dy
        else -> 0
      }
    if (d != 0 && v is JsonPrimitive) put(k, shiftNumber(v, d)) else put(k, v)
  }
}

/** Subtract [d] from a numeric primitive, keeping int/decimal shape; non-numbers pass through. */
private fun shiftNumber(v: JsonPrimitive, d: Int): JsonPrimitive {
  v.content.toIntOrNull()?.let {
    return JsonPrimitive(it - d)
  }
  v.content.toDoubleOrNull()?.let {
    return JsonPrimitive(it - d)
  }
  return v
}

private fun readZipEntries(zipBytes: ByteArray): LinkedHashMap<String, ByteArray> {
  val entries = LinkedHashMap<String, ByteArray>()
  ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
    while (true) {
      val entry = zin.nextEntry ?: break
      if (!entry.isDirectory) entries[entry.name] = zin.readBytes()
      zin.closeEntry()
    }
  }
  return entries
}

/** Deterministic zip: entries sorted by name, all timestamps pinned to the DOS epoch. */
private fun writeDeterministicZip(entries: Map<String, ByteArray>): ByteArray {
  val baos = ByteArrayOutputStream()
  ZipOutputStream(baos).use { zout ->
    for (name in entries.keys.sorted()) {
      zout.putNextEntry(ZipEntry(name).apply { time = ZIP_DOS_EPOCH_MS })
      zout.write(entries.getValue(name))
      zout.closeEntry()
    }
  }
  return baos.toByteArray()
}

internal class SplitSubcommand(private val args: List<String>) {
  fun run() {
    // The positional that isn't a flag value (`-o <dir>` etc. take non-dash values).
    val path = CliFlags.firstPositional(args)
    val outDirArg = args.flagValue("--output") ?: args.flagValue("-o")
    val sharedClasspathOut = args.flagValue("--shared-classpath-out")
    val carriageReportOut = args.flagValue("--carriage-report")
    if ("--view-only" in args && sharedClasspathOut != null) {
      System.err.println("bundle split: --view-only cannot be combined with --shared-classpath-out")
      exitProcess(64)
    }
    val mode =
      when {
        "--view-only" in args -> SplitMode.VIEW_ONLY
        sharedClasspathOut != null -> SplitMode.FULL_SHARED_CLASSPATH
        else -> SplitMode.FULL
      }
    // Crop to the component box by default; `--no-crop` keeps the full canvas.
    val crop = "--no-crop" !in args
    if (path == null) {
      System.err.println(
        "Usage: compose-preview bundle split <bundle.png | URL> -o <dir> " +
          "[--view-only | --shared-classpath-out <pool-dir>] [--no-crop] " +
          "[--carriage-report <file.json>]"
      )
      exitProcess(64)
    }
    val file =
      try {
        BundleSource.resolveToFile(path)
      } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
      }
    val outDir =
      File(
          outDirArg ?: (file.absoluteFile.parent.toString() + "/${file.nameWithoutExtension}-split")
        )
        .absoluteFile
    outDir.mkdirs()

    val zipBytes = BundleReader.extractZipBytes(file)

    // Distinct ids can sanitize to the same stem; append -2/-3/… instead of overwriting.
    val usedStems = HashSet<String>()
    val written = ArrayList<File>()
    val sharedPool = sharedClasspathOut?.let(::File)?.absoluteFile
    var carriage = SplitCarriage.NONE
    // Write each bundle as produced and drop it; holding them all OOMs on large catalogs.
    val emitted =
      try {
        forEachSplitPreview(
          zipBytes,
          mode,
          crop = crop,
          onSharedClasspath = { entry, bytes ->
            val pool = checkNotNull(sharedPool)
            pool.mkdirs()
            val target = File(pool, entry.sha256)
            if (target.isFile) {
              check(target.length() == entry.size && target.readBytes().contentEquals(bytes)) {
                "content-addressed pool collision at ${target.path}"
              }
            } else {
              target.writeBytes(bytes)
            }
          },
          onCarriage = { carriage = it },
        ) { preview ->
          val base = sanitizeSplitFileName(preview.id)
          var stem = base
          var n = 1
          while (!usedStems.add(stem)) {
            n++
            stem = "$base-$n"
          }
          val outFile = File(outDir, "$stem.png")
          outFile.writeBytes(preview.polyglot())
          written += outFile
        }
      } catch (e: IllegalArgumentException) {
        System.err.println("bundle split: ${e.message}")
        exitProcess(1)
      }
    if (emitted == 0) {
      System.err.println(
        "bundle split: no previews with a baked image found in ${file.name} — nothing to split."
      )
      exitProcess(1)
    }

    val sizes = written.map { it.length() }
    val total = sizes.sum()
    println(
      "bundle split — wrote ${written.size} bundle(s) to ${outDir.path} " +
        "(${when (mode) {
          SplitMode.VIEW_ONLY -> "view-only"
          SplitMode.FULL -> "full"
          SplitMode.FULL_SHARED_CLASSPATH -> "full-shared-classpath"
        }})\n" +
        "  total:   $total bytes\n" +
        "  size:    min ${sizes.minOrNull() ?: 0} / avg ${if (written.isNotEmpty()) total / written.size else 0} / max ${sizes.maxOrNull() ?: 0} bytes"
    )
    val summary = SplitCarriageSummary(mode, carriage, bundles = written.size, totalBytes = total)
    if (carriageReportOut != null) {
      val reportFile = File(carriageReportOut).absoluteFile
      reportFile.parentFile?.mkdirs()
      reportFile.writeText(summary.toJson())
    }
    // When the carriage dominates, it explains the size; otherwise an outsized bundle really is a
    // large render.
    val carriageWarning = summary.warning()
    if (carriageWarning != null) {
      System.err.println(carriageWarning)
    } else {
      val over = written.filter { it.length() > 100 * 1024 }
      if (over.isNotEmpty()) {
        System.err.println(
          "bundle split: ${over.size} bundle(s) exceed 100 KB " +
            "(largest ${over.maxByOrNull { it.length() }!!.length()} bytes) — usually a full-screen " +
            "render; the shared carriage is only ${formatPercent(summary.sharePercent)}% of the output."
        )
      }
    }
  }

  /** Filesystem-safe filename stem for a preview id (keep `A-Za-z0-9._-`, else `_`). */
  private fun sanitizeSplitFileName(id: String): String = buildString {
    for (c in id) append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
  }
}
