package ee.schimke.composeai.renderer

import android.view.View
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.DataExtensionTarget
import ee.schimke.composeai.data.render.extensions.DataProductKey
import ee.schimke.composeai.data.render.extensions.ExtensionContextKey
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import ee.schimke.composeai.data.render.extensions.RenderSession
import ee.schimke.composeai.data.render.extensions.RenderSessionParticipant

/**
 * Pure extractor — runs ATF over a View tree and returns the typed payloads. No invocation point
 * baked in; any binding (post-capture today, around-script-event tomorrow, frame driver later) can
 * call this directly.
 */
object AccessibilityHierarchyExtractor {
  fun extract(previewId: String?, root: View): AccessibilityAnalysis {
    val result = AccessibilityChecker.analyze(previewId ?: "preview", root)
    return AccessibilityAnalysis(
      hierarchy = AccessibilityHierarchyPayload(result.nodes),
      findings = AccessibilityFindingsPayload(result.findings),
    )
  }
}

/**
 * Bundled output of one ATF + hierarchy walk. Today the ATF library produces both findings and
 * nodes from the same `AccessibilityHierarchyAndroid` build, so they ship as a pair to avoid two
 * walks of the same View tree. Consumers that only want one product still benefit — the planner
 * pulls in this single producer once and routes both products to whoever declared them as inputs.
 */
data class AccessibilityAnalysis(
  val hierarchy: AccessibilityHierarchyPayload,
  val findings: AccessibilityFindingsPayload,
)

/**
 * Default binding for [AccessibilityHierarchyExtractor]: invokes it after the bitmap is captured
 * and emits the typed products into the store. Reads the captured `View` root from
 * [AccessibilityHierarchyContextKeys.ViewRoot] — the host populates it from its own platform
 * handle (the daemon's RenderEngine has the View directly; the Robolectric test runner gets it
 * via `ViewRootForTest`).
 *
 * `targets = {Android}` — a future Compose Multiplatform Desktop hierarchy producer would target
 * `{Desktop}` and emit the same product keys; the planner's target filter selects exactly one.
 */
class AccessibilityHierarchyExtension : PostCaptureProcessor, RenderSessionParticipant {
  override val id: DataExtensionId = DataExtensionId(EXTENSION_ID)
  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AfterCapture)
  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(phase = DataExtensionPhase.Capture)
  override val outputs: Set<DataProductKey<*>> =
    setOf(AccessibilityDataProducts.Hierarchy, AccessibilityDataProducts.Atf)
  override val targets: Set<DataExtensionTarget> = setOf(DataExtensionTarget.Android)

  /**
   * ATF needs Compose semantics to be populated, which means [LocalInspectionMode] must be `false`
   * for the composition. The Android renderer applies that override unconditionally today
   * (production-like rendering happens to want the same thing), so [configureSession] is a
   * declarative marker for now — the host reads applied participants and reflects on this
   * requirement when assembling its CompositionLocal overrides. Concrete state lives on the
   * platform-specific session implementation; the participant contract is the renderer-agnostic
   * piece that any backend (desktop, future iOS) can rely on.
   */
  override fun configureSession(session: RenderSession) {
    // No mutable state on the generic [RenderSession] today — the requirement that
    // `LocalInspectionMode` is `false` is enforced at the renderer-android layer based on
    // applied-extension membership. Kept as an explicit override (rather than relying on the
    // default no-op) so the documented contract surfaces in code: a11y *does* depend on
    // session-level configuration.
  }

  /**
   * Refuses to run when the host invokes the hook without opting the a11y extension into the
   * current [RenderSession]. The Robolectric runner gates per-preview ATF on this check, so a
   * misconfigured plugin/CLI invocation fails loudly with a pointer to `--with-extension a11y`
   * instead of silently emitting an `accessibility.json` from a session that wasn't set up for
   * it.
   */
  override fun validateSession(session: RenderSession) {
    check(session.isApplied(id)) {
      "Accessibility extension '${id.value}' is registered but not applied to this render " +
        "session (appliedExtensionIds=${session.appliedExtensionIds.map { it.value }}). " +
        "Enable via `compose-preview a11y` or `--with-extension a11y`."
    }
  }

  override fun process(context: ExtensionPostCaptureContext) {
    val view = context.require(AccessibilityHierarchyContextKeys.ViewRoot)
    val analysis = AccessibilityHierarchyExtractor.extract(context.previewId, view)
    context.products.put(AccessibilityDataProducts.Hierarchy, analysis.hierarchy)
    context.products.put(AccessibilityDataProducts.Atf, analysis.findings)
  }

  companion object {
    const val EXTENSION_ID: String = "a11y"
  }
}

/**
 * Typed keys this extension reads from [ExtensionPostCaptureContext.data]. The Android-platform
 * binding lives here (where [View] is in scope); a future `:data-a11y-hierarchy-desktop` would
 * declare its own `ViewRoot` key with the Desktop equivalent type.
 */
object AccessibilityHierarchyContextKeys {
  val ViewRoot: ExtensionContextKey<View> =
    ExtensionContextKey(name = "a11y-hierarchy.viewRoot", type = View::class.java)
}
