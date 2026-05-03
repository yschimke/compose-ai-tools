package ee.schimke.composeai.scroll

import ee.schimke.composeai.data.render.RenderPreviewExtension
import ee.schimke.composeai.data.render.pipeline.PreviewPipelinePlan
import ee.schimke.composeai.data.render.pipeline.PreviewPipelineValidator
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollPreviewExtensionTest {
  @Test
  fun gifScenarioCanBeClippedBeforeEncoding() {
    val plan =
      PreviewPipelinePlan(
        listOf(
          ScrollPreviewExtension.gifScrollScenario,
          RenderPreviewExtension.deviceClipProcessor,
          ScrollPreviewExtension.gifScrollEncoder,
        )
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }

  @Test
  fun gifScenarioCanCollectAggregateComposeTraceWhileScrolling() {
    val plan =
      PreviewPipelinePlan(
        listOf(
          ScrollPreviewExtension.gifScrollScenario,
          RenderPreviewExtension.composeTraceProfiler,
          ScrollPreviewExtension.gifScrollEncoder,
        )
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }

  @Test
  fun longScenarioCanBeClippedBeforeStitching() {
    val plan =
      PreviewPipelinePlan(
        listOf(
          ScrollPreviewExtension.longScrollScenario,
          RenderPreviewExtension.deviceClipProcessor,
          ScrollPreviewExtension.longScrollEncoder,
        )
      )

    assertTrue(PreviewPipelineValidator.validate(plan).isEmpty())
  }
}
