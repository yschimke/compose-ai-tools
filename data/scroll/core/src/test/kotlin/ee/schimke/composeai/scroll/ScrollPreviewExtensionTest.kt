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
  fun longFrameDriverDeclaresScenarioHookAndPlansSlicesAtUniformStride() {
    val plan =
      ScrollLongFramePlan(contentExtentPxHint = 1600f, viewportPx = 400f, density = 1f)
    val extension = ScrollLongFrameDriverExtension(plan)
    val hook: FrameDriverHook = extension
    val states = RecordingExtensionStateRegistry()
    val context =
      ExtensionFrameContext(
        extensionId = extension.id,
        previewId = "preview",
        renderMode = "scroll-long",
        states = states,
      )

    val sequence = hook.frames(context)
    val longSequence = states.requireValue(ScrollLongExtensionStateKeys.Frames)

    assertEquals(DataExtensionId(ScrollPreviewExtension.KIND_LONG), extension.id)
    assertEquals(setOf(DataExtensionHookKind.ScenarioDriver), extension.hooks)
    assertEquals(DataExtensionPhase.Scenario, extension.constraints.phase)
    assertEquals(DataExtensionLifecycle.MultiFrame, extension.constraints.lifecycle)
    assertTrue(extension.hasFrameDriverHook)
    assertEquals(0f, sequence.frames.first().fraction)
    assertEquals(1f, sequence.frames.last().fraction)
    assertEquals("vertical", sequence.extras["axis"])
    assertEquals(0f, longSequence.frames.first().scrollDeltaPx)
    val nonLastDeltas = longSequence.frames.drop(1).dropLast(1).map { it.scrollDeltaPx }
    assertTrue(
      "expected uniform 320px stride for inner slices, got $nonLastDeltas",
      nonLastDeltas.all { kotlin.math.abs(it - plan.stepPx) < 0.001f },
    )
    val totalDelta = longSequence.frames.sumOf { it.scrollDeltaPx.toDouble() }.toFloat()
    assertTrue(
      "total scrolled $totalDelta should equal extent 1600",
      kotlin.math.abs(totalDelta - 1600f) < 0.001f,
    )
  }

  @Test
  fun longFrameDriverEmitsOnlyInitialFrameForEmptyContent() {
    val plan = ScrollLongFramePlan(contentExtentPxHint = 0f, viewportPx = 400f, density = 1f)
    val extension = ScrollLongFrameDriverExtension(plan)

    val sequence =
      extension.scrollFrames(
        ExtensionFrameContext(
          extensionId = extension.id,
          previewId = "preview",
          renderMode = "scroll-long",
        )
      )

    assertEquals(1, sequence.frames.size)
    assertEquals(0f, sequence.frames.single().scrollDeltaPx)
    assertEquals(0f, sequence.frames.single().frame.fraction)
  }

  @Test
  fun longFrameDriverFinalSliceClampsToRemainingExtent() {
    // 1000 px extent, 400 px viewport × 0.8 stride = 320 px per step. 4 full
    // steps of 320 (= 1280) overshoot the 1000 px extent, so the planner
    // should emit 3 full strides + 1 short one of (1000 - 960) = 40 px.
    val plan = ScrollLongFramePlan(contentExtentPxHint = 1000f, viewportPx = 400f, density = 1f)
    val extension = ScrollLongFrameDriverExtension(plan)

    val sequence =
      extension.scrollFrames(
        ExtensionFrameContext(
          extensionId = extension.id,
          previewId = "preview",
          renderMode = "scroll-long",
        )
      )

    val deltas = sequence.frames.map { it.scrollDeltaPx }
    assertEquals(listOf(0f, 320f, 320f, 320f, 40f), deltas)
    assertEquals(1f, sequence.frames.last().frame.fraction)
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
