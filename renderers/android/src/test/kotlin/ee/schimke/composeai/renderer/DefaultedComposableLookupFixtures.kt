package ee.schimke.composeai.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Compiled `@Composable` shapes for [DefaultedComposableLookupTest]. They live in their own file so
 * the Kotlin compiler emits a synthetic `DefaultedComposableLookupFixturesKt` class carrying the
 * real Compose calling convention — the whole point of the test is the *compiled* signature, which
 * a hand-written `Method` stub could only approximate.
 *
 * Nothing here is ever composed; the test only reflects on the emitted methods.
 */
@Suppress("unused") @Composable fun noParameterPreviewFixture() = Unit

/** The JetLagged shape: a component that is its own preview, every parameter defaulted. */
@Suppress("unused")
@Composable
fun defaultedModifierPreviewFixture(modifier: Modifier = Modifier) {
  @Suppress("UNUSED_EXPRESSION") modifier
}

/** Several defaulted parameters, mixing a reference type with a primitive. */
@Suppress("unused")
@Composable
fun multiDefaultPreviewFixture(
  label: String = "",
  count: Int = 0,
  modifier: Modifier = Modifier,
) {
  @Suppress("UNUSED_EXPRESSION") label
  @Suppress("UNUSED_EXPRESSION") count
  @Suppress("UNUSED_EXPRESSION") modifier
}

/** A required parameter and no defaults — not callable with no arguments. */
@Suppress("unused")
@Composable
fun requiredParameterFixture(label: String) {
  @Suppress("UNUSED_EXPRESSION") label
}

/** Not composable at all: the scan must not offer it up. */
@Suppress("unused") fun plainFunctionFixture(label: String = "") = label

/**
 * Two fully-defaulted composable overloads of one name — the shape a review flagged as silently
 * rendering the wrong one. Both are invocable with no arguments, and the manifest records only the
 * name, so nothing at render time can tell which carried the `@Preview`.
 *
 * Legal to declare, but nearly unusable: every Kotlin call site of `ambiguousOverloadFixture()` is
 * itself ambiguous. That is why the renderer diagnoses it instead of threading a JVM descriptor
 * through the manifest to disambiguate.
 */
@Suppress("unused")
@Composable
fun ambiguousOverloadFixture(count: Int = 0) {
  @Suppress("UNUSED_EXPRESSION") count
}

@Suppress("unused")
@Composable
fun ambiguousOverloadFixture(count: Int = 0, label: String = "") {
  @Suppress("UNUSED_EXPRESSION") count
  @Suppress("UNUSED_EXPRESSION") label
}
