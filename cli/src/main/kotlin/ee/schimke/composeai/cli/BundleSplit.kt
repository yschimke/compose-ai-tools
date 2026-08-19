package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.clampTo
import ee.schimke.composeai.cli.serve.contentBoxFillsRender
import ee.schimke.composeai.cli.serve.pngAlphaBounds
import ee.schimke.composeai.cli.serve.svgContentBox
import ee.schimke.composeai.cli.serve.union
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
 * `compose-preview bundle split <sheet.png> -o <dir>` — turn a **sheet** bundle (one polyglot
 * carrying many previews) into one **valid, self-contained bundle per preview** (`<dir>/<id>.png`),
 * the addressable-preview unit.
 *
 * Unlike `pack --per-preview` (which re-renders + re-packs each preview through Gradle), this is
 * pure repackaging of an already-packed sheet: each output copies that preview's baked PNG + every
 * captured sidecar (`semantics` / `layout` / `figma.svg` / `overrides` / `catalog` / `fonts`) from
 * the sheet, so a sheet packed `--with-semantics` yields per-preview bundles that carry their
 * semantics with **no daemon and no re-render**.
 *
 * [SplitMode.FULL] copies the shared re-render classpath (`classes/app.jar` + `libs/`) into every
 * bundle so each can live-re-render — correct, and heavier by a factor of the **preview count**:
 * the carriage is a fixed per-bundle cost, so the output grows N× with it and every catalog edit
 * rewrites all N copies. [SplitMode.FULL_SHARED_CLASSPATH] keeps the live lane and publishes
 * `classes/app.jar` once into a content-addressed pool, with each manifest carrying a hash-verified
 * `externalClasspath`. [SplitMode.VIEW_ONLY] drops the classpath entirely: the result is a baked,
 * self-describing sticker (image + sidecars + manifest) that any PNG viewer / detached reader
 * opens, typically tens of KB. View-only is the right unit for a delivery branch of addressable
 * stickers that never re-render.
 *
 * Whichever mode is chosen, the split measures the carriage and says so ([SplitCarriageSummary]) —
 * `--carriage-report <file.json>` writes the same numbers for a publisher that gates on them.
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
 * The shared re-render payload a live split copies into **every** per-preview bundle:
 * `classes/app.jar` (unless pooled), `libs/`, `report.json`, and the Android `android/` payload.
 *
 * [bytesPerBundle] is what it costs *as written* — the same entries deflated into one zip — not the
 * sum of their raw sizes, because the written number is the one that lands on a delivery branch.
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

/**
 * One decimal place, locale-independent — a runner in a comma-decimal locale must not print 97,8.
 */
private fun formatPercent(value: Double): String =
  String.format(java.util.Locale.ROOT, "%.1f", value)

/**
 * Report the carriage once it is at least this share of everything the split wrote.
 *
 * Half is already the point where publishing the payload once is a bigger lever than every other
 * byte in the output combined, and the failure mode this guards is unbounded rather than
 * proportional: any catalog edit rewrites `classes/app.jar`, so a FULL split re-writes all N copies
 * and the delivery branch grows by the *repeated* size on every publish. m3-catalog sat at 97.8%
 * for 76 publishes and reached a 2.52 GiB clone.
 */
internal const val SPLIT_CARRIAGE_REPORT_PERCENT = 50.0

/**
 * What the shared carriage cost, measured against what the split actually wrote — the number that
 * decides whether a delivery branch is publishing one payload or N copies of it.
 */
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
   * The loud line, or null when the carriage is not the story. It names the remedy that fits **this
   * mode** — the previous size warning always suggested `--view-only`, which is no remedy at all
   * for a tier whose whole point is a live re-render, so every run of a live catalog printed advice
   * it could not take and the signal was learned as noise.
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
        // VIEW_ONLY carries nothing, so it can never dominate; kept exhaustive rather than
        // reachable.
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

/**
 * One split output: the preview id, its cover PNG (polyglot leading bytes), and the appended zip.
 */
internal class SplitPreview(val id: String, val coverPng: ByteArray, val zipBytes: ByteArray) {
  /** The complete PNG+ZIP polyglot: cover PNG followed by the appended zip. */
  fun polyglot(): ByteArray = coverPng + zipBytes
}

// The per-preview sidecar suffixes copied into a split bundle (kept explicit so an unrecognised
// future sidecar is simply not split rather than mis-attributed). `.figma-raster/<node>.png` crops
// live under a directory and are matched by prefix separately.
private val SPLIT_SIDECAR_SUFFIXES =
  listOf(
    ".png",
    BUNDLE_SEMANTICS_SUFFIX,
    BUNDLE_LAYOUT_SUFFIX,
    BUNDLE_FONTS_SUFFIX,
    BUNDLE_FIGMA_SVG_SUFFIX,
    ".overrides.json",
    ".catalog.json",
  )

