package ee.schimke.composeai.preview

/**
 * Opts a `@Preview` composable into focus-state capture.
 *
 * The compose-preview Gradle plugin's discovery task picks this up by FQN, mirroring the
 * [ScrollingPreview] / [AnimatedPreview] split: consumers that want to use the annotation in their
 * own code depend on `ee.schimke.composeai:preview-annotations`.
 *
 * The renderer fans out one capture per entry in [indices], driving the Compose focus owner across
 * the focusables in tab order via `FocusManager.moveFocus(...)` — no `FocusRequester` needed in the
 * preview body. Each frame is captured at its assigned focus position with a `_FOCUS_<index>`
 * filename suffix.
 *
 * Compose's `Modifier.clickable` registers its focusable with `Focusability.SystemDefined`, which
 * refuses focus while the host `LocalInputModeManager.inputMode == InputMode.Touch`. Robolectric's
 * renderer environment is permanently in touch mode (no real key input ever arrives), so this
 * annotation also flips `LocalInputModeManager` to Keyboard mode for the duration of its captures —
 * matching the state a real device is in after the user Tabs to a component.
 *
 * Example — a row of buttons, ring on each in turn:
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
   * focus on the first focusable).
   */
  val indices: IntArray = [0]
)
