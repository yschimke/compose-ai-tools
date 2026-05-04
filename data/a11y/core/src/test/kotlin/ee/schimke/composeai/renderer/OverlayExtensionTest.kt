package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.render.extensions.CommonDataProducts
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPlanner
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.RenderImageArtifact
import ee.schimke.composeai.data.render.extensions.SimplePlannedDataExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayExtensionTest {
  private val extension = OverlayExtension()

  @Test
  fun declaresExpectedInputsOutputsAndTarget() {
    assertEquals(
      setOf(
        AccessibilityDataProducts.Hierarchy,
        AccessibilityDataProducts.Atf,
        CommonDataProducts.ImageArtifact,
      ),
      extension.inputs,
    )
    assertEquals(setOf(AccessibilityDataProducts.Overlay), extension.outputs)
    assertEquals(setOf(DataExtensionTarget.Android), extension.targets)
  }

  @Test
  fun failsLoudlyWhenOutputDirectoryAttributeMissing() {
    val store = RecordingDataProductStore()
    store.put(AccessibilityDataProducts.Hierarchy, AccessibilityHierarchyPayload(emptyList()))
    store.put(AccessibilityDataProducts.Atf, AccessibilityFindingsPayload(emptyList()))
    store.put(CommonDataProducts.ImageArtifact, RenderImageArtifact("/tmp/preview.png"))

    val ex =
      assertThrows(IllegalStateException::class.java) {
        extension.process(
          ExtensionPostCaptureContext(
            extensionId = extension.id,
            previewId = "preview",
            renderMode = null,
            products = store.scopedFor(extension),
          )
        )
      }
    assertTrue(
      ex.message!!,
      ex.message!!.contains(OverlayExtension.OUTPUT_DIRECTORY_ATTRIBUTE),
    )
  }

  @Test
  fun plannerOrdersOverlayAfterHierarchyAndAtfProducers() {
    val hierarchyAtfProducer =
      SimplePlannedDataExtension(
        id = DataExtensionId("a11y"),
        outputs = setOf(AccessibilityDataProducts.Hierarchy, AccessibilityDataProducts.Atf),
      )

    val result =
      DataExtensionPlanner.planOutputs(
        extensions = listOf(extension, hierarchyAtfProducer),
        requestedOutputs = setOf(AccessibilityDataProducts.Overlay),
        initialProducts = setOf(CommonDataProducts.ImageArtifact),
        target = DataExtensionTarget.Android,
      )

    assertTrue(result.errors.toString(), result.isValid)
    assertEquals(
      listOf("a11y", OverlayExtension.EXTENSION_ID),
      result.orderedExtensions.map { it.id.value },
    )
  }

  @Test
  fun plannerSchedulesOverlayAndTouchTargetsTogetherAfterHierarchy() {
    val hierarchyAtfProducer =
      SimplePlannedDataExtension(
        id = DataExtensionId("a11y"),
        outputs = setOf(AccessibilityDataProducts.Hierarchy, AccessibilityDataProducts.Atf),
      )

    val result =
      DataExtensionPlanner.planOutputs(
        extensions = listOf(extension, TouchTargetsExtension(), hierarchyAtfProducer),
        requestedOutputs =
          setOf(AccessibilityDataProducts.Overlay, AccessibilityDataProducts.TouchTargets),
        initialProducts = setOf(CommonDataProducts.ImageArtifact, CommonDataProducts.Density),
        target = DataExtensionTarget.Android,
      )

    assertTrue(result.errors.toString(), result.isValid)
    val ordered = result.orderedExtensions.map { it.id.value }
    assertEquals("a11y", ordered.first())
    assertTrue(
      "expected both consumers after the hierarchy producer, got $ordered",
      ordered.containsAll(listOf(OverlayExtension.EXTENSION_ID, TouchTargetsExtension.EXTENSION_ID)),
    )
  }
}
