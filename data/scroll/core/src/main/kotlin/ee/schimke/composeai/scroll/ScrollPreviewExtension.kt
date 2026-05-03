package ee.schimke.composeai.scroll

import ee.schimke.composeai.data.render.pipeline.PipelineCapability
import ee.schimke.composeai.data.render.pipeline.PipelineStepTrait
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionCliCommand
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionUsageMode
import ee.schimke.composeai.data.render.pipeline.PreviewPipelineStep

/** Pipeline metadata for the scroll preview extension. */
object ScrollPreviewExtension {
  const val ID: String = "scroll"
  const val ANNOTATION_ID: String = "scrolling-preview-annotation"
  const val ANNOTATION_FQN: String = "ee.schimke.composeai.preview.ScrollingPreview"
  const val KIND_LONG: String = "render/scroll/long"
  const val KIND_GIF: String = "render/scroll/gif"

  val annotationSuggester: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "scroll.annotation.suggest",
      displayName = "ScrollingPreview suggestions",
      annotationFqns = listOf(ANNOTATION_FQN),
      usageModes = setOf(PreviewExtensionUsageMode.SuggestedExtraPreview),
      traits = setOf(PipelineStepTrait.AnnotationInspector, PipelineStepTrait.ExtraPreviewSuggester),
      requires = setOf(PipelineCapability.PreviewFunctionAnnotations),
      provides = setOf(PipelineCapability.SuggestedPreviews),
    )

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

  val longScrollDescriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "scroll-long",
      displayName = "Long scroll",
      usageModes =
        setOf(PreviewExtensionUsageMode.ExplicitEffect, PreviewExtensionUsageMode.SuggestedExtraPreview),
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "scroll-long.get",
            displayName = "Fetch long scroll image",
            summary = "Reads a stitched long-scroll image artifact for one preview.",
            command =
              listOf(
                "compose-preview",
                "extensions",
                "run",
                "scroll-long.get",
                "--id",
                "<preview-id>",
                "--output",
                "<path>",
              ),
            productKinds = listOf(KIND_LONG),
          )
        ),
      steps = listOf(longScrollScenario, longScrollEncoder),
    )

  val annotationDescriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = ANNOTATION_ID,
      displayName = "ScrollingPreview annotation",
      usageModes = setOf(PreviewExtensionUsageMode.SuggestedExtraPreview),
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "scrolling-preview-annotation.render",
            displayName = "Render scroll annotation suggestions",
            summary = "Discovers @ScrollingPreview annotations and renders their suggested extras.",
            command =
              listOf("compose-preview", "extensions", "run", "scrolling-preview-annotation.render", "--json"),
            agentRecommended = true,
            usageModes = setOf(PreviewExtensionUsageMode.SuggestedExtraPreview),
          )
        ),
      steps = listOf(annotationSuggester),
    )

  val gifScrollDescriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "scroll-gif",
      displayName = "Scroll GIF",
      usageModes =
        setOf(PreviewExtensionUsageMode.ExplicitEffect, PreviewExtensionUsageMode.SuggestedExtraPreview),
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "scroll-gif.get",
            displayName = "Fetch scroll GIF",
            summary = "Reads an animated scroll GIF artifact for one preview.",
            command =
              listOf(
                "compose-preview",
                "extensions",
                "run",
                "scroll-gif.get",
                "--id",
                "<preview-id>",
                "--output",
                "<path>",
              ),
            productKinds = listOf(KIND_GIF),
          )
        ),
      steps = listOf(gifScrollScenario, gifScrollEncoder),
    )
}
