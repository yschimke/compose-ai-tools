package ee.schimke.composeai.cli

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * The converted classes for an APK, so `compose-preview launch` pays for dex2jar once per APK and
 * converter rather than on every run (the sample app's 40 MB of dex takes about 40 seconds).
 *
 * Layout under [root] (`<cache>/composeai/simulator/` by default):
 * ```
 * <apk sha256>.lock            held while an entry is converted
 * <apk sha256>.inuse           held, shared, by every simulator running from the entry
 * <apk sha256>/
 *   source.txt                 the APK path last converted
 *   <converter stamp>/
 *     classes.jar              the converted, frame-computed classes
 *     classes.jar.index        each class's key and dependencies, for the next build's conversion
 *     conversion.json          the converter's summary line
 * ```
 *
 * The APK is keyed by content, so a rebuilt APK at the same path converts again and a copy of the
 * same APK elsewhere does not. A rebuilt APK is converted incrementally: the previous build's entry
 * (the newest one for the same path and converter stamp) is handed to the converter, which copies
 * every class whose dex did not change.
 *
 * The converter stamp covers the converter's own classes (not the rest of the simulator host, which
 * changes far more often), dex2jar, and the platform `android.jar` it resolves supertypes from, so
 * a newer converter never serves an older conversion.
 *
 * Pruning, after each use, never touches an entry that a conversion or a running simulator holds:
 * - the APK's other converter stamps;
 * - entries for an earlier build of the same APK path, once its successor is converted;
 * - beyond that, the least recently used APKs, down to [keepApks] and [maxBytes].
 *
 * Concurrent launches of one APK serialize on its lock file, so the second one finds the first's
 * result instead of converting again. Output lands in a temporary file and is moved into place, so
 * an interrupted conversion never leaves a partial `classes.jar` behind.
 */
