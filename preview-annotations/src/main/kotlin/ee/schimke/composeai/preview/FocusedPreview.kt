package ee.schimke.composeai.preview

/**
 * Opts a `@Preview` composable into focus-state capture.
 *
 * The compose-preview Gradle plugin's discovery task picks this up by FQN, mirroring the
 * [ScrollingPreview] / [AnimatedPreview] split: consumers that want to use the annotation in their
 * own code depend on `ee.schimke.composeai:preview-annotations`.
 *
 * Two driving modes — pick one:
 *
 * * **Indexed** ([indices]): fan out one capture per requested tab-order index. The renderer issues
 *   `moveFocus(Enter)` once on the first capture, then `moveFocus(Next)` to walk forward to each
 *   subsequent index. Filename suffix `_FOCUS_<index>`. Default mode (`indices = [0]`).
 *
 * * **Traversal** ([traverse]): one capture per direction in the array. The renderer issues
 *   `moveFocus(Enter)` once before the first step, then `moveFocus(direction)` per entry. Filename
 *   suffix `_FOCUS_step<n>_<direction>`. Useful for asserting Tab / Shift-Tab / D-pad traversal
 *   order in PR diffs — each step is a separate PNG so reviewers see exactly which focusable each
 *   move lands on.
 *
 * `traverse` takes precedence when both are set; mixing them in a single annotation isn't
 * meaningful.
 *
 * Compose's `Modifier.clickable` registers its focusable with `Focusability.SystemDefined`, which
 * refuses focus while the host `LocalInputModeManager.inputMode == InputMode.Touch`. Robolectric's
 * renderer environment is permanently in touch mode (no real key input ever arrives), so this
 * annotation also flips `LocalInputModeManager` to Keyboard mode for the duration of its captures —
 * matching the state a real device is in after the user Tabs to a component.
 *
 * Example — indexed:
 * ```
 * @Preview
 * @FocusedPreview(indices = [0, 1, 2, 3])
 * @Composable
 * fun MyButtonRow() {
 *     Row {
 *         listOf("Save", "Edit", "Share", "Delete").forEach { label ->
 *             Button(onClick = {}) { Text(label) }
 *         }
 *     }
 * }
 * ```
 *
 * Example — traversal:
 * ```
 * @Preview
 * @FocusedPreview(traverse = [FocusDirection.Next, FocusDirection.Next, FocusDirection.Previous])
 * @Composable
 * fun MyButtonRow() { ... }
 * ```
 *
 * When [overlay] is `true`, the renderer draws a stroke + index label over the captured PNG showing
 * the bounds of the currently-focused element. Useful when the component's own focus indicator is
 * subtle and you want an unambiguous review-time marker.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class FocusedPreview(
  /**
   * Tab-order indices to capture. The renderer issues `moveFocus(Enter)` once on the first capture,
   * then `moveFocus(Next)` to walk forward to each subsequent index. Indices must be non-negative
   * and strictly increasing — backward traversal isn't supported because Compose's focus search
   * doesn't expose a "go to absolute index" entry point. Defaults to `[0]` (a single capture with
   * focus on the first focusable). Ignored when [traverse] is non-empty.
   */
  val indices: IntArray = [0],
  /**
   * Directions to apply, one capture per entry. Each capture is taken *after* applying that step's
   * direction; the renderer issues a single `moveFocus(Enter)` before step 1 so the first move
   * lands on a real focusable rather than no-op'ing on an inactive root. Empty (the default) → use
   * [indices].
   */
  val traverse: Array<FocusDirection> = [],
  /**
   * When `true`, the renderer overlays a stroke + step / index label on each captured PNG showing
   * the currently-focused element's bounds. The overlay is post-applied to the output file, so the
   * pre-overlay capture is preserved alongside (`<basename>.raw.png`). Off by default to keep
   * captures byte-identical to a no-overlay run when reviewers don't want the marker.
   */
  val overlay: Boolean = false,
  /**
   * When `true`, the renderer stitches the per-step captures into a single animated GIF at
   * `renders/<id>.gif` instead of writing one PNG per step. Each `[indices]` entry (or each
   * `[traverse]` step) becomes one GIF frame, driven through the same `FocusManager.moveFocus` walk
   * that the per-PNG path uses — so consumer code stays plain `Row { Button(...) }` with no
   * hand-rolled `MutableInteractionSource` / `LaunchedEffect` focus emission. Ignored when the
   * annotation collapses to a single step (one `indices` entry, empty `traverse`). Off by default.
   */
  val gif: Boolean = false,
)

/**
 * Mirrors the Compose `androidx.compose.ui.focus.FocusDirection` value class as a discoverable
 * enum. The Gradle plugin can't load Compose at discovery time, so we duplicate the relevant set
 * here and let the renderer translate at render time.
 */
enum class FocusDirection {
  Next,
  Previous,
  Up,
  Down,
  Left,
  Right,
}
