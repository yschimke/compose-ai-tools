package ee.schimke.composeai.data.render.pipeline

import kotlinx.serialization.Serializable

/**
 * Renderer-agnostic description of a preview extension step.
 *
 * This is intentionally metadata-first: renderers and clients can validate a planned product before
 * they allocate a Compose scene, and UI clients can explain why a requested combination is not
 * available.
 */
@Serializable
data class PreviewPipelineStep(
  val id: String,
  val displayName: String = id,
  val productKinds: List<String> = emptyList(),
  val annotationFqns: List<String> = emptyList(),
  val usageModes: Set<PreviewExtensionUsageMode> = setOf(PreviewExtensionUsageMode.ExplicitEffect),
  val traits: Set<PipelineStepTrait> = emptySet(),
  val requires: Set<PipelineCapability> = emptySet(),
  val provides: Set<PipelineCapability> = emptySet(),
  val conflictsWith: Set<PipelineStepTrait> = emptySet(),
  val sampling: SamplingPolicy? = null,
  val extraction: ExtractionSpec? = null,
)

@Serializable
enum class PreviewExtensionUsageMode {
  ExplicitEffect,
  SuggestedExtraPreview,
}

@Serializable
data class PreviewExtensionDescriptor(
  val id: String,
  val displayName: String = id,
  val usageModes: Set<PreviewExtensionUsageMode> = setOf(PreviewExtensionUsageMode.ExplicitEffect),
  val componentExtensionIds: List<String> = emptyList(),
  val cliCommands: List<PreviewExtensionCliCommand> = emptyList(),
  val steps: List<PreviewPipelineStep> = emptyList(),
)

@Serializable
data class PreviewExtensionCliCommand(
  val id: String,
  val displayName: String = id,
  val summary: String = "",
  val command: List<String>,
  val agentRecommended: Boolean = false,
  val requiresDaemon: Boolean = false,
  val usageModes: Set<PreviewExtensionUsageMode> = emptySet(),
  val productKinds: List<String> = emptyList(),
)

@Serializable
enum class PipelineStepTrait {
  ScenarioDriver,
  InteractiveDriver,
  AnnotationInspector,
  ExtraPreviewSuggester,
  FrameProcessor,
  FinalArtifactProcessor,
  DataExtractor,
  Check,
  Encoder,
  Profiler,
}

@Serializable
enum class PipelineCapability {
  Frames,
  SingleFrame,
  MultipleFrames,
  PreviewFunctionAnnotations,
  SuggestedPreviews,
  DeviceGeometry,
  DeviceClip,
  DeviceBackground,
  ScrollState,
  SemanticsSnapshot,
  AccessibilityNodes,
  AccessibilityFindings,
  OverlayAnnotations,
  ImageArtifact,
  AnnotatedImageArtifact,
  AnimatedArtifact,
  InteractiveSession,
  TraceEvents,
}

@Serializable
enum class SamplingPolicy {
  Start,
  End,
  EachFrame,
  OnDemand,
  Aggregate,
  Failure,
}

@Serializable
data class ExtractionSpec(
  val kind: String,
  val sampling: SamplingPolicy,
  val requiresImage: Boolean = false,
  val requiresSemantics: Boolean = false,
  val aggregate: Boolean = false,
)

data class PreviewPipelinePlan(
  val steps: List<PreviewPipelineStep>,
  val initialCapabilities: Set<PipelineCapability> = emptySet(),
) {
  val providedCapabilities: Set<PipelineCapability> =
    steps.fold(initialCapabilities) { provided, step -> provided + step.provides }
}

data class PipelineValidationError(
  val code: String,
  val message: String,
  val steps: List<String> = emptyList(),
)

object PreviewPipelineValidator {
  fun validate(plan: PreviewPipelinePlan): List<PipelineValidationError> = buildList {
    val steps = plan.steps

    addAtMostOneTraitError(
      steps = steps,
      trait = PipelineStepTrait.ScenarioDriver,
      code = "MultipleScenarioDrivers",
      label = "scenario driver",
    )
    addAtMostOneTraitError(
      steps = steps,
      trait = PipelineStepTrait.InteractiveDriver,
      code = "MultipleInteractiveDrivers",
      label = "interactive driver",
    )
    addAtMostOneTraitError(
      steps = steps,
      trait = PipelineStepTrait.Encoder,
      code = "MultipleEncoders",
      label = "encoder",
    )

    var provided = plan.initialCapabilities
    for (step in steps) {
      val missing = step.requires - provided
      if (missing.isNotEmpty()) {
        add(
          PipelineValidationError(
            code = "MissingCapability",
            message =
              "Step '${step.id}' requires ${missing.joinToString()} but the pipeline does not " +
                "provide ${if (missing.size == 1) "it" else "them"}.",
            steps = listOf(step.id),
          )
        )
      }

      val conflictingSteps = steps.filter { other ->
        other.id != step.id && other.traits.any { it in step.conflictsWith }
      }
      if (conflictingSteps.isNotEmpty()) {
        add(
          PipelineValidationError(
            code = "ConflictingSteps",
            message =
              "Step '${step.id}' conflicts with ${conflictingSteps.joinToString { it.id }}.",
            steps = listOf(step.id) + conflictingSteps.map { it.id },
          )
        )
      }

      val extraction = step.extraction
      if (extraction != null) {
        if (extraction.requiresImage && PipelineCapability.ImageArtifact !in provided) {
          add(
            PipelineValidationError(
              code = "ExtractionRequiresImage",
              message = "Extractor '${step.id}' requires an image artifact.",
              steps = listOf(step.id),
            )
          )
        }
        if (extraction.requiresSemantics && PipelineCapability.SemanticsSnapshot !in provided) {
          add(
            PipelineValidationError(
              code = "ExtractionRequiresSemantics",
              message = "Extractor '${step.id}' requires a semantics snapshot.",
              steps = listOf(step.id),
            )
          )
        }
        if (
          extraction.sampling == SamplingPolicy.EachFrame &&
            PipelineCapability.Frames !in provided &&
            PipelineCapability.SingleFrame !in provided &&
            PipelineCapability.MultipleFrames !in provided
        ) {
          add(
            PipelineValidationError(
              code = "EachFrameExtractionRequiresFrames",
              message = "Extractor '${step.id}' samples each frame but the pipeline has no frames.",
              steps = listOf(step.id),
            )
          )
        }
      }

      provided = provided + step.provides
    }
  }

  private fun MutableList<PipelineValidationError>.addAtMostOneTraitError(
    steps: List<PreviewPipelineStep>,
    trait: PipelineStepTrait,
    code: String,
    label: String,
  ) {
    val matches = steps.filter { trait in it.traits }
    if (matches.size > 1) {
      add(
        PipelineValidationError(
          code = code,
          message = "Pipeline has multiple $label steps: ${matches.joinToString { it.id }}.",
          steps = matches.map { it.id },
        )
      )
    }
  }
}
