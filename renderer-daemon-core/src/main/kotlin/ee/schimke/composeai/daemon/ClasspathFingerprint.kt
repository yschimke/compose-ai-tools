package ee.schimke.composeai.daemon

import java.io.File
import java.security.MessageDigest

/**
 * Tier-1 staleness detector — see
 * [DESIGN.md § 8](../../../../../../docs/daemon/DESIGN.md#tier-1--project-fundamentally-changed)
 * and the protocol's [`classpathDirty`](../../../../../../docs/daemon/PROTOCOL.md#classpathdirty)
 * notification.
 *
 * The daemon holds **two** fingerprints:
 *
 * - **Cheap signal hash.** SHA-256 over a small fixed set of build files (`libs.versions.toml`,
 *   every `build.gradle.kts` / `build.gradle`, `settings.gradle.kts`, `gradle.properties`,
 *   `local.properties`). Computed at startup; recomputed on every `fileChanged` notification whose
 *   `kind` is `classpath`. Designed to take a few ms even on a large project — we read the bytes
 *   (not just `mtime`) so an editor that touches a file without changing content doesn't trigger a
 *   daemon respawn.
 * - **Authoritative classpath hash.** SHA-256 over `(absolutePath, length, lastModified)` of every
 *   resolved classpath JAR / class-dir on the daemon JVM's `-cp`. Computed once at startup and
 *   re-checked **only** when the cheap hash drifted — the resolved classpath rarely actually
 *   changes even when build files do (a comment edit in `build.gradle.kts` is the canonical
 *   false-alarm).
 *
 * Both files-set inputs are parameters, **not** hard-coded paths: the gradle plugin's
 * `composePreviewDaemonStart` task is the sole authority on which paths are the cheap-signal set,
 * and it surfaces them via the [CHEAP_SIGNAL_FILES_PROP] sysprop colon-delimited. This module is
 * renderer-agnostic and stays so — hard-coding `gradle/libs.versions.toml` here would tie the
 * daemon to the project layout.
 *
 * **Behaviour at startup.** [snapshot] computes both hashes once. Any [Snapshot] persisted from
 * that point becomes the reference. Subsequent calls to [cheapHash] / [classpathHash] re-read the
 * disk and produce a fresh value to compare against the stored snapshot.
 *
 * **Missing files.** A path that doesn't exist on disk contributes its absolute path string to the
 * hash but no bytes / size / mtime — i.e. the hash is well-defined whether the file exists or not,
 * but creating / deleting a path in the cheap-signal set changes the hash. The classpath hash uses
 * `(path, 0L, 0L)` for missing files, same idea.
 *
 * **Thread-safety.** All methods are pure — they take the input file lists from the constructor and
 * read the filesystem at call time. Callers (the daemon's `JsonRpcServer.handleFileChanged`) are
 * responsible for serialising recomputation; in practice this happens on the single read thread, so
 * no locking is needed.
 */
