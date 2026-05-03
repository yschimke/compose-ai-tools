package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.render.RenderPreviewExtension
import ee.schimke.composeai.data.render.pipeline.PipelineCapability
import ee.schimke.composeai.data.render.pipeline.PreviewPipelinePlan
import ee.schimke.composeai.data.render.pipeline.PreviewPipelineValidator
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityPreviewExtensionTest {
  @Test
  fun atfChecksCanRunOverAccessibilityNodes() {
    val plan =
      PreviewPipelinePlan(
        steps = listOf(AtfChecksPreviewExtension.finalSampleChecker),
        initialCapabilities = setOf(PipelineCapability.AccessibilityNodes),
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }

  @Test
  fun genericOverlayLegendCanUseAccessibilityOverlayAnnotations() {
    val plan =
      PreviewPipelinePlan(
        steps =
          listOf(
            AccessibilityOverlayPreviewExtension.annotationProcessor,
            RenderPreviewExtension.overlayLegendProcessor,
          ),
        initialCapabilities =
          setOf(
            PipelineCapability.ImageArtifact,
            PipelineCapability.AccessibilityNodes,
            PipelineCapability.AccessibilityFindings,
          ),
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }

  @Test
  fun overlayCanUseFinalSampleExtraction() {
    val plan =
      PreviewPipelinePlan(
        steps =
          listOf(
            AccessibilitySemanticsPreviewExtension.finalSampleExtractor,
            AtfChecksPreviewExtension.finalSampleChecker,
            AccessibilityOverlayPreviewExtension.annotationProcessor,
            RenderPreviewExtension.overlayLegendProcessor,
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
            AccessibilitySemanticsPreviewExtension.eachFrameExtractor,
            AtfChecksPreviewExtension.eachFrameChecker,
            AccessibilityOverlayPreviewExtension.annotationProcessor,
            RenderPreviewExtension.overlayLegendProcessor,
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

  @Test
  fun annotatedPreviewDescriptorComposesA11yChecksAndGenericOverlayLegend() {
    val plan =
      PreviewPipelinePlan(
        steps = AccessibilityAnnotatedPreviewExtension.descriptor.steps,
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
}
