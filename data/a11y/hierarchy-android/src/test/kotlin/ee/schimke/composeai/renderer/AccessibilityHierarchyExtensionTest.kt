package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.render.extensions.DataExtensionPlanner
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityHierarchyExtensionTest {
  private val extension = AccessibilityHierarchyExtension()

  @Test
  fun declaresExpectedOutputsAndTarget() {
    assertTrue(extension.inputs.isEmpty())
    assertEquals(
      setOf(AccessibilityDataProducts.Hierarchy, AccessibilityDataProducts.Atf),
      extension.outputs,
    )
    assertEquals(setOf(DataExtensionTarget.Android), extension.targets)
  }

  @Test
  fun failsLoudlyWhenViewRootContextKeyMissing() {
    val store = RecordingDataProductStore()

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
      ex.message!!.contains(AccessibilityHierarchyContextKeys.ViewRoot.name),
    )
  }

  @Test
  fun plannerSchedulesHierarchyBeforeTouchTargets() {
    val result =
      DataExtensionPlanner.planOutputs(
        extensions = listOf(TouchTargetsExtension(), extension),
        requestedOutputs = setOf(AccessibilityDataProducts.TouchTargets),
        initialProducts =
          setOf(ee.schimke.composeai.data.render.extensions.CommonDataProducts.Density),
        target = DataExtensionTarget.Android,
      )

    assertTrue(result.errors.toString(), result.isValid)
    assertEquals(
      listOf(AccessibilityHierarchyExtension.EXTENSION_ID, TouchTargetsExtension.EXTENSION_ID),
      result.orderedExtensions.map { it.id.value },
    )
  }

  @Test
  fun plannerSchedulesHierarchyBeforeOverlay() {
    val result =
      DataExtensionPlanner.planOutputs(
        extensions = listOf(OverlayExtension(), extension),
        requestedOutputs = setOf(AccessibilityDataProducts.Overlay),
        initialProducts =
          setOf(ee.schimke.composeai.data.render.extensions.CommonDataProducts.ImageArtifact),
        target = DataExtensionTarget.Android,
      )

    assertTrue(result.errors.toString(), result.isValid)
    assertEquals(
      listOf(AccessibilityHierarchyExtension.EXTENSION_ID, OverlayExtension.EXTENSION_ID),
      result.orderedExtensions.map { it.id.value },
    )
  }

  @Test
  fun plannerCoSchedulesAllConsumersAfterSingleHierarchyProducer() {
    val result =
      DataExtensionPlanner.planOutputs(
        extensions = listOf(OverlayExtension(), TouchTargetsExtension(), extension),
        requestedOutputs =
          setOf(AccessibilityDataProducts.Overlay, AccessibilityDataProducts.TouchTargets),
        initialProducts =
          setOf(
            ee.schimke.composeai.data.render.extensions.CommonDataProducts.ImageArtifact,
            ee.schimke.composeai.data.render.extensions.CommonDataProducts.Density,
          ),
        target = DataExtensionTarget.Android,
      )

    assertTrue(result.errors.toString(), result.isValid)
    assertEquals(
      AccessibilityHierarchyExtension.EXTENSION_ID,
      result.orderedExtensions.first().id.value,
    )
    val tail = result.orderedExtensions.drop(1).map { it.id.value }
    assertTrue(
      "expected overlay + touchTargets after hierarchy, got $tail",
      tail.containsAll(
        listOf(OverlayExtension.EXTENSION_ID, TouchTargetsExtension.EXTENSION_ID)
      ),
    )
  }
}
