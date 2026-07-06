package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * `compose-preview bundle externalize` — lift large binary resources (fonts by default) **out** of
 * a packed bundle's `classes/app.jar` and publish them content-addressed beside the bundle,
 * shrinking the carried `.png` from ~600 KB to ~30 KB.
 *
 * # Why
 *
 * A desktop-CMP catalog bundle (`compose-m3`) embeds its real font faces (Roboto / Noto Serif /
 * Droid Sans Mono, ~570 KB) inside `classes/app.jar` so the live daemon can rasterise text with the
 * same faces the baked stickers used. Carrying those bytes on **every** `design-artifacts/<system>`
 * branch — re-fetched by the public server on each catalog reload — is wasteful: the fonts rarely
 * change and are identical across variants. This step lifts them out, records each in the
 * manifest's [BundleReader.Manifest.externalResources] by name + sha256 + size, and writes the
 * bytes to a content-addressed pool (`<res-out>/<sha256>`) the publish pipeline carries once per
 * branch. The server rehydrates them into a shared hash-keyed cache and back onto the daemon
 * classpath at their recorded path, so `getResourceAsStream("/fonts/…")` resolves exactly as
 * before.
 *
 * # What it does
 *
 * 1. Reads the bundle's `classes/app.jar`, splitting each entry into *kept* (classes + small
 *    resources) vs *externalized* (a resource whose path matches one of [extensions]).
 * 2. Writes each externalized resource's bytes to `<res-out>/<sha256>` (deduped — identical bytes
 *    share a file), and records `{path, sha256, size}`.
 * 3. Rebuilds `classes/app.jar` without the externalized entries and merges the records into
 *    `bundle.json`'s `externalResources` (idempotent by path — re-running is a no-op).
 * 4. Rewrites the bundle in place (or to `-o`) via [injectRawZipEntries], preserving the polyglot
 *    PNG cover and every other entry.
 *
 * The manifest is edited as a **raw JSON tree** rather than a typed round-trip so fields the CLI's
 * [BundleReader.Manifest] mirror doesn't model (future schema additions) survive untouched.
 *
 * Idempotent: a second run finds the entries already gone from the jar and already recorded, and
 * changes nothing.
 */
internal object BundleExternalize {

  /** Default resource extensions lifted out — the font faces that dominate a catalog bundle. */
  val DEFAULT_EXTENSIONS: List<String> = listOf("ttf", "otf", "woff", "woff2")

  data class Externalized(val path: String, val sha256: String, val size: Long)

  data class Result(val bundleFile: File, val resDir: File, val externalized: List<Externalized>)

  /**
   * Externalize [extensions]-matching resources out of [bundleFile]'s `classes/app.jar` into
   * [resDir], rewriting the bundle in place (the polyglot PNG cover + all other entries preserved)
   * and merging the records into `bundle.json`. Returns the resources externalized on **this** run
   * (already-externalized ones on a re-run count as zero new work but stay recorded). Throws
   * [IllegalArgumentException] if the bundle carries no `classes/app.jar`.
   */
  fun externalize(
    bundleFile: File,
    resDir: File,
    extensions: List<String> = DEFAULT_EXTENSIONS,
    fileSystem: FileSystem = SystemFileSystem,
  ): Result {
    val exts = extensions.map { it.trim().lowercase().removePrefix(".") }.filter { it.isNotEmpty() }
    val zip = BundleReader.extractZipBytes(bundleFile, fileSystem)

    val appJar =
      readZipEntry(zip, "classes/app.jar")
        ?: throw IllegalArgumentException(
          "classes/app.jar missing in ${bundleFile.path} — not a packed class-backed bundle"
        )

    resDir.mkdirs()
    val externalized = LinkedHashMap<String, Externalized>()
    val strippedJar =
      rewriteJar(appJar) { name, bytes ->
        if (matchesExtension(name, exts)) {
          val sha = sha256Hex(bytes)
          fileSystem.write(File(resDir, sha).path.toPath()) { write(bytes) }
          externalized[name] = Externalized(path = name, sha256 = sha, size = bytes.size.toLong())
          false // drop from the jar
        } else {
          true // keep
        }
      }

    // Merge the records into bundle.json's externalResources as a raw JSON tree so unmodelled
    // fields
    // survive. Idempotent by path — a re-run replaces the same-path entry with an identical one.
    val manifestBytes =
      readZipEntry(zip, "bundle.json")
        ?: throw IllegalArgumentException("bundle.json missing in ${bundleFile.path}")
    val newManifest = mergeExternalResources(manifestBytes, externalized.values)

    injectRawZipEntries(
      bundleFile,
      mapOf("classes/app.jar" to strippedJar, "bundle.json" to newManifest),
      fileSystem,
    )
    return Result(bundleFile, resDir, externalized.values.toList())
  }

  private fun matchesExtension(name: String, exts: List<String>): Boolean {
    val dot = name.lastIndexOf('.')
    if (dot < 0) return false
    return name.substring(dot + 1).lowercase() in exts
  }

