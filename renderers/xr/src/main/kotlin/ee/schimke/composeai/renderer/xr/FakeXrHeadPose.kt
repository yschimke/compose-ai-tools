package ee.schimke.composeai.renderer.xr

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3

/**
 * Seeds the offline XR runtime with a **user head pose** so the `rotateToLookAtUser` ("face the
 * viewer" / billboard) `SubspaceModifier` produces a sensible facing rotation under the fake XR
 * runtime — the same recovery path [SubspaceSceneRecorder] drives for `@XrSubspacePreview`s.
 *
 * Why this is needed offline:
 * - `RotateToLookAtUserNode.onAttach` only wires up its `ArDevice` head-pose source when the
 *   `Session` config has device tracking enabled; the default offline `Session` is created with
 *   `DeviceTrackingMode.DISABLED`, so the node skips initialising `arDevice` and then crashes in
 *   its head-pose job (`UninitializedPropertyAccessException: lateinit property arDevice ...`).
 * - The fake `ArDevice` still needs an explicit, stable pose so every render observes the same
 *   viewer-relative state before it records layout.
 *
 * [install] fixes both: it pre-creates a `Session` (so `Subspace`'s `getOrCreateSession` reuses it
 * via the decor-view tag rather than building a `DISABLED` one), flips its config to
 * device-tracking, and seeds the fake `ArDevice`'s pose to the viewer-relative subspace origin. The
 * arcore fake (`FakePerceptionRuntimeFactory`) must be registered for `ServiceLoader`
 * (`META-INF/services/androidx.xr.runtime.internal.PerceptionRuntimeFactory`) for this to resolve.
 *
 * Both the config flip and the pose seed go in through the runtime **state**, not
 * `Session.configure` / `ArDevice.update`: those each spin an internal `runBlocking` that deadlocks
 * under the Compose-UI test coroutine environment and register a live perception runtime that hangs
 * the *next* preview rendered in the same JVM (the producer renders every `@XrSubspacePreview` in
 * one `ParameterizedRobolectricTestRunner` JVM). Setting `Session.config` + the `ArDevice` state
 * flow directly (reflectively, like the recorder's view/node recovery) keeps it side-effect-free
 * and the arcore + arcore-testing artifacts off this module's compile classpath — they're a
 * render-time dependency, registered alongside the scene/rendering fakes. The recorder tests are
 * the canary if that shape shifts.
 *
 * Call **before** `setContent`, while the spatial system feature is already enabled.
 */
public object FakeXrHeadPose {

  /**
   * The viewer/head position used when none is supplied: the subspace origin. XR subspace layout is
   * viewer-relative, so this makes offset panels face inward while the centred panel remains
   * head-on. Older XR Compose builds produced a degenerate Y-flip for the centred zero-length look
   * vector; the supported runtime now explicitly resolves it to identity.
   */
  public val DEFAULT_HEAD_POSE: Pose = Pose.Identity

