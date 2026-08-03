package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.FileSystem

/**
 * The annotation layer is a reading aid over the compare page. Every failure mode here must degrade
 * to "no annotations" rather than taking the page down, which is what these cover.
 */
class ServeAnnotationStoreTest {
  private fun store(json: String?): ServeAnnotationStore {
    val root = Files.createTempDirectory("annotations").toFile().also { it.deleteOnExit() }
    if (json != null) {
      File(root, ServeAnnotationStore.DIRECTORY).mkdirs()
      File(root, "${ServeAnnotationStore.DIRECTORY}/${ServeAnnotationStore.INDEX_FILE}")
        .writeText(json)
    }
    return ServeAnnotationStore.load(root, FileSystem.SYSTEM)
  }

  @Test
  fun `no manifest yields an empty store`() {
    assertTrue(store(null).isEmpty)
  }

  @Test
  fun `malformed manifest is ignored rather than thrown`() {
    assertTrue(store("{ not json").isEmpty)
  }

  @Test
  fun `a manifest from a future schema is ignored`() {
    val json =
      """{"schema":"compose-preview-annotations/v99","previews":{"p":[
         {"kind":"layout","bounds":{"x":0,"y":0,"width":8,"height":8},"label":"pad 8dp"}]}}"""
    assertTrue(store(json).isEmpty)
  }

  @Test
  fun `annotations are exposed per preview and per reference`() {
    val json =
      """{"schema":"compose-preview-annotations/v1",
         "previews":{"button__light":[
           {"kind":"typography","bounds":{"x":4,"y":6,"width":40,"height":12},
            "label":"bodyMedium 14sp/20","role":"Label"}]},
         "references":{"design-button":[
           {"kind":"layout","bounds":{"x":0,"y":0,"width":80,"height":32},
            "label":"pad 16dp"}]}}"""
    val loaded = store(json)
    assertEquals(1, loaded.forPreview("button__light").size)
    assertEquals("bodyMedium 14sp/20", loaded.forPreview("button__light").single().label)
    assertEquals("Label", loaded.forPreview("button__light").single().role)
    assertEquals(1, loaded.forReference("design-button").size)
    assertTrue(loaded.forPreview("nope").isEmpty())
    assertTrue(loaded.forReference("nope").isEmpty())
  }

  @Test
  fun `undrawable and unknown records are dropped but their siblings survive`() {
    val json =
      """{"schema":"compose-preview-annotations/v1","previews":{"p":[
         {"kind":"layout","bounds":{"x":0,"y":0,"width":0,"height":8},"label":"zero width"},
         {"kind":"layout","bounds":{"x":-4,"y":0,"width":8,"height":8},"label":"negative origin"},
         {"kind":"spacing","bounds":{"x":0,"y":0,"width":8,"height":8},"label":"unknown kind"},
         {"kind":"typography","bounds":{"x":0,"y":0,"width":8,"height":8},"label":"  "},
         {"kind":"typography","bounds":{"x":1,"y":2,"width":8,"height":8},"label":"kept"}]}}"""
    val kept = store(json).forPreview("p")
    assertEquals(listOf("kept"), kept.map { it.label })
  }

  /**
   * A served catalog is a staging directory assembled from fetched parts, so the store has to read
   * the manifest from wherever staging put it — the published tree is never what is loaded. This
   * pins the directory/filename contract the staging step writes to; if the two ever disagree, a
   * catalog serves silently without annotations, which is exactly the failure this guards.
   */
  @Test
  fun `store reads the manifest from the staged bundle layout`() {
    val root = Files.createTempDirectory("staging").toFile().also { it.deleteOnExit() }
    val staged = File(root, "${ServeAnnotationStore.DIRECTORY}/${ServeAnnotationStore.INDEX_FILE}")
    staged.parentFile.mkdirs()
    staged.writeText(
      """{"schema":"compose-preview-annotations/v1","previews":{"p":[
         {"kind":"layout","bounds":{"x":0,"y":0,"width":8,"height":8},"label":"pad 8dp"}]}}"""
    )
    assertEquals(
      "pad 8dp",
      ServeAnnotationStore.load(root, FileSystem.SYSTEM).forPreview("p").single().label,
    )
  }

  /**
   * The payload is embedded in a `<script type="application/json">` block, where a literal
   * `</script>` inside a string value would end the block early and truncate the JSON.
   */
  @Test
  fun `payload encoding escapes markup that would close the script block`() {
    val encoded =
      encodeAnnotationPayload(
        AnnotationPayload(
          actual =
            listOf(
              DesignAnnotation(
                kind = AnnotationKind.TYPOGRAPHY,
                bounds = AnnotationBounds(0, 0, 4, 4),
                label = "</script><img src=x>",
              )
            )
        )
      )
    assertTrue(encoded.contains("\\u003c"), "expected escaped markup in: $encoded")
    assertTrue(!encoded.contains("</script>"), "raw closing tag survived in: $encoded")
  }
}
