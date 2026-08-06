package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.compose.CompositionTracing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Composable-level composition spans, end to end through a real Compose Desktop render.
 *
 * The mechanism is the Compose compiler's own `traceEventStart`/`traceEventEnd` call sites, which
 * only exist if the previews were compiled with source information — so this has to run against a
 * genuinely compiled fixture rather than a fake, or it would prove nothing about the real path.
 */
class CompositionTracingTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @After
  fun clearProperty() {
    System.clearProperty(CompositionTracing.ENABLED_PROP)
  }

  private fun redSquareSpec(outputBaseName: String) =
    RenderSpec(
      previewId = "composition-trace",
      className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
      functionName = "RedSquare",
      widthPx = 64,
      heightPx = 64,
      density = 1.0f,
      showBackground = true,
      outputBaseName = outputBaseName,
    )

  @Test
  fun offByDefaultTheTraceCarriesOnlyEnginePhases() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders"))

    val trace = engine.render(redSquareSpec("no-composition"), requestId = 1L).trace

    assertNotNull(trace)
    assertTrue(
      "composition spans must not appear unless asked for: " +
        trace!!.spans.map { it.name }.take(5),
      trace.spans.none { it.category == CompositionTracing.CATEGORY },
    )
  }

  @Test
  fun whenEnabledComposableSpansNestInsideSetContent() {
    System.setProperty(CompositionTracing.ENABLED_PROP, "true")
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders"))

    val trace = engine.render(redSquareSpec("with-composition"), requestId = 1L).trace

    assertNotNull(trace)
    val composition = trace!!.spans.filter { it.category == CompositionTracing.CATEGORY }
    assertTrue("expected composable spans, got none", composition.isNotEmpty())
    // The compiler names them with source information, which is the whole value: a bare key would
    // be unactionable.
    assertTrue(
      "composable spans should be source-named: ${composition.first().name}",
      composition.any { it.name.contains(".kt:") },
    )
    // They sit inside `compose:setContent`, so every one is deeper than that phase.
    val setContent = trace.spans.single { it.name == "compose:setContent" }
    assertTrue(
      "composition spans must nest inside compose:setContent",
      composition.all { it.depth > setContent.depth },
    )
    // The engine's own phases are still there and still on their own category.
    assertTrue(trace.spans.any { it.name == "render:once" })
  }

  @Test
  fun theTracerIsUninstalledAfterTheRender() {
    System.setProperty(CompositionTracing.ENABLED_PROP, "true")
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders"))
    engine.render(redSquareSpec("first"), requestId = 1L)
    System.clearProperty(CompositionTracing.ENABLED_PROP)

    // A second render with the gate off must be clean — the tracer is process-global, so a leaked
    // one would silently keep instrumenting every later render in the daemon.
    val trace = engine.render(redSquareSpec("second"), requestId = 2L).trace

    assertEquals(
      emptyList<String>(),
      trace!!.spans.filter { it.category == CompositionTracing.CATEGORY }.map { it.name },
    )
  }
}
