package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `composePreviewRenderAll`'s missing-render post-condition, under a **narrowed** render
 * (issue #3730).
 *
 * The gate exists to catch a whole-module wiring failure (`composePreviewRender` silently reporting
 * NO-SOURCE), and it did that by requiring a PNG for every entry in the manifest. That is exactly
 * wrong for a filtered run: `-PcomposePreview.filter` / `.idFilter` deliberately renders a subset
 * and leaves every other preview at whatever the previous run wrote — nothing at all on a clean
 * tree — so the gate would fail the very run the filter asked for. It only *appeared* to pass
 * before because the validation task's inputs didn't mention the filters, so a second run with a
 * different filter came back UP-TO-DATE and never reached the check.
 */
class FilteredRenderValidationTest {

  @get:Rule val tempDir = TemporaryFolder()

  private fun preview(id: String, functionName: String = id) =
    PreviewInfo(
      id = id,
      functionName = functionName,
      className = "com.example.PreviewsKt",
      captures = listOf(Capture(renderOutput = "renders/$id.png")),
    )

  private val previews =
    listOf(
      preview("FontScale100Preview"),
      preview("FontScale150Preview"),
      preview("FontScale200Preview"),
    )

  private val manifest = PreviewManifest(module = "cmp", variant = "debug", previews = previews)

  private fun render(vararg ids: String) {
    val renders = tempDir.root.resolve("renders").apply { mkdirs() }
    for (id in ids) renders.resolve("$id.png").writeBytes(byteArrayOf(1, 2, 3))
  }

  @Test
  fun `an unfiltered run still gates on every preview`() {
    render("FontScale100Preview")

    val missing =
      ComposePreviewTasks.missingPreviewOutputIds(manifest, tempDir.root, isFastTier = false)

    assertThat(missing).containsExactly("FontScale150Preview", "FontScale200Preview")
  }

  @Test
  fun `an id-filtered run gates only on the previews it rendered`() {
    render("FontScale200Preview")

    val validated =
      ComposePreviewTasks.selectFilteredPreviews(
        previews,
        nameFilters = emptyList(),
        idFilters = listOf("=FontScale200Preview"),
        idExcludes = emptyList(),
      )
    val missing =
      ComposePreviewTasks.missingPreviewOutputIds(
        manifest,
        tempDir.root,
        isFastTier = false,
        validate = validated,
      )

    assertThat(missing).isEmpty()
  }

  @Test
  fun `a filtered run still fails when the preview it did ask for produced nothing`() {
    // The gate keeps its teeth where it matters: the requested preview blew up, and a narrowed
    // render must not become a way to render nothing and call it a success.
    val validated =
      ComposePreviewTasks.selectFilteredPreviews(
        previews,
        nameFilters = listOf("FontScale200Preview"),
        idFilters = emptyList(),
        idExcludes = emptyList(),
      )
    val missing =
      ComposePreviewTasks.missingPreviewOutputIds(
        manifest,
        tempDir.root,
        isFastTier = false,
        validate = validated,
      )

    assertThat(missing).containsExactly("FontScale200Preview")
  }

  @Test
  fun `excluded ids drop out of the gate`() {
    render("FontScale100Preview")

    val validated =
      ComposePreviewTasks.selectFilteredPreviews(
        previews,
        nameFilters = emptyList(),
        idFilters = emptyList(),
        idExcludes = listOf("FontScale150Preview", "FontScale200Preview"),
      )

    assertThat(validated.map { it.id }).containsExactly("FontScale100Preview")
    assertThat(
        ComposePreviewTasks.missingPreviewOutputIds(
          manifest,
          tempDir.root,
          isFastTier = false,
          validate = validated,
        )
      )
      .isEmpty()
  }

  @Test
  fun `filters compose in the same order the render applies them`() {
    // name filter first, then the id filter over what it kept — mirroring
    // `RenderPreviewsTask.render` / `PreviewFilter.select`.
    val validated =
      ComposePreviewTasks.selectFilteredPreviews(
        previews,
        nameFilters = listOf("FontScale1*"),
        idFilters = listOf("*200*"),
        idExcludes = emptyList(),
      )

    assertThat(validated).isEmpty()
  }

  @Test
  fun `an empty filter list keeps every preview`() {
    assertThat(
        ComposePreviewTasks.selectFilteredPreviews(
          previews,
          nameFilters = listOf("", "  "),
          idFilters = emptyList(),
          idExcludes = emptyList(),
        )
      )
      .isEqualTo(previews)
  }
}
