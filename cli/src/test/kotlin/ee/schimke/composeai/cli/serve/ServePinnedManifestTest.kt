package ee.schimke.composeai.cli.serve

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServePinnedManifestTest {

  private val commit = "1111111111111111111111111111111111111111"

  @Test
  fun `catalog images key by the id the loader serves them under`() {
    val paths =
      ServePinnedManifest.parseCatalog(
        """
        {"schema":"design-parity-catalog/v1","components":[
          {"componentId":"Button/Filled","images":[
            {"path":"images/button-filled/ideal__default__dark.png"},
            {"path":"images/button-filled/ideal__default__light.png"}]},
          {"componentId":"Card","images":[{"path":"images/card/ideal.png"}]}]}
        """
          .trimIndent()
      )

    // Keyed by ServeCatalogStore.previewIdFor, so a pinned id and a served id are the same string
    // by construction — the join this whole class exists to make.
    assertEquals(
      mapOf(
        "button-filled__ideal__default__dark" to "images/button-filled/ideal__default__dark.png",
        "button-filled__ideal__default__light" to "images/button-filled/ideal__default__light.png",
        "card__ideal" to "images/card/ideal.png",
      ),
      paths,
    )
  }

  @Test
  fun `references key by their declared id, whatever path they carry`() {
    val paths =
      ServePinnedManifest.parseReferences(
        """
        {"schema":"compose-preview-references/v1","references":[
          {"id":"button-figma","previewId":"button","raster":{"path":"references/legacy/b.png"}},
          {"id":"card-figma","previewId":"card","raster":{"path":"design-references/c.png"}}]}
        """
          .trimIndent()
      )

    assertEquals(
      mapOf("button-figma" to "references/legacy/b.png", "card-figma" to "design-references/c.png"),
      paths,
    )
  }

  @Test
  fun `a manifest this reader does not understand yields no paths rather than failing`() {
    // Published by an older (or newer) CLI than the one reading it, truncated, or simply a 404 page
    // — every one of these degrades to "resolve through the tip's map instead".
    assertEquals(emptyMap(), ServePinnedManifest.parseCatalog(""))
    assertEquals(emptyMap(), ServePinnedManifest.parseCatalog("<html>404</html>"))
    assertEquals(emptyMap(), ServePinnedManifest.parseCatalog("""{"components":"not-an-array"}"""))
    assertEquals(emptyMap(), ServePinnedManifest.parseReferences("""{"references":[{"id":1}]}"""))
    // A single malformed entry costs only itself, not the rest of the map.
    assertEquals(
      mapOf("card__ideal" to "images/card/ideal.png"),
      ServePinnedManifest.parseCatalog(
        """{"components":[{"images":[{"nopath":1},{"path":"images/card/ideal.png"}]}]}"""
      ),
    )
  }

  @Test
  fun `a commit is read once, however many assets it is asked about`() {
    val fetches = AtomicInteger()
    val manifest =
      ServePinnedManifest(
        fetch = { _, file ->
          fetches.incrementAndGet()
          when (file) {
            ServeCatalogRevision.CATALOG_FILE ->
              """{"components":[{"images":[{"path":"images/card/ideal.png"}]}]}"""
                .encodeToByteArray()
            else -> null
          }
        }
      )

    repeat(5) {
      assertEquals("images/card/ideal.png", manifest.forCommit(commit).renders["card__ideal"])
    }

    // Two files on the first call (the catalog and the reference manifest); nothing after that —
    // which is what keeps a pinned page of many images to one pair of manifest fetches.
    assertEquals(2, fetches.get())
  }

  @Test
  fun `a commit whose manifests cannot be read is remembered as such`() {
    val fetches = AtomicInteger()
    val manifest = ServePinnedManifest(fetch = { _, _ -> fetches.incrementAndGet().let { null } })

    repeat(4) { assertTrue(manifest.forCommit(commit).isEmpty) }

    // A branch that cannot answer for a commit will not start answering, and a page of broken
    // pinned images must not re-ask once per image.
    assertEquals(2, fetches.get())
  }

  @Test
  fun `only a commit sha is ever fetched for`() {
    val asked = mutableListOf<String>()
    val manifest =
      ServePinnedManifest(
        fetch = { commit, _ ->
          asked += commit
          null
        }
      )

    assertTrue(manifest.forCommit("main").isEmpty)
    assertTrue(manifest.forCommit("refs/heads/main").isEmpty)
    assertTrue(manifest.forCommit("").isEmpty)

    // Same rule as every other pinned lane: a ref is not a revision, and it never reaches a fetch.
    assertEquals(emptyList(), asked)
  }
}
