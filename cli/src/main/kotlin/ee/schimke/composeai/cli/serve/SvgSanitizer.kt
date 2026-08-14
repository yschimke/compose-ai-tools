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
 * Reduce a design tool's SVG export to markup that is safe to **inline** into a served page.
 *
 * Inlining is not a stylistic choice here: it is the entire point of the `/{system}/pages/`
 * surface. An `<img src="page.svg">` is a picture — the page's own script cannot reach inside it to
 * hide the design's drawing of one node and put a render in its place. An inline `<svg>` is a
 * document, and `data-node-id` makes every node in it addressable. That capability and this file
 * are the same decision: the moment third-party markup lands in the document tree it can carry
 * script, and the catalog that produced it is not trusted ([ServeCatalogStore] treats a delivery
 * branch as third-party throughout).
 *
 * ## Allowlist, never a denylist
 *
 * Elements and attributes are dropped unless they are named below. A denylist of "dangerous" tags
 * is a losing game — `<foreignObject>` smuggles arbitrary HTML, `<animate attributeName="href">`
 * rewrites a link after load, `<set>` does it without an animation, an `<a>` inside the picture is
 * a navigation the page never offered — and the set of such tricks is not closed. What a specimen
 * sheet actually needs is shapes, paint and text, which is a short list.
 *
 * Three rules carry most of the weight, and each closes a hole the obvious version leaves open:
 *
 * - **`on*` attributes go before the allowlist is consulted**, so no future addition to
 *   [SAFE_ATTRIBUTES] can accidentally admit one.
 * - **URL-bearing attributes are re-validated by value**, not merely by name. `href` is legitimate
 *   (`url(#clip0)` internal references are how every Figma export clips), so the *name* cannot be
 *   the check: `#local` and `data:` rasters are kept, and everything else — `javascript:`, an
 *   `http:` beacon that would tell a third party who opened the page, a protocol-relative `//host`
 *   — is dropped. `data:image/svg+xml` is refused with the rest, because an SVG carried inside a
 *   data URI is an SVG this function never saw.
 * - **`style` is parsed for `url(` targets**, since a stylesheet reaches the network as readily as
 *   an attribute does and `mix-blend-mode` is a real thing Figma emits.
 *
 * ## Parser hardening
 *
 * The document is parsed with secure processing, DOCTYPEs refused outright, and an entity resolver
 * that answers nothing. Without the first two, an export is a billion-laughs bomb; without the
 * third, an external entity turns "render this catalog's page" into "read a file off the server".
 *
 * Returns null for anything that fails to parse, is not rooted at `<svg>`, or exceeds [MAX_BYTES] —
 * fail-soft, like every other reader on this surface. A page that cannot be sanitized is a page the
 * catalog serves without, never a page it serves unsafely.
 */
object SvgSanitizer {

  /**
   * A ceiling on the export this will parse into a DOM.
   *
   * A specimen sheet is large — the Material 3 kit's `Shape` page is ~840 KB with its text outlined
   * — so the limit is generous, but it is not absent: parsing is O(bytes) in both time and heap, it
   * happens at catalog load, and a delivery branch is not trusted to be sane about what it
   * publishes.
   */
  const val MAX_BYTES: Int = 12 * 1024 * 1024

  /**
   * What a specimen sheet is made of: shapes, paint, and text.
   *
   * Notable absences, all deliberate: `script`, `foreignObject`, `a`, `style`, `animate` / `set` /
   * `animateTransform` / `animateMotion`, `handler`, `audio`, `video`, `iframe`. Figma emits none
   * of them for a design page, and each is a way for markup to become behaviour.
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
   * Geometry, paint and layout attributes.
   *
   * `data-node-id` is on this list for the same reason the whole surface exists — it is the join
   * between a shape in the picture and a component in the catalog, and stripping it would leave a
   * document that is safe and useless. `id` is kept because internal `url(#…)` references depend on
   * it; note that inlining therefore puts the export's id namespace into the host document, which
   * is why exactly one page is ever inlined at a time.
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
   * Raster payloads a `data:` URI may carry.
   *
   * `image/svg+xml` is absent on purpose: an SVG inside a data URI is markup this sanitizer never
   * walked, and `<image>` renders it. Keeping the raster formats means a design page with a photo
   * on it still works; admitting nested SVG would mean the allowlist stops at the first hop.
   */
  private val DATA_IMAGE_PREFIX =
    Regex("^data:image/(png|jpeg|jpg|gif|webp|bmp);base64,[A-Za-z0-9+/=\\s]+$")

  /** Anything in a `style` value that would reach off-document, plus the classic CSS escapes. */
  private val UNSAFE_STYLE =
    Regex("""(?i)@import|expression\s*\(|javascript:|url\s*\(\s*(?!#|'#|"#)""")

  fun sanitize(svg: String): String? {
    val bytes = svg.toByteArray()
    if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
    val document =
      runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        // The two that actually stop entity attacks. Set individually rather than in one
        // runCatching so a parser missing one still gets the others.
        runCatching { factory.setFeature(DISALLOW_DOCTYPE, true) }
        runCatching { factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false) }
        runCatching { factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false) }
        runCatching { factory.setFeature(LOAD_EXTERNAL_DTD, false) }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        // Belt and braces: if a parser silently ignored `disallow-doctype-decl`, this makes every
        // external reference resolve to nothing instead of to a file on this host.
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
      // No `<?xml …?>` prologue: the output is spliced into an HTML document, where a prologue is
      // a parse error rather than a declaration.
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
      transformer.setOutputProperty(OutputKeys.METHOD, "xml")
      val writer = StringWriter()
      transformer.transform(DOMSource(root), StreamResult(writer))
      writer.toString()
    }
      .getOrNull()
  }

  /**
   * Depth-first prune. Children are walked over a snapshot, since removal mutates the live list.
   */
  private fun scrub(element: Element) {
    scrubAttributes(element)
    val children = (0 until element.childNodes.length).map { element.childNodes.item(it) }
    for (child in children) {
      when (child.nodeType) {
        Node.ELEMENT_NODE -> {
          val childElement = child as Element
          if (localName(childElement) in SAFE_ELEMENTS) scrub(childElement)
          // Removed with its subtree. A disallowed element's children are not promoted into its
          // place: `<foreignObject>`'s children are HTML, and re-parenting them would keep exactly
          // the payload the removal was for.
          else element.removeChild(child)
        }
        // Comments and processing instructions carry nothing a page needs and PIs are executable in
        // some contexts. Text and CDATA stay — that is the label on a specimen.
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
          // First, and before the allowlist: an event handler is never geometry, and checking it
          // here means no later addition to SAFE_ATTRIBUTES can admit one by accident.
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

  /**
   * An internal reference or an inline raster. Everything else — including `//host` — is refused.
   */
  private fun isSafeUrl(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("#") || DATA_IMAGE_PREFIX.matches(trimmed.replace("\n", ""))
  }

  /**
   * The tag name without its namespace prefix, lowercased. `clipPath` and `clippath` are one tag.
   */
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
