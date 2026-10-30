package ee.schimke.composeai.cli

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * The device an APK is built for, which picks `launch`'s display: a round watch for Wear OS, the
 * AI-glasses display for a Jetpack Compose Glimmer app, a home-space panel for an Android XR app,
 * and a phone for everything else (`docs/SIMULATOR.md` → Form factors).
 *
 * Read from the APK itself, so no `aapt2` is needed:
 * - **Wear**: the manifest's `<uses-feature android:name="android.hardware.type.watch">` with
 *   `required` not `false`, as Play and the Wear launcher read it.
 * - **Glimmer**: an activity with `android:requiredDisplayCategory` of `xr_projected` (projected
 *   onto the glasses' display), or code that references `androidx.xr.glimmer`.
 * - **XR**: a `<uses-feature android:name="android.software.xr.api.*">`, or code that references
 *   Jetpack XR's spatial UI (`androidx.xr.compose`, `androidx.xr.scenecore`).
 */
internal enum class ApkFormFactor(
  /** The `--form-factor` spelling and the `composeai.simulator.formFactor` value. */
  val id: String,
  /** Robolectric resource qualifiers for the default display. */
  val qualifiers: String,
) {
  /** A phone-shaped display that fits on a laptop screen at one device pixel per screen pixel. */
  PHONE("phone", "w360dp-h640dp-hdpi"),

  /**
   * A large round watch, Android Studio's `wearos_large_round` (227dp, 454 px at xhdpi), with the
   * `round` and `watch` qualifiers Wear Compose and the Wear resource system read.
   */
  WEAR("wear", "w227dp-h227dp-round-watch-xhdpi"),

  /**
   * The AI-glasses display the Glimmer previews use: 960×720 px at density 1.0, Glimmer's 30
   * pixels-per-degree calibration (`samples/xr-glimmer`'s `AI_GLASSES_DEVICE_SPEC`).
   */
  GLIMMER("glimmer", "w960dp-h720dp-land-mdpi"),

  /**
   * A home-space panel: Android XR's default 2D panel for an app, 1024×720dp, at density 1.0 so it
   * fits on a laptop screen.
   */
  XR("xr", "w1024dp-h720dp-land-mdpi");

  /**
   * What [inspect] found. [projectedActivity] is the fully qualified class (or alias) of the first
   * activity whose `requiredDisplayCategory` is projected: an AI-glasses app typically pairs a
   * phone launcher with it, and a Glimmer launch starts it instead of the launcher.
   */
  data class Detected(val formFactor: ApkFormFactor, val projectedActivity: String?)

  companion object {
    fun byId(id: String): ApkFormFactor? = entries.firstOrNull { it.id == id.lowercase() }

    /** Detects [apk]'s form factor; [PHONE] when nothing marks it, or it cannot be read. */
    fun detect(apk: File): ApkFormFactor = inspect(apk).formFactor

    /** [apk]'s manifest `package`, or null when it cannot be read. */
    fun packageOf(apk: File): String? = runCatching {
      ZipFile(apk).use { zip ->
        zip.getEntry("AndroidManifest.xml")?.let {
          ManifestSignals.read(zip.getInputStream(it).readBytes()).packageName
        }
      }
    }
      .getOrNull()
      ?.ifEmpty { null }

    /**
     * [apk]'s form factor and its projected (glasses) activity, if it declares one; [PHONE] and no
     * activity when nothing marks it, or it cannot be read.
     */
    fun inspect(apk: File): Detected = runCatching {
      ZipFile(apk).use { zip ->
        val manifest =
          zip.getEntry("AndroidManifest.xml")?.let { zip.getInputStream(it).readBytes() }
        val signals = manifest?.let { ManifestSignals.read(it) } ?: ManifestSignals()
        val dex =
          zip
            .entries()
            .asSequence()
            .filter { DEX.matches(it.name) }
            .map { zip.getInputStream(it).readBytes() }
        Detected(classify(signals, dex), signals.projectedActivities.firstOrNull())
      }
    }
      .getOrDefault(Detected(PHONE, null))

    private val DEX = Regex("""classes\d*\.dex""")

    internal fun classify(signals: ManifestSignals, dex: Sequence<ByteArray>): ApkFormFactor {
      if (WATCH_FEATURE in signals.requiredFeatures) return WEAR
      if (signals.projectedActivities.isNotEmpty()) return GLIMMER
      val xrFeature = signals.features.any { it.startsWith(XR_FEATURE_PREFIX) }
      var glimmer = false
      var spatial = false
      for (bytes in dex) {
        if (!glimmer && contains(bytes, GLIMMER_TYPE)) glimmer = true
        if (!spatial && (contains(bytes, XR_COMPOSE_TYPE) || contains(bytes, SCENECORE_TYPE))) {
          spatial = true
        }
        if (glimmer) break
      }
      return when {
        glimmer -> GLIMMER
        xrFeature || spatial -> XR
        else -> PHONE
      }
    }

    const val WATCH_FEATURE = "android.hardware.type.watch"
    const val XR_FEATURE_PREFIX = "android.software.xr.api."
    /** Jetpack XR's two spellings of the projected (glasses) display category. */
    val XR_PROJECTED = setOf("android.hardware.display.category.XR_PROJECTED", "xr_projected")
    private val GLIMMER_TYPE = "Landroidx/xr/glimmer/".toByteArray()
    private val XR_COMPOSE_TYPE = "Landroidx/xr/compose/".toByteArray()
    private val SCENECORE_TYPE = "Landroidx/xr/scenecore/".toByteArray()

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
      if (needle.isEmpty()) return true
      val first = needle[0]
      val last = haystack.size - needle.size
      var i = 0
      outer@ while (i <= last) {
        if (haystack[i] != first) {
          i++
          continue
        }
        for (j in 1 until needle.size) {
          if (haystack[i + j] != needle[j]) {
            i++
            continue@outer
          }
        }
        return true
      }
      return false
    }
  }
}

