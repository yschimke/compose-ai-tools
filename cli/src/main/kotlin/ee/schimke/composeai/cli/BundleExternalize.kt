package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.ZIP_DOS_EPOCH_MS
import ee.schimke.composeai.bundle.injectRawZipEntries
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
 * `compose-preview bundle externalize`: lift large binary resources (fonts by default) out of a
 * bundle's `classes/app.jar` into a content-addressed pool beside it, shrinking the `.png` from
 * ~600 KB to ~30 KB. Fonts rarely change and are identical across variants, so a delivery branch
 * carries them once; the server rehydrates them onto the daemon classpath at their recorded paths.
 *
 * 1. Split `classes/app.jar` entries into kept vs externalized (path matches [extensions]).
 * 2. Write each externalized resource to `<res-out>/<sha256>` (deduped) and record
 *    `{path, sha256, size}`.
 * 3. Rebuild the jar without them and merge the records into `bundle.json`'s `externalResources`.
 * 4. Rewrite the bundle in place (or to `-o`) via [injectRawZipEntries], keeping the PNG cover and
 *    other entries.
 *
 * The manifest is edited as a raw JSON tree so unmodelled fields survive. Idempotent.
 */
internal object BundleExternalize {

  /** Default resource extensions lifted out — the font faces that dominate a catalog bundle. */
  val DEFAULT_EXTENSIONS: List<String> = listOf("ttf", "otf", "woff", "woff2")

  data class Externalized(val path: String, val sha256: String, val size: Long)

  data class Result(val bundleFile: File, val resDir: File, val externalized: List<Externalized>)

  /**
   * Externalize [extensions]-matching resources from [bundleFile] into [resDir], rewriting the
   * bundle and merging records into `bundle.json`. Returns resources externalized on this run.
   * Throws [IllegalArgumentException] if there is no `classes/app.jar`.
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

    // Merge as a raw JSON tree so unmodelled fields survive; idempotent by path.
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
   * Rebuild a jar keeping entries where [keep] is true, preserving order and directories, with
   * times pinned to [ZIP_DOS_EPOCH_MS] for byte stability.
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
   * [manifestBytes] with [records] merged into `externalResources`: same-path entries replaced,
   * others kept, new ones appended in order, all other fields untouched.
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
 * `compose-preview bundle externalize <bundle.png> --res-out <dir> [-o <file.png>] [--ext
 * ttf,otf]`. Rewrites in place by default (`-o` copies first); prints a per-resource summary, or
 * JSON with `--json` for the publish pipeline.
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

    // A URL input resolves to a delete-on-exit temp file, so require `-o` for downloaded bundles.
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
