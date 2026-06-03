package ee.schimke.composeai.tui

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.TemporaryDirectory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.source

/**
 * Extracts the two products a self-contained preview bundle needs to drive its own daemon — the
 * user `classes/app.jar` (unpacked into a `classes/` dir) and the `previews.json` discovery
 * manifest — out of the PNG+ZIP polyglot, with no Gradle project involved.
 *
 * The polyglot zip-slice routine is shared with [BundlePngMetadata.extractZipBytes] rather than
 * re-implemented; the per-entry unpack mirrors `cli`'s `BundleDaemonCommand.expandJarBytesSafely`,
 * including its Zip-Slip guard (a hostile entry name resolving outside the target dir is rejected).
 */
object BundleExtractor {

  /** The on-disk products of [extract]: where the daemon reads classes + the discovery manifest. */
  data class Extracted(val workDir: File, val classesDir: File, val previewsJson: File)

  /**
   * Extract [bundle] into a fresh temp [workDir]. Returns null when [bundle] is not a readable
   * polyglot or is missing `classes/app.jar` / `previews.json` (i.e. a plain PNG, not a bundle) —
   * callers fall back to showing the static seed image.
   *
   * The caller owns [Extracted.workDir]; delete it when the session closes.
   */
  fun extract(bundle: File, fileSystem: FileSystem = SystemFileSystem): Extracted? {
    val zipBytes = BundlePngMetadata.extractZipBytes(bundle) ?: return null

    val workDir =
      (TemporaryDirectory / "compose-preview-tui-bundle-${UUID.randomUUID()}")
        .also { fileSystem.createDirectories(it) }
        .toFile()
    val classesDir = File(workDir, "classes").apply { mkdirs() }
    val previewsJson = File(workDir, "previews.json")

    var sawAppJar = false
    var sawPreviews = false
    try {
      ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
        while (true) {
          val entry = zin.nextEntry ?: break
          when (entry.name) {
            "previews.json" -> {
              fileSystem.write(previewsJson.path.toPath()) { write(zin.readBytes()) }
              sawPreviews = true
            }
            "classes/app.jar" -> {
              expandJarSafely(zin.readBytes(), classesDir, fileSystem)
              sawAppJar = true
            }
          }
          zin.closeEntry()
        }
      }
    } catch (_: Throwable) {
      workDir.deleteRecursively()
      return null
    }

    if (!sawAppJar || !sawPreviews) {
      workDir.deleteRecursively()
      return null
    }
    return Extracted(workDir = workDir, classesDir = classesDir, previewsJson = previewsJson)
  }

  /**
   * Unpack [appJarBytes] into [targetDir], rejecting Zip-Slip (an entry whose resolved path escapes
   * [targetDir]). Same shape as `BundleDaemonCommand.expandJarBytesSafely`; duplicated rather than
   * shared to keep `:tui-cli` off `:cli`'s classpath.
   */
  private fun expandJarSafely(
    appJarBytes: ByteArray,
    targetDir: File,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val targetPath = targetDir.canonicalFile.toPath()
    ZipInputStream(ByteArrayInputStream(appJarBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        // Resolve + normalize the entry against the target and verify containment via
        // Path.startsWith — the form CodeQL's java/zipslip recognizes as sanitization (the prior
        // canonicalFile + String.startsWith guard was equally safe but flagged as a false
        // positive).
        val resolved = targetPath.resolve(entry.name).normalize()
        if (!resolved.startsWith(targetPath)) {
          throw SecurityException("bundle app jar entry escapes target dir: ${entry.name}")
        }
        val candidate = resolved.toFile()
        if (entry.isDirectory) {
          candidate.mkdirs()
        } else {
          candidate.parentFile?.mkdirs()
          fileSystem.write(candidate.path.toPath()) { writeAll(zin.source()) }
        }
        zin.closeEntry()
      }
    }
  }
}
