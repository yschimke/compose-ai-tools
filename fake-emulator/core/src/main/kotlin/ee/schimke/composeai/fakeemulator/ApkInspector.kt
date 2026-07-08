package ee.schimke.composeai.fakeemulator

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * What we can learn from an installed APK **without executing it** — the metadata the fake emulator
 * uses to wire a preview launch to the right component and to know whether the APK even carries
 * `@Preview`s worth discovering.
 *
 * [declaresComposePreviews] is a cheap presence signal: it's `true` when a `classesN.dex` contains
 * the `@Preview` annotation's type descriptor. Enumerating the individual `@Preview` methods (their
 * FQNs, for `--es composable` validation) needs a full DEX annotations walk (dexlib2) and is a
 * documented follow-up — see docs/fake-emulator/README.md.
 */
data class ApkInfo(
  /** `package` attribute of the `<manifest>` element, or `null` if it couldn't be parsed. */
  val packageName: String?,
  val versionCode: Long? = null,
  val versionName: String? = null,
  /** A `classesN.dex` references the Compose `@Preview` annotation descriptor. */
  val declaresComposePreviews: Boolean = false,
  /** Number of `classesN.dex` entries — a `.apk` has ≥1; a bare file (pushed junk) has 0. */
  val dexEntryCount: Int = 0,
  val sizeBytes: Int = 0,
) {
  /** A parseable manifest with at least one DEX is our proxy for "this is a real APK". */
  val looksLikeApk: Boolean
    get() = packageName != null && dexEntryCount > 0
}

/**
 * Reads APK bytes as a ZIP and extracts [ApkInfo]: the package name (and version) from the binary
 * `AndroidManifest.xml`, and whether any `classesN.dex` carries the Compose `@Preview` annotation.
 * Pure-Kotlin (`java.util.zip` + a small binary-XML reader) so `:fake-emulator-core` stays free of
 * the AAPT / dexlib2 toolchain. Never throws: unparseable input yields an [ApkInfo] with nulls.
 */
object ApkInspector {
  /**
   * MUTF-8 descriptor of `androidx.compose.ui.tooling.preview.Preview`, as stored in DEX strings.
   */
  private val PREVIEW_DESCRIPTOR =
    "Landroidx/compose/ui/tooling/preview/Preview;".toByteArray(Charsets.US_ASCII)

  fun inspect(apk: ByteArray): ApkInfo {
    var manifest: ByteArray? = null
    var dexCount = 0
    var hasPreviews = false
    runCatching {
      ZipInputStream(ByteArrayInputStream(apk)).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          val name = entry.name
          when {
            name == "AndroidManifest.xml" -> manifest = zip.readBytes()
            name.startsWith("classes") && name.endsWith(".dex") -> {
              dexCount++
              val bytes = zip.readBytes()
              if (!hasPreviews && containsBytes(bytes, PREVIEW_DESCRIPTOR)) hasPreviews = true
            }
          }
        }
      }
    }
    val axml = manifest?.let { runCatching { BinaryXml.parseManifest(it) }.getOrNull() }
    return ApkInfo(
      packageName = axml?.packageName,
      versionCode = axml?.versionCode,
      versionName = axml?.versionName,
      declaresComposePreviews = hasPreviews,
      dexEntryCount = dexCount,
      sizeBytes = apk.size,
    )
  }

  /** Naive substring search over bytes — fine for the small, distinctive descriptor we look for. */
  private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty() || haystack.size < needle.size) return false
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return true
    }
    return false
  }
}

/** The handful of `<manifest>` attributes we read out of the binary XML. */
internal data class ManifestAttributes(
  val packageName: String?,
  val versionCode: Long?,
  val versionName: String?,
)

/**
 * A deliberately tiny reader for Android's binary XML (`AXML`) resource format — just enough to
 * pull the `<manifest>` element's `package` / `versionCode` / `versionName` attributes. Implements
 * the `ResChunk` / `ResStringPool` / `ResXMLTree_attrExt` layout from AOSP `ResourceTypes.h`.
 */
internal object BinaryXml {
  private const val RES_STRING_POOL_TYPE = 0x0001
  private const val RES_XML_START_ELEMENT_TYPE = 0x0102
  private const val UTF8_FLAG = 1 shl 8
  private const val TYPE_STRING = 0x03
  private const val NO_ENTRY = 0xFFFFFFFF.toInt()

  fun parseManifest(bytes: ByteArray): ManifestAttributes? {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (bytes.size < 8) return null
    // Outer file header: type(2) headerSize(2) size(4). Chunks follow at headerSize.
    val fileHeaderSize = buf.getShort(2).toInt() and 0xFFFF
    var offset = fileHeaderSize
    var pool: StringPool? = null

    while (offset + 8 <= bytes.size) {
      val type = buf.getShort(offset).toInt() and 0xFFFF
      val chunkSize = buf.getInt(offset + 4)
      if (chunkSize <= 0 || offset + chunkSize > bytes.size) break
      when (type) {
        RES_STRING_POOL_TYPE -> pool = StringPool.parse(buf, offset)
        RES_XML_START_ELEMENT_TYPE -> {
          val strings = pool ?: return null
          val attrs = readStartElement(buf, offset, strings)
          if (attrs != null) return attrs // first start element is <manifest>
        }
      }
      offset += chunkSize
    }
    return null
  }

