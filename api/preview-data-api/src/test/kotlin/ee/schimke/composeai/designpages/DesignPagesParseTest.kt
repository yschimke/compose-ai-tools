package ee.schimke.composeai.designpages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [DesignPagesManifest] to the JSON the producer actually writes.
 *
 * Unlike the foreign contract this replaced, both ends live in this repository —
 * `scripts/design-artifacts/emit-design-pages.mjs` writes it and [ServeDesignPageStore] reads it —
 * so the fixture below is the wire format quoted verbatim rather than a vendored sample of someone
 * else's release. `design-pages.test.mjs` asserts the same shape from the producer's side; if the
 * two ever disagree, one of these two tests is wrong and the surface is broken either way.
 */
class DesignPagesParseTest {

  private val fixture =
    """
    {
      "version": 2,
      "source": "figma",
      "fileKey": "ocdacdEsnHipMJD3egzxKb",
      "pages": [
        {
          "id": "shape",
          "name": "Shape",
          "nodeId": "58548:7093",
          "frame": { "width": 5326.0, "height": 4497.0 },
          "image": { "uri": "shape.svg", "format": "svg" },
          "nodes": [
            {
              "nodeId": "58548:7249",
              "name": "Shape=Circle",
              "depth": 3,
              "ref": "figma:ocdacdEsnHipMJD3egzxKb/58548:7249",
              "link": "manifest",
              "confidence": "high",
              "code": "catalog/src/main/kotlin/ee/schimke/m3catalog/sections/Shapes.kt#CircleShape",
              "previewId": "shape-circle__light"
            },
            { "nodeId": "58548:7395", "name": ".Header", "depth": 2, "link": "unlinked" }
          ]
        }
      ]
    }
    """
      .trimIndent()

  private fun parse(text: String): DesignPagesManifest =
    DesignPagesJson.decodeFromString(DesignPagesManifest.serializer(), text)

  @Test
  fun `parses the producer's manifest`() {
    val manifest = parse(fixture)

    assertEquals(DESIGN_PAGES_VERSION, manifest.version)
    assertEquals("figma", manifest.source)
    assertTrue(manifest.isSupported)

    val page = manifest.pages.single()
    assertEquals("shape", page.id)
    assertEquals("58548:7093", page.nodeId)
    assertEquals(5326.0, page.frame.width)
    assertEquals(PageImage.SVG, page.image.format)
    assertEquals("shape.svg", page.image.uri)

    val circle = page.nodes.first()
    assertEquals("58548:7249", circle.nodeId)
    assertEquals(PageNodeLink.MANIFEST, circle.link)
    assertEquals(PageNodeConfidence.HIGH, circle.confidence)
    assertEquals("shape-circle__light", circle.renderablePreviewId)
    assertTrue(circle.isRenderable)
    assertFalse(circle.isUnlinked)

    assertEquals(listOf("58548:7249"), page.linked.map { it.nodeId })
    assertEquals(listOf("58548:7395"), page.unlinked.map { it.nodeId })
  }

  /** A node the producer left unlinked must not be drawn, even if a stale preview id survives. */
  @Test
  fun `an unlinked node is never renderable`() {
    val node =
      PageNode(nodeId = "1:2", previewId = "something__light", link = PageNodeLink.UNLINKED)
    assertNull(node.renderablePreviewId)
  }

  /** A ref is derivable from the manifest, so a producer that wrote none still deep-links. */
  @Test
  fun `refFor fills in a missing ref`() {
    val manifest = parse(fixture)
    val header = manifest.pages.single().nodes.last()
    assertEquals("figma:ocdacdEsnHipMJD3egzxKb/58548:7395", manifest.refFor(header))
  }

  /**
   * Version 1 was design-parity's page-backdrop contract: a composed screen as a flat PNG. It
   * describes a surface that no longer exists, and its raster carries nothing addressable — so it
   * is refused rather than half-read. A stale delivery branch shows no pages, not a dead page.
   */
  @Test
  fun `the retired page-backdrop version is refused`() {
    assertFalse(supportsDesignPagesVersion(1))
    assertFalse(supportsDesignPagesVersion(DESIGN_PAGES_VERSION + 1))
    assertTrue(supportsDesignPagesVersion(DESIGN_PAGES_VERSION))
  }

  /**
   * A delivery branch is regenerated on its own schedule and can be newer than the server reading
   * it, so an added field must not take the whole manifest down.
   */
  @Test
  fun `an unknown field does not fail the parse`() {
    val manifest =
      parse(fixture.replace("\"source\": \"figma\",", "\"source\": \"figma\", \"tomorrow\": 7,"))
    assertEquals(1, manifest.pages.size)
  }
}
