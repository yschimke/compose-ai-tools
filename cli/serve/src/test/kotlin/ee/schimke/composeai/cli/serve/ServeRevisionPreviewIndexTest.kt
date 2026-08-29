package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServeRevisionPreviewIndexTest {
  @Test
  fun `valid index maps historic commits to preview sets`() {
    val index =
      ServeRevisionPreviewIndex.parse(
        """
        {"schema":"compose-preview-revision-index/v1","current":["today"],"revisions":[
          {"commit":"1111111","previews":["old","shared"]},
          {"commit":"not-a-sha","previews":["ignored"]}]}
        """
          .trimIndent()
          .encodeToByteArray()
      )

    assertEquals(
      mapOf("2222222" to setOf("today"), "1111111" to setOf("old", "shared")),
      index?.previewsByCommit("2222222"),
    )
  }

  @Test
  fun `missing malformed and unknown indexes fail open`() {
    assertNull(ServeRevisionPreviewIndex.parse(null))
    assertNull(ServeRevisionPreviewIndex.parse("nope".encodeToByteArray()))
    assertNull(
      ServeRevisionPreviewIndex.parse("""{"schema":"future","revisions":[]}""".encodeToByteArray())
        ?.previewsByCommit("2222222")
    )
  }

  @Test
  fun `invalid current commit fails open instead of dropping every revision`() {
    val index =
      ServeRevisionPreviewIndex.parse(
        """{"schema":"compose-preview-revision-index/v1","current":["today"]}""".encodeToByteArray()
      )

    assertNull(index?.previewsByCommit("not-a-sha"))
  }
}
