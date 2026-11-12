package ee.schimke.composeai.rccreation.dwf

/**
 * Minimal, zero-dependency XML parser for Watch Face Format (`<WatchFace>`) and Android `<vector>`
 * resources across all Compose Multiplatform targets.
 */
internal sealed interface XmlNode

internal data class XmlText(val text: String) : XmlNode

internal data class XmlElement(
  val name: String,
  val localName: String,
  val attributes: Map<String, String>,
  val children: List<XmlNode>,
) : XmlNode {
  fun attr(key: String): String? =
    attributes[key]
      ?: attributes.entries.firstOrNull { it.key.substringAfter(':', it.key) == key }?.value

  fun childElements(): List<XmlElement> = children.filterIsInstance<XmlElement>()

  fun childElements(localTag: String): List<XmlElement> =
    children.filterIsInstance<XmlElement>().filter { it.localName == localTag }

  fun firstChild(localTag: String): XmlElement? =
    children.filterIsInstance<XmlElement>().firstOrNull { it.localName == localTag }

  fun textContent(): String = buildString {
    for (child in children) {
      when (child) {
        is XmlText -> append(child.text)
        is XmlElement -> append(child.textContent())
      }
    }
  }
}

internal object XmlMiniDom {
  fun parseDocument(xml: String): XmlElement {
    val parser = Parser(stripBom(xml))
    return parser.parseRoot()
  }

  fun parseDocumentOrNull(xml: String): XmlElement? = runCatching { parseDocument(xml) }.getOrNull()

  private fun stripBom(input: String): String =
    if (input.isNotEmpty() && input[0] == '\uFEFF') input.substring(1) else input

  private class Parser(private val src: String) {
    private var pos: Int = 0
    private val len: Int = src.length

    fun parseRoot(): XmlElement {
      while (pos < len) {
        skipWhitespace()
        if (pos >= len) break
        if (startsWith("<!--")) {
          skipUntilAfter("-->")
        } else if (startsWith("<?")) {
          skipUntilAfter("?>")
        } else if (startsWith("<!DOCTYPE") || startsWith("<!doctype")) {
          skipDoctype()
        } else if (src[pos] == '<') {
          return parseElement()
        } else {
          pos++
        }
      }
      throw IllegalArgumentException("No root XML element found")
    }

    private fun parseElement(): XmlElement {
      expect('<')
      val rawName = readName()
      if (rawName.isEmpty()) {
        throw IllegalArgumentException("Expected element tag name at offset $pos")
      }
      val localName = rawName.substringAfter(':', rawName)
      val attrs = LinkedHashMap<String, String>()
      while (pos < len) {
        skipWhitespace()
        if (pos >= len) throw IllegalArgumentException("Unexpected EOF inside <$rawName>")
        if (startsWith("/>")) {
          pos += 2
          return XmlElement(rawName, localName, attrs, emptyList())
        }
        if (src[pos] == '>') {
          pos++
          break
        }
        val attrName = readName()
        if (attrName.isEmpty()) {
          throw IllegalArgumentException("Expected attribute name in <$rawName> at offset $pos")
        }
        skipWhitespace()
        val attrValue =
          if (pos < len && src[pos] == '=') {
            pos++
            skipWhitespace()
            readQuotedValue()
          } else {
            ""
          }
        attrs[attrName] = attrValue
      }

      val children = ArrayList<XmlNode>()
      while (pos < len) {
        if (startsWith("</")) {
          pos += 2
          val closeName = readName()
          skipWhitespace()
          expect('>')
          if (closeName != rawName && closeName.substringAfter(':') != localName) {
            throw IllegalArgumentException("Mismatched closing tag </$closeName> for <$rawName>")
          }
          return XmlElement(rawName, localName, attrs, children)
        } else if (startsWith("<!--")) {
          skipUntilAfter("-->")
        } else if (startsWith("<![CDATA[")) {
          pos += 9
          val start = pos
          val end = src.indexOf("]]>", start)
          if (end < 0) throw IllegalArgumentException("Unterminated CDATA section")
          children += XmlText(src.substring(start, end))
          pos = end + 3
        } else if (startsWith("<?")) {
          skipUntilAfter("?>")
        } else if (src[pos] == '<') {
          children += parseElement()
        } else {
          val start = pos
          while (pos < len && src[pos] != '<') {
            pos++
          }
          val rawText = src.substring(start, pos)
          if (rawText.isNotEmpty()) {
            children += XmlText(unescapeEntities(rawText))
          }
        }
      }
      throw IllegalArgumentException("Unterminated element <$rawName>")
    }

