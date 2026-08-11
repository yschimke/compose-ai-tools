package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path.Companion.toPath

/** Restore opt-in shared classpath entries into a self-contained executable bundle. */
internal object BundleClasspathHydration {
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Hydrate [source] into [output]. Every declared blob is size/hash verified before it is
   * injected. The external declaration and any now-stale producer signatures are removed from the
   * derivative.
   */
  fun hydrate(
    source: File,
    output: File,
    fileSystem: FileSystem = SystemFileSystem,
    resolve: (BundleReader.ExternalClasspath) -> ByteArray?,
  ): File {
    val metadata = BundleReader.readMetadata(source)
    val external = metadata.manifest.externalClasspath
    output.parentFile?.mkdirs()
    if (fileSystem.exists(output.path.toPath())) fileSystem.delete(output.path.toPath())
    fileSystem.copy(source.path.toPath(), output.path.toPath())
    if (external.isEmpty()) return output

    val additions = LinkedHashMap<String, ByteArray>()
    for (entry in external) {
      require(entry.path == "classes/app.jar") {
        "unsupported external classpath path '${entry.path}'"
      }
      require(entry.sha256.length == 64 && entry.sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
        "external classpath sha256 '${entry.sha256}' is malformed"
      }
      val bytes =
        checkNotNull(resolve(entry)) {
          "external classpath ${entry.path} (${entry.sha256}) is unavailable"
        }
      check(bytes.size.toLong() == entry.size) {
        "external classpath ${entry.path}: ${bytes.size} bytes != declared ${entry.size}"
      }
      check(sha256Hex(bytes) == entry.sha256) {
        "external classpath ${entry.path}: sha256 mismatch"
      }
      additions[entry.path] = bytes
    }

    val zip = BundleReader.extractZipBytes(source)
    val manifest =
      readZipEntry(zip, "bundle.json") ?: error("bundle.json missing in ${source.path}")
    val root = json.parseToJsonElement(manifest.decodeToString()) as JsonObject
    additions["bundle.json"] =
      JsonObject(root.toMutableMap().apply { remove("externalClasspath") })
        .toString()
        .encodeToByteArray()
    rewriteRawZipEntries(
      output,
      additions,
      removals = setOf(BundleSigning.SIGNATURES_PATH),
      fileSystem = fileSystem,
    )
    return output
  }

  private fun readZipEntry(zip: ByteArray, wanted: String): ByteArray? {
    java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zip)).use { input ->
      while (true) {
        val entry = input.nextEntry ?: return null
        if (!entry.isDirectory && entry.name == wanted) return input.readBytes()
        input.closeEntry()
      }
    }
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
