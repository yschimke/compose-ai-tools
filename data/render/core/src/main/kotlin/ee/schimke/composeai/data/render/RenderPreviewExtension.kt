package ee.schimke.composeai.data.render

import ee.schimke.composeai.data.render.pipeline.PipelineCapability
import ee.schimke.composeai.data.render.pipeline.PipelineStepTrait
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionCliCommand
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor
import ee.schimke.composeai.data.render.pipeline.PreviewPipelineStep
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy

/** Pipeline metadata for built-in render-level preview extension steps. */
object RenderPreviewExtension {
  const val ID: String = "render"
  const val KIND_DEVICE_CLIP: String = "render/deviceClip"
  const val KIND_TRACE: String = "render/trace"
  const val KIND_COMPOSE_AI_TRACE: String = "render/composeAiTrace"
  const val KIND_TEST_FAILURE: String = "test/failure"

  val deviceClipProcessor: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "render.deviceClip",
      displayName = "Device clip",
      productKinds = listOf(KIND_DEVICE_CLIP),
      traits = setOf(PipelineStepTrait.FrameProcessor),
      requires = setOf(PipelineCapability.DeviceGeometry, PipelineCapability.ImageArtifact),
      provides = setOf(PipelineCapability.DeviceClip, PipelineCapability.ImageArtifact),
    )

  val renderTraceProfiler: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "render.trace",
      displayName = "Render trace",
      productKinds = listOf(KIND_TRACE),
      traits = setOf(PipelineStepTrait.Profiler),
      provides = setOf(PipelineCapability.TraceEvents),
      sampling = SamplingPolicy.Aggregate,
    )

  val composeTraceProfiler: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "render.composeAiTrace",
      displayName = "Compose composition trace",
      productKinds = listOf(KIND_COMPOSE_AI_TRACE),
      traits = setOf(PipelineStepTrait.Profiler),
      provides = setOf(PipelineCapability.TraceEvents),
      sampling = SamplingPolicy.Aggregate,
    )

  val overlayLegendProcessor: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "render.overlayLegend",
      displayName = "Overlay with legend",
      traits = setOf(PipelineStepTrait.FrameProcessor),
      requires = setOf(PipelineCapability.ImageArtifact, PipelineCapability.OverlayAnnotations),
      provides = setOf(PipelineCapability.ImageArtifact, PipelineCapability.AnnotatedImageArtifact),
    )

  val deviceClipDescriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "render-device-clip",
      displayName = "Device clip",
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "render-device-clip.get",
            displayName = "Fetch device-clipped image",
            summary = "Reads a rendered device-clipped image artifact for one preview.",
            command =
              listOf(
                "compose-preview",
                "data",
                "get",
                "--id",
                "<preview-id>",
                "--kind",
                KIND_DEVICE_CLIP,
                "--output",
                "<path>",
              ),
            productKinds = listOf(KIND_DEVICE_CLIP),
          )
        ),
      steps = listOf(deviceClipProcessor),
    )

  val renderTraceDescriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "render-trace",
      displayName = "Render trace",
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "render-trace.get",
            displayName = "Fetch render trace",
            summary = "Reads aggregate render trace data for one preview.",
            command =
              listOf(
                "compose-preview",
                "data",
                "get",
                "--id",
                "<preview-id>",
                "--kind",
                KIND_TRACE,
                "--json",
              ),
            agentRecommended = true,
            productKinds = listOf(KIND_TRACE),
          )
        ),
      steps = listOf(renderTraceProfiler),
    )

  val composeTraceDescriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "compose-trace",
      displayName = "Compose composition trace",
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "compose-trace.get",
            displayName = "Fetch Compose composition trace",
            summary = "Reads aggregate Compose composition tracing data for one preview.",
            command =
              listOf(
                "compose-preview",
                "data",
                "get",
                "--id",
                "<preview-id>",
                "--kind",
                KIND_COMPOSE_AI_TRACE,
                "--json",
              ),
            agentRecommended = true,
            productKinds = listOf(KIND_COMPOSE_AI_TRACE),
          )
        ),
      steps = listOf(composeTraceProfiler),
    )

  val overlayLegendDescriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = "overlay-legend",
      displayName = "Overlay with legend",
      steps = listOf(overlayLegendProcessor),
    )
}
