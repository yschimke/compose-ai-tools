package ee.schimke.composeai.renderer

import ee.schimke.composeai.data.render.extensions.DataProductKey
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Accessibility data-product models — shared by the renderer-side ATF
// integration ([AccessibilityChecker]), the daemon-side data product producer
// ([ee.schimke.composeai.daemon.AccessibilityDataProducer]), and CLI / plugin
// consumers that read the on-disk JSON sidecars.
//
// Lives in `:data-a11y-core` so the Gradle plugin's standalone path
// (AggregateAccessibilityTask) and the daemon path can share a single
// definition without either depending on the other's artefact. Kept in the
// `ee.schimke.composeai.renderer` package for source-compatibility — this is
// where the types lived before the D2.2 extraction, and downstream consumers
// (CLI, plugin, VS Code) keep their own structurally-identical mirrors that
// are unaffected by the package being now hosted in the data-products module.
// ---------------------------------------------------------------------------

/**
 * ATF findings per preview. Written by [RobolectricRenderTestBase] when accessibility checks
 * are enabled, read by the plugin's post-render verify task and by downstream tools (CLI,
 * VSCode).
 */
@Serializable
data class AccessibilityReport(
    val module: String,
    val entries: List<AccessibilityEntry>,
)

@Serializable
data class AccessibilityEntry(
    val previewId: String,
    val findings: List<AccessibilityFinding>,
    /**
     * Every accessibility-relevant node ATF saw on the rendered tree. Populated whether or
     * not [findings] is empty so consumers can render a Paparazzi-style "what TalkBack sees"
     * overlay even when there's nothing to fix. Empty list ≈ a11y disabled or the View has
     * no labelled / actionable content.
     */
    val nodes: List<AccessibilityNode> = emptyList(),
    /**
     * Relative path (from the aggregated `accessibility.json`) to an annotated screenshot
     * showing each finding as a numbered badge + legend. `null` when there were no findings,
     * or when overlay generation was skipped. Consumers should treat a missing file the same
     * as a missing pointer — fall back to the clean render.
     */
    val annotatedPath: String? = null,
)

/**
 * One accessibility-relevant node from the rendered View tree, captured for the
 * Paparazzi-style overlay (translucent colour fill on the screenshot matched against a
 * swatched legend). The shape is deliberately small — we keep only what TalkBack would
 * announce and what the overlay needs to draw, not the full ATF
 * [com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement]
 * graph.
 */
@Serializable
data class AccessibilityNode(
    /** Visible text or contentDescription. Always non-empty for emitted nodes. */
    val label: String,
    /**
     * TalkBack's class announcement (`Button`, `Image`, `TextView`, …). `null` for plain
     * Views that only carry a label, so the legend can skip the role chip and avoid the
     * noisy `View` everyone gets.
     */
    val role: String? = null,
    /**
     * Non-default behavioural / state flags surfaced to the legend subtitle. Currently
     * emitted (when their underlying value differs from the View default): `clickable`,
     * `long-clickable`, `scrollable`, `editable`, `disabled`, `checked` / `unchecked`, plus
     * the verbatim `getStateDescription()` string and a `hint: <text>` line for
     * `getHintText()`. Heading isn't here — ATF's hierarchy doesn't expose it cleanly enough
     * to detect Compose-side `Modifier.semantics { heading() }`.
     */
    val states: List<String> = emptyList(),
    /**
     * `true` when this node is its own TalkBack focus target (ATF: `isScreenReaderFocusable()`,
     * or no screen-reader-focusable ancestor exists). `false` when it sits underneath a
     * focusable ancestor — e.g. the inner `Text` of a `Button` whose semantics are merged
     * into the button. The overlay uses this to draw unmerged descendants with a dashed
     * border + `↳ ` legend prefix so reviewers can see structure without confusing it for
     * "two separate TalkBack stops". Default `true` keeps older `accessibility.json` files
     * parsing as merged.
     */
    val merged: Boolean = true,
    /**
     * `left,top,right,bottom` in source-bitmap pixels — same shape as
     * [AccessibilityFinding.boundsInScreen].
     */
    val boundsInScreen: String,
)

@Serializable
data class AccessibilityFinding(
    /** `ERROR`, `WARNING`, `INFO`, or `NOT_RUN` — upper-cased ATF `AccessibilityCheckResultType`. */
    val level: String,
    /** Short rule identifier — ATF check class simple name (e.g. `TouchTargetSizeCheck`). */
    val type: String,
    val message: String,
    /** Human-readable description of the offending element, if ATF could resolve one. */
    val viewDescription: String? = null,
    /** `left,top,right,bottom` in the preview's pixel space — agents can highlight on the PNG. */
    val boundsInScreen: String? = null,
)

@Serializable data class AccessibilityHierarchyPayload(val nodes: List<AccessibilityNode>)

@Serializable data class AccessibilityFindingsPayload(val findings: List<AccessibilityFinding>)

@Serializable
data class AccessibilityTouchTarget(
    val nodeId: String,
    val boundsInScreen: String,
    val widthDp: Float,
    val heightDp: Float,
    val findings: List<String>,
    val overlappingNodeIds: List<String>? = null,
)

@Serializable data class AccessibilityTouchTargetsPayload(val targets: List<AccessibilityTouchTarget>)

@Serializable
data class AccessibilityOverlayArtifact(
    val path: String,
    val mediaType: String = "image/png",
)

object AccessibilityDataProducts {
    const val SCHEMA_VERSION: Int = 1
    const val KIND_HIERARCHY: String = "a11y/hierarchy"
    const val KIND_ATF: String = "a11y/atf"
    const val KIND_TOUCH_TARGETS: String = "a11y/touchTargets"
    const val KIND_OVERLAY: String = "a11y/overlay"

    val Hierarchy: DataProductKey<AccessibilityHierarchyPayload> =
        DataProductKey(KIND_HIERARCHY, SCHEMA_VERSION, AccessibilityHierarchyPayload::class.java)

    val Atf: DataProductKey<AccessibilityFindingsPayload> =
        DataProductKey(KIND_ATF, SCHEMA_VERSION, AccessibilityFindingsPayload::class.java)

    val TouchTargets: DataProductKey<AccessibilityTouchTargetsPayload> =
        DataProductKey(
            KIND_TOUCH_TARGETS,
            SCHEMA_VERSION,
            AccessibilityTouchTargetsPayload::class.java,
        )

    val Overlay: DataProductKey<AccessibilityOverlayArtifact> =
        DataProductKey(KIND_OVERLAY, SCHEMA_VERSION, AccessibilityOverlayArtifact::class.java)
}
