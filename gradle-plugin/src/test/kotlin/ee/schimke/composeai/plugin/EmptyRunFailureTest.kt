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
  fun `a theme fan-out's suffixed siblings count as written`() {
    // DroidKaigi's :core:ui fans every preview over five named themes, so the renderer writes
    // `Foo-<hash>_DeepTeal.png` and friends and never the bare path the capture was scheduled at.
    // Judged on exact paths it produced 450 files and still failed with "none produced a file".
    png("CollapsingHeaderLayoutPreview-b2afa1fb_DeepTeal.png", 8)
    png("CollapsingHeaderLayoutPreview-b2afa1fb_SakuraPlum.png", 8)
    assertThat(
        RenderPreviewsTask.emptyRunFailure(
          listOf(File(tmp.root, "CollapsingHeaderLayoutPreview-b2afa1fb.png"))
        )
      )
      .isNull()
  }

  @Test
  fun `an unrelated file sharing no stem is not a surviving capture`() {
    // The stem match must not degrade into "something is in the directory": a genuinely empty run
    // that happens to sit beside another preview's output is still an empty run.
    png("SomethingElse_DeepTeal.png", 8)
    assertThat(RenderPreviewsTask.emptyRunFailure(listOf(File(tmp.root, "Foo.png")))).isNotNull()
  }

  @Test
  fun `an empty suffixed sibling does not count as written`() {
    png("Foo_DeepTeal.png", 0)
    assertThat(RenderPreviewsTask.emptyRunFailure(listOf(File(tmp.root, "Foo.png")))).isNotNull()
  }

  @Test
  fun `a stem that is a prefix of another preview's name is not a match`() {
    // `Foo` must not be satisfied by `FooBar_DeepTeal.png` — the separator is part of the match.
    png("FooBar_DeepTeal.png", 8)
    assertThat(RenderPreviewsTask.emptyRunFailure(listOf(File(tmp.root, "Foo.png")))).isNotNull()
  }

  @Test
  fun `a run that scheduled nothing is not a failure`() {
    // A filtered or preview-less module renders zero captures and no-ops cleanly, as it always has.
    assertThat(RenderPreviewsTask.emptyRunFailure(emptyList())).isNull()
  }
}
