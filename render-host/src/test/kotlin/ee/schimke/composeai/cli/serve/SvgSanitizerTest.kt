package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SvgSanitizer] is the trust boundary for the one place this server puts third-party markup into
 * the document tree, so these tests are two lists: what a design export legitimately needs and must
 * therefore survive, and what nothing needs and must therefore not.
 *
 * The second list is the load-bearing half. Each case below is a way markup becomes behaviour, and
 * the allowlist exists because that set is not closed — a denylist would have to have anticipated
 * `<set attributeName="href">`, and the next one after it.
 */
class SvgSanitizerTest {

  private fun clean(svg: String): String? = SvgSanitizer.sanitize(svg)

  private fun wrap(body: String): String =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">$body</svg>"""

  @Test
  fun `a design export keeps its shapes, paint and node ids`() {
    val out =
      assertNotNull(
        clean(
          wrap(
            """<defs><linearGradient id="g0"><stop offset="0" stop-color="#fff"/></linearGradient>
               <clipPath id="c0"><rect width="10" height="10"/></clipPath></defs>
               <g data-node-id="58548:7249" clip-path="url(#c0)" opacity="0.5">
                 <path d="M0 0 L10 10" fill="url(#g0)" stroke-width="2"/>
                 <text x="1" y="9" font-family="Roboto" font-size="4">Circle</text>
               </g>"""
          )
        )
      )
    assertTrue(out.contains("data-node-id=\"58548:7249\""))
    assertTrue(out.contains("linearGradient"))
    assertTrue(out.contains("clip-path=\"url(#c0)\""))
    assertTrue(out.contains("Circle"))
  }

  @Test
  fun `script is removed with its contents`() {
    val out =
      assertNotNull(clean(wrap("""<script>alert(1)</script><rect width="1" height="1"/>""")))
    assertFalse(out.contains("script"))
    assertFalse(out.contains("alert"))
    assertTrue(out.contains("rect"))
  }

  @Test
  fun `event handlers go before the allowlist is even consulted`() {
    val out =
      assertNotNull(clean(wrap("""<rect width="1" height="1" onload="alert(1)" onclick="x()"/>""")))
    assertFalse(out.contains("onload"))
    assertFalse(out.contains("onclick"))
    assertTrue(out.contains("width=\"1\""))
  }

  @Test
  fun `foreignObject is removed WITH its subtree, never unwrapped`() {
    // Its children are HTML. Promoting them into the parent — the usual "keep the content" instinct
    // — would keep exactly the payload the removal was for.
    val out =
      assertNotNull(
        clean(wrap("""<foreignObject><div onclick="alert(1)">hi</div></foreignObject>"""))
      )
    assertFalse(out.contains("foreignObject"))
    assertFalse(out.contains("div"))
    assertFalse(out.contains("alert"))
  }

  @Test
  fun `animation elements that rewrite attributes are removed`() {
    // `<animate attributeName="href">` rewrites a link after load; `<set>` does it with no
    // animation at all. Neither is anything a specimen sheet needs.
    val out =
      assertNotNull(
        clean(
          wrap(
            """<a href="#x"><set attributeName="href" to="javascript:alert(1)"/>
               <animate attributeName="fill" to="red"/></a>"""
          )
        )
      )
    assertFalse(out.contains("<a"))
    assertFalse(out.contains("set"))
    assertFalse(out.contains("animate"))
    assertFalse(out.contains("javascript"))
  }

  @Test
  fun `an href is judged by value, not by name`() {
    // `url(#…)` internal references are how every export clips and gradients, so the name cannot be
    // the check. Off-document targets are the ones that matter.
    val out =
      assertNotNull(
        clean(
          wrap(
            """<use href="#g0"/>
               <image href="https://example.test/pixel.png" width="1" height="1"/>
               <image href="//example.test/pixel.png" width="1" height="1"/>
               <image href="data:image/png;base64,iVBORw0KGgo=" width="1" height="1"/>"""
          )
        )
      )
    assertTrue(out.contains("href=\"#g0\""))
    assertTrue(out.contains("data:image/png;base64"))
    assertFalse(out.contains("example.test"))
  }

  @Test
  fun `a data URI carrying SVG is refused — the allowlist would stop at the first hop`() {
    val out =
      assertNotNull(
        clean(
          wrap(
            """<image href="data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=" width="1" height="1"/>"""
          )
        )
      )
    assertFalse(out.contains("svg+xml"))
  }

  @Test
  fun `a style attribute reaching off-document is dropped, an internal one survives`() {
    val out =
      assertNotNull(
        clean(
          wrap(
            """<rect width="1" height="1" style="mix-blend-mode:multiply"/>
               <rect width="2" height="2" style="fill:url(https://example.test/x)"/>
               <rect width="3" height="3" style="filter:url(#f0)"/>"""
          )
        )
      )
    assertTrue(out.contains("mix-blend-mode:multiply"))
    assertTrue(out.contains("filter:url(#f0)"))
    assertFalse(out.contains("example.test"))
  }

  @Test
  fun `a DOCTYPE with an external entity does not read a file off this host`() {
    val bomb =
      """<?xml version="1.0"?>
         <!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
         <svg xmlns="http://www.w3.org/2000/svg"><text>&xxe;</text></svg>"""
    val out = clean(bomb)
    // Either the parse is refused outright (DOCTYPEs are disallowed) or the entity resolves to
    // nothing. Both are correct; what must never happen is the file's contents appearing.
    if (out != null) assertFalse(out.contains("root:"))
  }

  @Test
  fun `markup that is not an SVG document is refused`() {
    assertNull(clean("<html><body>nope</body></html>"))
    assertNull(clean("not xml at all"))
    assertNull(clean(""))
  }

  @Test
  fun `an oversized export is refused rather than parsed into a DOM`() {
    val huge = "<svg xmlns=\"http://www.w3.org/2000/svg\">" + " ".repeat(SvgSanitizer.MAX_BYTES)
    assertNull(clean(huge))
  }

  @Test
  fun `a fourteen megabyte maximum-node design page is accepted`() {
    // m3-catalog's 500-node Buttons export is 14,576,730 bytes. The old 12 MiB ceiling silently
    // dropped that page even though the node boundary itself is inclusive.
    val body = " ".repeat(14 * 1024 * 1024)
    assertNotNull(clean(wrap(body)))
  }

  @Test
  fun `the output has no XML prologue — it is spliced into an HTML document`() {
    val out =
      assertNotNull(clean("""<?xml version="1.0"?>${wrap("<rect width=\"1\" height=\"1\"/>")}"""))
    assertFalse(out.contains("<?xml"))
    assertEquals("<svg", out.take(4))
  }

  @Test
  fun `comments and processing instructions are dropped`() {
    val out =
      assertNotNull(clean(wrap("""<!-- secret --><?php echo 1;?><rect width="1" height="1"/>""")))
    assertFalse(out.contains("secret"))
    assertFalse(out.contains("php"))
  }
}
