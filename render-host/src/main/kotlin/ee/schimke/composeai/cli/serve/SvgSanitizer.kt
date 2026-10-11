package ee.schimke.composeai.cli.serve

import java.io.ByteArrayInputStream
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Reduce a design tool's SVG export to markup safe to inline into a served page. Inlining is what
 * lets the `/{system}/pages/` surface address nodes (`data-node-id`) and swap renders in, and it
 * also means untrusted catalog markup could carry script.
 *
 * Allowlist, never a denylist: elements and attributes not named below are dropped (the space of
 * SVG tricks — `<foreignObject>`, `<animate attributeName="href">`, `<set>`, `<a>` — isn't closed).
 * - `on*` attributes are rejected before the allowlist is consulted.
 * - URL-bearing attributes are validated by value: `#local` refs and raster `data:` URIs are kept;
 *   `javascript:`, `http:` beacons, `//host` and `data:image/svg+xml` are dropped.
 * - `style` is parsed for `url(` targets.
 *
 * The parser uses secure processing, refuses DOCTYPEs and resolves no entities (no billion laughs,
 * no file reads). Returns null for anything unparseable, not rooted at `<svg>`, or over
 * [MAX_BYTES].
 */
public object SvgSanitizer {

  /**
   * Ceiling on the export parsed into a DOM: large enough for a ~14 MB outlined specimen sheet, but
   * bounded since parsing happens at catalog load on untrusted input.
   */
  public const val MAX_BYTES: Int = 16 * 1024 * 1024

  /**
   * Shapes, paint and text. Deliberately absent: `script`, `foreignObject`, `a`, `style`, animation
   * elements, `handler`, `audio`, `video`, `iframe` — each turns markup into behaviour.
   */
  private val SAFE_ELEMENTS =
    setOf(
      "svg",
      "g",
      "defs",
      "symbol",
      "use",
      "title",
      "desc",
      "path",
      "rect",
      "circle",
      "ellipse",
      "line",
      "polyline",
      "polygon",
      "text",
      "tspan",
      "image",
      "clippath",
      "mask",
      "pattern",
      "lineargradient",
      "radialgradient",
      "stop",
      "filter",
      "fegaussianblur",
      "fecolormatrix",
      "feoffset",
      "feblend",
      "feflood",
      "fecomposite",
      "femerge",
      "femergenode",
      "fedropshadow",
      "femorphology",
      "fetile",
      "feturbulence",
      "fedisplacementmap",
    )

  /**
   * Geometry, paint and layout attributes. `data-node-id` is the join to catalog components. `id`
   * is kept for internal `url(#…)` refs, which puts the export's ids in the host document — hence
   * only one page is ever inlined at a time.
   */
  private val SAFE_ATTRIBUTES =
    setOf(
      "data-node-id",
      "id",
      "class",
      "viewbox",
      "preserveaspectratio",
      "width",
      "height",
      "x",
      "y",
      "x1",
      "y1",
      "x2",
      "y2",
      "cx",
      "cy",
      "r",
      "rx",
      "ry",
      "fx",
      "fy",
      "d",
      "points",
      "transform",
      "gradienttransform",
      "patterntransform",
      "gradientunits",
      "patternunits",
      "patterncontentunits",
      "spreadmethod",
      "maskunits",
      "maskcontentunits",
      "clippathunits",
      "filterunits",
      "primitiveunits",
      "offset",
      "stop-color",
      "stop-opacity",
      "fill",
      "fill-opacity",
      "fill-rule",
      "stroke",
      "stroke-width",
      "stroke-opacity",
      "stroke-linecap",
      "stroke-linejoin",
      "stroke-miterlimit",
      "stroke-dasharray",
      "stroke-dashoffset",
      "opacity",
      "color",
      "clip-path",
      "clip-rule",
      "mask",
      "filter",
      "mix-blend-mode",
      "shape-rendering",
      "vector-effect",
      "paint-order",
      "font-family",
      "font-size",
      "font-style",
      "font-weight",
      "letter-spacing",
      "word-spacing",
      "text-anchor",
      "dominant-baseline",
      "alignment-baseline",
      "white-space",
      "xml:space",
      "in",
      "in2",
      "result",
      "mode",
      "operator",
      "type",
      "values",
      "stddeviation",
      "dx",
      "dy",
      "flood-color",
      "flood-opacity",
      "radius",
      "k1",
      "k2",
      "k3",
      "k4",
      "basefrequency",
      "numoctaves",
      "seed",
      "scale",
      "xchannelselector",
      "ychannelselector",
    )

  /** Attributes whose value is a URL and must therefore be judged by value, not by name. */
  private val URL_ATTRIBUTES = setOf("href", "xlink:href")

  /**
   * Raster types a `data:` URI may carry. Not `image/svg+xml`: nested SVG would bypass this
   * sanitizer.
   */
  private val DATA_IMAGE_PREFIX =
    Regex("^data:image/(png|jpeg|jpg|gif|webp|bmp);base64,[A-Za-z0-9+/=\\s]+$")

  /** Anything in a `style` value that would reach off-document, plus the classic CSS escapes. */
  private val UNSAFE_STYLE =
    Regex("""(?i)@import|expression\s*\(|javascript:|url\s*\(\s*(?!#|'#|"#)""")

  public fun sanitize(svg: String): String? {
    val bytes = svg.toByteArray()
    if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
    val document =
      runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        // The features that stop entity attacks, set individually so a parser missing one keeps the
        // rest.
        runCatching { factory.setFeature(DISALLOW_DOCTYPE, true) }
        runCatching { factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false) }
        runCatching { factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false) }
        runCatching { factory.setFeature(LOAD_EXTERNAL_DTD, false) }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        // Belt and braces: resolve every external reference to nothing.
        builder.setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        builder.parse(InputSource(ByteArrayInputStream(bytes)))
      }
        .getOrNull() ?: return null

    val root = document.documentElement ?: return null
    if (localName(root) != "svg") return null
    scrub(root)

    return runCatching {
      val factory = TransformerFactory.newInstance()
      runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
      val transformer = factory.newTransformer()
      // No XML prologue: the output is spliced into HTML.
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
      transformer.setOutputProperty(OutputKeys.METHOD, "xml")
      val writer = StringWriter()
      transformer.transform(DOMSource(root), StreamResult(writer))
      writer.toString()
    }
      .getOrNull()
  }

  /** Depth-first prune over a snapshot of children, since removal mutates the live list. */
  private fun scrub(element: Element) {
    scrubAttributes(element)
    val children = (0 until element.childNodes.length).map { element.childNodes.item(it) }
    for (child in children) {
      when (child.nodeType) {
        Node.ELEMENT_NODE -> {
          val childElement = child as Element
          if (localName(childElement) in SAFE_ELEMENTS) scrub(childElement)
          // Removed with its subtree; children aren't promoted (`<foreignObject>` children are
          // HTML).
          else element.removeChild(child)
        }
        // Comments and PIs carry nothing needed (PIs can be executable); text and CDATA stay.
        Node.COMMENT_NODE,
        Node.PROCESSING_INSTRUCTION_NODE -> element.removeChild(child)
        else -> Unit
      }
    }
  }

  private fun scrubAttributes(element: Element) {
    val attributes = element.attributes
    val doomed = mutableListOf<String>()
    for (i in 0 until attributes.length) {
      val attribute = attributes.item(i)
      val name = attribute.nodeName.lowercase()
      val value = attribute.nodeValue.orEmpty()
      val keep =
        when {
          // Before the allowlist, so no addition to SAFE_ATTRIBUTES can admit an event handler.
          name.startsWith("on") -> false
          name == "xmlns" || name.startsWith("xmlns:") -> true
          name in URL_ATTRIBUTES -> isSafeUrl(value)
          name == "style" -> !UNSAFE_STYLE.containsMatchIn(value)
          else -> name in SAFE_ATTRIBUTES
        }
      if (!keep) doomed += attribute.nodeName
    }
    for (name in doomed) element.removeAttribute(name)
  }

  /** An internal reference or an inline raster; everything else (including `//host`) is refused. */
  private fun isSafeUrl(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("#") || DATA_IMAGE_PREFIX.matches(trimmed.replace("\n", ""))
  }

  /** Tag name without namespace prefix, lowercased (`clipPath` == `clippath`). */
  private fun localName(element: Element): String =
    (element.localName ?: element.tagName).lowercase()

  private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
  private const val EXTERNAL_GENERAL_ENTITIES =
    "http://xml.org/sax/features/external-general-entities"
  private const val EXTERNAL_PARAMETER_ENTITIES =
    "http://xml.org/sax/features/external-parameter-entities"
  private const val LOAD_EXTERNAL_DTD =
    "http://apache.org/xml/features/nonvalidating/load-external-dtd"
}