/**
 * What [ApkFormFactor] reads from a compiled (binary XML) `AndroidManifest.xml`: every
 * `uses-feature` name, those not marked `required="false"`, and the activities (or aliases) whose
 * `requiredDisplayCategory` is projected, fully qualified against the manifest's `package`.
 */
internal data class ManifestSignals(
  val features: Set<String> = emptySet(),
  val requiredFeatures: Set<String> = emptySet(),
  val projectedActivities: List<String> = emptyList(),
  /** The manifest's `package`; empty when it has none. */
  val packageName: String = "",
) {
  companion object {
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val UTF8_FLAG = 0x100

    private const val ATTR_NAME = 0x01010003
    private const val ATTR_REQUIRED = 0x0101028e

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12

    /**
     * Walks the chunks of a binary XML file. Attributes are matched by name or by resource id
     * (through the resource map), so a shrunk manifest with stripped attribute names still reads.
     */
    fun read(bytes: ByteArray): ManifestSignals {
      val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val features = linkedSetOf<String>()
      val required = linkedSetOf<String>()
      val projected = mutableListOf<String>()
      var packageName = ""
      var strings: List<String> = emptyList()
      var resourceIds = IntArray(0)
      var offset = buffer.getShort(2).toInt() and 0xffff // the file header's size
      while (offset + 8 <= bytes.size) {
        val type = buffer.getShort(offset).toInt() and 0xffff
        val headerSize = buffer.getShort(offset + 2).toInt() and 0xffff
        val size = buffer.getInt(offset + 4)
        if (size <= 0 || offset + size > bytes.size) break
        when (type) {
          RES_STRING_POOL_TYPE -> strings = stringPool(buffer, offset)
          RES_XML_RESOURCE_MAP_TYPE ->
            resourceIds =
              IntArray((size - headerSize) / 4) { buffer.getInt(offset + headerSize + it * 4) }
          RES_XML_START_ELEMENT_TYPE -> {
            val ext = offset + headerSize
            val element = strings.getOrNull(buffer.getInt(ext + 4)) ?: ""
            if (
              element == "manifest" ||
                element == "uses-feature" ||
                element == "activity" ||
                element == "activity-alias"
            ) {
              val attributeStart = buffer.getShort(ext + 8).toInt() and 0xffff
              val attributeSize = buffer.getShort(ext + 10).toInt() and 0xffff
              val count = buffer.getShort(ext + 12).toInt() and 0xffff
              var name: String? = null
              var isRequired = true
              var category: String? = null
              var manifestPackage: String? = null
              for (i in 0 until count) {
                val at = ext + attributeStart + i * attributeSize
                val nameIndex = buffer.getInt(at + 4)
                val id = resourceIds.getOrElse(nameIndex) { 0 }
                val attribute = strings.getOrNull(nameIndex) ?: ""
                val raw = buffer.getInt(at + 8)
                val dataType = bytes[at + 15].toInt() and 0xff
                val data = buffer.getInt(at + 16)
                val text =
                  when {
                    raw >= 0 -> strings.getOrNull(raw)
                    dataType == TYPE_STRING -> strings.getOrNull(data)
                    else -> null
                  }
                when {
                  id == ATTR_NAME || attribute == "name" -> name = text
                  id == ATTR_REQUIRED || attribute == "required" ->
                    isRequired =
                      if (dataType == TYPE_INT_BOOLEAN) data != 0 else text?.toBoolean() ?: true
                  attribute == "requiredDisplayCategory" -> category = text
                  attribute == "package" -> manifestPackage = text
                }
              }
              if (element == "uses-feature" && name != null) {
                features += name
                if (isRequired) required += name
              }
              if (element == "manifest") packageName = manifestPackage ?: ""
              if (
                element != "uses-feature" && name != null && category in ApkFormFactor.XR_PROJECTED
              ) {
                projected += qualify(packageName, name)
              }
            }
          }
        }
        offset += size
      }
      return ManifestSignals(features, required, projected, packageName)
    }

    /** An activity name as the manifest writes it (`.Glasses`, `Glasses`, or qualified). */
    private fun qualify(packageName: String, name: String): String =
      when {
        name.startsWith(".") -> packageName + name
        '.' !in name && packageName.isNotEmpty() -> "$packageName.$name"
        else -> name
      }

    private fun stringPool(buffer: ByteBuffer, chunk: Int): List<String> {
      val count = buffer.getInt(chunk + 8)
      val flags = buffer.getInt(chunk + 16)
      val stringsStart = buffer.getInt(chunk + 20)
      val headerSize = buffer.getShort(chunk + 2).toInt() and 0xffff
      val utf8 = flags and UTF8_FLAG != 0
      return List(count) { i ->
        val at = chunk + stringsStart + buffer.getInt(chunk + headerSize + i * 4)
        if (utf8) utf8String(buffer, at) else utf16String(buffer, at)
      }
    }

    private fun utf8String(buffer: ByteBuffer, start: Int): String {
      var at = start
      // UTF-16 length, then UTF-8 length; each one byte, or two with the high bit set.
      at += if (buffer.get(at).toInt() and 0x80 != 0) 2 else 1
      var length = buffer.get(at).toInt() and 0xff
      if (length and 0x80 != 0) {
        length = ((length and 0x7f) shl 8) or (buffer.get(at + 1).toInt() and 0xff)
        at += 2
      } else {
        at += 1
      }
      val bytes = ByteArray(length) { buffer.get(at + it) }
      return String(bytes, Charsets.UTF_8)
    }

    private fun utf16String(buffer: ByteBuffer, start: Int): String {
      var at = start
      var length = buffer.getShort(at).toInt() and 0xffff
      if (length and 0x8000 != 0) {
        length = ((length and 0x7fff) shl 16) or (buffer.getShort(at + 2).toInt() and 0xffff)
        at += 4
      } else {
        at += 2
      }
      val chars = CharArray(length) { buffer.getShort(at + it * 2).toInt().toChar() }
      return String(chars)
    }
  }
}