  /** Read one entry's bytes out of a raw zip, or null if absent. */
  private fun readZipEntry(zip: ByteArray, name: String): ByteArray? {
    ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (entry.name == name) return zin.readBytes()
        zin.closeEntry()
      }
    }
    return null
  }

  /**
   * Rebuild a jar keeping only entries for which [keep] returns true. Entry order + directory
   * entries are preserved; times are pinned to [ZIP_DOS_EPOCH_MS] so the stripped jar stays
   * byte-stable across runs.
   */
  private fun rewriteJar(
    jarBytes: ByteArray,
    keep: (name: String, bytes: ByteArray) -> Boolean,
  ): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zout ->
      ZipInputStream(ByteArrayInputStream(jarBytes)).use { zin ->
        while (true) {
          val entry = zin.nextEntry ?: break
          if (entry.isDirectory) {
            zout.putNextEntry(ZipEntry(entry.name).apply { time = ZIP_DOS_EPOCH_MS })
            zout.closeEntry()
          } else {
            val bytes = zin.readBytes()
            if (keep(entry.name, bytes)) {
              zout.putNextEntry(ZipEntry(entry.name).apply { time = ZIP_DOS_EPOCH_MS })
              zout.write(bytes)
              zout.closeEntry()
            }
          }
          zin.closeEntry()
        }
      }
    }
    return baos.toByteArray()
  }

  /**
   * Return [manifestBytes] with [records] merged into its `externalResources` array — existing
   * entries whose `path` collides are replaced (idempotent), the rest preserved, and every other
   * top-level field left untouched. New entries are appended in [records] order after the
   * survivors.
   */
  private fun mergeExternalResources(
    manifestBytes: ByteArray,
    records: Collection<Externalized>,
  ): ByteArray {
    val root = json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)) as JsonObject
    val recordByPath = records.associateBy { it.path }
    val existing = (root["externalResources"] as? JsonArray).orEmpty()
    val merged = buildJsonArray {
      for (element in existing) {
        val path = (element as? JsonObject)?.get("path")?.jsonPrimitiveContentOrNull()
        if (path != null && path in recordByPath) continue // replaced below
        add(element)
      }
      for (record in records) {
        add(
          buildJsonObject {
            put("path", record.path)
            put("sha256", record.sha256)
            put("size", record.size)
          }
        )
      }
    }
    val newRoot = JsonObject(root.toMutableMap().apply { put("externalResources", merged) })
    return json.encodeToString(JsonObject.serializer(), newRoot).toByteArray(Charsets.UTF_8)
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
  }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContentOrNull(): String? =
  (this as? kotlinx.serialization.json.JsonPrimitive)?.content

/**
 * `compose-preview bundle externalize <bundle.png> --res-out <dir> [-o <file.png>] [--ext ttf,otf]`
 * — the CLI wrapper around [BundleExternalize.externalize]. Rewrites the bundle in place by
 * default; `-o` writes a copy first and externalizes that. Prints a one-line-per-resource summary;
 * with `--json` prints a machine-readable summary the publish pipeline consumes.
 */
internal class ExternalizeSubcommand(
  private val args: List<String>,
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  fun run() {
    val path = args.firstOrNull { !it.startsWith("-") }
    val resOut = args.flagValue("--res-out")
    val outArg = args.flagValue("--output") ?: args.flagValue("-o")
    val extArg = args.flagValue("--ext")
    val asJson = "--json" in args
    if (path == null || resOut == null) {
      System.err.println(
        "Usage: compose-preview bundle externalize <bundle.png | URL> --res-out <dir> " +
          "[-o <file.png>] [--ext ttf,otf,woff,woff2] [--json]"
      )
      exitProcess(64)
    }
    val source =
      try {
        BundleSource.resolveToFile(path)
      } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
      }

    // A URL input resolved to a delete-on-exit temp file — rewriting it "in place" would vanish on
    // exit, so require -o for a downloaded bundle (same guard as `bundle embed --in-bundle`).
    val target =
      if (outArg != null) {
        val t = File(outArg).absoluteFile
        t.parentFile?.mkdirs()
        val bytes = fileSystem.read(source.path.toPath()) { readByteArray() }
        fileSystem.write(t.path.toPath()) { write(bytes) }
        t
      } else if (BundleSource.looksLikeUrl(path)) {
        System.err.println(
          "bundle externalize: the input is a downloaded URL (a temporary file). " +
            "Pass -o <file.png> so the externalized bundle is written somewhere durable."
        )
        exitProcess(64)
      } else {
        source
      }

    val extensions =
      extArg?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: BundleExternalize.DEFAULT_EXTENSIONS

    val result =
      try {
        BundleExternalize.externalize(target, File(resOut), extensions, fileSystem)
      } catch (e: IllegalArgumentException) {
        System.err.println("bundle externalize: ${e.message}")
        exitProcess(1)
      }

    if (asJson) {
      val summary = buildJsonObject {
        put("bundle", result.bundleFile.absolutePath)
        put("resDir", result.resDir.absolutePath)
        put("size", result.bundleFile.length())
        putJsonArray("externalized") {
          for (r in result.externalized) {
            add(
              buildJsonObject {
                put("path", r.path)
                put("sha256", r.sha256)
                put("size", r.size)
              }
            )
          }
        }
      }
      println(summaryJson.encodeToString(JsonObject.serializer(), summary))
    } else {
      val total = result.externalized.sumOf { it.size }
      println(
        "externalized ${result.externalized.size} resource(s) (${total} bytes) from " +
          "${target.name} → ${result.resDir.path}/  (bundle now ${target.length()} bytes)"
      )
      for (r in result.externalized) println("  ${r.path}  →  ${r.sha256.take(12)}…  (${r.size} B)")
    }
  }

  private val summaryJson = Json { prettyPrint = false }
}
