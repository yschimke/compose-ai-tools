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

  @Test
  fun `formatMissingPreviewsMessage collapses one root cause into a single counted line`() {
    // Issue #3690's shape: skiko failed to load, so the first preview carries the real error and
    // every other one carries the same cascading NoClassDefFoundError. Listing five arbitrary
    // members of that cascade is how the actual cause got buried.
    val manifest =
      PreviewManifest(
        module = "catalog",
        variant = "debug",
        previews = (1..8).map { previewWithCapture("P$it", "renders/P$it.png") },
      )
    val sidecars =
      (1..8).associate { i ->
        "P$i" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.NoClassDefFoundError",
            message = "Could not initialize class org.jetbrains.skia.Surface",
            diagnosis = "${ComposePreviewTasks.CASCADE_DIAGNOSIS_PREFIX} in this render JVM.",
          )
      }

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = (1..8).map { "P$it" },
        sidecars = sidecars,
      )

    assertThat(msg).contains("NoClassDefFoundError")
    assertThat(msg).contains("8 previews, e.g. P1, P2, P3")
    // One line covered all eight, so there is nothing left to apologise for.
    assertThat(msg).doesNotContain("more with sidecars")
  }

  @Test
  fun `formatMissingPreviewsMessage keeps distinct source frames apart`() {
    // Same exception and message, different composables: three separate bugs. Collapsing them
    // under the first one's frame would blame `A.kt:10` for failures in files it never touched.
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews = listOf("A", "B").map { previewWithCapture(it, "renders/$it.png") },
      )
    val sidecars =
      mapOf(
        "A" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.IllegalStateException",
            message = "missing state",
            topAppFrame = ComposePreviewTasks.ErrorSidecar.TopAppFrame("A.kt", 10, "APreview"),
          ),
        "B" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.IllegalStateException",
            message = "missing state",
            topAppFrame = ComposePreviewTasks.ErrorSidecar.TopAppFrame("B.kt", 20, "BPreview"),
          ),
      )

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = listOf("A", "B"),
        sidecars = sidecars,
      )

    assertThat(msg).contains("A.kt:10")
    assertThat(msg).contains("B.kt:20")
    // Two lines, each naming its own preview — not one line claiming "2 previews".
    assertThat(msg).doesNotContain("2 previews")
  }

  @Test
  fun `formatMissingPreviewsMessage leads with the real cause, not the cascade`() {
    val manifest =
      PreviewManifest(
        module = "catalog",
        variant = "debug",
        previews =
          listOf(previewWithCapture("A", "renders/A.png"), previewWithCapture("B", "renders/B.png")),
      )
    val rootCause =
      "skiko's native library could not be loaded because this process mixed two glibc builds"
    val sidecars =
      mapOf(
        // Deliberately cascade-first: the map's iteration order must not decide which explanation
        // the user is shown.
        "A" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.NoClassDefFoundError",
            message = "Could not initialize class org.jetbrains.skia.Surface",
            diagnosis = "${ComposePreviewTasks.CASCADE_DIAGNOSIS_PREFIX} in this render JVM.",
          ),
        "B" to
          ComposePreviewTasks.ErrorSidecar(
            exception = "java.lang.ExceptionInInitializerError",
            message = "",
            diagnosis = rootCause,
          ),
      )

    val msg =
      ComposePreviewTasks.formatMissingPreviewsMessage(
        manifest = manifest,
        missingIds = listOf("A", "B"),
        sidecars = sidecars,
      )

    assertThat(msg).contains(rootCause)
    // Before the per-preview list, which is where it was previously buried.
    assertThat(msg.indexOf(rootCause)).isLessThan(msg.indexOf("Per-preview render errors"))
  }
}