private val SPLIT_JSON = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

/**
 * Split a sheet bundle's [sheetZip] (the appended-zip bytes, not the polyglot) into one
 * [SplitPreview] per preview listed in the sheet manifest. A preview with no baked
 * `previews/<id>.png` in the sheet is skipped (there's no image to make a sticker from) — the
 * caller is told the count.
 *
 * Pure + deterministic (sorted entries, DOS-epoch timestamps), so it's exercised directly by tests
 * without a Gradle build or a daemon.
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
 * Streaming counterpart of [splitBundleZip]: hands each [SplitPreview] to [onPreview] as it is
 * built and keeps no reference to it, returning how many were emitted.
 *
 * Accumulating the whole list is what the CLI used to do, and it does not scale. Every FULL bundle
 * carries the shared re-render payload — `classes/app.jar`, `libs/`, and the Android
 * `resources.ap_` table — so holding N of them costs N copies of it at once. Pocket Casts' 181
 * previews against an unpruned app-resource table OOM'd a 4 GB CI heap (`OutOfMemoryError` in
 * [writeDeterministicZip]); the resource pruner had been masking it by shrinking that payload, and
 * the masking stopped when the pruner was corrected to retain resources it cannot positively
 * attribute to a dependency.
 *
 * Peak memory is now the source bundle plus ONE output bundle, independent of preview count. The
 * list-returning [splitBundleZip] is kept for tests, which split a handful of previews.
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

  // Shared re-render carriage — copied into every FULL bundle, omitted for VIEW_ONLY. Besides
  // the classpath (`classes/app.jar` + `libs/`), this carries the Android app-resource payload
  // under `android/` (the merged `resources.ap_` table, `AndroidManifest.xml`, and the generated
  // `r-classes.jar`): a detached daemon re-rendering an Android per-preview bundle needs the
  // app's own `0x7f` table for `stringResource(R.string.…)` / `colorResource(…)` to resolve —
  // the same table the monolithic `pack` bundle carries (via
  // `BundlePreviewTask.resolveAndroidResources`, re-registered by
  // `AndroidBundleResources.daemonClasspath`). Without it every app-resource lookup misses and
  // renders the `⟦res 0x7f…⟧` placeholder — which only surfaces once an override forces a live
  // per-preview re-render (a plain browse serves the baked PNG/SVG), so a per-variant knob edit
  // on a Wear/Android sticker showed the placeholder instead of the real label.
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

  // Measure what the carriage costs per bundle before writing any of them, deflated exactly as it
  // will land, so the caller can report (or gate on) the repetition rather than discovering it as a
  // delivery-branch size months later. VIEW_ONLY copies none of it, so its carriage is zero.
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
    // Crop the sticker's cover PNG to its component content box (read from the carried figma-svg),
    // so a Wear sticker rendered on a 454² watch canvas ships tight instead of a speck in empty
    // canvas. A no-op when cropping is off, the preview carries no figma-svg, or the box already
    // fills the render (a full-screen component / a phone capture). The figma-svg's `translate`
    // still
    // describes the full render, but it renders by its own content-cropped `viewBox`, and the
    // coordinate sidecars are re-based below, so the cropped sticker stays fully self-consistent.
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
    // The sidecar copy above re-added the *uncropped* `.png`; replace it with the cropped cover so
    // the addressable bundle's `previews/<id>.png` matches the polyglot cover byte-for-byte.
    out["$BUNDLE_PREVIEWS_DIR/$id.png"] = cover
    // Re-base the carried coordinate sidecars (`semantics` / `layout` bounds are in full-render
    // pixels) into the cropped image's space, so a consumer overlaying them on the tight PNG stays
    // aligned instead of offset by the discarded canvas margin.
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
      // Keep the raw-id list parallel to previewIds: subset to this preview's raw id (same
      // index in the source lists), falling back to the bundle id when the source bundle
      // predates the field or the lists disagree.
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
      // (v9) The repositories exist to re-resolve `classpath` coordinates. A FULL split keeps them
      // — that is the live lane a per-preview bundle serves, and dropping them is what leaves the
      // daemon on an incomplete classpath. A view-only bundle has no coordinates to resolve, so
      // carrying URLs would advertise a lane it doesn't have.
      "repositories" -> if (fullMode) put(key, value)
      "resolution" -> put(key, if (fullMode) value else JsonPrimitive("view-only"))
      // The Android app-resource carriage rides the FULL re-render set (the `android/` entries
      // copied above); a VIEW_ONLY bundle ships none of it, so drop the manifest pointer too
      // rather than advertise a `resources.ap_` table the zip doesn't carry (mirrors emptying
      // `classpath`). A FULL bundle keeps the pointer, now backed by the carried `android/`
      // files.
      "androidResources" -> if (fullMode) put(key, value)
      // Keep only this preview's intermediate representation (empty for classpath-backed
      // previews).
      "intermediateRepresentations" ->
        put(
          key,
          buildJsonArray {
            value.jsonArray
              .filter { it.jsonObject["previewId"]?.jsonPrimitive?.content == id }
              .forEach { add(it) }
          },
        )
      // Data-extension reports in a sheet are sliced to the sheet's cover, not this preview —
      // drop
      // them rather than carry another preview's data.
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
 * Crop [pngBytes] to the component content box read from its [svgText] (a figma-svg), returning the
 * re-encoded cropped PNG — or `null` (caller keeps the full image) when the svg carries no box, the
 * PNG can't be decoded, or the box already fills the render (a full-screen component / an
 * already-tight capture, via [contentBoxFillsRender]). The box is clamped to the image so a padded
 * box that overruns the edge still crops safely; the returned [CroppedCover.cropX]/[cropY] are the
 * clamped origin, which the caller uses to re-base coordinate sidecars into the tight image's
 * space. Pure + deterministic (ImageIO writes no timestamp), so [splitBundleZip] stays
 * byte-reproducible.
 */
