package ee.schimke.composeai.scroll

import ee.schimke.composeai.data.render.pipeline.PipelineCapability
import ee.schimke.composeai.data.render.pipeline.PipelineStepTrait
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionUsageMode
import ee.schimke.composeai.data.render.pipeline.PreviewPipelineStep

/** Pipeline metadata for the scroll preview extension. */
object ScrollPreviewExtension {
  const val ID: String = "scroll"
  const val KIND_LONG: String = "render/scroll/long"
  const val KIND_GIF: String = "render/scroll/gif"

  /** Long scroll owns scroll position over a sequence of viewport captures. */
  val longScrollScenario: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "scroll.long.scenario",
      displayName = "Long scroll scenario",
      usageModes =
        setOf(PreviewExtensionUsageMode.ExplicitEffect, PreviewExtensionUsageMode.SuggestedExtraPreview),
      traits = setOf(PipelineStepTrait.ScenarioDriver),
      provides =
        setOf(
          PipelineCapability.Frames,
          PipelineCapability.MultipleFrames,
          PipelineCapability.ScrollState,
          PipelineCapability.DeviceGeometry,
          PipelineCapability.SemanticsSnapshot,
          PipelineCapability.ImageArtifact,
        ),
    )

  /** GIF scroll owns scroll position and frame timing over a sequence of viewport captures. */
  val gifScrollScenario: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "scroll.gif.scenario",
      displayName = "Scroll GIF scenario",
      usageModes =
        setOf(PreviewExtensionUsageMode.ExplicitEffect, PreviewExtensionUsageMode.SuggestedExtraPreview),
      traits = setOf(PipelineStepTrait.ScenarioDriver),
      provides =
        setOf(
          PipelineCapability.Frames,
          PipelineCapability.MultipleFrames,
          PipelineCapability.ScrollState,
          PipelineCapability.DeviceGeometry,
          PipelineCapability.SemanticsSnapshot,
          PipelineCapability.ImageArtifact,
        ),
    )

  /** Stitches long-scroll frames into one final still image. */
  val longScrollEncoder: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "scroll.long.encoder",
      displayName = "Long scroll stitcher",
      productKinds = listOf(KIND_LONG),
      usageModes =
        setOf(PreviewExtensionUsageMode.ExplicitEffect, PreviewExtensionUsageMode.SuggestedExtraPreview),
      traits = setOf(PipelineStepTrait.Encoder),
      requires = setOf(PipelineCapability.MultipleFrames, PipelineCapability.ImageArtifact),
      provides = setOf(PipelineCapability.ImageArtifact),
    )

  /** Encodes scroll frames into an animated GIF. */
  val gifScrollEncoder: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "scroll.gif.encoder",
      displayName = "Scroll GIF encoder",
      productKinds = listOf(KIND_GIF),
      usageModes =
        setOf(PreviewExtensionUsageMode.ExplicitEffect, PreviewExtensionUsageMode.SuggestedExtraPreview),
      traits = setOf(PipelineStepTrait.Encoder),
      requires = setOf(PipelineCapability.MultipleFrames, PipelineCapability.ImageArtifact),
      provides = setOf(PipelineCapability.AnimatedArtifact),
    )

  val descriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = ID,
      displayName = "Scroll",
      steps = listOf(longScrollScenario, gifScrollScenario, longScrollEncoder, gifScrollEncoder),
    )
}
