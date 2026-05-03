package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.render.pipeline.ExtractionSpec
import ee.schimke.composeai.data.render.pipeline.PipelineCapability
import ee.schimke.composeai.data.render.pipeline.PipelineStepTrait
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor
import ee.schimke.composeai.data.render.pipeline.PreviewPipelineStep
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy

/** Pipeline metadata for accessibility checks. */
object AccessibilityChecksPreviewExtension {
  const val ID: String = "a11y-checks"
  const val KIND_ATF: String = "a11y/atf"
  const val KIND_HIERARCHY: String = "a11y/hierarchy"
  const val KIND_TOUCH_TARGETS: String = "a11y/touchTargets"

  /**
   * Static/single-output a11y extraction: collect semantics and ATF findings at the final render
   * sample.
   */
  val finalSampleExtractor: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "a11y.extract.end",
      displayName = "Accessibility extraction",
      productKinds = listOf(KIND_ATF, KIND_HIERARCHY, KIND_TOUCH_TARGETS),
      traits = setOf(PipelineStepTrait.DataExtractor, PipelineStepTrait.Check),
      requires = setOf(PipelineCapability.SemanticsSnapshot),
      provides = setOf(PipelineCapability.AccessibilityFindings),
      extraction =
        ExtractionSpec(
          kind = KIND_ATF,
          sampling = SamplingPolicy.End,
          requiresSemantics = true,
        ),
    )

  /**
   * Animated/scrolling a11y extraction: collect findings for each captured frame so overlays can be
   * painted before GIF encoding.
   */
  val eachFrameExtractor: PreviewPipelineStep =
    finalSampleExtractor.copy(
      id = "a11y.extract.eachFrame",
      productKinds = listOf(KIND_ATF, KIND_HIERARCHY, KIND_TOUCH_TARGETS),
      extraction =
        ExtractionSpec(
          kind = KIND_ATF,
          sampling = SamplingPolicy.EachFrame,
          requiresImage = true,
          requiresSemantics = true,
          aggregate = true,
        ),
    )

  val descriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = ID,
      displayName = "Accessibility checks",
      steps = listOf(finalSampleExtractor, eachFrameExtractor),
    )
}

/** Pipeline metadata for painting accessibility findings onto rendered images. */
object AccessibilityOverlayPreviewExtension {
  const val ID: String = "a11y-overlay"
  const val KIND_OVERLAY: String = "a11y/overlay"

  val overlayProcessor: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "a11y.overlay",
      displayName = "Accessibility overlay",
      productKinds = listOf(KIND_OVERLAY),
      traits = setOf(PipelineStepTrait.FrameProcessor),
      requires =
        setOf(
          PipelineCapability.ImageArtifact,
          PipelineCapability.AccessibilityFindings,
          PipelineCapability.DeviceGeometry,
        ),
      provides = setOf(PipelineCapability.ImageArtifact),
    )

  val descriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = ID,
      displayName = "Accessibility overlay",
      steps = listOf(overlayProcessor),
    )
}
