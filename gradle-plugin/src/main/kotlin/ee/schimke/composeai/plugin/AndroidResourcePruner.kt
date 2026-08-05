package ee.schimke.composeai.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Drops merged-dependency (AAR) **file** resources a Compose `@Preview` render never inflates from
 * a bundle's packed unit-test resource APK (`android/resources.ap_`), so a bundle carries only the
 * resources its live daemon re-render can actually use.
 *
 * Why this is safe to do unconditionally:
 * - `resources.arsc` — the compiled table (strings, attrs, dimens, colours, styles) — is left
 *   **byte-for-byte untouched**. This is not resource-table surgery; it only stops shipping file
 *   bytes that nothing resolves. The table keeps its (now dangling) entries, which is invisible
 *   because they are never looked up.
 * - Only View-system file types are candidates ([PRUNABLE_TYPE_BASES]): `drawable`, `layout`,
 *   `anim`, `animator`, `mipmap`. Compose draws from Kotlin — no `R.layout` inflation, no view
 *   animations, no launcher mipmaps — so a Compose render resolves none of these unless the module
 *   itself authored one — or a sibling project module did — and [prunableFileResources] never names
 *   those. An empty set here drops nothing, which is also what [BundlePreviewTask] passes when
 *   ownership metadata is unavailable.
 * - A small set of dependency resources is retained explicitly. AppCompat loads
 *   `drawable/abc_vector_test` at runtime to validate VectorDrawableCompat, so dropping it produces
 *   the misleading "incorrect configuration" exception even though the consumer build is valid.
 * - The baked catalog PNGs are rendered from the **full** APK *before* packing, so they are
 *   unaffected. The pruned APK feeds only the live daemon re-render.
 *
 * An over-aggressive drop is **not** graceful. `PlaceholderFallbackResources` degrades a miss to a
 * `⟦res 0x…⟧` placeholder only on the accessors it wraps (`getText` / `getColor` / the dimension
 * family / every `getDrawable*` overload); Compose resolves a vector through `Resources.getValue` +
 * `getXml`, which that wrapper's own kdoc lists as uncovered, so a pruned vector drawable aborts
 * the whole render with `NotFoundException: File res/drawable/<name>.xml from xml type xml resource
 * ID #0x7f…` rather than drawing a magenta box. That is issue #3260, where a multi-module app's
 * sibling-module icons (`ic_play`) were pruned and every `painterResource` died.
 *
 * ## Why the drop-set is positive, not a retain-set
 *
 * This used to take a *retain*-set and drop everything outside it, which makes "we couldn't
 * classify this resource" and "this resource is a droppable AAR file" the same input. Both of the
 * ways that has failed in production were misclassification, not a wrong retain-set: #3260 (the
 * retain-set covered only the rendering module) and #3299 (an unreadable blame file yielded an
 * empty retain-set that read as "this build authors nothing"). Under a retain-set, every such gap
 * biases toward deleting a resource that is in use — the one outcome that is fatal.
 *
 * So the caller now passes the resources it has **positively attributed to a third-party AAR**
 * ([prunableFileResources]) and nothing else is touched. A resource the blame data doesn't mention,
 * can't be parsed, or attributes to both an AAR and a project module simply stays in the APK. The
 * cost of being wrong is now a slightly larger bundle instead of a dead render, which is the right
 * way round for a saving measured in tens of KB.
 *
 * For a Compose-only catalog (e.g. `:samples:design-catalog-wear-m3`, which authors no file
 * resources of its own) this drops the entire merged AAR drawable/layout payload — ~140 KB of Wear
 * gesture-animation vectors, call icons, and notification templates — while every specimen still
 * renders identically.
 */
internal object AndroidResourcePruner {

  /** `res/<type>` bases a Compose render never inflates from a compiled file. */
  private val PRUNABLE_TYPE_BASES = setOf("drawable", "layout", "anim", "animator", "mipmap")

  data class Result(val bytes: ByteArray, val droppedEntries: Int, val bytesSaved: Long)

  /**
   * Re-emit [apkBytes] without the prunable merged-AAR file resources.
   *
   * @param prunableFileResources resource identities positively attributed to a third-party AAR and
   *   to no project in this build, each `"<typeBase>/<name>"` (e.g. `"drawable/abc_ic_menu"`).
   *   Anything absent from this set is retained. See [MergedResourceOwnership], which derives it
   *   from AGP's merge-blame file.
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
          // Preserve the original storage method — resources.arsc is STORED (uncompressed) in an
          // AAPT2 APK and Robolectric reads it straight out of the zip; keep it that way. STORED
          // entries need size + CRC set explicitly on the target entry.
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
   * Resource name from a packed file entry: drop the extension, and normalise AAPT-generated
   * animated-vector split names (`$base__12.xml`) back to their `base`, so a split is matched
   * against [prune]'s drop-set under the identity the merge blame knows it by.
   */
  internal fun resourceNameOf(fileName: String): String {
    val noExt = fileName.substringBefore('.')
    return if (noExt.startsWith('$')) noExt.drop(1).substringBefore("__") else noExt
  }

  private val REQUIRED_DEPENDENCY_RESOURCES = setOf("drawable/abc_vector_test")
}
