package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The tag index is the element identity a scoped parity acceptance resolves against, so the
 * uniqueness signal ([ServeSemanticsTags.TagEntry.count]) has to be trustworthy in the cases that
 * would otherwise report a duplicate as unique. Those are what these cover.
 */
class ServeSemanticsTagsTest {

  private fun node(
    id: String,
    bounds: String,
    testTag: String? = null,
    placed: Boolean = true,
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = id,
      boundsInRoot = bounds,
      testTag = testTag,
      placed = placed,
      children = children,
    )

  @Test
  fun `a trial-measured copy of a tag is not a second use of it`() {
    // A `SubcomposeLayout` measuring a trial copy of its content to choose a layout (Wear
    // `AlertDialogContent`) duplicates every tag in the tree. The copy was never placed, so it is
    // not on the frame — counting it reports `count = 2` for a tag that identifies exactly one
    // node, and a scoped parity acceptance then rejects it as ambiguous.
    val root =
      node(
        "root",
        "0,0,200,200",
        children =
          listOf(
            node("real", "10,10,60,40", testTag = "submit"),
            node(
              "trial",
              "0,0,120,60",
              placed = false,
              children = listOf(node("trial-child", "0,0,50,30", testTag = "submit")),
            ),
          ),
      )

    val entry = index(root)["submit"]

    assertEquals(1, entry?.count)
    assertEquals(AnnotationBounds(10, 10, 50, 30), entry?.bounds)
  }

  private fun index(root: ComposeSemanticsNode) =
    ServeSemanticsTags.index(ComposeSemanticsPayload(root))

  @Test
  fun `a unique tag reports count one and its render-pixel box`() {
    val tags = index(node("0", "0,0,100,100", children = listOf(node("1", "24,24,48,48", "glyph"))))
    assertEquals(setOf("glyph"), tags.keys)
    assertEquals(1, tags.getValue("glyph").count)
    assertEquals(
      AnnotationBounds(x = 24, y = 24, width = 24, height = 24),
      tags.getValue("glyph").bounds,
    )
  }

  @Test
  fun `a repeated tag reports every occurrence`() {
    val tags =
      index(
        node(
          "0",
          "0,0,100,100",
          children =
            listOf(
              node("1", "0,0,20,20", "row"),
              node("2", "0,20,20,40", "row"),
              node("3", "0,40,20,60", "row"),
            ),
        )
      )
    assertEquals(3, tags.getValue("row").count)
  }

  /**
   * The case the whole field exists for: a zero-area duplicate must not be dropped, because
   * dropping it reports `count = 1` for a tag two nodes carry and an acceptance would resolve it as
   * unique.
   */
  @Test
  fun `a duplicate with no usable bounds still raises the count`() {
    val tags =
      index(
        node(
          "0",
          "0,0,100,100",
          children =
            listOf(
              node("1", "10,10,30,30", "chip"),
              node("2", "40,40,40,40", "chip"), // zero area
              node("3", "not,a,box", "chip"), // unparseable
            ),
        )
      )
    assertEquals(3, tags.getValue("chip").count)
    // The first usable box wins, so the reported geometry is still the drawable node's.
    assertEquals(
      AnnotationBounds(x = 10, y = 10, width = 20, height = 20),
      tags.getValue("chip").bounds,
    )
  }

  @Test
  fun `a tag whose only node has no usable bounds reports a count and no box`() {
    val tags = index(node("0", "0,0,100,100", children = listOf(node("1", "5,5,5,5", "ghost"))))
    assertEquals(1, tags.getValue("ghost").count)
    assertNull(tags.getValue("ghost").bounds)
  }

  /** Depth-first, so "first usable box" means the same thing in both engines. */
  @Test
  fun `the first usable box in depth-first order is the one reported`() {
    val tags =
      index(
        node(
          "0",
          "0,0,100,100",
          children =
            listOf(
              node("1", "0,0,50,50", children = listOf(node("2", "1,1,11,11", "dup"))),
              node("3", "50,50,90,90", "dup"),
            ),
        )
      )
    assertEquals(2, tags.getValue("dup").count)
    assertEquals(
      AnnotationBounds(x = 1, y = 1, width = 10, height = 10),
      tags.getValue("dup").bounds,
    )
  }

  /**
   * A tag is matched by Compose as the exact string, so the index must not normalise it. Trimming
   * would merge these two distinct tags into one `count = 2` entry — false ambiguity for `"pad"`,
   * and no key at all for an acceptance recording `" pad "`.
   */
  @Test
  fun `a tag is keyed verbatim, not trimmed`() {
    val tags =
      index(
        node(
          "0",
          "0,0,100,100",
          children = listOf(node("1", "0,0,10,10", "pad"), node("2", "20,20,30,30", " pad ")),
        )
      )
    assertEquals(setOf("pad", " pad "), tags.keys)
    assertEquals(1, tags.getValue("pad").count)
    assertEquals(1, tags.getValue(" pad ").count)
  }

  @Test
  fun `blank and absent tags are not keys`() {
    val tags =
      index(
        node(
          "0",
          "0,0,100,100",
          children =
            listOf(
              node("1", "0,0,10,10"),
              node("2", "0,0,10,10", ""),
              node("3", "0,0,10,10", "   "),
            ),
        )
      )
    assertTrue(tags.isEmpty(), "expected no keys, got ${tags.keys}")
  }

  /**
   * The space is on the wire because the design doc and this producer currently disagree about
   * whether the index is canonical-plane or render-pixel. A consumer must be able to tell without
   * guessing, since treating render pixels as canonical is exactly what produces a wrong
   * `element-moved` verdict.
   */
  @Test
  fun `every entry names its coordinate space`() {
    val tags = index(node("0", "0,0,64,64", "tagged"))
    assertEquals(ServeSemanticsTags.RENDER_PIXELS, tags.getValue("tagged").space)
  }

  /**
   * Asserted on the **raw JSON**, not on a decoded [ServeSemanticsTags.TagEntry]. Decoding restores
   * the Kotlin default, so a round-trip test passes even when the field never reached the wire —
   * which is exactly what happened: the host serialises with `encodeDefaults = false`, and without
   * `@EncodeDefault` the discriminator was dropped while every Kotlin-side assertion still saw it.
   * A browser reading the response is the consumer that matters here.
   */
  @Test
  fun `the coordinate space survives serialisation under encodeDefaults false`() {
    val json = Json { ignoreUnknownKeys = true } // the host's config: encodeDefaults defaults false
    val encoded =
      json.encodeToString(
        MapSerializer(String.serializer(), ServeSemanticsTags.TagEntry.serializer()),
        index(node("0", "0,0,64,64", "tagged")),
      )
    val space =
      json
        .parseToJsonElement(encoded)
        .jsonObject
        .getValue("tagged")
        .jsonObject["space"]
        ?.jsonPrimitive
        ?.content
    assertEquals(
      ServeSemanticsTags.RENDER_PIXELS,
      space,
      "the space discriminator must be present in the encoded JSON, not just in the Kotlin default",
    )
  }

  @Test
  fun `a tagged root is indexed like any other node`() {
    val tags = index(node("0", "0,0,64,64", "root-tag"))
    assertEquals(1, tags.getValue("root-tag").count)
    assertEquals(
      AnnotationBounds(x = 0, y = 0, width = 64, height = 64),
      tags.getValue("root-tag").bounds,
    )
  }
}
