package ee.schimke.composeai.data.layoutinspector

/**
 * The one place that knows what a *placeholder* modifier is (issue #2646).
 *
 * Wear M3's `Modifier.placeholder` / `Modifier.placeholderShimmer` (and the `androidx.compose
 * .material3` equivalents) are content-loading chrome that rides on the **caller's** modifier
 * chain, ahead of the component's own `Surface`. Two ways they leak into the layered figma-svg
 * export when the exporter sees only the chain and not the placeholder *state*:
 * - `Modifier.placeholder` draws through a `drawWithContent`, which the hybrid canvas-draw path
 *   treats as un-vectorisable chrome and crops to an `<image>` — even in the ideal (content-loaded)
 *   state, where the draw is a pass-through and the node's real text/children are perfectly
 *   vectorisable (issue #2644).
 * - `Modifier.placeholderShimmer` exposes `PlaceholderDefaults.shape` (`ShapeTokens.CornerFull`, a
 *   50% pill) as its inspectable `shape`, so as the first shape-bearing modifier on the chain it
 *   hijacks the container corner and a placeholdered `TitleCard`/`Button` exports as a full pill
 *   (issue #2645).
 *
 * Both were point-fixed per symptom; this object is the shared identity those fixes now go through,
 * so the capture side ([LayoutInspectorPlaceholder], resolved by the connector's
 * `ModifierTokenResolver`) and the export side ([FigmaSvgModel]) agree on what counts as a
 * placeholder. A placeholder modifier never contributes container tokens (shape/background) and
 * never forces a raster; what it *does* contribute is its own state-aware layer — see
 * [LayoutInspectorPlaceholder.visible].
 */
object PlaceholderModifiers {

  /** Inspector `nameFallback` of `Modifier.placeholder`. */
  const val NAME_PLACEHOLDER: String = "placeholder"

  /** Inspector `nameFallback` of `Modifier.placeholderShimmer`. */
  const val NAME_SHIMMER: String = "placeholderShimmer"

  /**
   * [LayoutInspectorPlaceholder.kind] for a `Modifier.placeholder` (the content-covering block).
   */
  const val KIND_PLACEHOLDER: String = "placeholder"

  /** [LayoutInspectorPlaceholder.kind] for a `Modifier.placeholderShimmer` (the sweep overlay). */
  const val KIND_SHIMMER: String = "shimmer"

  /**
   * True for a placeholder-family modifier, matched by the inspector [name]
   * (`placeholder`/`placeholderShimmer`) or — when inspector info was compiled out — the element
   * [className] (`PlaceholderElement`, `PlaceholderShimmerElement`, `PlaceholderModifierNode…`).
   *
   * This catches the modifiers that lower to their **own element**, which today is
   * `Modifier.placeholderShimmer` (a `PlaceholderShimmerElement`). `Modifier.placeholder` itself
   * lowers to a bare `drawWithContent` + `graphicsLayer` pair with no identity of its own — see
   * [isPlaceholderOrigin], the other half of the recognition.
   */
  fun isPlaceholderModifier(name: String?, className: String?): Boolean =
    name == NAME_PLACEHOLDER || name == NAME_SHIMMER || className?.startsWith("Placeholder") == true

  /**
   * True for a class that *belongs to* a placeholder implementation — the Wear M3 (or M3)
   * `Placeholder.kt` file class and the lambdas compiled out of it.
   *
   * This is how the block placeholder is recognised at all. `Modifier.placeholder(state, shape,
   * color)` is not a named element: it appends `Modifier.drawWithContent { … }.graphicsLayer { …
   * }`, so the captured chain shows a generic `drawWithContent` that is indistinguishable from a
   * progress track's — except that the draw lambda it holds was compiled from
   * `androidx.wear.compose.material3.PlaceholderKt`. Matching that origin is what lets the export
   * tell "placeholder chrome, ignorable in the ideal state" from "real imperative drawing that must
   * be rasterised".
   *
   * Deliberately anchored to a Material package so an application's own `PlaceholderKt` file can't
   * claim the identity.
   */
  fun isPlaceholderOrigin(className: String?): Boolean {
    val n = className ?: return false
    val material =
      n.startsWith("androidx.wear.compose.material3.") ||
        n.startsWith("androidx.wear.compose.material.") ||
        n.startsWith("androidx.compose.material3.") ||
        n.startsWith("androidx.compose.material.")
    return material && n.substringAfterLast('.').startsWith("Placeholder")
  }

  /**
   * The [LayoutInspectorPlaceholder.kind] for a placeholder-family modifier, or null when it isn't
   * one. Shimmer is matched first: `PlaceholderShimmerElement` also starts with `Placeholder`.
   */
  fun kindOf(name: String?, className: String?): String? =
    when {
      name == NAME_SHIMMER || className?.startsWith("PlaceholderShimmer") == true -> KIND_SHIMMER
      isPlaceholderModifier(name, className) -> KIND_PLACEHOLDER
      else -> null
    }
}
