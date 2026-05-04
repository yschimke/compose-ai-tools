package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.render.extensions.CommonDataProducts
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPlanner
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.RenderDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchTargetsExtensionTest {
  private val extension = TouchTargetsExtension()

  @Test
  fun emitsTouchTargetsPayloadFromHierarchyAndDensity() {
    val hierarchy =
      AccessibilityHierarchyPayload(
        nodes =
          listOf(
            AccessibilityNode(
              label = "Small",
              role = "Button",
              states = listOf("clickable"),
              boundsInScreen = "0,0,80,80",
            ),
            AccessibilityNode(
              label = "Static text",
              boundsInScreen = "100,0,200,80",
            ),
          )
      )

    val store = RecordingDataProductStore()
    store.put(AccessibilityDataProducts.Hierarchy, hierarchy)
    store.put(CommonDataProducts.Density, RenderDensity(2f))
    val scoped = store.scopedFor(extension)

    extension.process(
      ExtensionPostCaptureContext(
        extensionId = extension.id,
        previewId = "preview",
        renderMode = null,
        products = scoped,
      )
    )

    val emitted = store.require(AccessibilityDataProducts.TouchTargets)
    assertEquals(1, emitted.targets.size)
    val target = emitted.targets.single()
    assertEquals("node-0", target.nodeId)
    assertEquals(40f, target.widthDp)
    assertEquals(40f, target.heightDp)
    assertEquals(listOf("belowMinimum"), target.findings)
  }

  @Test
  fun scopedStoreRefusesToReadUndeclaredProduct() {
    val foreignProduct =
      ee.schimke.composeai.data.render.extensions.DataProductKey(
        "foreign/key",
        schemaVersion = 1,
        AccessibilityHierarchyPayload::class.java,
      )
    val scoped = RecordingDataProductStore().scopedFor(extension)

    val ex = assertThrows(IllegalArgumentException::class.java) { scoped.get(foreignProduct) }
    assertTrue(ex.message!!.contains("undeclared product"))
  }

  @Test
  fun plannerOrdersTouchTargetsAfterHierarchyProducer() {
    val hierarchyProducer =
      ee.schimke.composeai.data.render.extensions.SimplePlannedDataExtension(
        id = DataExtensionId("a11y"),
        outputs = setOf(AccessibilityDataProducts.Hierarchy),
      )

    val result =
      DataExtensionPlanner.planOutputs(
        extensions = listOf(extension, hierarchyProducer),
        requestedOutputs = setOf(AccessibilityDataProducts.TouchTargets),
        initialProducts = setOf(CommonDataProducts.Density),
      )

    assertTrue(result.errors.toString(), result.isValid)
    assertEquals(
      listOf("a11y", TouchTargetsExtension.EXTENSION_ID),
      result.orderedExtensions.map { it.id.value },
    )
  }
}
