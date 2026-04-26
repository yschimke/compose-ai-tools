package ee.schimke.composeai.plugin

import java.io.File
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/**
 * Maps an XML drawable / mipmap file to a [ResourceType] by reading just its root tag. Returns
 * `null` for tags we don't render (`<shape>`, `<selector>`, raster `<bitmap>`, …) so callers can
 * skip without erroring — the catalogue is opt-in extensible.
 *
 * Uses StAX so we don't allocate the whole DOM for files we'll only look at the first 64 bytes of.
 */
object ResourceXmlClassifier {

  private val factory: XMLInputFactory =
    XMLInputFactory.newFactory().apply {
      // Defence in depth — drawables are first-party files but we don't need entity expansion or
      // DTD support, and disabling them keeps the parser cheap and immune to XXE-shaped surprises
      // from tooling-generated XML.
      setProperty(XMLInputFactory.SUPPORT_DTD, false)
      setProperty("javax.xml.stream.isSupportingExternalEntities", false)
    }

  fun classify(file: File): ResourceType? = file.inputStream().use { classify(it) }

  fun classify(input: InputStream): ResourceType? {
    val reader =
      try {
        factory.createXMLStreamReader(input)
      } catch (_: XMLStreamException) {
        return null
      }
    try {
      while (reader.hasNext()) {
        val event = reader.next()
        if (event == XMLStreamConstants.START_ELEMENT) {
          return when (reader.localName) {
            "vector" -> ResourceType.VECTOR
            "animated-vector" -> ResourceType.ANIMATED_VECTOR
            "adaptive-icon" -> ResourceType.ADAPTIVE_ICON
            else -> null
          }
        }
      }
      return null
    } catch (_: XMLStreamException) {
      return null
    } finally {
      try {
        reader.close()
      } catch (_: XMLStreamException) {
        // already closed / never opened — ignore
      }
    }
  }
}
