package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A render run where captures were scheduled and not one file came out is an environment or
 * classpath fault, and used to exit 0 — the renderer swallows its own per-capture failures, so an
 * `encodeToData` that no longer links reported "N capture(s) drawn" and published an empty sticker
 * sheet with a green build behind it (compose-ai-tools#4190).
 */
class EmptyRunFailureTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun png(name: String, bytes: Int): File =
    tmp.newFile(name).apply { writeBytes(ByteArray(bytes)) }

  @Test
  fun `a run that wrote nothing fails, naming an expected output`() {
    val missing = listOf(File(tmp.root, "renders/A.png"), File(tmp.root, "renders/B.png"))
    val message = RenderPreviewsTask.emptyRunFailure(missing)
    assertThat(message).contains("2 capture(s) were drawn")
    assertThat(message).contains("A.png")
  }

  @Test
  fun `one surviving capture is enough — a single broken preview is not this failure`() {
    // Deliberately all-or-nothing: a preview that cannot draw is an ordinary fact about a catalog
    // and is already reported through its `.error.json` sidecar.
    assertThat(
        RenderPreviewsTask.emptyRunFailure(listOf(png("ok.png", 8), File(tmp.root, "no.png")))
      )
      .isNull()
  }

  @Test
  fun `an empty file does not count as written`() {
    assertThat(RenderPreviewsTask.emptyRunFailure(listOf(png("empty.png", 0)))).isNotNull()
  }

  @Test
  fun `a run that scheduled nothing is not a failure`() {
    // A filtered or preview-less module renders zero captures and no-ops cleanly, as it always has.
    assertThat(RenderPreviewsTask.emptyRunFailure(emptyList())).isNull()
  }
}
