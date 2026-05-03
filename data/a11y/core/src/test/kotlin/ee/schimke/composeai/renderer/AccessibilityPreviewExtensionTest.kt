package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.render.pipeline.PipelineCapability
import ee.schimke.composeai.data.render.pipeline.PreviewPipelinePlan
import ee.schimke.composeai.data.render.pipeline.PreviewPipelineValidator
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityPreviewExtensionTest {
  @Test
  fun overlayCanUseFinalSampleExtraction() {
    val plan =
      PreviewPipelinePlan(
        steps =
          listOf(
            AccessibilityChecksPreviewExtension.finalSampleExtractor,
            AccessibilityOverlayPreviewExtension.overlayProcessor,
          ),
        initialCapabilities =
          setOf(
            PipelineCapability.SingleFrame,
            PipelineCapability.DeviceGeometry,
            PipelineCapability.SemanticsSnapshot,
            PipelineCapability.ImageArtifact,
          ),
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }

  @Test
  fun overlayCanUseEachFrameExtraction() {
    val plan =
      PreviewPipelinePlan(
        steps =
          listOf(
            AccessibilityChecksPreviewExtension.eachFrameExtractor,
            AccessibilityOverlayPreviewExtension.overlayProcessor,
          ),
        initialCapabilities =
          setOf(
            PipelineCapability.Frames,
            PipelineCapability.MultipleFrames,
            PipelineCapability.DeviceGeometry,
            PipelineCapability.SemanticsSnapshot,
            PipelineCapability.ImageArtifact,
          ),
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }
}
