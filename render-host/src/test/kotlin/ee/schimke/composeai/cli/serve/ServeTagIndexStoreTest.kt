package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.FileSystem

/**
 * The published tag index is third-party data read at staging time, and an acceptance that cannot
 * find its tag degrades to no element gate. So every failure mode here must drop to "no index"
 * rather than take the catalog down — the same posture as [ServeAnnotationStoreTest].
 */
class ServeTagIndexStoreTest {
  private fun store(json: String?): ServeTagIndexStore {
    val root = Files.createTempDirectory("tags").toFile().also { it.deleteOnExit() }
    if (json != null) {
      File(root, ServeTagIndexStore.DIRECTORY).mkdirs()
      File(root, "${ServeTagIndexStore.DIRECTORY}/${ServeTagIndexStore.INDEX_FILE}").writeText(json)
    }
    return ServeTagIndexStore.load(root, FileSystem.SYSTEM)
  }

  private val valid =
    """
    {"schema":"compose-preview-tags/v1","previews":{
      "button-filled__ideal__default__light":{
        "glyph":{"count":1,"bounds":{"x":8,"y":8,"width":32,"height":32},"space":"render-pixels"},
        "row":{"count":3,"space":"render-pixels"}
      }}}
    """

  @Test
  fun `no index yields an empty store`() {
    assertTrue(store(null).isEmpty)
  }

  @Test
  fun `malformed json is ignored rather than thrown`() {
    assertTrue(store("{ not json").isEmpty)
  }

  @Test
  fun `an index from a future schema is ignored`() {
    assertTrue(store(valid.replace("compose-preview-tags/v1", "compose-preview-tags/v99")).isEmpty)
  }

  @Test
  fun `a valid index is keyed by served preview id`() {
    val tags = store(valid).forPreview("button-filled__ideal__default__light")
    assertEquals(setOf("glyph", "row"), tags.keys)
    assertEquals(1, tags.getValue("glyph").count)
    assertEquals(
      AnnotationBounds(x = 8, y = 8, width = 32, height = 32),
      tags.getValue("glyph").bounds,
    )
    assertEquals(ServeSemanticsTags.RENDER_PIXELS, tags.getValue("glyph").space)
  }

  /**
   * A tag whose every node had unusable bounds still counts — that is what makes an ambiguity check
   * work — so an absent box must not be mistaken for a broken entry and dropped.
   */
  @Test
  fun `a counted tag with no bounds survives`() {
    val tags = store(valid).forPreview("button-filled__ideal__default__light")
    assertEquals(3, tags.getValue("row").count)
    assertEquals(null, tags.getValue("row").bounds)
  }

  @Test
  fun `an unknown preview has no tags rather than throwing`() {
    assertTrue(store(valid).forPreview("nothing-published-here").isEmpty())
  }

  @Test
  fun `a zero-area box is dropped, and a preview left with nothing drops with it`() {
    val json =
      """{"schema":"compose-preview-tags/v1","previews":{"p":{
         "flat":{"count":1,"bounds":{"x":0,"y":0,"width":0,"height":10},"space":"render-pixels"}}}}"""
    assertTrue(store(json).isEmpty, "a preview whose only entry is unusable should not be listed")
  }

  @Test
  fun `an oversized index is refused wholesale`() {
    val previews =
      (0..ServeTagIndexStore.MAX_PREVIEWS).joinToString(",") {
        """"p$it":{"t":{"count":1,"space":"render-pixels"}}"""
      }
    assertTrue(store("""{"schema":"compose-preview-tags/v1","previews":{$previews}}""").isEmpty)
  }

  @Test
  fun `unknown keys are tolerated so a newer producer does not break an older host`() {
    val json =
      """{"schema":"compose-preview-tags/v1","generatedAt":"2026-01-01","previews":{"p":{
         "t":{"count":2,"space":"render-pixels","somethingNew":true}}}}"""
    assertEquals(2, store(json).forPreview("p").getValue("t").count)
  }

  /**
   * The discriminator has to be *declared*, not inferred. Defaulting a missing `space` would make
   * an index that stated no coordinate space indistinguishable from one that stated render pixels,
   * and a later element gate would compare bounds in a plane nobody declared.
   */
  @Test
  fun `an entry with no declared space is refused`() {
    val json =
      """{"schema":"compose-preview-tags/v1","previews":{"p":{
         "t":{"count":1,"bounds":{"x":0,"y":0,"width":8,"height":8}}}}}"""
    assertTrue(store(json).isEmpty, "a missing space must not default to render-pixels")
  }

  /**
   * An unknown space is refused too — a future canonical producer must not read as render-pixel.
   */
  @Test
  fun `an entry with an unrecognised space is refused`() {
    val json =
      """{"schema":"compose-preview-tags/v1","previews":{"p":{
         "t":{"count":1,"space":"canonical-plane"}}}}"""
    assertTrue(store(json).isEmpty)
  }

  @Test
  fun `a non-positive count is refused`() {
    val json =
      """{"schema":"compose-preview-tags/v1","previews":{"p":{
         "t":{"count":0,"space":"render-pixels"}}}}"""
    assertTrue(store(json).isEmpty)
  }
}
