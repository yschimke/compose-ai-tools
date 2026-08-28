package ee.schimke.composeai.cli.serve

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServePinnedManifestTest {

  private val commit = "1111111111111111111111111111111111111111"

  @Test
  fun `catalog images key by the id the loader serves them under`() {
    val entries =
      ServePinnedManifest.parseCatalog(
        """
        {"schema":"design-parity-catalog/v1","components":[
          {"componentId":"Button/Filled","images":[
            {"path":"images/button-filled/ideal__default__dark.png","theme":"light"},
            {"path":"images/button-filled/ideal__default__light.png","theme":"dark"}]},
          {"componentId":"Card","images":[{"path":"images/card/ideal.png"}]}]}
        """
          .trimIndent()
      )!!

    // Keyed by ServeCatalogStore.previewIdFor, so a pinned id and a served id are the same string
    // by construction — the join this whole class exists to make.
    assertEquals(
      mapOf(
        "button-filled__ideal__default__dark" to "images/button-filled/ideal__default__dark.png",
        "button-filled__ideal__default__light" to "images/button-filled/ideal__default__light.png",
        "card__ideal" to "images/card/ideal.png",
      ),
      entries.paths,
    )
    // The component each id belonged to, which is all a pinned page needs to name a preview the
    // live catalog no longer lists.
    assertEquals("Button/Filled", entries.labels["button-filled__ideal__default__dark"])
    assertEquals("Card", entries.labels["card__ideal"])
    // Theme is catalog metadata, not something inferred from a path whose naming is unconstrained.
    assertEquals("light", entries.themes["button-filled__ideal__default__dark"])
    assertEquals("dark", entries.themes["button-filled__ideal__default__light"])
  }

  @Test
  fun `a revision's captions are read from its own catalog`() {
    // The caption is `catalog.json` data, so a pinned page must print the sentence THAT publish
    // carried — not the tip's, which may since have been rewritten to describe the component
    // differently than the render on screen. Absence is an answer too: a component that authored
    // no caption then gets none now.
    val entries =
      ServePinnedManifest.parseCatalog(
        """
        {"schema":"design-parity-catalog/v1","components":[
          {"componentId":"Button/Loading","caption":"The kit's loading pattern.",
           "images":[{"path":"images/button-loading/ideal.png"}]},
          {"componentId":"Card","images":[{"path":"images/card/ideal.png"}]}]}
        """
          .trimIndent()
      )!!

    assertEquals("The kit's loading pattern.", entries.captions["button-loading__ideal"])
    assertNull(entries.captions["card__ideal"])
  }

  @Test
  fun `a caption follows the winning path, exactly as its label does`() {
    // Two declarations flattening to one route id: the entry that owns the pixels owns the words.
    // A caption left behind by the loser would explain someone else's render.
    val entries =
      ServePinnedManifest.parseCatalog(
        """
        {"schema":"design-parity-catalog/v1","components":[
          {"componentId":"Old","caption":"The one that lost.",
           "images":[{"path":"images/shared/ideal.png"}]},
          {"componentId":"New","images":[{"path":"images/shared/ideal.png"}]}]}
        """
          .trimIndent()
      )!!

    assertEquals("New", entries.labels["shared__ideal"])
    assertNull(entries.captions["shared__ideal"])
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
  fun `a manifest this reader cannot parse is distinguishable from one that lists nothing`() {
    // The distinction decides whether a fallback to the tip's map is licensed at all, so it is the
    // parser's job to answer "was this a manifest?" separately from "what did it contain?".
    assertNull(ServePinnedManifest.parseCatalog(""))
    assertNull(ServePinnedManifest.parseCatalog("<html>404</html>"))
    assertNull(ServePinnedManifest.parseCatalog("""{"components":"not-an-array"}"""))
    assertNull(ServePinnedManifest.parseReferences("nonsense"))
    // Read, and it genuinely lists none — an answer, not an absence.
    assertEquals(emptyMap(), ServePinnedManifest.parseCatalog("""{"components":[]}""")?.paths)
    assertEquals(emptyMap(), ServePinnedManifest.parseReferences("""{"references":[]}"""))
    // A single malformed entry costs only itself, not the rest of the map.
    assertEquals(
      mapOf("card__ideal" to "images/card/ideal.png"),
      ServePinnedManifest.parseCatalog(
          """{"components":[{"images":[{"nopath":1},{"path":"images/card/ideal.png"}]}]}"""
        )
        ?.paths,
    )
  }

  @Test
  fun `each lane resolves a duplicate id the way its own loader does`() {
    // Two paths can flatten to one route id. The live loader assigns (`bakedPathById[id] = path`),
    // so the LAST declaration is what a visitor sees — and a pin to that same commit must agree,
    // or the revision serves different pixels than it served while current.
    assertEquals(
      mapOf("foo__bar__baz" to "images/foo/bar__baz.png"),
      ServePinnedManifest.parseCatalog(
          """{"components":[{"images":[
             {"path":"images/foo__bar/baz.png"},{"path":"images/foo/bar__baz.png"}]}]}"""
        )
        ?.paths,
    )
    // …but only among entries the loader would actually have served. An ineligible path (outside
    // images/, or not a PNG) never reaches the live map, so letting one win a collision here would
    // answer a pin with bytes that revision never exposed.
    assertEquals(
      mapOf("foo__bar__baz" to "images/foo__bar/baz.png"),
      ServePinnedManifest.parseCatalog(
          """{"components":[{"images":[
             {"path":"images/foo__bar/baz.png"},{"path":"foo/bar__baz.png"}]}]}"""
        )
        ?.paths,
    )
    assertEquals(
      emptyMap(),
      ServePinnedManifest.parseCatalog(
          """{"components":[{"images":[
             {"path":"images/a/../../secret.png"},{"path":"images/b/c.svg"}]}]}"""
        )
        ?.paths,
    )
    // The label follows the winning path even when the winner is unnamed: leaving the loser's
    // component behind would attribute one component's render to another.
    val collided =
      ServePinnedManifest.parseCatalog(
        """{"components":[
             {"componentId":"Named","images":[{"path":"images/foo__bar/baz.png"}]},
             {"images":[{"path":"images/foo/bar__baz.png"}]}]}"""
      )!!
    assertEquals("images/foo/bar__baz.png", collided.paths["foo__bar__baz"])
    assertNull(collided.labels["foo__bar__baz"])
    // References go the other way, because their importer discards a duplicate id rather than
    // overwriting: first wins. The asymmetry mirrors the two loaders, not one another.
    assertEquals(
      mapOf("dup" to "references/first.png"),
      ServePinnedManifest.parseReferences(
        """{"references":[
             {"id":"dup","raster":{"path":"references/first.png"}},
             {"id":"dup","raster":{"path":"references/second.png"}}]}"""
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
  fun `a readable manifest is authoritative, an unreadable one licenses the fallback`() {
    val readable =
      ServePinnedManifest(
        fetch = { _, file ->
          if (file == ServeCatalogRevision.CATALOG_FILE)
            """{"components":[{"images":[{"path":"images/card/ideal.png"}]}]}""".encodeToByteArray()
          else null
        }
      )

    val paths = readable.forCommit(commit)

    // The catalog was read, so what it does not list, that revision did not publish — the caller
    // must not fall back to the tip's map for an id this manifest omits.
    assertTrue(paths.catalogRead)
    assertNull(paths.renders["never-published"])
    // The reference manifest was NOT readable, which is an absence rather than an answer.
    assertFalse(paths.referencesRead)
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
