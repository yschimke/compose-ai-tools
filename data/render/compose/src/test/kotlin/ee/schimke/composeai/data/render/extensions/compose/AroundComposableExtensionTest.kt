package ee.schimke.composeai.data.render.extensions.compose

import androidx.compose.runtime.Composable
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionLifecycle
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AroundComposableExtensionTest {
  @Test
  fun declaresAroundComposableHookForSimpleWrapperExtensions() {
    val extension = SimpleBackgroundExtension()
    val hook: AroundComposableHook = extension

    assertEquals(DataExtensionId("render-device-background"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AroundComposable), extension.hooks)
    assertEquals(extension, hook)
  }

  @Test
  fun declaresComposableExtractorHookForSimpleExtractorExtensions() {
    val extension = SimpleThemeExtractorExtension()
    val hook: ComposableExtractorHook = extension

    assertEquals(DataExtensionId("compose-theme"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.ComposableExtractor), extension.hooks)
    assertEquals(extension, hook)
  }

  @Test
  fun declaresCompositionObserverHookForSimpleObserverExtensions() {
    val extension = SimpleRecompositionObserverExtension()
    val hook: CompositionObserverHook = extension

    assertEquals(DataExtensionId("compose-recomposition"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.CompositionObserver), extension.hooks)
    assertEquals(extension, hook)
  }

  @Test
  fun declaresScenarioDriverHookForNormalizedFrameDrivers() {
    val extension = SimpleScrollFrameDriverExtension()
    val hook: FrameDriverHook = extension
    val sequence =
      hook.frames(
        ExtensionFrameContext(extensionId = extension.id, previewId = "preview", renderMode = "gif")
      )

    assertEquals(DataExtensionId("scroll-gif"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.ScenarioDriver), extension.hooks)
    assertEquals(DataExtensionPhase.Scenario, extension.constraints.phase)
    assertEquals(DataExtensionLifecycle.MultiFrame, extension.constraints.lifecycle)
    assertEquals(true, extension.hasFrameDriverHook)
    assertEquals(listOf(0f, 0.5f, 1f), sequence.frames.map { it.fraction })
    assertEquals(0.25f, sequence.curve.transform(0.5f))
  }

  @Test
  fun declaresAfterCaptureHookForImageFrameTransforms() {
    val extension = SimpleAnimationCurveOverlayExtension()
    val hook: ImageFrameTransformHook<String> = extension
    val transformed =
      hook.transform(
        ImageFrameTransformInput(
          image = "frame",
          frame = ExtensionFrame(index = 2, fraction = 0.5f, timeMillis = 160L, delayMillis = 80),
          sequence =
            ExtensionFrameSequence(frames = emptyList(), extras = mapOf("curve" to "ease-in")),
        )
      )

    assertEquals(DataExtensionId("animation-curve-overlay"), extension.id)
    assertEquals(setOf(DataExtensionHookKind.AfterCapture), extension.hooks)
    assertEquals(DataExtensionPhase.PostProcess, extension.constraints.phase)
    assertEquals(true, extension.hasImageFrameTransformHook)
    assertEquals("frame@0.5", transformed)
  }

  @Test
  fun extensionContextExposesTypedExtractionDataAndSlotTables() {
    val scrollAxisKey = ExtensionContextKey("scroll-axis", String::class.java)
    val extraction =
      ExtensionExtractionContext(
        slotTables = ExtensionSlotTables.Empty,
        data = ExtensionContextData.of(scrollAxisKey provides "vertical"),
      )
    val context =
      ExtensionComposeContext(
        extensionId = DataExtensionId("scroll"),
        previewId = "preview",
        renderMode = "gif",
        extraction = extraction,
      )

    assertEquals(emptyList<Any>(), context.slotTables.snapshot())
    assertEquals("vertical", context.get(scrollAxisKey))
    assertEquals("vertical", context.require(scrollAxisKey))
    assertTrue(extraction.data.contains(scrollAxisKey))
    assertNull(context.get(ExtensionContextKey("missing", String::class.java)))
  }

  @Test
  fun extensionContextExposesTypedStateExports() {
    val owner = DataExtensionId("scroll-gif")
    val framesKey = ExtensionStateKey(owner = owner, name = "frames", type = String::class.java)
    val registry = RecordingExtensionStateRegistry()
    val context =
      ExtensionComposeContext(
        extensionId = owner,
        previewId = "preview",
        renderMode = "gif",
        states = registry,
      )

    context.exportState(framesKey, staticExtensionState("planned"))

    assertEquals("planned", context.value(framesKey))
    assertEquals("planned", context.state(framesKey)?.value)
    assertEquals("planned", registry.requireValue(framesKey))
    assertNull(
      context.state(ExtensionStateKey(owner = owner, name = "missing", String::class.java))
    )
  }

  private class SimpleBackgroundExtension :
    AroundComposableExtension(DataExtensionId("render-device-background")) {
    @Composable
    override fun AroundComposable(content: @Composable () -> Unit) {
      content()
    }
  }

  private class SimpleThemeExtractorExtension :
    ComposableExtractorExtension(DataExtensionId("compose-theme")) {
    @Composable
    override fun Extract(sink: ExtensionCompositionSink) {
      sink.put(id, "theme", "material")
    }
  }

  private class SimpleRecompositionObserverExtension :
    CompositionObserverExtension(DataExtensionId("compose-recomposition")) {
    @Composable
    override fun Observe(sink: ExtensionCompositionSink) {
      sink.put(id, "observer", "installed")
    }
  }

  private class SimpleScrollFrameDriverExtension :
    NormalizedFrameDriverExtension(DataExtensionId("scroll-gif")) {
    override fun frames(context: ExtensionFrameContext): ExtensionFrameSequence =
      ExtensionFrameSequence(
        frames =
          listOf(
            ExtensionFrame(index = 0, fraction = 0f, timeMillis = 0L, delayMillis = 80),
            ExtensionFrame(index = 1, fraction = 0.5f, timeMillis = 80L, delayMillis = 80),
            ExtensionFrame(index = 2, fraction = 1f, timeMillis = 160L, delayMillis = 80),
          ),
        curve = ExtensionFrameCurve { fraction -> fraction * fraction },
        extras = mapOf("axis" to "vertical"),
      )
  }

  private class SimpleAnimationCurveOverlayExtension :
    ImageFrameTransformExtension<String>(DataExtensionId("animation-curve-overlay")) {
    override fun transform(input: ImageFrameTransformInput<String>): String =
      "${input.image}@${input.frame.fraction}"
  }
}
