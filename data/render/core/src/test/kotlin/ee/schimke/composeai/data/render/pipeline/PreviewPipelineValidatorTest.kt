package ee.schimke.composeai.data.render.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPipelineValidatorTest {
  @Test
  fun validScrollGifWithDeviceClipAndA11yOverlay() {
    val plan =
      PreviewPipelinePlan(
        listOf(
          PreviewPipelineStep(
            id = "scroll-gif",
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
          ),
          PreviewPipelineStep(
            id = "device-clip",
            traits = setOf(PipelineStepTrait.FrameProcessor),
            requires = setOf(PipelineCapability.DeviceGeometry, PipelineCapability.ImageArtifact),
            provides = setOf(PipelineCapability.DeviceClip, PipelineCapability.ImageArtifact),
          ),
          PreviewPipelineStep(
            id = "a11y-each-frame",
            traits = setOf(PipelineStepTrait.DataExtractor),
            provides =
              setOf(PipelineCapability.SemanticsSnapshot, PipelineCapability.AccessibilityFindings),
            extraction =
              ExtractionSpec(
                kind = "a11y/atf",
                sampling = SamplingPolicy.EachFrame,
                requiresImage = true,
                requiresSemantics = true,
              ),
          ),
          PreviewPipelineStep(
            id = "a11y-overlay",
            traits = setOf(PipelineStepTrait.FrameProcessor),
            requires =
              setOf(
                PipelineCapability.ImageArtifact,
                PipelineCapability.AccessibilityFindings,
                PipelineCapability.DeviceGeometry,
              ),
            provides = setOf(PipelineCapability.ImageArtifact),
          ),
          PreviewPipelineStep(
            id = "gif-encoder",
            traits = setOf(PipelineStepTrait.Encoder),
            requires = setOf(PipelineCapability.MultipleFrames, PipelineCapability.ImageArtifact),
            provides = setOf(PipelineCapability.AnimatedArtifact),
          ),
        )
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }

  @Test
  fun rejectsMultipleInteractiveDrivers() {
    val errors =
      PreviewPipelineValidator.validate(
        PreviewPipelinePlan(
          listOf(
            PreviewPipelineStep(
              id = "interactive-session",
              traits = setOf(PipelineStepTrait.InteractiveDriver),
            ),
            PreviewPipelineStep(
              id = "scripted-touch",
              traits = setOf(PipelineStepTrait.InteractiveDriver),
            ),
          )
        )
      )

    assertEquals(listOf("MultipleInteractiveDrivers"), errors.map { it.code })
  }

  @Test
  fun rejectsTwoScenarioDrivers() {
    val errors =
      PreviewPipelineValidator.validate(
        PreviewPipelinePlan(
          listOf(
            PreviewPipelineStep(id = "scroll", traits = setOf(PipelineStepTrait.ScenarioDriver)),
            PreviewPipelineStep(id = "animation", traits = setOf(PipelineStepTrait.ScenarioDriver)),
          )
        )
      )

    assertEquals(listOf("MultipleScenarioDrivers"), errors.map { it.code })
  }

  @Test
  fun rejectsMissingOverlayInputs() {
    val errors =
      PreviewPipelineValidator.validate(
        PreviewPipelinePlan(
          listOf(
            PreviewPipelineStep(
              id = "a11y-overlay",
              traits = setOf(PipelineStepTrait.FrameProcessor),
              requires =
                setOf(PipelineCapability.AccessibilityFindings, PipelineCapability.DeviceGeometry),
            )
          )
        )
      )

    assertEquals(listOf("MissingCapability"), errors.map { it.code })
  }

  @Test
  fun rejectsProcessorBeforeItsInputsExist() {
    val errors =
      PreviewPipelineValidator.validate(
        PreviewPipelinePlan(
          listOf(
            PreviewPipelineStep(
              id = "a11y-overlay",
              traits = setOf(PipelineStepTrait.FrameProcessor),
              requires = setOf(PipelineCapability.AccessibilityFindings),
            ),
            PreviewPipelineStep(
              id = "a11y-extract",
              traits = setOf(PipelineStepTrait.DataExtractor),
              provides = setOf(PipelineCapability.AccessibilityFindings),
            ),
          )
        )
      )

    assertEquals(listOf("MissingCapability"), errors.map { it.code })
  }

  @Test
  fun rejectsEachFrameExtractorWithoutFrames() {
    val errors =
      PreviewPipelineValidator.validate(
        PreviewPipelinePlan(
          steps =
            listOf(
              PreviewPipelineStep(
                id = "strings",
                traits = setOf(PipelineStepTrait.DataExtractor),
                extraction =
                  ExtractionSpec(
                    kind = "text/strings",
                    sampling = SamplingPolicy.EachFrame,
                    requiresImage = true,
                  ),
              )
            ),
          initialCapabilities = setOf(PipelineCapability.ImageArtifact),
        )
      )

    assertEquals(listOf("EachFrameExtractionRequiresFrames"), errors.map { it.code })
  }
}