class ClasspathFingerprint(
  /**
   * Cheap-signal file set. The daemon recomputes [cheapHash] on every `fileChanged` notification
   * tagged `kind: "classpath"` — so the set should stay small (single-digit files) and stable for
   * the lifetime of the daemon. `libs.versions.toml`, `*.gradle.kts`, `settings.gradle.kts`,
   * `gradle.properties`, `local.properties` per DESIGN § 8.
   */
  val cheapSignalFiles: List<File>,
  /**
   * Authoritative classpath. Hashed over `(absolutePath, length, lastModified)` per entry — bytes
   * are intentionally NOT read (a 200-JAR Compose classpath would cost hundreds of ms per check).
   * The mtime/size pair is sufficient for a build-system-driven classpath: gradle's resolved
   * artefact cache touches the JAR's mtime when the version changes.
   */
  val classpathEntries: List<File>,
) {

  /**
   * SHA-256 hex over the **bytes** of every file in [cheapSignalFiles]. Order is preserved
   * (insertion order in the constructor list); a file that doesn't exist contributes its absolute
   * path string only.
   */
  fun cheapHash(): String {
    val md = MessageDigest.getInstance(SHA_256)
    for (file in cheapSignalFiles) {
      md.update(file.absolutePath.toByteArray(Charsets.UTF_8))
      md.update(0)
      if (file.isFile) {
        // Read in 64KB chunks so a hypothetical multi-MB build script doesn't blow the heap. In
        // practice cheap-signal files are < 100KB each and the whole set fits in one buffer.
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { stream ->
          while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
          }
        }
      }
      md.update(MARKER)
    }
    return md.digest().toHexString()
  }

  /**
   * SHA-256 hex over `(absolutePath, length, lastModified)` for every entry in [classpathEntries].
   * Cheap (no byte reads) so this is safe to recompute on every cheap-signal hit. Missing files
   * contribute `(path, 0L, 0L)` — i.e. removal of a JAR from the resolved classpath changes the
   * hash; a JAR being temporarily missing during a Gradle re-resolve would too, but Gradle
   * re-resolves are atomic from the daemon's POV (it doesn't watch them mid-flight).
   */
  fun classpathHash(): String {
    val md = MessageDigest.getInstance(SHA_256)
    for (file in classpathEntries) {
      md.update(file.absolutePath.toByteArray(Charsets.UTF_8))
      md.update(0)
      val length = if (file.exists()) file.length() else 0L
      val mtime = if (file.exists()) file.lastModified() else 0L
      md.update(longToBytes(length))
      md.update(longToBytes(mtime))
      md.update(MARKER)
    }
    return md.digest().toHexString()
  }

  /** Composite snapshot — the daemon stores this at startup as the reference. */
  fun snapshot(): Snapshot = Snapshot(cheapHash = cheapHash(), classpathHash = classpathHash())

  /** Pair of hashes recorded at a single point in time. */
  data class Snapshot(val cheapHash: String, val classpathHash: String)

  companion object {
    /**
     * Sysprop the gradle plugin's `composePreviewDaemonStart` task sets to a colon-delimited
     * (`File.pathSeparator`) list of absolute paths in the cheap-signal file set. Read by
     * [parseCheapSignalFilesSysprop] at daemon startup.
     */
    const val CHEAP_SIGNAL_FILES_PROP: String = "composeai.daemon.cheapSignalFiles"

    /** SHA-256 hash size — used by tests to assert hex-string length. */
    const val SHA_256_HEX_LENGTH: Int = 64

    private const val SHA_256: String = "SHA-256"
    private const val BUFFER_SIZE: Int = 64 * 1024
    private val MARKER: ByteArray = byteArrayOf(0xFE.toByte(), 0xED.toByte())

    /**
     * Reads the [CHEAP_SIGNAL_FILES_PROP] system property and splits it on [File.pathSeparator]
     * into absolute [File] paths. Empty / unset returns an empty list — the daemon then runs with
     * "no cheap signal" and the Tier-1 trigger never fires (classpath changes are observed only on
     * daemon respawn). That's the pre-B2.1 fallback and it's what the harness's fake-mode scenarios
     * deliberately use so they don't drift on B2.1's Tier-1 logic.
     */
    fun parseCheapSignalFilesSysprop(
      value: String? = System.getProperty(CHEAP_SIGNAL_FILES_PROP)
    ): List<File> {
      if (value.isNullOrBlank()) return emptyList()
      return value.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it.trim()) }
    }

    private fun longToBytes(v: Long): ByteArray {
      val out = ByteArray(8)
      var x = v
      for (i in 7 downTo 0) {
        out[i] = (x and 0xFF).toByte()
        x = x ushr 8
      }
      return out
    }

    private fun ByteArray.toHexString(): String {
      val sb = StringBuilder(size * 2)
      for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0F])
      }
      return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
  }
}
