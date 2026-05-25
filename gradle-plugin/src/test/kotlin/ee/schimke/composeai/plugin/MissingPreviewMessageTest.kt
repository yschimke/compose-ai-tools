package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MissingPreviewMessageTest {

  @get:Rule val tempDir = TemporaryFolder()

  private fun previewWithCapture(id: String, renderOutput: String) =
    PreviewInfo(
      id = id,
      functionName = id,
      className = "com.example.PreviewsKt",
      captures = listOf(Capture(renderOutput = renderOutput)),
    )

  @Test
  fun `formatMissingPreviewsMessage keeps legacy guidance when no sidecars exist`() {
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews = listOf(previewWithCapture("A", "renders/A.png")),
      )

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = listOf("A"),
        sidecars = emptyMap(),
      )

    // The renderAll wrapper's original "NO-SOURCE" diagnosis stays
    // intact when no .error.json sidecars are present — the task really
    // was skipped (or genuinely produced nothing), which is what the
    // legacy message was written for.
    assertThat(msg).contains("composePreviewRender")
    assertThat(msg).contains("NO-SOURCE")
    assertThat(msg).contains("1 of 1")
  }

  @Test
  fun `formatMissingPreviewsMessage surfaces sidecar exception details`() {
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews =
          listOf(previewWithCapture("A", "renders/A.png"), previewWithCapture("B", "renders/B.png")),
      )

    val sidecars =
      mapOf(
        "A" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.ClassNotFoundException",
            message = "com.example.PreviewsKt",
            topAppFrame =
              ComposePreviewTasks.ErrorSidecar.TopAppFrame(
                file = "Previews.kt",
                line = 42,
                function = "Greeting",
              ),
          )
      )

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = listOf("A", "B"),
        sidecars = sidecars,
      )

    // The misleading "NO-SOURCE / RobolectricRenderTest.class" sentence
    // is the whole reason this code exists — make sure we DON'T emit it
    // when at least one sidecar was found.
    assertThat(msg).doesNotContain("NO-SOURCE")
    assertThat(msg).doesNotContain("RobolectricRenderTest")
    // The sidecar's exception class + message + frame should all be in
    // the body so the user sees the actual failure rather than having
    // to grep for an .error.json file by hand.
    assertThat(msg).contains("ClassNotFoundException")
    assertThat(msg).contains("com.example.PreviewsKt")
    assertThat(msg).contains("Previews.kt:42")
    assertThat(msg).contains("A:")
    // Previews without a sidecar still get called out separately so a
    // mixed "some threw, some were skipped" run doesn't hide the
    // skip-class entries.
    assertThat(msg).contains("No sidecar")
    assertThat(msg).contains("B")
  }

  @Test
  fun `readErrorSidecarsFor parses sidecar JSON next to each missing preview's render path`() {
    val outDir = tempDir.root.resolve("compose-previews")
    val rendersDir = outDir.resolve("renders").apply { mkdirs() }
    // Schema mirrors RenderErrorSidecar.write — verifies we stay
    // compatible with the renderer-side encoder without taking a
    // cross-module dependency on the writer.
    rendersDir
      .resolve("A.png.error.json")
      .writeText(
        """
        {
          "schema": "compose-preview-error/v1",
          "exception": "java.lang.NoSuchMethodError",
          "message": "ComposeUiNode.setCompositeKeyHash",
          "topAppFrame": {"file": "MyPreview.kt", "line": 17, "function": "Greet"},
          "stackTrace": "..."
        }
        """
          .trimIndent()
      )
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews =
          listOf(previewWithCapture("A", "renders/A.png"), previewWithCapture("B", "renders/B.png")),
      )

    val sidecars = ComposePreviewTasks.readErrorSidecarsFor(manifest, listOf("A", "B"), outDir)

    // A has a sidecar; B does not. The map shape is exactly
    // {id -> ErrorSidecar} for the ones present — `formatMissingPreviewsMessage`
    // relies on `id !in sidecars` to bucket "skipped" vs "threw" previews.
    assertThat(sidecars.keys).containsExactly("A")
    val a = sidecars.getValue("A")
    assertThat(a.exception).isEqualTo("java.lang.NoSuchMethodError")
    assertThat(a.message).isEqualTo("ComposeUiNode.setCompositeKeyHash")
    assertThat(a.topAppFrame?.file).isEqualTo("MyPreview.kt")
    assertThat(a.topAppFrame?.line).isEqualTo(17)
  }

  @Test
  fun `formatMissingPreviewsMessage truncates long sidecar lists`() {
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews = (1..10).map { previewWithCapture("P$it", "renders/P$it.png") },
      )
    val sidecars =
      (1..10).associate { i ->
        "P$i" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.RuntimeException",
            message = "boom $i",
          )
      }

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = (1..10).map { "P$it" },
        sidecars = sidecars,
      )

    // Cap at 5 sidecar entries to keep the error block readable —
    // anything past that gets a "(+N more with sidecars)" footer rather
    // than scrolling the user off-screen.
    assertThat(msg).contains("(+5 more with sidecars)")
  }
}
