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
 *   itself authored one, which [moduleOwnFileResources] always retains.
 * - The baked catalog PNGs are rendered from the **full** APK *before* packing, so they are
 *   unaffected. The pruned APK feeds only the live daemon re-render, which degrades any miss to a
 *   `⟦res 0x…⟧` placeholder rather than throwing (see the daemon's `PlaceholderFallbackResources`),
 *   so even an over-aggressive drop is graceful, never a crash or a wrong sticker.
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
   * @param moduleOwnFileResources resource identities the module declares in its own
   *   `src/<sourceSet>/res`, each `"<typeBase>/<name>"` (e.g. `"drawable/ic_logo"`); always
   *   retained.
   */
  fun prune(apkBytes: ByteArray, moduleOwnFileResources: Set<String>): Result {
    val out = ByteArrayOutputStream(apkBytes.size)
    var dropped = 0
    var saved = 0L
    ZipOutputStream(out).use { zos ->
      ZipInputStream(ByteArrayInputStream(apkBytes)).use { zis ->
        while (true) {
          val entry = zis.nextEntry ?: break
          val data = zis.readBytes()
          if (shouldDrop(entry.name, moduleOwnFileResources)) {
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

  private fun shouldDrop(entryName: String, moduleOwn: Set<String>): Boolean {
    if (!entryName.startsWith("res/")) return false
    val rest = entryName.removePrefix("res/")
    val slash = rest.indexOf('/')
    if (slash < 0) return false
    val typeBase = rest.substring(0, slash).substringBefore('-')
    if (typeBase !in PRUNABLE_TYPE_BASES) return false
    return "$typeBase/${resourceNameOf(rest.substring(slash + 1))}" !in moduleOwn
  }

  /**
   * Resource name from a packed file entry: drop the extension, and normalise AAPT-generated
   * animated-vector split names (`$base__12.xml`) back to their `base` so a module-authored
   * animated vector is matched against [moduleOwnFileResources] and kept.
   */
  internal fun resourceNameOf(fileName: String): String {
    val noExt = fileName.substringBefore('.')
    return if (noExt.startsWith('$')) noExt.drop(1).substringBefore("__") else noExt
  }
}
