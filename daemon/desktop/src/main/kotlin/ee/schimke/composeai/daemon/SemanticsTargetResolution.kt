@file:OptIn(
  androidx.compose.ui.InternalComposeUiApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ee.schimke.composeai.daemon

import androidx.compose.ui.ImageComposeScene
import ee.schimke.composeai.daemon.protocol.SemanticsInputTarget
import ee.schimke.composeai.data.layoutinspector.SemanticsTarget

/**
 * Shared helpers for resolving a wire [SemanticsInputTarget] (issue #1784) against a held desktop
 * scene, used by both [DesktopInteractiveSession] (`interactive/input`) and
 * [DesktopRecordingSession] (`record_preview` script events) so the two paths resolve targets
 * identically.
 */

/** Map the wire target onto the core resolver's [SemanticsTarget]; null when no field is set. */
internal fun SemanticsInputTarget.toSemanticsTarget(): SemanticsTarget? {
  val r = ref
  val tag = testTag
  return when {
    !r.isNullOrBlank() -> SemanticsTarget.Ref(r)
    !tag.isNullOrBlank() -> SemanticsTarget.Tag(tag)
    role != null || text != null -> SemanticsTarget.RoleText(role, text)
    else -> null
  }
}

/**
 * Project the held scene's unmerged semantics tree into the ref-bearing wire model — the same
 * projection the `compose/semantics` data product uses ([ComposeSemanticsDataProducer]) — so target
 * resolution sees identical refs / testTags / roles / bounds to what an agent fetched. Returns null
 * when the scene has no semantics owner yet (nothing rendered).
 */
internal fun ImageComposeScene.composeSemanticsRoot(): ComposeSemanticsNode? {
  val node = semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode ?: return null
  return ComposeSemanticsDataProducer.buildPayload(node).root
}