  /**
   * Pre-creates the offline XR [Session] on [rule], enables device tracking on it, and seeds the
   * fake head/device pose to [headPose]. Returns the session.
   */
  public fun install(
    rule: AndroidComposeTestRule<*, ComponentActivity>,
    headPose: Pose = DEFAULT_HEAD_POSE,
  ): Session {
    val created = Session.create(rule.activity)
    check(created is SessionCreateSuccess) { "Could not create offline XR Session: $created" }
    val session = created.session

    // Flip the session config to device-tracking so RotateToLookAtUserNode.onAttach wires up its
    // ArDevice source instead of bailing out and leaving `arDevice` uninitialised. Set the field
    // directly (Session.configure runs a deadlock-prone runBlocking and registers a global
    // perception
    // runtime — see the class KDoc). The fake perception runtime already defaults to a
    // device-tracking
    // config, so ArDevice.getInstance resolves.
    //
    // This is a REQUIRED prerequisite, not part of the best-effort seed below: with tracking left
    // disabled, `RotateToLookAtUserNode.onAttach` skips assigning `arDevice` yet still starts its
    // head-pose job on placement, so a `rotateToLookAtUser` preview crashes on the uninitialised
    // `arDevice` rather than degrading. A miss here must surface loudly. It reflects the
    // still-present
    // `Session.access$setConfig$p` synthetic (verified against the resolved alpha15 runtime), so it
    // is
    // not the version-fragile path the seed below guards.
    setSessionConfig(session, Config(deviceTracking = DeviceTrackingMode.SPATIAL_LAST_KNOWN))

    // Make Subspace's getOrCreateSession reuse THIS session (it reads the decor-view tag first).
    // alpha15 dropped the `AndroidComposeTestRule.session` test extension; write the same
    // `androidx.xr.compose` R.id.compose_xr_session decor-view tag it used to set.
    rule.activity.window.decorView.setTag(androidx.xr.compose.R.id.compose_xr_session, session)

    // Best-effort: the head-pose seed reaches `androidx.xr.runtime` / `androidx.xr.arcore`
    // internals
    // reflectively, so a version bump that renames/moves/drops one of those symbols (e.g.
    // `androidx.xr
    // .runtime.TrackingState` disappearing) must NOT take down the whole XR render — the seed only
    // powers the `rotateToLookAtUser` billboard facing. A reflective/linkage miss logs a warning
    // and
    // the render still produces its scene.json + textures.
    runCatching { seedHeadPose(session, headPose) }
      .onFailure { warnSeedSkipped("seed the viewer head pose", it) }
    return session
  }

  /**
   * Replays the seeded pose after the subspace has been placed, then waits for the resulting
   * placement invalidation to settle.
   *
   * `rotateToLookAtUser` starts collecting `ArDevice.state` from `onPlaced`. A render that records
   * immediately after its first Compose idle can therefore race the collector's initial emission:
   * the scene may contain either the identity/default head pose or the seeded one. Publish a
   * deliberately different pose first so `StateFlow` cannot suppress the replay as equal, wait for
   * that placement, then restore [headPose] and wait again. The final recorded layout is
   * consequently downstream of an observed seeded-pose emission rather than merely downstream of
   * composition.
   *
   * This is harmless for previews without `rotateToLookAtUser`: their `ArDevice.state` has no
   * collector, so the two writes only update the offline fake.
   */
  public fun settleAfterComposition(
    rule: AndroidComposeTestRule<*, ComponentActivity>,
    session: Session,
    headPose: Pose = DEFAULT_HEAD_POSE,
  ) {
    val fartherPose =
      Pose(
        translation =
          Vector3(
            headPose.translation.x,
            headPose.translation.y,
            headPose.translation.z + 1f,
          ),
        rotation = headPose.rotation,
      )
    runCatching { seedHeadPose(session, fartherPose) }
      .onFailure { warnSeedSkipped("replay the viewer head pose", it) }
    rule.waitForIdle()
    runCatching { seedHeadPose(session, headPose) }
      .onFailure { warnSeedSkipped("restore the viewer head pose", it) }
    rule.waitForIdle()
  }

  /**
   * Logs a best-effort warning when the reflective head-pose seed can't run against the resolved
   * `androidx.xr.*` runtime (a version skew renamed/moved/dropped an internal symbol). The render
   * continues without the seed — `rotateToLookAtUser` billboards fall back to a default facing
   * rather than failing the whole `composePreviewRenderXr` task. Update the reflective accessors
   * here if the seed matters for the target version.
   */
  private fun warnSeedSkipped(step: String, t: Throwable) {
    System.err.println(
      "FakeXrHeadPose: could not $step (${t.javaClass.simpleName}: ${t.message}); " +
        "rotateToLookAtUser billboards fall back to a default facing. This is usually an " +
        "androidx.xr.* version skew — update the reflective accessors in FakeXrHeadPose."
    )
  }

