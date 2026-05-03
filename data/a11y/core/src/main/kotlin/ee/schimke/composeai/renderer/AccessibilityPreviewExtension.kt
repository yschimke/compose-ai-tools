package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.render.RenderPreviewExtension
import ee.schimke.composeai.data.render.pipeline.ExtractionSpec
import ee.schimke.composeai.data.render.pipeline.PipelineCapability
import ee.schimke.composeai.data.render.pipeline.PipelineStepTrait
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionCliCommand
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionUsageMode
import ee.schimke.composeai.data.render.pipeline.PreviewPipelineStep
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy

/** Pipeline metadata for accessibility hierarchy extraction. */
object AccessibilitySemanticsPreviewExtension {
  const val ID: String = "a11y"
  const val KIND_HIERARCHY: String = "a11y/hierarchy"

  val finalSampleExtractor: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "a11y.semantics.end",
      displayName = "Accessibility hierarchy",
      productKinds = listOf(KIND_HIERARCHY),
      traits = setOf(PipelineStepTrait.DataExtractor),
      requires = setOf(PipelineCapability.SemanticsSnapshot),
      provides = setOf(PipelineCapability.AccessibilityNodes),
      extraction =
        ExtractionSpec(
          kind = KIND_HIERARCHY,
          sampling = SamplingPolicy.End,
          requiresSemantics = true,
        ),
    )

  val eachFrameExtractor: PreviewPipelineStep =
    finalSampleExtractor.copy(
      id = "a11y.semantics.eachFrame",
      extraction =
        ExtractionSpec(
          kind = KIND_HIERARCHY,
          sampling = SamplingPolicy.EachFrame,
          requiresImage = true,
          requiresSemantics = true,
          aggregate = true,
        ),
    )

  val descriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = ID,
      displayName = "Accessibility hierarchy",
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "a11y.hierarchy.get",
            displayName = "Fetch accessibility hierarchy",
            summary = "Reads the structured accessibility hierarchy for one preview.",
            command =
              listOf(
                "compose-preview",
                "extensions",
                "run",
                "a11y.hierarchy.get",
                "--id",
                "<preview-id>",
                "--json",
              ),
            agentRecommended = true,
            productKinds = listOf(KIND_HIERARCHY),
          )
        ),
      steps = listOf(finalSampleExtractor, eachFrameExtractor),
    )
}

/** Pipeline metadata for ATF checks over accessibility hierarchy data. */
object AtfChecksPreviewExtension {
  const val ID: String = "atf-checks"
  const val KIND_ATF: String = "a11y/atf"
  const val KIND_TOUCH_TARGETS: String = "a11y/touchTargets"

  val finalSampleChecker: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "atf.check.end",
      displayName = "ATF checks",
      productKinds = listOf(KIND_ATF, KIND_TOUCH_TARGETS),
      traits = setOf(PipelineStepTrait.Check),
      requires = setOf(PipelineCapability.AccessibilityNodes),
      provides = setOf(PipelineCapability.AccessibilityFindings),
      extraction =
        ExtractionSpec(
          kind = KIND_ATF,
          sampling = SamplingPolicy.End,
        ),
    )

  val eachFrameChecker: PreviewPipelineStep =
    finalSampleChecker.copy(
      id = "atf.check.eachFrame",
      extraction =
        ExtractionSpec(
          kind = KIND_ATF,
          sampling = SamplingPolicy.EachFrame,
          requiresImage = true,
          aggregate = true,
        ),
    )

  val descriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = ID,
      displayName = "ATF checks",
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "atf-checks.run",
            displayName = "Run ATF accessibility checks",
            summary = "Renders previews and returns ATF accessibility findings.",
            command = listOf("compose-preview", "extensions", "run", "atf-checks.run", "--json"),
            agentRecommended = true,
            productKinds = listOf(KIND_ATF, KIND_TOUCH_TARGETS),
          ),
          PreviewExtensionCliCommand(
            id = "atf-checks.get",
            displayName = "Fetch ATF findings",
            summary = "Reads previously emitted ATF findings for one preview.",
            command =
              listOf(
                "compose-preview",
                "extensions",
                "run",
                "atf-checks.get",
                "--id",
                "<preview-id>",
                "--json",
              ),
            productKinds = listOf(KIND_ATF),
          ),
        ),
      steps = listOf(finalSampleChecker, eachFrameChecker),
    )
}

/** Pipeline metadata for adapting accessibility findings into generic overlay annotations. */
object AccessibilityOverlayPreviewExtension {
  const val ID: String = "a11y-overlay"
  const val KIND_OVERLAY: String = "a11y/overlay"

  val annotationProcessor: PreviewPipelineStep =
    PreviewPipelineStep(
      id = "a11y.overlayAnnotations",
      displayName = "Accessibility overlay annotations",
      traits = setOf(PipelineStepTrait.FrameProcessor),
      requires =
        setOf(
          PipelineCapability.AccessibilityNodes,
          PipelineCapability.AccessibilityFindings,
        ),
      provides = setOf(PipelineCapability.OverlayAnnotations),
    )

  val descriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = ID,
      displayName = "Accessibility overlay annotations",
      componentExtensionIds = listOf(AccessibilitySemanticsPreviewExtension.ID, AtfChecksPreviewExtension.ID),
      steps = listOf(annotationProcessor),
    )
}

/** Suggested a11y annotated image composed from a11y data, ATF checks, and generic overlay legend. */
object AccessibilityAnnotatedPreviewExtension {
  const val ID: String = "a11y-annotated-preview"

  val descriptor: PreviewExtensionDescriptor =
    PreviewExtensionDescriptor(
      id = ID,
      displayName = "Accessibility annotated preview",
      usageModes = setOf(PreviewExtensionUsageMode.SuggestedExtraPreview),
      componentExtensionIds =
        listOf(
          AccessibilitySemanticsPreviewExtension.ID,
          AtfChecksPreviewExtension.ID,
          AccessibilityOverlayPreviewExtension.ID,
          "overlay-legend",
        ),
      cliCommands =
        listOf(
          PreviewExtensionCliCommand(
            id = "a11y-annotated-preview.render",
            displayName = "Render accessibility annotated previews",
            summary = "Discovers and renders suggested accessibility annotated preview extras.",
            command =
              listOf("compose-preview", "extensions", "run", "a11y-annotated-preview.render", "--json"),
            usageModes = setOf(PreviewExtensionUsageMode.SuggestedExtraPreview),
          ),
          PreviewExtensionCliCommand(
            id = "a11y-overlay.get",
            displayName = "Fetch accessibility overlay",
            summary = "Reads the rendered accessibility overlay artifact for one preview.",
            command =
              listOf(
                "compose-preview",
                "extensions",
                "run",
                "a11y-overlay.get",
                "--id",
                "<preview-id>",
                "--output",
                "<path>",
              ),
            productKinds = listOf(AccessibilityOverlayPreviewExtension.KIND_OVERLAY),
          )
        ),
      steps =
        listOf(
          AccessibilitySemanticsPreviewExtension.finalSampleExtractor,
          AtfChecksPreviewExtension.finalSampleChecker,
          AccessibilityOverlayPreviewExtension.annotationProcessor,
          RenderPreviewExtension.overlayLegendProcessor.copy(
            productKinds = listOf(AccessibilityOverlayPreviewExtension.KIND_OVERLAY)
          ),
        ),
    )
}
