package ee.schimke.composeai.simulator.launch

import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * What the simulator works out from a file and needs again next launch (an APK's SHA-256, its form
 * factor, a jar's digest), remembered under `<cache>/simulator/facts/` until the file changes.
 *
 * A fact is keyed by the file's path and checked against its size, modification time (to the
 * nanosecond the file system keeps) and file key (device and inode), so a rebuilt APK, even one of
 * the same size, is worked out again. Hashing and scanning a large APK every launch was most of the
 * CLI's start-up: Cadence's 104 MB debug APK took 3.4 s before the simulator JVM started.
 */
public object FileFacts {

  private val root: File by lazy { File(composeAiCacheDir("simulator"), "facts") }

  /** [kind] of [file] as [compute] works it out, from the store while [file] is unchanged. */
  public fun remember(file: File, kind: String, compute: () -> String): String {
    val stamp = stampOf(file) ?: return compute()
    val record = File(File(root, kind), key(file))
    runCatching {
      val lines = record.readLines()
      if (lines.size == 2 && lines[0] == stamp) return lines[1]
    }
    val value = compute()
    if (value.contains('\n')) return value
    runCatching {
      record.parentFile.mkdirs()
      val temp = File(record.parentFile, "${record.name}.${ProcessHandle.current().pid()}.tmp")
      temp.writeText("$stamp\n$value")
      if (!temp.renameTo(record)) temp.delete()
    }
    return value
  }

  /** Size, modification time and file key, or null when [file] cannot be read. */
  internal fun stampOf(file: File): String? = runCatching {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    val modified = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)
    "${attributes.size()}:$modified:${attributes.fileKey()}"
  }
    .getOrNull()

  private fun key(file: File): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(file.absolutePath.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
  }
}