internal class ApkConversionCache(
  private val root: File,
  private val keepApks: Int = DEFAULT_KEEP_APKS,
  private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

  /** What [ensure] returns. */
  data class Entry(val classesJar: File, val summary: String, val cached: Boolean) {
    /** The `<apk sha256>` directory. */
    val apkDir: File
      get() = classesJar.parentFile.parentFile
  }

  /**
   * The converted classes for [apk], running [convert] only when no entry for this APK and
   * [converterStamp] exists. [convert] writes the jar it is given, and its index beside it
   * ([indexOf]), and returns the converter's summary; its second argument is the previous build's
   * converted jar, when there is one to reuse classes from.
   */
  fun ensure(
    apk: File,
    converterStamp: String,
    convert: (out: File, previous: File?) -> String,
  ): Entry {
    val apkDir = File(root, sha256(apk))
    val entryDir = File(apkDir, converterStamp)
    val classesJar = File(entryDir, CLASSES_JAR)
    val summaryFile = File(entryDir, SUMMARY_JSON)
    val source = apk.absolutePath
    apkDir.mkdirs()
    var converted = false
    RandomAccessFile(lockFile(apkDir.name), "rw").use { lockFile ->
      lockFile.channel.lock().use {
        File(apkDir, SOURCE_TXT).writeText(source + "\n")
        apkDir.setLastModified(System.currentTimeMillis())
        if (!(classesJar.isFile && summaryFile.isFile)) {
          entryDir.mkdirs()
          val partial = File(entryDir, "$CLASSES_JAR.partial")
          fun clean() {
            partial.delete()
            indexOf(partial).delete()
          }
          clean()
          val previous = previousBuild(source, converterStamp, apkDir)
          // The previous build's entry stays put while it is read.
          val summary =
            use(previous?.parentFile?.parentFile).use {
              try {
                convert(partial, previous).trim()
              } catch (e: Exception) {
                clean()
                throw e
              }
            }
          check(partial.isFile) { "the converter wrote no classes for $apk" }
          val partialIndex = indexOf(partial)
          if (partialIndex.isFile) {
            Files.move(
              partialIndex.toPath(),
              indexOf(classesJar).toPath(),
              StandardCopyOption.REPLACE_EXISTING,
            )
          }
          Files.move(partial.toPath(), classesJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
          summaryFile.writeText(summary + "\n")
          converted = true
        }
      }
    }
    prune(apkDir, converterStamp, source, superseded = converted)
    return Entry(classesJar, summaryFile.readText().trim(), cached = !converted)
  }

  /**
   * Marks [entry] in use until closed: pruning, from this process or another, leaves it alone. A
   * launch holds it for as long as its simulator runs from the entry's jar.
   */
  fun use(entry: Entry): Closeable = use(entry.apkDir)

  private fun use(apkDir: File?): Closeable {
    if (apkDir == null) return Closeable {}
    val file = RandomAccessFile(inUseFile(apkDir.name), "rw")
    val lock =
      try {
        file.channel.lock(0, Long.MAX_VALUE, true)
      } catch (e: Exception) {
        file.close()
        throw e
      }
    return Closeable {
      lock.release()
      file.close()
    }
  }

  /** The newest converted entry, by [converterStamp], for an earlier build of [source]. */
  private fun previousBuild(source: String, converterStamp: String, apkDir: File): File? =
    apkDirs()
      .filter { it != apkDir && sourceOf(it) == source }
      .sortedByDescending { it.lastModified() }
      .map { File(File(it, converterStamp), CLASSES_JAR) }
      .firstOrNull { it.isFile && indexOf(it).isFile }

  private fun prune(apkDir: File, converterStamp: String, source: String, superseded: Boolean) {
    // The APK's own entries for other converters.
    withExclusive(apkDir.name) {
      apkDir
        .listFiles { f -> f.isDirectory && f.name != converterStamp }
        .orEmpty()
        .forEach { it.deleteRecursively() }
    }
    val others = apkDirs().filter { it != apkDir }
    // Earlier builds of this APK path: the new conversion has taken what it could from them.
    if (superseded) {
      others.filter { sourceOf(it) == source }.forEach { remove(it) }
    }
    // Then the least recently used, by count and by size; the entry just used is never one.
    var kept = 1
    var bytes = sizeOf(apkDir)
    for (dir in apkDirs().filter { it != apkDir }.sortedByDescending { it.lastModified() }) {
      val size = sizeOf(dir)
      if (kept < keepApks && bytes + size <= maxBytes) {
        kept++
        bytes += size
      } else if (!remove(dir)) {
        kept++
        bytes += size
      }
    }
  }

  /** Deletes an APK entry unless a conversion or a simulator holds it; whether it did. */
  private fun remove(dir: File): Boolean =
    withExclusive(dir.name) {
      dir.deleteRecursively()
      lockFile(dir.name).delete()
      inUseFile(dir.name).delete()
    }

  /**
   * Runs [block] holding both of [name]'s locks exclusively, or does nothing and returns false when
   * either is held, by this process or another.
   */
  private fun withExclusive(name: String, block: () -> Unit): Boolean {
    val held = mutableListOf<Pair<RandomAccessFile, FileLock>>()
    try {
      for (file in listOf(lockFile(name), inUseFile(name))) {
        val channel = RandomAccessFile(file, "rw")
        val lock =
          try {
            channel.channel.tryLock()
          } catch (e: OverlappingFileLockException) {
            null
          }
        if (lock == null) {
          channel.close()
          return false
        }
        held += channel to lock
      }
      block()
      return true
    } finally {
      for ((channel, lock) in held.asReversed()) {
        runCatching { lock.release() }
        channel.close()
      }
    }
  }

  private fun apkDirs(): List<File> =
    root.listFiles { f -> f.isDirectory && f.name.length == SHA256_HEX_LENGTH }.orEmpty().toList()

  private fun sourceOf(apkDir: File): String? =
    File(apkDir, SOURCE_TXT).takeIf { it.isFile }?.readText()?.trim()

  private fun sizeOf(dir: File): Long = dir.walk().filter { it.isFile }.sumOf { it.length() }

  private fun lockFile(name: String) = File(root, "$name.lock")

  private fun inUseFile(name: String) = File(root, "$name.inuse")

  companion object {
    const val CLASSES_JAR = "classes.jar"
    const val SUMMARY_JSON = "conversion.json"
    const val SOURCE_TXT = "source.txt"
    const val DEFAULT_KEEP_APKS = 8
    /** A converted jar is about as large as its APK's dex; Home Assistant's is 96 MB. */
    const val DEFAULT_MAX_BYTES = 2L shl 30
    private const val SHA256_HEX_LENGTH = 64

    /**
     * What the stamp covers of the simulator host jar: the converter's own classes, and the shared
     * library classes the simulator provides, which the converter drops from the APK
     * (`ApkClassConverter.SHARED_LIBRARY_PACKAGES`).
     */
    const val CONVERTER_CLASSES = "ee/schimke/composeai/simulator/ApkClassConverter"
    val SHARED_LIBRARY_PACKAGES = listOf("com/google/android/wearable/", "com/google/wear/")

    /** The index the converter writes beside [jar] (`ApkClassConverter.indexOf`). */
    fun indexOf(jar: File): File = File(jar.parentFile, "${jar.name}.index")

    /**
     * A stamp for the converter: the converter's own classes and the shared-library classes in the
     * simulator host jar (so a change to the rest of the simulator does not reconvert every APK),
     * every other jar it runs from (dex2jar), and the platform jar's name and size (its content is
     * large and changes only with a new platform).
     */
    fun converterStamp(converterJars: List<File>, platformJar: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      for (jar in converterJars.sortedBy { it.name }) {
        digest.update(jar.name.toByteArray())
        if (!jar.name.startsWith("simulator-host") || !digestConverterClasses(jar, digest)) {
          jar.inputStream().use { digest.update(it) }
        }
      }
      digest.update("${platformJar.parentFile?.name}/${platformJar.length()}".toByteArray())
      return digest.digest().toHex().take(16)
    }

    /** Digests [jar]'s converter classes, by name; false when it holds none. */
    private fun digestConverterClasses(jar: File, digest: MessageDigest): Boolean {
      val zip = runCatching { ZipFile(jar) }.getOrNull() ?: return false
      zip.use {
        val entries =
          zip
            .entries()
            .asSequence()
            .filter { entry ->
              entry.name.endsWith(".class") &&
                (entry.name.startsWith(CONVERTER_CLASSES) ||
                  SHARED_LIBRARY_PACKAGES.any { entry.name.startsWith(it) })
            }
            .sortedBy { it.name }
            .toList()
        for (entry in entries) {
          digest.update(entry.name.toByteArray())
          zip.getInputStream(entry).use { digest.update(it) }
        }
        return entries.isNotEmpty()
      }
    }

    fun sha256(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { digest.update(it) }
      return digest.digest().toHex()
    }

    private fun MessageDigest.update(input: java.io.InputStream) {
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        update(buffer, 0, read)
      }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
  }
}
