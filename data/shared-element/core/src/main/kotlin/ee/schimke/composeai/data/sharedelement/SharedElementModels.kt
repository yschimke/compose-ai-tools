package ee.schimke.composeai.data.sharedelement

import kotlinx.serialization.Serializable

/**
 * Stable identity and wire shape of the `compose/shared-element` data product — the structured,
 * machine-readable counterpart to the pixels that Compose 1.11's
 * `LookaheadAnimationVisualDebugging` overlay paints over an in-flight shared-element transition.
 *
 * The overlay (`androidx.compose.animation.LookaheadAnimationVisualDebugging`, gated by
 * `@ExperimentalLookaheadAnimationVisualDebugApi`) classifies every `rememberSharedContentState`
 * key into one of three states and rasterises them — target bounds in `overlayColor`, **unmatched**
 * keys in `unmatchedElementColor` (red), **multiply-matched** keys in `multipleMatchesColor`
 * (green) — but exposes none of that as data: the classification is computed inside the
 * (module-internal) `LookaheadAnimationVisualDebugHelper` at draw time and never surfaced. This
 * model is the wire shape a future producer emits so an agent or CI can *assert* "no unmatched
 * shared elements" without OCR'ing a GIF — the same role `compose/semantics` plays for the
 * layout-inspector overlay.
 *
 * This module is deliberately producer-free and dependency-light (no Compose, no Robolectric, no
 * experimental API): the three-way classification and the per-key target bounds are the *public*
 * contract of the overlay, so the shape is stable to model even though extracting the values at
 * render time is not yet in scope (it requires either reflection into the experimental internals or
 * an independent re-derivation of the match state). Mirrors `:data-layoutinspector-core` so MCP
 * clients in other languages can depend on the findings model without dragging in a renderer.
 */
object SharedElementProduct {
  const val KIND: String = "compose/shared-element"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "shared-element-findings.json"
}

/**
 * Match classification of a single shared-element key during a transition — the three states the
 * 1.11 overlay paints.
 *
 * - [MATCHED] — the key has exactly one counterpart in the other `AnimatedContent` state, so the
 *   element animates its bounds between the two; the overlay draws only target bounds + label.
 * - [UNMATCHED] — the key is registered on only one side, so it has nothing to animate toward; the
 *   overlay flags it in red. The single most common shared-element bug (a key missing on one side).
 * - [MULTIPLE_MATCHES] — the same key is registered more than once in a state, leaving the match
 *   ambiguous; the overlay flags it in green.
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
 * @property key the `rememberSharedContentState(key = …)` value, as the overlay's optional key
 *   label would render it.
 * @property status the [SharedElementMatchStatus] classification for this key.
 * @property occurrences how many times the key was registered in the captured frame — `1` for a
 *   well-formed [MATCHED]/[UNMATCHED] key, `> 1` for [MULTIPLE_MATCHES].
 * @property modifier which shared modifier registered the key — `"sharedElement"` or
 *   `"sharedBounds"` — or null when the producer can't attribute it.
 * @property targetBoundsInRoot the bounds the element animates toward, as `"left,top,right,bottom"`
 *   in root pixels (matching the `boundsInRoot` convention on `compose/semantics`), or null when
 *   there is no target (an [UNMATCHED] key) or the producer didn't resolve it.
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
 * The `compose/shared-element` payload: every shared-element key observed in the captured
 * transition frame, with convenience tallies a CLI report or CI gate reads to decide pass/fail. The
 * derived counts live in the class body (not the constructor), so they are computed on read and
 * never serialised — the JSON carries only [findings].
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
