package ee.schimke.composeai.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Drops merged-dependency (AAR) **file** resources a Compose render never inflates from a bundle's
 * packed resource APK (`android/resources.ap_`).
 *
 * Safe because:
 * - `resources.arsc` is left byte-identical; only unreferenced file bytes are dropped, and the
 *   dangling table entries are never looked up.
 * - Only View-system file types ([PRUNABLE_TYPE_BASES]) are candidates, and only those positively
 *   attributed to an AAR ([prunableFileResources]); an empty set drops nothing.
 * - A few dependency resources are always kept (AppCompat loads `drawable/abc_vector_test` to
 *   validate VectorDrawableCompat).
 * - Baked PNGs are rendered from the full APK; the pruned one only feeds live daemon re-renders.
 *
 * Over-pruning is fatal, not graceful: Compose loads vectors via `Resources.getValue` + `getXml`,
 * which `PlaceholderFallbackResources` doesn't cover, so a pruned vector aborts the render with
 * `NotFoundException` (#3260).
 *
 * ## Why a positive drop-set
 *
 * A retain-set made "couldn't classify" equal "droppable", and both production failures were
 * misclassification (#3260: retain-set covered only the rendering module; #3299: an unreadable
 * blame file read as "authors nothing"). Now only resources positively attributed to a third-party
 * AAR are dropped; anything else stays, so being wrong costs bundle size, not a dead render.
 *
 * For a Compose-only catalog this drops ~140 KB of AAR drawables and layouts with identical
 * renders.
 */
internal object AndroidResourcePruner {

  /** `res/<type>` bases a Compose render never inflates from a compiled file. */
  private val PRUNABLE_TYPE_BASES = setOf("drawable", "layout", "anim", "animator", "mipmap")

  data class Result(val bytes: ByteArray, val droppedEntries: Int, val bytesSaved: Long)

  /**
   * Re-emits [apkBytes] without the prunable resources.
   *
   * @param prunableFileResources ids (`"<typeBase>/<name>"`, e.g. `"drawable/abc_ic_menu"`)
   *   attributed to a third-party AAR and to no project; everything else is retained. See
   *   [MergedResourceOwnership].
   */
  fun prune(apkBytes: ByteArray, prunableFileResources: Set<String>): Result {
    val out = ByteArrayOutputStream(apkBytes.size)
    var dropped = 0
    var saved = 0L
    ZipOutputStream(out).use { zos ->
      ZipInputStream(ByteArrayInputStream(apkBytes)).use { zis ->
        while (true) {
          val entry = zis.nextEntry ?: break
          val data = zis.readBytes()
          if (shouldDrop(entry.name, prunableFileResources)) {
            dropped++
            saved += data.size.toLong()
            continue
          }
          // Keep each entry's storage method: AAPT2 stores `resources.arsc` uncompressed for
          // Robolectric, and STORED entries need size + CRC set explicitly.
          val copy = ZipEntry(entry.name)
          copy.method = entry.method
          if (entry.method == ZipEntry.STORED) {
            copy.size = data.size.toLong()
            copy.compressedSize = data.size.toLong()
            copy.crc = CRC32().apply { update(data) }.value
          }
          zos.putNextEntry(copy)
          zos.write(data)
          zos.closeEntry()
        }
      }
    }
    return Result(out.toByteArray(), dropped, saved)
  }

  private fun shouldDrop(entryName: String, prunable: Set<String>): Boolean {
    if (!entryName.startsWith("res/")) return false
    val rest = entryName.removePrefix("res/")
    val slash = rest.indexOf('/')
    if (slash < 0) return false
    val typeBase = rest.substring(0, slash).substringBefore('-')
    if (typeBase !in PRUNABLE_TYPE_BASES) return false
    val key = "$typeBase/${resourceNameOf(rest.substring(slash + 1))}"
    return key in prunable && key !in REQUIRED_DEPENDENCY_RESOURCES
  }

  /**
   * Resource name from a packed file entry: extension dropped, and AAPT animated-vector split names
   * (`$base__12.xml`) normalised to `base`, matching the blame identity.
   */
  internal fun resourceNameOf(fileName: String): String {
    val noExt = fileName.substringBefore('.')
    return if (noExt.startsWith('$')) noExt.drop(1).substringBefore("__") else noExt
  }

  private val REQUIRED_DEPENDENCY_RESOURCES = setOf("drawable/abc_vector_test")
}
