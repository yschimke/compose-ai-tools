package ee.schimke.composeai.cli

import java.io.File
import java.io.OutputStream
import java.util.Collections

/**
 * Tees an [OutputStream] (Gradle stdout/stderr) and scans the byte stream for Kotlin's incremental
 * compiler "Storage for [...] is already registered" failure. The marker is emitted by
 * `FilePageCache.registerPagedFileStorage` when an in-process Kotlin compiler daemon retains a
 * cache-file registration across builds (upstream KT-59321 / KT-55435).
 *
 * The Gradle build typically still reports BUILD SUCCESSFUL because Kotlin falls back to
 * non-incremental, but the on-disk `caches-jvm` directory can be left inconsistent and subsequent
 * `compileKotlin UP-TO-DATE` runs will not reflect the user's edit. [GradleConnection] uses the
 * detected directories to drive a stop-daemon + wipe-cache + retry recovery pass — see issue #1493.
 *
 * Detection is path-based: the marker carries an absolute path ending in
 * `caches-jvm/jvm/kotlin/source-to-classes.tab` (or a sibling `.tab` file). We walk parents until
 * we hit a directory named `caches-jvm` and record that as the wipe target.
 */
class KotlinIcStorageDetector(private val delegate: OutputStream) : OutputStream() {
  private val lineBuffer = StringBuilder()
  private val detected = Collections.synchronizedSet(linkedSetOf<File>())

  fun detectedCachesJvmDirs(): Set<File> = synchronized(detected) { detected.toSet() }

  override fun write(b: Int) {
    delegate.write(b)
    handleByte(b.toByte())
  }

  override fun write(b: ByteArray) {
    delegate.write(b)
    for (byte in b) handleByte(byte)
  }

  override fun write(b: ByteArray, off: Int, len: Int) {
    delegate.write(b, off, len)
    for (i in off until off + len) handleByte(b[i])
  }

  override fun flush() {
    delegate.flush()
  }

  override fun close() {
    if (lineBuffer.isNotEmpty()) {
      processLine(lineBuffer.toString())
      lineBuffer.clear()
    }
    delegate.close()
  }

  private fun handleByte(b: Byte) {
    val c = (b.toInt() and 0xFF).toChar()
    if (c == '\n' || c == '\r') {
      if (lineBuffer.isNotEmpty()) {
        processLine(lineBuffer.toString())
        lineBuffer.clear()
      }
    } else {
      lineBuffer.append(c)
      // Bound the buffer so a pathological no-newline stream can't OOM us. The
      // marker line is < 1 KiB in practice; 16 KiB leaves ample headroom and
      // matches a typical compiler diagnostic length.
      if (lineBuffer.length > MAX_LINE_LENGTH) {
        processLine(lineBuffer.toString())
        lineBuffer.clear()
      }
    }
  }

  private fun processLine(line: String) {
    val match = STORAGE_REGEX.find(line) ?: return
    val dir = findCachesJvmAncestor(File(match.groupValues[1])) ?: return
    detected.add(dir)
  }

  companion object {
    private const val MAX_LINE_LENGTH = 16 * 1024
    private val STORAGE_REGEX = Regex("""Storage for \[([^\]]+)] is already registered""")

    /**
     * Walks parents of [path] until a directory named `caches-jvm` is found. Returns null if no
     * such ancestor exists — defensive against future Kotlin error-message refactors that change
     * the cache layout.
     */
    fun findCachesJvmAncestor(path: File): File? {
      var current: File? = path
      while (current != null) {
        if (current.name == "caches-jvm") return current
        current = current.parentFile
      }
      return null
    }
  }
}
