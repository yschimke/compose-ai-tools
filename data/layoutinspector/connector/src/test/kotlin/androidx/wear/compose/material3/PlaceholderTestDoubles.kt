// Test doubles deliberately declared in Wear M3's own package: `PlaceholderModifiers
// .isPlaceholderOrigin` recognises the block placeholder by *where its draw lambda was compiled*
// (`androidx.wear.compose.material3.Placeholder…`), because `Modifier.placeholder` lowers to a bare
// `drawWithContent` with no identity of its own. Standing these up in a test-local package would
// exercise a different code path than production. See `ModifierTokenResolverPlaceholderStateTest`.
package androidx.wear.compose.material3

/** Stands in for Wear's `PlaceholderState` — matched by class name, read via `isVisible`. */
class PlaceholderStateDouble(private val visible: Boolean) {
  fun isVisible(): Boolean = visible
}

/**
 * Stands in for the `drawWithContent` lambda `Modifier.placeholder` remembers: compiled out of
 * Wear's `Placeholder.kt`, capturing the state / colour the placeholder draws with.
 */
class PlaceholderDrawLambdaDouble(
  @JvmField val placeholderState: Any,
  @JvmField val color: Long = 0L,
)
