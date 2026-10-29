package ee.schimke.composeai.cli

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The converted classes for an APK, so `compose-preview launch` pays for dex2jar once per APK and
 * converter rather than on every run (the sample app's 40 MB of dex takes about 40 seconds).
 *
 * Layout under [root] (`<cache>/composeai/simulator/` by default):
 * ```
 * <apk sha256>/
 *   source.txt                 the APK path last converted, for people reading the cache
 *   <converter stamp>/
 *     classes.jar              the converted, frame-computed classes
 *     conversion.json          the converter's summary line
 * ```
 *
 * The APK is keyed by content, so a rebuilt APK at the same path converts again and a copy of the
 * same APK elsewhere does not. The converter stamp covers the converter's own jars and the platform
 * `android.jar` it resolves supertypes from, so a newer converter never serves an older conversion;
 * older stamps of the same APK are removed when a new one is written. Only the [keepApks] most
 * recently used APKs are kept.
 *
 * Concurrent launches of one APK serialize on a lock file, so the second one finds the first's
 * result instead of converting again. Output lands in a temporary file and is moved into place, so
 * an interrupted conversion never leaves a partial `classes.jar` behind.
 */
internal class ApkConversionCache(
  private val root: File,
  private val keepApks: Int = DEFAULT_KEEP_APKS,
) {

  /** What [ensure] returns. */
  data class Entry(val classesJar: File, val summary: String, val cached: Boolean)

  /**
   * The converted classes for [apk], running [convert] (which writes the jar it is given and
   * returns the converter's summary) only when no entry for this APK and [converterStamp] exists.
   */
  fun ensure(apk: File, converterStamp: String, convert: (out: File) -> String): Entry {
    val apkDir = File(root, sha256(apk))
    val entryDir = File(apkDir, converterStamp)
    val classesJar = File(entryDir, CLASSES_JAR)
    val summaryFile = File(entryDir, SUMMARY_JSON)
    apkDir.mkdirs()
    RandomAccessFile(File(root, "${apkDir.name}.lock"), "rw").use { lockFile ->
      lockFile.channel.lock().use {
        File(apkDir, "source.txt").writeText(apk.absolutePath + "\n")
        apkDir.setLastModified(System.currentTimeMillis())
        if (classesJar.isFile && summaryFile.isFile) {
          return Entry(classesJar, summaryFile.readText().trim(), cached = true)
        }
        entryDir.mkdirs()
        val partial = File(entryDir, "$CLASSES_JAR.partial")
        partial.delete()
        val summary =
          try {
            convert(partial).trim()
          } catch (e: Exception) {
            partial.delete()
            throw e
          }
        check(partial.isFile) { "the converter wrote no classes for $apk" }
        Files.move(partial.toPath(), classesJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        summaryFile.writeText(summary + "\n")
        apkDir
          .listFiles { f -> f.isDirectory && f.name != converterStamp }
          .orEmpty()
          .forEach { it.deleteRecursively() }
      }
    }
    prune()
    return Entry(classesJar, summaryFile.readText().trim(), cached = false)
  }

  /** Drops all but the [keepApks] most recently used APK entries. */
  private fun prune() {
    val apks =
      root
        .listFiles { f -> f.isDirectory && f.name.length == SHA256_HEX_LENGTH }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
    for (stale in apks.drop(keepApks)) {
      stale.deleteRecursively()
      File(root, "${stale.name}.lock").delete()
    }
  }

  companion object {
    const val CLASSES_JAR = "classes.jar"
    const val SUMMARY_JSON = "conversion.json"
    const val DEFAULT_KEEP_APKS = 8
    private const val SHA256_HEX_LENGTH = 64

    /**
     * A stamp for the converter: the content of every jar it runs from, plus the platform jar's
     * name and size (its content is large and changes only with a new platform).
     */
    fun converterStamp(converterJars: List<File>, platformJar: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      for (jar in converterJars.sortedBy { it.name }) {
        digest.update(jar.name.toByteArray())
        jar.inputStream().use { input ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
          }
        }
      }
      digest.update("${platformJar.parentFile?.name}/${platformJar.length()}".toByteArray())
      return digest.digest().toHex().take(16)
    }

    fun sha256(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          digest.update(buffer, 0, read)
        }
      }
      return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
  }
}
