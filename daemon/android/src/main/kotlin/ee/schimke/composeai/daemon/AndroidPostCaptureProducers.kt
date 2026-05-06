package ee.schimke.composeai.daemon

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.onRoot
import ee.schimke.composeai.data.render.PreviewBackends
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.ExtensionContextData
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.provides
import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.renderer.AccessibilityDataProducts
import ee.schimke.composeai.renderer.AccessibilityHierarchyContextKeys
import ee.schimke.composeai.renderer.AccessibilityHierarchyExtension

/**
 * Default-mode producers extracted from the inline `if (dataDir != null) { try { ... } catch }`
 * blocks that used to live in `RenderEngine.kt:316–449`.
 *
 * Each producer wraps an existing `*DataProducer.writeArtifacts(...)` call. The data and
 * connector modules still own the actual write logic; this file is only the registration glue
 * that lets `RenderEngine` iterate over a list rather than hardcode the call sites.
 *
 * Producers needing a snapshot of the merged semantics tree share `context.fetchSemanticsRoot`
 * — three of them do (compose/semantics, layout/inspector, i18n/translations). The lazy in
 * [AndroidPostCaptureContext] guarantees the lookup runs at most once per render.
 */
object AndroidPostCaptureProducers {

  /** Default registration set used by `DaemonMain`. Order matches the original inline order. */
  fun defaults(): List<AndroidPostCaptureProducer> =
    listOf(
      FontsUsedAndroid,
      ResourcesUsedAndroid,
      ComposeSemanticsAndroid,
      LayoutInspectorAndroid,
      I18nTranslationsAndroid,
      AccessibilityAndroid,
    )

  /** `fonts/used` — wraps [FontsUsedDataProducer]. */
  object FontsUsedAndroid : AndroidPostCaptureProducer {
    override val id: String = "compose:fontsUsedDataProduct"

    override fun write(context: AndroidPostCaptureContext) {
      FontsUsedDataProducer.writeArtifacts(
        rootDir = context.dataDir,
        previewId = context.spec.previewId ?: context.spec.outputBaseName,
        payload = context.fontRecorder.payload(),
      )
    }
  }

  /** `resources/used` — wraps [ResourcesUsedDataProducer]. */
  object ResourcesUsedAndroid : AndroidPostCaptureProducer {
    override val id: String = "android:resourcesUsedDataProduct"

    override fun write(context: AndroidPostCaptureContext) {
      ResourcesUsedDataProducer.writeArtifacts(
        rootDir = context.dataDir,
        previewId = context.spec.outputBaseName,
        recorder = context.resourceRecorder,
      )
    }
  }

  /** `compose/semantics` — wraps [ComposeSemanticsDataProducer]. */
  object ComposeSemanticsAndroid : AndroidPostCaptureProducer {
    override val id: String = "compose:semanticsDataProduct"

    override fun write(context: AndroidPostCaptureContext) {
      val semanticsRoot = context.rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
      ComposeSemanticsDataProducer.writeArtifacts(
        rootDir = context.dataDir,
        previewId = context.spec.outputBaseName,
        root = semanticsRoot,
      )
    }
  }

  /** `layout/inspector` — wraps [LayoutInspectorDataProducer]. */
  object LayoutInspectorAndroid : AndroidPostCaptureProducer {
    override val id: String = "compose:layoutInspectorDataProduct"

    override fun write(context: AndroidPostCaptureContext) {
      val semanticsRoot = context.rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
      val previewContext =
        PreviewContext.Builder(
            previewId = context.spec.previewId,
            backend = PreviewBackends.ANDROID,
            renderMode = context.spec.renderMode,
            outputBaseName = context.spec.outputBaseName,
          )
          .deviceFromRenderPixels(
            context.spec.device,
            context.spec.widthPx,
            context.spec.heightPx,
            context.spec.density,
            resolvedDevice =
              context.spec.device?.let(DeviceDimensions::resolve)?.previewDeviceSpec(),
          )
          .parameterInformationCollected()
          .addSlotTables(context.slotTables)
          .rootForTest(semanticsRoot.root)
          .build()
      LayoutInspectorDataProducer.writeArtifacts(
        rootDir = context.dataDir,
        previewId = context.spec.outputBaseName,
        previewContext = previewContext,
      )
    }
  }

  /** `i18n/translations` — wraps [I18nTranslationsDataProducer]. */
  object I18nTranslationsAndroid : AndroidPostCaptureProducer {
    override val id: String = "i18n:translationsDataProduct"

    override fun write(context: AndroidPostCaptureContext) {
      val semanticsRoot = context.rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
      I18nTranslationsDataProducer.writeArtifacts(
        rootDir = context.dataDir,
        previewId = context.spec.outputBaseName,
        root = semanticsRoot,
        renderedLocale = context.spec.localeTag,
      )
    }
  }

  /**
   * `a11y/atf` + `a11y/hierarchy` — wraps [AccessibilityDataProducer]. Walks the same
   * `ViewRootForTest` ATF can populate. Gated on `runAccessibility` because the a11y mode flips
   * `LocalInspectionMode` during composition and the rest of the producers stay mode-agnostic.
   */
  object AccessibilityAndroid : AndroidPostCaptureProducer {
    override val id: String = "a11y:dataProducts"

    override fun shouldRun(context: AndroidPostCaptureContext): Boolean = context.runAccessibility

    override fun write(context: AndroidPostCaptureContext) {
      val view = (context.rule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
      val hierarchyExtension = AccessibilityHierarchyExtension()
      val store = RecordingDataProductStore()
      hierarchyExtension.process(
        ExtensionPostCaptureContext(
          extensionId = hierarchyExtension.id,
          previewId = context.spec.outputBaseName,
          renderMode = context.spec.renderMode,
          products = store.scopedFor(hierarchyExtension),
          data =
            ExtensionContextData.of(AccessibilityHierarchyContextKeys.ViewRoot provides view),
        )
      )
      val hierarchy = store.require(AccessibilityDataProducts.Hierarchy)
      val findings = store.require(AccessibilityDataProducts.Atf)
      AccessibilityDataProducer.writeArtifacts(
        rootDir = context.dataDir,
        previewId = context.spec.outputBaseName,
        findings = findings.findings,
        nodes = hierarchy.nodes,
        density = context.spec.density,
        pngFile = context.outputFile,
        isRound = context.isRound,
        imageProcessors = context.imageProcessors,
      )
    }
  }
}
