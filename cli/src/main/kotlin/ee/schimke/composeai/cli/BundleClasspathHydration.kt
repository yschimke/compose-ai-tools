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
  ): File =
    hydrate(
      source = source,
      output = output,
      fileSystem = fileSystem,
      resolveClasspath = resolve,
      resolveResource = { null },
    )

  /**
   * Hydrate both whole classpath entries and resources lifted out of `classes/app.jar`. The result
   * is published atomically, so a failed resolver never leaves a thin bundle at [output].
   */
  fun hydrate(
    source: File,
    output: File,
    fileSystem: FileSystem = SystemFileSystem,
    resolveClasspath: (BundleReader.ExternalClasspath) -> ByteArray?,
    resolveResource: (BundleReader.ExternalResource) -> ByteArray?,
  ): File {
    val metadata = BundleReader.readMetadata(source)
    val externalClasspath = metadata.manifest.externalClasspath
    val externalResources = metadata.manifest.externalResources
    output.parentFile?.mkdirs()
    val temporary =
      File(
        output.parentFile,
        "${output.name}.${Thread.currentThread().id}.${System.nanoTime()}.hydrate-tmp",
      )
    val temporaryPath = temporary.path.toPath()
    if (fileSystem.exists(temporaryPath)) fileSystem.delete(temporaryPath)

    try {
      fileSystem.copy(source.path.toPath(), temporaryPath)
      if (externalClasspath.isEmpty() && externalResources.isEmpty()) {
        fileSystem.atomicMove(temporaryPath, output.path.toPath())
        return output
      }

      val zip = BundleReader.extractZipBytes(source)
      val additions = LinkedHashMap<String, ByteArray>()
      for (entry in externalClasspath) {
        require(entry.path == "classes/app.jar") {
          "unsupported external classpath path '${entry.path}'"
        }
        validateHash(entry.sha256, "external classpath")
        val bytes =
          checkNotNull(resolveClasspath(entry)) {
            "external classpath ${entry.path} (${entry.sha256}) is unavailable"
          }
        verifyBlob(entry.path, entry.sha256, entry.size, bytes)
        additions[entry.path] = bytes
      }

      if (externalResources.isNotEmpty()) {
        val resources = LinkedHashMap<String, ByteArray>()
        for (entry in externalResources) {
          require(
            entry.path.isNotBlank() && !entry.path.startsWith("/") && ".." !in entry.path.split("/")
          ) {
            "external resource path '${entry.path}' is invalid"
          }
          validateHash(entry.sha256, "external resource")
          val bytes =
            checkNotNull(resolveResource(entry)) {
              "external resource ${entry.path} (${entry.sha256}) is unavailable"
            }
          verifyBlob(entry.path, entry.sha256, entry.size, bytes)
          resources[entry.path] = bytes
        }
        val appJar =
          additions["classes/app.jar"]
            ?: readZipEntry(zip, "classes/app.jar")
            ?: error("classes/app.jar missing while restoring external resources")
        additions["classes/app.jar"] = addOrReplaceZipEntries(appJar, resources)
      }

      val manifest =
        readZipEntry(zip, "bundle.json") ?: error("bundle.json missing in ${source.path}")
      val root = json.parseToJsonElement(manifest.decodeToString()) as JsonObject
      additions["bundle.json"] =
        JsonObject(
            root.toMutableMap().apply {
              if (externalClasspath.isNotEmpty()) remove("externalClasspath")
              if (externalResources.isNotEmpty()) remove("externalResources")
            }
          )
          .toString()
          .encodeToByteArray()
      rewriteRawZipEntries(
        temporary,
        additions,
        removals = setOf(BundleSigning.SIGNATURES_PATH),
        fileSystem = fileSystem,
      )
      fileSystem.atomicMove(temporaryPath, output.path.toPath())
      return output
    } catch (e: Exception) {
      if (fileSystem.exists(temporaryPath)) fileSystem.delete(temporaryPath)
      throw e
    }
  }

  private fun validateHash(sha256: String, label: String) {
    require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
      "$label sha256 '$sha256' is malformed"
    }
  }

  private fun verifyBlob(path: String, sha256: String, size: Long, bytes: ByteArray) {
    check(bytes.size.toLong() == size) { "$path: ${bytes.size} bytes != declared $size" }
    check(sha256Hex(bytes) == sha256) { "$path: sha256 mismatch" }
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
