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
 *   itself authored one — or a sibling project module did — which [firstPartyFileResources] always
 *   retains.
 * - The baked catalog PNGs are rendered from the **full** APK *before* packing, so they are
 *   unaffected. The pruned APK feeds only the live daemon re-render.
 *
 * An over-aggressive drop is **not** graceful, so the retain-set is what carries the safety here.
 * `PlaceholderFallbackResources` degrades a miss to a `⟦res 0x…⟧` placeholder only on the accessors
 * it wraps (`getText` / `getColor` / the dimension family / every `getDrawable*` overload); Compose
 * resolves a vector through `Resources.getValue` + `getXml`, which that wrapper's own kdoc lists as
 * uncovered, so a pruned vector drawable aborts the whole render with `NotFoundException: File
 * res/drawable/<name>.xml from xml type xml resource ID #0x7f…` rather than drawing a magenta box.
 * That is issue #3260: [firstPartyFileResources] used to carry only the rendering module's own
 * `src/<sourceSet>/res`, so a multi-module app whose icons live in sibling project modules had
 * every one of them pruned. The retain-set now comes from AGP's merge-blame file
 * (`MergedResourceOwnership`) and covers the whole build's first-party resources.
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
   * @param firstPartyFileResources resource identities authored anywhere in this build — the
   *   rendering module's own `src/<sourceSet>/res` and every sibling project module's — each
   *   `"<typeBase>/<name>"` (e.g. `"drawable/ic_logo"`); always retained. See
   *   [MergedResourceOwnership], which derives it from AGP's merge-blame file.
   */
  fun prune(apkBytes: ByteArray, firstPartyFileResources: Set<String>): Result {
    val out = ByteArrayOutputStream(apkBytes.size)
    var dropped = 0
    var saved = 0L
    ZipOutputStream(out).use { zos ->
      ZipInputStream(ByteArrayInputStream(apkBytes)).use { zis ->
        while (true) {
          val entry = zis.nextEntry ?: break
          val data = zis.readBytes()
          if (shouldDrop(entry.name, firstPartyFileResources)) {
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

  private fun shouldDrop(entryName: String, firstParty: Set<String>): Boolean {
    if (!entryName.startsWith("res/")) return false
    val rest = entryName.removePrefix("res/")
    val slash = rest.indexOf('/')
    if (slash < 0) return false
    val typeBase = rest.substring(0, slash).substringBefore('-')
    if (typeBase !in PRUNABLE_TYPE_BASES) return false
    return "$typeBase/${resourceNameOf(rest.substring(slash + 1))}" !in firstParty
  }

  /**
   * Resource name from a packed file entry: drop the extension, and normalise AAPT-generated
   * animated-vector split names (`$base__12.xml`) back to their `base` so a module-authored
   * animated vector is matched against [firstPartyFileResources] and kept.
   */
  internal fun resourceNameOf(fileName: String): String {
    val noExt = fileName.substringBefore('.')
    return if (noExt.startsWith('$')) noExt.drop(1).substringBefore("__") else noExt
  }
}