internal fun cropPngToContentBox(pngBytes: ByteArray, svgText: String): CroppedCover? {
  val svgBox = svgContentBox(svgText) ?: return null
  val src = runCatching { ImageIO.read(ByteArrayInputStream(pngBytes)) }.getOrNull() ?: return null
  val rw = src.width
  val rh = src.height
  if (rw <= 0 || rh <= 0) return null
  // Union the render's actual non-transparent extent into the figma box so a focus ring / disabled
  // outline drawn outside the layout-derived box is never clipped (self-correcting per variant).
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

/**
 * A cropped sticker cover: the re-encoded PNG plus the crop origin (in the full render's pixels).
 */
internal class CroppedCover(val png: ByteArray, val cropX: Int, val cropY: Int)

/**
 * Re-base a coordinate sidecar (`.semantics.json` / `.layout.json`) into a cropped image's pixel
 * space by subtracting the crop origin ([dx],[dy]) from every absolute coordinate, so a consumer
 * overlaying the carried bounds on the tight PNG lands them in the right place instead of offset by
 * the discarded canvas margin. A **whole-tree JSON transform** (not a model round-trip) so every
 * unrelated field survives verbatim and no schema drift can drop data. The absolute-coordinate
 * fields, per the layout-inspector / semantics models:
 * - `boundsInRoot` — the semantics node's `"left,top,right,bottom"` root-pixel string;
 * - `bounds` — the layout node's / modifier's `{left,top,right,bottom}` object;
 * - `centerXPx` / `centerYPx` — a Wear curved-text run's arc centre (root-pixel).
 *
 * Sizes, constraints, radii, angles, and node-relative text-line offsets are position-independent
 * and left untouched. Malformed values pass through unchanged (defensive). A zero origin is a
 * no-op.
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

/**
 * Shift a `"left,top,right,bottom"` string by (`dx`,`dy`); pass through anything that isn't 4 ints.
 */
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

/**
 * Subtract [d] from a numeric [JsonPrimitive], keeping int/decimal shape; pass through non-numbers.
 */
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
    // The bare token that is not some flag's value — `-o <dir>` / `--carriage-report <file>` write
    // paths that do not start with `-`, so a naive "first non-flag token" reads whichever came
    // first as the sheet.
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
    // Content-crop each sticker's PNG to its component box by default (tight importable stickers);
    // `--no-crop` keeps the full render canvas.
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

    // Distinct ids can sanitize to the same stem (e.g. `A B` and `A_B`, or `/` vs `_`); on a
    // collision append -2/-3/… so every preview keeps its own file instead of silently overwriting.
    val usedStems = HashSet<String>()
    val written = ArrayList<File>()
    val sharedPool = sharedClasspathOut?.let(::File)?.absoluteFile
    var carriage = SplitCarriage.NONE
    // Write each bundle as it is produced and let it go. Collecting them first meant holding every
    // per-preview bundle — each carrying the shared classpath + Android resource table — in memory
    // simultaneously, which OOM'd on a 181-preview catalog. Only the File handles are kept, for the
    // size summary below.
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
    // The carriage, when it dominates, IS the explanation for the size — say that instead of the
    // generic "some bundles are big", which named `--view-only` as the only remedy and so was
    // unactionable (hence ignorable) on every live catalog. Only when the carriage is NOT the story
    // does an outsized bundle mean what the old warning claimed: a genuinely large render.
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