  private fun readStartElement(
    buf: ByteBuffer,
    chunkOffset: Int,
    pool: StringPool,
  ): ManifestAttributes? {
    // ResXMLTree_node = header(8) + lineNumber(4) + comment(4); attrExt begins at +16.
    val extStart = chunkOffset + 16
    val nameIdx = buf.getInt(extStart + 4)
    val elementName = pool.string(nameIdx)
    if (elementName != "manifest") return null
    val attributeStart = buf.getShort(extStart + 8).toInt() and 0xFFFF
    val attributeSize = buf.getShort(extStart + 10).toInt() and 0xFFFF
    val attributeCount = buf.getShort(extStart + 12).toInt() and 0xFFFF

    var pkg: String? = null
    var versionCode: Long? = null
    var versionName: String? = null
    var attrOffset = extStart + attributeStart
    repeat(attributeCount) {
      // ResXMLTree_attribute: ns(4) name(4) rawValue(4) + Res_value{ size(2) res0(1) type(1)
      // data(4) }
      val name = pool.string(buf.getInt(attrOffset + 4))
      val rawValue = buf.getInt(attrOffset + 8)
      val valueType = buf.get(attrOffset + 15).toInt() and 0xFF
      val data = buf.getInt(attrOffset + 16)
      val stringValue =
        when {
          rawValue != NO_ENTRY -> pool.string(rawValue)
          valueType == TYPE_STRING -> pool.string(data)
          else -> null
        }
      when (name) {
        "package" -> pkg = stringValue
        "versionCode" -> versionCode = (data.toLong() and 0xFFFFFFFFL)
        "versionName" -> versionName = stringValue
      }
      attrOffset += attributeSize
    }
    return ManifestAttributes(pkg, versionCode, versionName)
  }

  /** Decoded `ResStringPool`: resolves a pool index to its UTF-8 / UTF-16 string. */
  private class StringPool(
    private val buf: ByteBuffer,
    private val count: Int,
    private val offsetsBase: Int,
    private val stringsBase: Int,
    private val utf8: Boolean,
  ) {
    fun string(index: Int): String? {
      if (index < 0 || index >= count) return null
      val at = stringsBase + buf.getInt(offsetsBase + index * 4)
      return if (utf8) utf8String(at) else utf16String(at)
    }

    private fun utf8String(at: Int): String {
      // Two varint-u8 lengths (char count, then byte count); we only need the byte count.
      var p = at
      p += lenSkip(p)
      val byteLen = lenU8(p)
      p += lenBytes(p)
      val out = ByteArray(byteLen)
      for (i in 0 until byteLen) out[i] = buf.get(p + i)
      return String(out, Charsets.UTF_8)
    }

    private fun utf16String(at: Int): String {
      var len = buf.getShort(at).toInt() and 0xFFFF
      var p = at + 2
      if (len and 0x8000 != 0) {
        len = ((len and 0x7FFF) shl 16) or (buf.getShort(p).toInt() and 0xFFFF)
        p += 2
      }
      val chars = CharArray(len)
      for (i in 0 until len) chars[i] = buf.getShort(p + i * 2).toInt().toChar()
      return String(chars)
    }

    /** Value of a varint-u8 length at [p]. */
    private fun lenU8(p: Int): Int {
      val b0 = buf.get(p).toInt() and 0xFF
      return if (b0 and 0x80 != 0) ((b0 and 0x7F) shl 8) or (buf.get(p + 1).toInt() and 0xFF)
      else b0
    }

    /** Byte width of the varint-u8 length at [p] (1 or 2). */
    private fun lenBytes(p: Int): Int = if (buf.get(p).toInt() and 0x80 != 0) 2 else 1

    /** Width of the first (char-count) length, to skip to the byte-count length. */
    private fun lenSkip(p: Int): Int = lenBytes(p)

    companion object {
      fun parse(buf: ByteBuffer, chunkOffset: Int): StringPool {
        val headerSize = buf.getShort(chunkOffset + 2).toInt() and 0xFFFF
        val stringCount = buf.getInt(chunkOffset + 8)
        val flags = buf.getInt(chunkOffset + 16)
        val stringsStart = buf.getInt(chunkOffset + 20)
        return StringPool(
          buf = buf,
          count = stringCount,
          offsetsBase = chunkOffset + headerSize,
          stringsBase = chunkOffset + stringsStart,
          utf8 = flags and UTF8_FLAG != 0,
        )
      }
    }
  }
}
