package ee.schimke.composeai.discovery

import kotlinx.serialization.Serializable

/**
 * A screen a builder composed out of components, in the shape a code generator can consume.
 *
 * This is deliberately **not** the UI builder's own document model. It is the narrow subset
 * [ScreenGenerator] needs — which component, what the user set, what went in each slot — so that
 * the generator can be tested and reasoned about without dragging an editor's undo stack, presence
 * state and collaboration protocol along with it. A builder projects its document onto this; the
 * projection is the builder's job and the interesting decisions are not in it.
 */
@Serializable
data class ScreenDocument(
  /** The generated composable's name. Must be a valid Kotlin identifier — the generator checks. */
  val name: String,
  val root: ScreenNode,
)

/**
 * One placed component: which one, the arguments the user set, and what they dropped into its
 * slots.
 *
 * @property componentId the [ComponentRecord.canonicalId] this node places. An id with no matching
 *   record is a refusal, never a guess — a builder pinned to a catalog it no longer has is exactly
 *   the case where inventing a call site produces confident nonsense.
 * @property arguments values the user set, by **source parameter name**. A name the component does
 *   not declare is a refusal: silently dropping it would generate a screen that compiles and is not
 *   the one that was designed.
 * @property slots children by slot name. A slot the component does not declare is likewise refused.
 */
@Serializable
data class ScreenNode(
  val componentId: String,
  val arguments: Map<String, ScreenValue> = emptyMap(),
  val slots: Map<String, List<ScreenNode>> = emptyMap(),
)

/**
 * A value a builder can set on a parameter.
 *
 * A closed set on purpose. Every case here has an unambiguous Kotlin literal *and* an unambiguous
 * type to check against the parameter's [TargetParameter.typeFqn], which is what lets the generator
 * reject `text = true` instead of emitting it. Widening this set means widening the type check with
 * it — a value kind whose type cannot be checked is a call site nobody can prove compiles.
 */
@Serializable
sealed interface ScreenValue {
  @Serializable data class Text(val value: String) : ScreenValue

  @Serializable data class Bool(val value: Boolean) : ScreenValue

  @Serializable data class Whole(val value: Long) : ScreenValue

  @Serializable data class Fractional(val value: Double) : ScreenValue
}
