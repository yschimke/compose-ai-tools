package ee.schimke.composeai.daemon

import androidx.compose.ui.ImageComposeScene
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The two seams that make the advanced performance surface work:
 * - `RenderResult.trace` — real phase spans, which `render/trace` v2 reports;
 * - `sceneLifecycleListener` — the hook that lets `compose/recomposition` instrument an ordinary
 *   render rather than only a held interactive session.
 *
 * Both are asserted against a real Compose Desktop render, because both are about *when* things
 * happen relative to composition and a fake host can't tell you that.
 */
class RenderEngineTraceTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private fun redSquareSpec(outputBaseName: String) =
    RenderSpec(
      previewId = "ee.schimke.composeai.daemon.RedFixturePreviewsKt.RedSquare",
      className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
      functionName = "RedSquare",
      widthPx = 64,
      heightPx = 64,
      density = 1.0f,
      showBackground = true,
      outputBaseName = outputBaseName,
    )

  @Test
  fun `a render carries its own phase spans`() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders"))

    val result = engine.render(redSquareSpec("trace-spans"), requestId = 1L)

    val trace = result.trace
    assertNotNull("a desktop render must carry phase spans for render/trace v2", trace)
    assertEquals("desktop", trace!!.backend)
    val names = trace.spans.map { it.name }
    // The three phases the one-shot wrapper owns. Their presence is what proves the trace is
    // snapshotted after `finally`, not from inside `renderOnce` — `compose:tearDown` runs there.
    assertTrue("expected compose:setUp in $names", "compose:setUp" in names)
    assertTrue("expected render:once in $names", "render:once" in names)
    assertTrue("expected compose:tearDown in $names", "compose:tearDown" in names)
    // …and at least one phase nested inside `render:once`, which is the structure v1 could not
    // express at all.
    assertTrue("expected a nested phase in $names", trace.spans.any { it.depth > 0 })
    assertTrue("total time must be positive", trace.totalMicros > 0)
    assertEquals(
      "every span must be accounted for in the aggregates",
      trace.spans.size,
      trace.sections.sumOf { it.count },
    )
  }

  @Test
  fun `the scene is announced before composition and released at teardown`() {
    // `compose/recomposition` installs a CompositionObserver on the scene's recomposer. Installing
    // after `setContent` would miss the initial composition — the very thing worth counting — so
    // the ordering here is the contract, not an implementation detail.
    val events = CopyOnWriteArrayList<Pair<String, Boolean>>()
    var sceneAtAnnounce: ImageComposeScene? = null
    val engine =
      RenderEngine(
        outputDir = tempFolder.newFolder("renders"),
        sceneLifecycleListener = { previewId, scene ->
          events += previewId to (scene != null)
          if (scene != null) sceneAtAnnounce = scene
        },
      )

    engine.render(redSquareSpec("trace-lifecycle"), requestId = 1L)

    assertEquals(
      listOf(
        "ee.schimke.composeai.daemon.RedFixturePreviewsKt.RedSquare" to true,
        "ee.schimke.composeai.daemon.RedFixturePreviewsKt.RedSquare" to false,
      ),
      events.toList(),
    )
    assertNotNull("the listener must be handed the live scene, not null", sceneAtAnnounce)
  }

  @Test
  fun `a listener that throws cannot fail the render it is observing`() {
    val engine =
      RenderEngine(
        outputDir = tempFolder.newFolder("renders"),
        sceneLifecycleListener = { _, _ -> error("producer blew up") },
      )

    val result = engine.render(redSquareSpec("trace-listener-throws"), requestId = 1L)

    assertNotNull("the render must still produce a PNG", result.pngPath)
  }
}
