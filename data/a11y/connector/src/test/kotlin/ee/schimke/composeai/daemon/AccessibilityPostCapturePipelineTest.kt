package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.render.extensions.CommonDataProducts
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataProductKey
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import ee.schimke.composeai.data.render.extensions.RenderImageArtifact
import ee.schimke.composeai.renderer.AccessibilityDataProducts
import ee.schimke.composeai.renderer.AccessibilityFindingsPayload
import ee.schimke.composeai.renderer.AccessibilityHierarchyPayload
import ee.schimke.composeai.renderer.AccessibilityNode
import ee.schimke.composeai.renderer.TouchTargetsExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AccessibilityPostCapturePipelineTest {
  @Test
  fun seedsHierarchyAtfDensityAndRunsTouchTargetsByDefault() {
    val store =
      runAccessibilityPostCapturePipeline(
        previewId = "preview",
        hierarchy =
          AccessibilityHierarchyPayload(
            nodes =
              listOf(
                AccessibilityNode(
                  label = "Tiny",
                  role = "Button",
                  states = listOf("clickable"),
                  boundsInScreen = "0,0,80,80",
                )
              )
          ),
        findings = AccessibilityFindingsPayload(emptyList()),
        density = 2f,
      )

    val touchTargets = store.get(AccessibilityDataProducts.TouchTargets)
    assertNotNull("touch-targets must be populated by the default pipeline", touchTargets)
    assertEquals(1, touchTargets!!.targets.size)
    assertEquals(40f, touchTargets.targets.single().widthDp)
    assertEquals(listOf("belowMinimum"), touchTargets.targets.single().findings)
  }

  @Test
  fun returnsSeededProductsEvenWhenNoExtensionsPlanned() {
    val hierarchy = AccessibilityHierarchyPayload(emptyList())
    val findings = AccessibilityFindingsPayload(emptyList())

    val store =
      runAccessibilityPostCapturePipeline(
        previewId = "preview",
        hierarchy = hierarchy,
        findings = findings,
        density = 1f,
        extensions = emptyList(),
      )

    assertSame(hierarchy, store.get(AccessibilityDataProducts.Hierarchy))
    assertSame(findings, store.get(AccessibilityDataProducts.Atf))
    assertEquals(1f, store.get(CommonDataProducts.Density)!!.density)
    assertNull(store.get(AccessibilityDataProducts.TouchTargets))
  }

  @Test
  fun skipsImageDependentExtensionsWhenNoImageArtifact() {
    val invocations = mutableListOf<String>()
    val imageRequiringExtension = recordingProcessor(
      id = "image-requiring",
      inputs = setOf(CommonDataProducts.ImageArtifact),
      outputs = setOf(StringProductKey),
      invocations = invocations,
    )

    runAccessibilityPostCapturePipeline(
      previewId = "preview",
      hierarchy = AccessibilityHierarchyPayload(emptyList()),
      findings = AccessibilityFindingsPayload(emptyList()),
      density = 1f,
      imageArtifact = null,
      extensions = listOf(imageRequiringExtension),
    )

    assertEquals(emptyList<String>(), invocations)
  }

  @Test
  fun runsImageDependentExtensionsWhenImageArtifactSeeded() {
    val invocations = mutableListOf<String>()
    val imageRequiringExtension = recordingProcessor(
      id = "image-requiring",
      inputs = setOf(CommonDataProducts.ImageArtifact),
      outputs = setOf(StringProductKey),
      invocations = invocations,
    )

    runAccessibilityPostCapturePipeline(
      previewId = "preview",
      hierarchy = AccessibilityHierarchyPayload(emptyList()),
      findings = AccessibilityFindingsPayload(emptyList()),
      density = 1f,
      imageArtifact = RenderImageArtifact(path = "/tmp/preview.png"),
      extensions = listOf(imageRequiringExtension),
    )

    assertEquals(listOf("image-requiring"), invocations)
  }

  @Test
  fun transitiveImageDependencyDoesNotStrandUnrelatedConsumers() {
    // Chain: chained ← intermediate ← ImageArtifact. When ImageArtifact isn't seeded, neither
    // of those should run, but TouchTargetsExtension (no image dep, anywhere in its chain) must
    // still run — the previous direct-input filter would let `chained` slip through and the
    // planner would then error on the missing ImageArtifact for the whole render.
    val intermediate =
      recordingProcessor(
        id = "intermediate",
        inputs = setOf(CommonDataProducts.ImageArtifact),
        outputs = setOf(IntermediateProduct),
        invocations = mutableListOf(),
      )
    val chained =
      recordingProcessor(
        id = "chained",
        inputs = setOf(IntermediateProduct),
        outputs = setOf(ChainedProduct),
        invocations = mutableListOf(),
      )

    val store =
      runAccessibilityPostCapturePipeline(
        previewId = "preview",
        hierarchy =
          AccessibilityHierarchyPayload(
            nodes =
              listOf(
                AccessibilityNode(
                  label = "Tiny",
                  states = listOf("clickable"),
                  boundsInScreen = "0,0,80,80",
                )
              )
          ),
        findings = AccessibilityFindingsPayload(emptyList()),
        density = 2f,
        imageArtifact = null,
        extensions = listOf(intermediate, chained, TouchTargetsExtension()),
      )

    assertNull(store.get(IntermediateProduct))
    assertNull(store.get(ChainedProduct))
    assertNotNull(
      "TouchTargetsExtension must still run when an unrelated chain is unsatisfiable",
      store.get(AccessibilityDataProducts.TouchTargets),
    )
  }

  @Test
  fun extensionFailureIsLoggedAndDoesNotStrandLaterProducts() {
    val failingExtension =
      object : PostCaptureProcessor {
        override val id: DataExtensionId = DataExtensionId("failing")
        override val hooks: Set<DataExtensionHookKind> =
          setOf(DataExtensionHookKind.AfterCapture)
        override val constraints: DataExtensionConstraints =
          DataExtensionConstraints(phase = DataExtensionPhase.PostProcess)
        override val outputs: Set<DataProductKey<*>> = setOf(FailingOutput)

        override fun process(context: ExtensionPostCaptureContext) {
          throw IllegalStateException("boom")
        }
      }

    val store =
      runAccessibilityPostCapturePipeline(
        previewId = "preview",
        hierarchy =
          AccessibilityHierarchyPayload(
            nodes =
              listOf(
                AccessibilityNode(
                  label = "Tiny",
                  states = listOf("clickable"),
                  boundsInScreen = "0,0,80,80",
                )
              )
          ),
        findings = AccessibilityFindingsPayload(emptyList()),
        density = 2f,
        extensions = listOf(failingExtension, TouchTargetsExtension()),
      )

    assertNull(store.get(FailingOutput))
    assertNotNull(
      "TouchTargetsExtension still ran after the unrelated failing extension",
      store.get(AccessibilityDataProducts.TouchTargets),
    )
  }

  private fun recordingProcessor(
    id: String,
    inputs: Set<DataProductKey<*>>,
    outputs: Set<DataProductKey<*>>,
    invocations: MutableList<String>,
  ): PostCaptureProcessor =
    object : PostCaptureProcessor {
      override val id: DataExtensionId = DataExtensionId(id)
      override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
      override val constraints: DataExtensionConstraints =
        DataExtensionConstraints(phase = DataExtensionPhase.PostProcess)
      override val inputs: Set<DataProductKey<*>> = inputs
      override val outputs: Set<DataProductKey<*>> = outputs

      override fun process(context: ExtensionPostCaptureContext) {
        invocations += id
        outputs.forEach { key ->
          if (key.type == String::class.java) {
            @Suppress("UNCHECKED_CAST")
            context.products.put(key as DataProductKey<String>, "ok")
          }
        }
      }
    }

  companion object {
    private val StringProductKey: DataProductKey<String> =
      DataProductKey("test/string", schemaVersion = 1, String::class.java)

    private val FailingOutput: DataProductKey<String> =
      DataProductKey("test/failing", schemaVersion = 1, String::class.java)

    private val IntermediateProduct: DataProductKey<String> =
      DataProductKey("test/intermediate", schemaVersion = 1, String::class.java)

    private val ChainedProduct: DataProductKey<String> =
      DataProductKey("test/chained", schemaVersion = 1, String::class.java)
  }
}