  /**
   * Sets `Session.config` directly via the synthetic `access$setConfig$p` accessor (no
   * runBlocking).
   */
  private fun setSessionConfig(session: Session, config: Config) {
    Session::class
      .java
      .getMethod("access\$setConfig\$p", Session::class.java, Config::class.java)
      .invoke(null, session, config)
  }

  /**
   * Seeds the head pose into the `ArDevice` the node collects. `ArDevice.getInstance(session)`
   * returns the cached wrapper the node reads; its pose ultimately comes from the fake runtime
   * device, which the perception update cycle refreshes `ArDevice.state` from. We seed the runtime
   * device's pose (the durable path) and, best-effort, also prime the `StateFlow<State>` directly
   * so the seed is present immediately on attach — rather than `ArDevice.update()`, a
   * deadlock-prone runBlocking under the multi-preview render's test-coroutine environment. All
   * arcore access is reflective so the artifacts stay off the compile classpath; the direct-prime
   * reaches version-fragile internals, so it degrades to the runtime-device seed alone when they
   * shift (see the inline note).
   */
  private fun seedHeadPose(session: Session, headPose: Pose) {
    val arDeviceClass = Class.forName("androidx.xr.arcore.ArDevice")
    val arDevice = arDeviceClass.getMethod("getInstance", Session::class.java).invoke(null, session)

    // Seed the underlying fake *runtime* device's pose. The perception runtime's update cycle
    // (which
    // runs during waitForIdle in the multi-preview render task) refreshes ArDevice.state FROM the
    // runtime device, so THIS is what actually lands the viewer pose the RotateToLookAtUserNode job
    // collects — it also survives that refresh (a directly-set ArDevice.state would be overwritten
    // back to the runtime's default). Uses `androidx.xr.arcore.runtime.TrackingState`, the runtime
    // enum that is still present (mirrors `:samples:xr-spatial`'s inlined seedHeadPose).
    val runtimeArDevice = arDeviceClass.getMethod("getRuntimeArDevice\$arcore").invoke(arDevice)
    runtimeArDevice.javaClass
      .getMethod("setDevicePose", Pose::class.java)
      .invoke(runtimeArDevice, headPose)
    val runtimeTrackingClass = Class.forName("androidx.xr.arcore.runtime.TrackingState")
    runtimeArDevice.javaClass
      .getMethod("setTrackingState", runtimeTrackingClass)
      .invoke(runtimeArDevice, runtimeTrackingClass.getField("TRACKING").get(null))

    // Belt-and-suspenders: also prime ArDevice._state directly so the seed is present immediately
    // on
    // attach, not only after the first refresh. This reaches `androidx.xr.runtime.TrackingState`
    // and
    // the `ArDevice.State` constructor — internals that move between XR versions (alpha16 dropped
    // `androidx.xr.runtime.TrackingState`), so keep it best-effort: if the shape has shifted, the
    // runtime-device seed above still carries the pose on the next refresh, so warn and skip rather
    // than fail the whole render.
    runCatching {
      val trackingStateClass = Class.forName("androidx.xr.runtime.TrackingState")
      val tracking = trackingStateClass.getField("TRACKING").get(null)
      val stateClass = Class.forName("androidx.xr.arcore.ArDevice\$State")
      val state =
        stateClass
          .getConstructor(Pose::class.java, trackingStateClass, arDeviceClass)
          .newInstance(headPose, tracking, arDevice)

      val stateField = arDeviceClass.getDeclaredField("_state").apply { isAccessible = true }
      @Suppress("UNCHECKED_CAST")
      val mutableState = stateField.get(arDevice) as kotlinx.coroutines.flow.MutableStateFlow<Any?>
      mutableState.value = state
    }
      .onFailure { warnSeedSkipped("prime the ArDevice state flow", it) }
  }
}
