package ee.schimke.composeai.scroll

import ee.schimke.composeai.data.render.RenderPreviewExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionLifecycle
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.ExtensionFrameContext
import ee.schimke.composeai.data.render.extensions.compose.FrameDriverHook
import ee.schimke.composeai.data.render.extensions.compose.RecordingExtensionStateRegistry
import ee.schimke.composeai.data.render.extensions.compose.hasFrameDriverHook
import ee.schimke.composeai.data.render.pipeline.PipelineCapability
import ee.schimke.composeai.data.render.pipeline.PreviewPipelinePlan
import ee.schimke.composeai.data.render.pipeline.PreviewPipelineValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollPreviewExtensionTest {
  @Test
  fun gifFrameDriverDeclaresScenarioHookAndNormalizesFrames() {
    val extension =
      ScrollGifFrameDriverExtension(
        ScrollGifFramePlan(
          contentExtentPxHint = 600f,
          viewportPx = 400f,
          density = 1f,
          frameIntervalMs = 80,
        )
      )
    val hook: FrameDriverHook = extension
    val states = RecordingExtensionStateRegistry()
    val context =
      ExtensionFrameContext(
        extensionId = extension.id,
        previewId = "preview",
        renderMode = "gif",
        states = states,
      )

    val sequence = hook.frames(context)
    val scrollSequence = states.requireValue(ScrollGifExtensionStateKeys.Frames)

    assertEquals(DataExtensionId(ScrollPreviewExtension.KIND_GIF), extension.id)
    assertEquals(setOf(DataExtensionHookKind.ScenarioDriver), extension.hooks)
    assertEquals(DataExtensionPhase.Scenario, extension.constraints.phase)
    assertEquals(DataExtensionLifecycle.MultiFrame, extension.constraints.lifecycle)
    assertTrue(extension.hasFrameDriverHook)
    assertEquals(0f, sequence.frames.first().fraction)
    assertEquals(1f, sequence.frames.last().fraction)
    assertTrue(sequence.frames.zipWithNext().all { (a, b) -> b.timeMillis >= a.timeMillis })
    assertEquals("vertical", sequence.extras["axis"])
    assertTrue((sequence.extras["scrollDeltasPx"] as List<*>).isNotEmpty())
    assertEquals(sequence.frames, scrollSequence.frames.map { it.frame })
    assertTrue(scrollSequence.frames.any { it.scrollDeltaPx > 0f })
  }

  @Test
  fun gifFrameDriverTreatsZeroFrameIntervalAsDefaultCadence() {
    val extension =
      ScrollGifFrameDriverExtension(
        ScrollGifFramePlan(
          contentExtentPxHint = 600f,
          viewportPx = 400f,
          density = 1f,
          frameIntervalMs = 0,
        )
      )

    val sequence =
      extension.scrollFrames(
        ExtensionFrameContext(extensionId = extension.id, previewId = "preview", renderMode = "gif")
      )

    assertEquals(DEFAULT_SCROLL_GIF_FRAME_INTERVAL_MS, sequence.frames[1].frame.delayMillis)
    assertEquals(HOLD_START_MS.toLong(), sequence.frames[1].frame.timeMillis)
    assertTrue(sequence.frames.zipWithNext().all { (a, b) -> b.frame.timeMillis > a.frame.timeMillis })
  }

  @Test
  fun gifScenarioCanBeClippedBeforeEncoding() {
    val plan =
      PreviewPipelinePlan(
        listOf(
          ScrollPreviewExtension.gifScrollScenario,
          RenderPreviewExtension.deviceClipProcessor,
          ScrollPreviewExtension.gifScrollEncoder,
        )
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }

  @Test
  fun annotationDescriptorSuggestsExtraPreviewsFromAnnotations() {
    val plan =
      PreviewPipelinePlan(
        steps = ScrollPreviewExtension.annotationDescriptor.steps,
        initialCapabilities = setOf(PipelineCapability.PreviewFunctionAnnotations),
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }

  @Test
  fun gifScenarioCanCollectAggregateComposeTraceWhileScrolling() {
    val plan =
      PreviewPipelinePlan(
        listOf(
          ScrollPreviewExtension.gifScrollScenario,
          RenderPreviewExtension.composeTraceProfiler,
          ScrollPreviewExtension.gifScrollEncoder,
        )
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }

  @Test
  fun gifDescriptorIsIndependentlyValid() {
    val plan = PreviewPipelinePlan(ScrollPreviewExtension.gifScrollDescriptor.steps)

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }

  @Test
  fun longScenarioCanBeClippedBeforeStitching() {
    val plan =
      PreviewPipelinePlan(
        listOf(
          ScrollPreviewExtension.longScrollScenario,
          RenderPreviewExtension.deviceClipProcessor,
          ScrollPreviewExtension.longScrollEncoder,
        )
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }

  @Test
  fun longDescriptorIsIndependentlyValid() {
    val plan = PreviewPipelinePlan(ScrollPreviewExtension.longScrollDescriptor.steps)

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }
}
