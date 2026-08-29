package ee.schimke.composeai.data.render.pipeline

import ee.schimke.composeai.daemon.protocol.PipelineCapability
import ee.schimke.composeai.daemon.protocol.PipelineStepTrait
import ee.schimke.composeai.daemon.protocol.PreviewPipelineStep
import ee.schimke.composeai.daemon.protocol.SamplingPolicy

public data class PreviewPipelinePlan(
  val steps: List<PreviewPipelineStep>,
  val initialCapabilities: Set<PipelineCapability> = emptySet(),
) {
  val providedCapabilities: Set<PipelineCapability> =
    steps.fold(initialCapabilities) { provided, step -> provided + step.provides }
}

public data class PipelineValidationError(
  val code: String,
  val message: String,
  val steps: List<String> = emptyList(),
)

public object PreviewPipelineValidator {
  public fun validate(plan: PreviewPipelinePlan): List<PipelineValidationError> = buildList {
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