    private fun readQuotedValue(): String {
      if (pos >= len) throw IllegalArgumentException("Unexpected EOF reading attribute value")
      val quote = src[pos]
      if (quote != '"' && quote != '\'') {
        val start = pos
        while (pos < len && !src[pos].isWhitespace() && src[pos] != '>' && src[pos] != '/') {
          pos++
        }
        return unescapeEntities(src.substring(start, pos))
      }
      pos++
      val start = pos
      while (pos < len && src[pos] != quote) {
        pos++
      }
      if (pos >= len) throw IllegalArgumentException("Unterminated attribute string")
      val raw = src.substring(start, pos)
      pos++
      return unescapeEntities(raw)
    }

    private fun readName(): String {
      val start = pos
      while (pos < len) {
        val c = src[pos]
        if (c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.') {
          pos++
        } else {
          break
        }
      }
      return src.substring(start, pos)
    }

    private fun skipDoctype() {
      var depth = 0
      while (pos < len) {
        val c = src[pos++]
        if (c == '<' || c == '[') depth++
        else if (c == ']') depth--
        else if (c == '>') {
          depth--
          if (depth <= 0) return
        }
      }
    }

    private fun skipUntilAfter(marker: String) {
      val idx = src.indexOf(marker, pos)
      pos = if (idx < 0) len else idx + marker.length
    }

    private fun skipWhitespace() {
      while (pos < len && src[pos].isWhitespace()) {
        pos++
      }
    }

    private fun startsWith(prefix: String): Boolean = src.startsWith(prefix, pos)

    private fun expect(ch: Char) {
      if (pos >= len || src[pos] != ch) {
        val actual = if (pos < len) "'${src[pos]}'" else "EOF"
        throw IllegalArgumentException("Expected '$ch' but found $actual at offset $pos")
      }
      pos++
    }
  }

  fun unescapeEntities(text: String): String {
    if ('&' !in text) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (c == '&') {
        val semi = text.indexOf(';', i + 1)
        if (semi > i + 1 && semi - i <= 12) {
          val entity = text.substring(i + 1, semi)
          val decoded = decodeEntity(entity)
          if (decoded != null) {
            out.append(decoded)
            i = semi + 1
            continue
          }
        }
      }
      out.append(c)
      i++
    }
    return out.toString()
  }

  private fun decodeEntity(entity: String): String? =
    when (entity) {
      "amp" -> "&"
      "lt" -> "<"
      "gt" -> ">"
      "quot" -> "\""
      "apos" -> "'"
      else ->
        if (entity.startsWith("#x") || entity.startsWith("#X")) {
          entity.substring(2).toIntOrNull(16)?.let { codePointToString(it) }
        } else if (entity.startsWith("#")) {
          entity.substring(1).toIntOrNull(10)?.let { codePointToString(it) }
        } else {
          null
        }
    }

  private fun codePointToString(cp: Int): String? {
    if (cp < 0 || cp > 0x10FFFF) return null
    return if (cp <= 0xFFFF) {
      cp.toChar().toString()
    } else {
      val offset = cp - 0x10000
      val high = ((offset ushr 10) + 0xD800).toChar()
      val low = ((offset and 0x3FF) + 0xDC00).toChar()
      "$high$low"
    }
  }
}
