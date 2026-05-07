package ee.schimke.composeai.preview

/**
 * Opts a `@Preview` composable into Wear OS ambient-mode capture.
 *
 * The compose-preview Gradle plugin's discovery task picks this up by FQN, mirroring
 * [FocusedPreview] / [ScrollingPreview]: consumers that want to use the annotation in their own
 * code depend on `ee.schimke.composeai:preview-annotations`. The renderer translates the captured
 * state into an `AmbientOverrideExtension` (see `:data-ambient-connector`) wrapping the preview's
 * composition, so consumer code reading
 * `androidx.wear.compose.foundation.LocalAmbientModeManager.current?.currentAmbientMode` observes
 * the requested state — same seam `androidx.wear.compose.foundation.samples.AmbientModeBasicSample`
 * uses for its production lookup via `rememberAmbientModeManager()`.
 *
 * Static `@Preview` rendering through the plugin's per-capture loop pushes the override into
 * `AmbientStateController.set(...)` before each capture; daemon-driven
 * `renderNow.overrides.ambient` already does the same through the connector's planner. Both paths
 * end up at the same composable seam.
 *
 * Example:
 * ```
 * @Preview(device = WearDevices.LARGE_ROUND, showBackground = true)
 * @AmbientPreview(state = AmbientPreviewState.Ambient, burnInProtectionRequired = true)
 * @Composable
 * fun MyWatchFaceAmbientPreview() {
 *   MyWatchFace()
 * }
 * ```
 *
 * Wear-only — applying this to a non-Wear preview is a no-op (the renderer installs the local but
 * nothing in non-Wear UI reads it).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class AmbientPreview(
  /**
   * Requested ambient state. [AmbientPreviewState.Ambient] flips the manager's `currentAmbientMode`
   * to `AmbientMode.Ambient(...)` for the render; [AmbientPreviewState.Interactive] forces
   * `AmbientMode.Interactive` (useful when stacked under a multi-preview annotation that defaults
   * elsewhere).
   */
  val state: AmbientPreviewState = AmbientPreviewState.Ambient,
  /**
   * Mirrors `AmbientMode.Ambient.isBurnInProtectionRequired`. Forwarded to the preview's
   * `LocalAmbientModeManager.current.currentAmbientMode` so consumer code branching on burn-in
   * protection runs unchanged. Only meaningful when [state] is [AmbientPreviewState.Ambient].
   */
  val burnInProtectionRequired: Boolean = false,
  /**
   * Mirrors `AmbientMode.Ambient.isLowBitAmbientSupported`. Only meaningful when [state] is
   * [AmbientPreviewState.Ambient].
   */
  val deviceHasLowBitAmbient: Boolean = false,
)

/**
 * Discoverable mirror of the active states `androidx.wear.compose.foundation.AmbientMode` exposes.
 * The Gradle plugin can't load Compose at discovery time, so we duplicate the relevant set here and
 * let the renderer translate at render time.
 */
enum class AmbientPreviewState {
  Interactive,
  Ambient,
}
