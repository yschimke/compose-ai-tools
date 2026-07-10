package ee.schimke.composeai.preview

/**
 * Opts a plain `@Preview` composable into Wear OS one-handed-gesture **hint** capture.
 *
 * The point is that the previewed screen stays ordinary app code — a `Button` wrapped in
 * `:data-gestures-connector`'s `GestureHint` (or a scroll/page indicator) — with **no**
 * preview-only flags threaded through it. The real `OneHandedGestureIndicator` only flashes its
 * hint from on-device sensor input, so off a Pixel Watch a normal `@Preview` shows nothing. This
 * annotation flips the hint on from *outside* the screen: the compose-preview Gradle plugin picks
 * it up by FQN (mirroring [AmbientPreview] / [FocusedPreview] / [ScrollingPreview]) and the
 * renderer wraps the composition with `GestureOverrideExtension`, which primes
 * `GestureStateController` with `showHints`, exactly as daemon-driven
 * `renderNow.overrides.gestures.showHints` does through the connector's planner. Both paths end up
 * at the same composable seam — `GestureHint` reads the controller's force-show state — so the
 * screen renders the hint without knowing a preview asked for it.
 *
 * Pair a bare `@Preview` (hint off — the resting screen) with a second `@Preview` + this annotation
 * (hint on) over the *same* composable to capture both states from one screen definition.
 *
 * Example:
 * ```
 * @Composable
 * fun MediaScreen() { /* Button wrapped in GestureHint — normal app code */ }
 *
 * @Preview(device = WearDevices.LARGE_ROUND, showBackground = true)
 * @Composable
 * fun MediaScreenPreview() { MediaScreen() }            // hint off
 *
 * @Preview(device = WearDevices.LARGE_ROUND, showBackground = true)
 * @GestureHintPreview
 * @Composable
 * fun MediaScreenHintPreview() { MediaScreen() }        // hint on, via the override
 * ```
 *
 * Wear-only — applying this to a non-Wear preview is a no-op (the renderer primes the controller
 * but nothing in non-Wear UI reads it).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class GestureHintPreview(
  /**
   * Force-show the one-handed-gesture hints for the render. `true` (the default) is the point of
   * the annotation; `false` forces hints off, useful when stacked under a multi-preview annotation
   * that enables them elsewhere.
   */
  val showHints: Boolean = true
)
