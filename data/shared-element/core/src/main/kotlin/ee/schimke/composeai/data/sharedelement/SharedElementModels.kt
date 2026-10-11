package ee.schimke.composeai.data.sharedelement

import kotlinx.serialization.Serializable

/**
 * Identity and wire shape of the `compose/shared-element` data product: the machine-readable
 * counterpart to Compose 1.11's `LookaheadAnimationVisualDebugging` overlay, which classifies each
 * `rememberSharedContentState` key (matched / unmatched / multiply-matched) but only as pixels.
 * This lets an agent or CI assert "no unmatched shared elements" without OCR'ing a GIF.
 *
 * Producer-free and dependency-light, like `:data-layoutinspector-core`: the classification is the
 * overlay's public contract, though extracting it at render time isn't implemented yet.
 */
object SharedElementProduct {
  const val KIND: String = "compose/shared-element"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "shared-element-findings.json"
}

/**
 * Match classification of one shared-element key, as the 1.11 overlay paints it.
 *
 * - [MATCHED] — exactly one counterpart in the other state; animates its bounds.
 * - [UNMATCHED] — registered on one side only (flagged red); the most common shared-element bug.
 * - [MULTIPLE_MATCHES] — registered more than once in a state, so ambiguous (flagged green).
 */
@Serializable
enum class SharedElementMatchStatus {
  MATCHED,
  UNMATCHED,
  MULTIPLE_MATCHES,
}

/**
 * One shared-element key observed during the transition.
 *
 * @property key the `rememberSharedContentState(key = …)` value, as the overlay labels it.
 * @property status the [SharedElementMatchStatus] for this key.
 * @property occurrences registrations in the captured frame: `1`, or `> 1` for [MULTIPLE_MATCHES].
 * @property modifier `"sharedElement"` or `"sharedBounds"`, or null if unattributed.
 * @property targetBoundsInRoot target bounds as `"left,top,right,bottom"` root pixels (as in
 *   `compose/semantics`), or null when there is none or it wasn't resolved.
 */
@Serializable
data class SharedElementFinding(
  val key: String,
  val status: SharedElementMatchStatus,
  val occurrences: Int = 1,
  val modifier: String? = null,
  val targetBoundsInRoot: String? = null,
)

/**
 * The `compose/shared-element` payload: every key observed in the captured frame, with tallies for
 * a report or CI gate. The tallies are body properties, so only [findings] is serialised.
 */
@Serializable
data class SharedElementPayload(val findings: List<SharedElementFinding> = emptyList()) {
  val matchedCount: Int
    get() = findings.count { it.status == SharedElementMatchStatus.MATCHED }

  val unmatchedCount: Int
    get() = findings.count { it.status == SharedElementMatchStatus.UNMATCHED }

  val multipleMatchesCount: Int
    get() = findings.count { it.status == SharedElementMatchStatus.MULTIPLE_MATCHES }

  /** True when any key is unmatched or multiply-matched — the threshold a CI gate fails on. */
  val hasProblems: Boolean
    get() = unmatchedCount > 0 || multipleMatchesCount > 0
}
