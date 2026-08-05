package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServeFigmaSpecTest {

  private fun reference(
    id: String = "chat-figma",
    label: String = "Contact chat",
    provider: String = "figma",
    uri: String? = null,
    attributes: Map<String, String> = emptyMap(),
  ) =
    DesignReference(
      id = id,
      previewId = "com.example.ContactChatPreview",
      label = label,
      raster = DesignReferenceRaster(path = "references/$id.png"),
      source = DesignReferenceSource(provider = provider, uri = uri, attributes = attributes),
    )

  @Test
  fun `the published figma handle resolves to a node deep link`() {
    // What scripts/design-artifacts/design-references.mjs republishes from a design-map entry.
    val spec = ServeFigmaSpec.of(reference(uri = "figma:gYzowY4cQ7rNr2gYoco1M6/73:6"))
    // Figma's URL form spells the node id with `-` where the design map and the API use `:`.
    assertEquals("https://www.figma.com/design/gYzowY4cQ7rNr2gYoco1M6?node-id=73-6", spec?.url)
    assertEquals("Contact chat", spec?.label, "the label names which spec the link opens")
  }

  @Test
  fun `a file URL plus a nodeId attribute resolves too`() {
    // The shape the manifest example in docs/public-preview-server.md uses.
    val spec =
      ServeFigmaSpec.of(
        reference(
          uri = "https://www.figma.com/file/gYzowY4cQ7rNr2gYoco1M6/MeshCore",
          attributes = mapOf("nodeId" to "10:2"),
        )
      )
    assertEquals("https://www.figma.com/design/gYzowY4cQ7rNr2gYoco1M6?node-id=10-2", spec?.url)
  }

  @Test
  fun `a node-id already in the URL is honoured`() {
    val spec =
      ServeFigmaSpec.of(
        reference(uri = "https://figma.com/design/abc123/MeshCore?node-id=42-7&t=xyz")
      )
    assertEquals("https://www.figma.com/design/abc123?node-id=42-7", spec?.url)
  }

  @Test
  fun `a reference that is not figma-backed names no spec`() {
    // meshcore-mobile's design map is mostly `claude-design` HTML exports; those get no link
    // rather than a guessed one.
    assertNull(ServeFigmaSpec.of(reference(provider = "claude-design", uri = "design/Chat.html")))
    assertNull(ServeFigmaSpec.of(reference(provider = "png", uri = "references/chat.png")))
  }

  @Test
  fun `a figma reference with no usable handle names no spec`() {
    assertNull(ServeFigmaSpec.of(reference(uri = null)), "no uri at all")
    assertNull(ServeFigmaSpec.of(reference(uri = "figma:onlyakey")), "no node segment")
    assertNull(ServeFigmaSpec.of(reference(uri = "https://www.figma.com/design/abc")), "no node")
    assertNull(ServeFigmaSpec.of(reference(uri = "figma:abc/not-a-node")), "unparseable node")
  }

  @Test
  fun `a hostile catalog cannot put an arbitrary href on the viewer`() {
    // A catalog is third-party data. Nothing from it is interpolated into the URL unvalidated: the
    // origin is a literal and both components must match their shapes, so these resolve to no link
    // at all rather than to an attacker-chosen destination.
    assertNull(ServeFigmaSpec.of(reference(uri = "javascript:alert(1)")))
    assertNull(ServeFigmaSpec.of(reference(uri = "figma:../../evil/1:1")))
    assertNull(ServeFigmaSpec.of(reference(uri = "figma:key\"onmouseover=\"x/1:1")))
    assertNull(ServeFigmaSpec.of(reference(uri = "https://figma.com.evil.example/design/k/1:1")))
    assertNull(
      ServeFigmaSpec.of(
        reference(uri = "figma:abc/1:1", attributes = mapOf("nodeId" to "1:1\"><script>"))
      ),
      "the attribute is validated exactly like the handle's own node segment",
    )
  }

  @Test
  fun `the first figma-backed reference wins, and an empty list names nothing`() {
    val html = reference(id = "html", provider = "claude-design", uri = "design/Chat.html")
    val figma = reference(id = "figma-a", uri = "figma:abc123/1:1")
    val other = reference(id = "figma-b", uri = "figma:abc123/2:2")
    assertEquals(
      "https://www.figma.com/design/abc123?node-id=1-1",
      ServeFigmaSpec.of(listOf(html, figma, other))?.url,
      "the manifest's order is the producer's own precedence",
    )
    assertNull(ServeFigmaSpec.of(emptyList()), "a catalog with no references names no spec")
    assertNull(ServeFigmaSpec.of(listOf(html)), "…and neither does an all-HTML one")
  }
}
