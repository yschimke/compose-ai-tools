package ee.schimke.composeai.plugin

import java.io.File
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Extracts every `@drawable/...` and `@mipmap/...` reference from an `AndroidManifest.xml`'s
 * icon-bearing attributes (`android:icon`, `android:roundIcon`, `android:logo`, `android:banner`)
 * across the supported component tags. Output rows are independent — `<application>` referencing
 * `@mipmap/ic_launcher` for both `android:icon` and `android:roundIcon` produces two
 * [ManifestReference] rows so downstream tooling (CodeLens, resource grid) can label each
 * separately.
 *
 * Component-name resolution uses [packageName] when the `android:name` value starts with `.` (the
 * common short-form). Manifest placeholders (`${applicationId}`) are passed through verbatim — the
 * caller can hand the *merged* manifest in to get them resolved.
 */
object ManifestReferenceExtractor {

  private val ICON_ATTRIBUTES = setOf("icon", "roundIcon", "logo", "banner")

  private val COMPONENT_TAGS =
    setOf("application", "activity", "activity-alias", "service", "receiver", "provider")

  private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

  private val factory: XMLInputFactory =
    XMLInputFactory.newFactory().apply {
      setProperty(XMLInputFactory.SUPPORT_DTD, false)
      setProperty("javax.xml.stream.isSupportingExternalEntities", false)
    }

  /**
   * Parses [file] and returns the manifest references it contains. [source] is recorded verbatim on
   * each row and should be the path the caller wants surfaced to tooling (typically module-relative
   * — e.g. `src/main/AndroidManifest.xml`).
   */
  fun extract(file: File, source: String, packageName: String? = null): List<ManifestReference> =
    file.inputStream().use { extract(it, source, packageName) }

  fun extract(
    input: InputStream,
    source: String,
    packageName: String? = null,
  ): List<ManifestReference> {
    val reader =
      try {
        factory.createXMLStreamReader(input)
      } catch (_: XMLStreamException) {
        return emptyList()
      }
    val out = mutableListOf<ManifestReference>()
    var resolvedPackage = packageName
    try {
      while (reader.hasNext()) {
        val event = reader.next()
        if (event != XMLStreamConstants.START_ELEMENT) continue
        when (reader.localName) {
          "manifest" -> {
            // Source manifests carry `package="..."`; merged manifests have it stripped (the
            // applicationId lives in the AGP build artifacts instead). When present we use it to
            // resolve `.RelativeName` activity refs into FQNs.
            if (resolvedPackage == null) {
              resolvedPackage = readAttribute(reader, namespaceUri = "", localName = "package")
            }
          }
          in COMPONENT_TAGS -> readComponent(reader, resolvedPackage, source, out)
        }
      }
    } catch (_: XMLStreamException) {
      // Truncated / malformed manifests — return what we collected so far rather than erroring.
    } finally {
      try {
        reader.close()
      } catch (_: XMLStreamException) {
        // ignore
      }
    }
    return out
  }

  private fun readComponent(
    reader: XMLStreamReader,
    packageName: String?,
    source: String,
    out: MutableList<ManifestReference>,
  ) {
    val componentKind = reader.localName
    val rawName = readAttribute(reader, ANDROID_NS, "name")
    val componentName =
      if (componentKind == "application") null else resolveComponentName(rawName, packageName)
    for (attr in ICON_ATTRIBUTES) {
      val value = readAttribute(reader, ANDROID_NS, attr) ?: continue
      val parsed = parseResourceReference(value) ?: continue
      out +=
        ManifestReference(
          source = source,
          componentKind = componentKind,
          componentName = componentName,
          attributeName = "android:$attr",
          resourceType = parsed.first,
          resourceName = parsed.second,
        )
    }
  }

  private fun readAttribute(
    reader: XMLStreamReader,
    namespaceUri: String,
    localName: String,
  ): String? {
    for (i in 0 until reader.attributeCount) {
      val ns = reader.getAttributeNamespace(i) ?: ""
      val name = reader.getAttributeLocalName(i)
      if (ns == namespaceUri && name == localName) return reader.getAttributeValue(i)
    }
    return null
  }

  private fun resolveComponentName(rawName: String?, packageName: String?): String? {
    if (rawName.isNullOrEmpty()) return null
    return when {
      rawName.startsWith(".") -> packageName?.let { it + rawName } ?: rawName
      !rawName.contains(".") -> packageName?.let { "$it.$rawName" } ?: rawName
      else -> rawName
    }
  }

  /**
   * Parses `@drawable/foo` or `@mipmap/bar` (with optional `@android:type/` namespace) into `(type,
   * name)`. Returns `null` for theme refs (`?attr/...`), framework drawables
   * (`@android:drawable/...`), and anything that isn't a drawable/mipmap reference.
   */
  internal fun parseResourceReference(value: String): Pair<String, String>? {
    if (!value.startsWith("@")) return null
    val withoutPrefix = value.removePrefix("@").removePrefix("+")
    val slashIdx = withoutPrefix.indexOf('/')
    if (slashIdx == -1) return null
    val type = withoutPrefix.substring(0, slashIdx)
    val name = withoutPrefix.substring(slashIdx + 1)
    // Skip `@android:drawable/...` / `@*android:drawable/...` framework references — there's no
    // consumer source file to link to.
    if (type.contains(":")) return null
    if (type != "drawable" && type != "mipmap") return null
    if (name.isEmpty()) return null
    return type to name
  }
}
