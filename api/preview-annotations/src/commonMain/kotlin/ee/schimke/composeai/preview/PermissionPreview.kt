package ee.schimke.composeai.preview

/**
 * Declares the Android **runtime-permission grant state** a `@Preview` should be captured under, so
 * a permission-gated screen's granted branch is reachable from the static Gradle render lane and
 * not only from a live daemon session.
 *
 * The problem it solves (issue #3676): a screen that gates on
 * `ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)` has two branches, but
 * off-device *both* `@Preview`s of it render the denied one — nothing in the build ever grants the
 * permission. Authors used to work around that by threading a `granted: Boolean` parameter through
 * the composable, which is exactly the preview-only flag this project exists to avoid: the screen
 * then no longer exercises the platform call the app ships. This annotation flips the grant state
 * from *outside* the screen instead, leaving the composable as ordinary app code that keeps reading
 * `checkSelfPermission`.
 *
 * The compose-preview Gradle plugin's discovery task picks it up by FQN — mirroring
 * [AmbientPreview] / [GestureHintPreview] / [FocusedPreview] — so a project that never depends on
 * `ee.schimke.composeai:preview-annotations` is unaffected, and a project that wants the annotation
 * simply adds that artifact. The renderer translates the discovered grant map into
 * `:data-permissions-connector`'s `PermissionsOverrideExtension`, whose construction seeds
 * Robolectric's `ShadowApplication.grantPermissions/denyPermissions` **before the first composition
 * pass**. That ordering is the whole trick: unlike ambient / gesture hints, which wrap the
 * composition and are read from inside it, a permission is read through the platform API on the
 * very first composition, so a seed applied from a `DisposableEffect` would land one render too
 * late. Daemon-driven `renderNow.overrides.permissions` reaches the same controller through the
 * connector's planner, so a baked capture and a live chip flip agree by construction.
 *
 * Pair a bare `@Preview` (denied — the resting state off-device) with a second `@Preview` + this
 * annotation (granted) over the *same* screen to capture both branches from one definition:
 * ```
 * @Preview(name = "Camera permission — denied", showBackground = true)
 * @Composable
 * fun CameraPermissionDeniedPreview() {
 *   PermissionGatedCameraScreen()
 * }
 *
 * @Preview(name = "Camera permission — granted", showBackground = true)
 * @PermissionPreview(grants = ["android.permission.CAMERA=granted"])
 * @Composable
 * fun CameraPermissionGrantedPreview() {
 *   PermissionGatedCameraScreen()
 * }
 * ```
 *
 * Applies to every `@Preview` expansion on the function — each light/dark multipreview member is
 * captured under the same grant state — the same "one annotation, applies to every expansion"
 * policy [AmbientPreview] / [GestureHintPreview] / [ScrollingPreview] follow.
 *
 * ## Failure modes worth knowing
 * * **Android-only.** The grant state is seeded into a Robolectric shadow; the Desktop (Skiko) lane
 *   has no Android platform to seed, so the annotation is a no-op there. It is also dropped for
 *   non-composable previews (tile / notification / Glance / XR), which render outside the
 *   composition the connector wraps.
 * * **The grant map is exhaustive, not additive.** A permission absent from [grants] is explicitly
 *   *denied* for the render, so a previous preview's grants can never leak into this one. Name
 *   every permission the capture depends on.
 * * **Only the `ContextWrapper.checkPermission` family is intercepted end-to-end.** That covers
 *   `ContextCompat.checkSelfPermission(...)` (the recommended AndroidX shape),
 *   `Activity.checkSelfPermission(...)`, `Context.checkPermission(...)` and accompanist's
 *   `rememberPermissionState`. A screen that asks the `PackageManager` about *another* package, or
 *   that consults a permission through a path this connector does not model, still renders its real
 *   answer.
 * * **A malformed entry is dropped with a build warning, not a build failure.** Discovery treats an
 *   unparseable annotation the way it treats a broken `@PreviewAxis`: it costs the grant it would
 *   have applied and says so, rather than failing every other preview in the module. The visible
 *   symptom of ignoring that warning is a "granted" preview that quietly captures the denied branch
 *   — the exact defect this annotation exists to fix.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class PermissionPreview(
  /**
   * Grant state per permission, each entry `"<permission>=<state>"` — e.g.
   * `"android.permission.CAMERA=granted"`. The permission is the full Android constant string
   * (`Manifest.permission.CAMERA` resolves to `"android.permission.CAMERA"`), because that is the
   * key `checkSelfPermission` is queried with and the key Robolectric's grant set stores.
   *
   * The state is `granted` or `denied`, matched case-insensitively, so `GRANTED` / `Granted` all
   * work. Whitespace around either side is trimmed. Anything else — a missing `=`, a blank
   * permission, an unrecognised state — is dropped with a discovery warning naming the offending
   * entry.
   *
   * Typed as `Array<String>` rather than a nested annotation for the same reason [OverrideVariant]
   * spells its seeds `"key=value"`: the Gradle plugin reads annotations off compiled bytecode with
   * ClassGraph and cannot load a Compose-adjacent enum at discovery time, so a flat string entry is
   * the shape that survives the scan without a parallel enum mirror.
   *
   * An empty array (the default) leaves the preview with no grant override at all — identical to
   * omitting the annotation.
   */
  val grants: Array<String> = []
)
