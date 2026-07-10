package ee.schimke.composeai.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * bundle so each can live-re-render — correct but heavier (the shared jars repeat N times).
 * [SplitMode.VIEW_ONLY] drops that classpath: the result is a baked, self-describing sticker
 * (image + sidecars + manifest) that any PNG viewer / detached reader opens, typically tens of KB.
 * View-only is the right unit for a delivery branch of addressable stickers.
 */
internal enum class SplitMode {
  FULL,
  VIEW_ONLY,
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
internal fun splitBundleZip(sheetZip: ByteArray, mode: SplitMode): List<SplitPreview> {
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

  // Shared re-render classpath — copied into every FULL bundle, omitted for VIEW_ONLY.
  val shared = entries.filterKeys {
    it == "classes/app.jar" || it.startsWith("libs/") || it == "report.json"
  }
  val fullMode = mode == SplitMode.FULL

  val result = ArrayList<SplitPreview>(ids.size)
  for (id in ids) {
    val cover = entries["$BUNDLE_PREVIEWS_DIR/$id.png"] ?: continue // no image → nothing to address

    val out = LinkedHashMap<String, ByteArray>()
    if (fullMode) out.putAll(shared)
    for (suffix in SPLIT_SIDECAR_SUFFIXES) {
      val key = "$BUNDLE_PREVIEWS_DIR/$id$suffix"
      entries[key]?.let { out[key] = it }
    }
    val rasterPrefix = "$BUNDLE_PREVIEWS_DIR/$id$BUNDLE_FIGMA_RASTER_DIR_SUFFIX/"
    val irPrefix = "ir/$id."
    for ((name, bytes) in entries) {
      if (name.startsWith(rasterPrefix)) out[name] = bytes
      if (fullMode && name.startsWith(irPrefix)) out[name] = bytes
    }

    out["bundle.json"] =
      SPLIT_JSON.encodeToString(JsonObject.serializer(), perPreviewManifest(manifest, id, fullMode))
        .encodeToByteArray()
    out["previews.json"] =
      SPLIT_JSON.encodeToString(
          JsonObject.serializer(),
          perPreviewPreviews(previews, previewsArray, id),
        )
        .encodeToByteArray()

    result += SplitPreview(id, cover, writeDeterministicZip(out))
  }
  return result
}

/** Rewrite the sheet manifest for a single preview: cover + previewIds = [id], IR filtered, etc. */
private fun perPreviewManifest(manifest: JsonObject, id: String, fullMode: Boolean): JsonObject =
  buildJsonObject {
    for ((key, value) in manifest) {
      when (key) {
        "previewIds" -> put(key, buildJsonArray { add(JsonPrimitive(id)) })
        "coverPreviewId" -> put(key, JsonPrimitive(id))
        // View-only carries no re-render classpath, so record that honestly.
        "classpath" -> put(key, if (fullMode) value else JsonArray(emptyList()))
        "resolution" -> put(key, if (fullMode) value else JsonPrimitive("view-only"))
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
    val path = args.firstOrNull { !it.startsWith("-") }
    val outDirArg = args.flagValue("--output") ?: args.flagValue("-o")
    val mode = if ("--view-only" in args) SplitMode.VIEW_ONLY else SplitMode.FULL
    if (path == null) {
      System.err.println(
        "Usage: compose-preview bundle split <bundle.png | URL> -o <dir> [--view-only]"
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
    val split =
      try {
        splitBundleZip(zipBytes, mode)
      } catch (e: IllegalArgumentException) {
        System.err.println("bundle split: ${e.message}")
        exitProcess(1)
      }
    if (split.isEmpty()) {
      System.err.println(
        "bundle split: no previews with a baked image found in ${file.name} — nothing to split."
      )
      exitProcess(1)
    }

    val written = ArrayList<File>(split.size)
    for (preview in split) {
      val outFile = File(outDir, sanitizeSplitFileName(preview.id) + ".png")
      outFile.writeBytes(preview.polyglot())
      written += outFile
    }

    val sizes = written.map { it.length() }
    val total = sizes.sum()
    println(
      "bundle split — wrote ${written.size} bundle(s) to ${outDir.path} " +
        "(${if (mode == SplitMode.VIEW_ONLY) "view-only" else "full"})\n" +
        "  total:   $total bytes\n" +
        "  size:    min ${sizes.minOrNull() ?: 0} / avg ${if (written.isNotEmpty()) total / written.size else 0} / max ${sizes.maxOrNull() ?: 0} bytes"
    )
    val over = written.filter { it.length() > 100 * 1024 }
    if (over.isNotEmpty()) {
      System.err.println(
        "bundle split: ${over.size} bundle(s) exceed 100 KB " +
          "(largest ${over.maxByOrNull { it.length() }!!.length()} bytes) — usually a full-screen " +
          "render, or --view-only was not set so the shared classpath repeats in each bundle."
      )
    }
  }

  /** Filesystem-safe filename stem for a preview id (keep `A-Za-z0-9._-`, else `_`). */
  private fun sanitizeSplitFileName(id: String): String = buildString {
    for (c in id) append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
  }
}
