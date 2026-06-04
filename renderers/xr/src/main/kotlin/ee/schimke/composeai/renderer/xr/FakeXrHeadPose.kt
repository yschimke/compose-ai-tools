package ee.schimke.composeai.renderer.xr

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.xr.compose.testing.session
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3

/**
 * Seeds the offline XR runtime with a **user head pose** so the `rotateToLookAtUser`
 * ("face the viewer" / billboard) `SubspaceModifier` produces a sensible facing rotation under the
 * fake XR runtime — the same recovery path [SubspaceSceneRecorder] drives for `@XrSubspacePreview`s.
 *
 * Why this is needed offline:
 *  - `RotateToLookAtUserNode.onAttach` only wires up its `ArDevice` head-pose source when the
 *    `Session` config has device tracking enabled; the default offline `Session` is created with
 *    `DeviceTrackingMode.DISABLED`, so the node skips initialising `arDevice` and then crashes in its
 *    head-pose job (`UninitializedPropertyAccessException: lateinit property arDevice ...`).
 *  - Even configured, the fake `ArDevice` reports an identity pose at the origin, so a panel at the
 *    origin would "look at" itself — a degenerate 180° Y-flip rather than facing the viewer.
 *
 * [install] fixes both: it pre-creates a `Session` (so `Subspace`'s `getOrCreateSession` reuses it
 * via the decor-view tag rather than building a `DISABLED` one), flips its config to device-tracking,
 * and seeds the fake `ArDevice`'s pose to the viewer's position — by default in front of the panels
 * on +Z, where [SubspaceSceneRecorder.defaultCamera] puts the offline camera. The arcore fake
 * (`FakePerceptionRuntimeFactory`) must be registered for `ServiceLoader`
 * (`META-INF/services/androidx.xr.runtime.internal.PerceptionRuntimeFactory`) for this to resolve.
 *
 * Both the config flip and the pose seed go in through the runtime **state**, not `Session.configure`
 * / `ArDevice.update`: those each spin an internal `runBlocking` that deadlocks under the Compose-UI
 * test coroutine environment and register a live perception runtime that hangs the *next* preview
 * rendered in the same JVM (the producer renders every `@XrSubspacePreview` in one
 * `ParameterizedRobolectricTestRunner` JVM). Setting `Session.config` + the `ArDevice` state flow
 * directly (reflectively, like the recorder's view/node recovery) keeps it side-effect-free and the
 * arcore + arcore-testing artifacts off this module's compile classpath — they're a render-time
 * dependency, registered alongside the scene/rendering fakes. The recorder tests are the canary if
 * that shape shifts.
 *
 * Call **before** `setContent`, while the spatial system feature is already enabled.
 */
public object FakeXrHeadPose {

  /**
   * The viewer/head position used when none is supplied: in front of the panels, pulled back along
   * +Z (the runtime works in metres; the recorder/compositor work in dp, but only the *direction*
   * panel→head matters for the look-at rotation, so an order-of-magnitude-correct +Z is enough — it
   * matches the sign of the offline camera in [SubspaceSceneRecorder.defaultCamera]).
   */
  public val DEFAULT_HEAD_POSE: Pose = Pose(translation = Vector3(0f, 0f, 2f))

  /**
   * Pre-creates the offline XR [Session] on [rule], enables device tracking on it, and seeds the fake
   * head/device pose to [headPose]. Returns the session.
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
    // directly (Session.configure runs a deadlock-prone runBlocking and registers a global perception
    // runtime — see the class KDoc). The fake perception runtime already defaults to a device-tracking
    // config, so ArDevice.getInstance resolves.
    setSessionConfig(session, Config(deviceTracking = DeviceTrackingMode.SPATIAL_LAST_KNOWN))

    // Make Subspace's getOrCreateSession reuse THIS session (it reads the decor-view tag first).
    rule.session = session

    seedHeadPose(session, headPose)
    return session
  }

  /** Sets `Session.config` directly via the synthetic `access$setConfig$p` accessor (no runBlocking). */
  private fun setSessionConfig(session: Session, config: Config) {
    Session::class
      .java
      .getMethod("access\$setConfig\$p", Session::class.java, Config::class.java)
      .invoke(null, session, config)
  }

  /**
   * Seeds the head pose into the `ArDevice` state the node collects. `ArDevice.getInstance(session)`
   * returns the cached wrapper the node reads; its pose comes from a `StateFlow<State>` seeded with an
   * identity pose at construction. We set that flow's value directly (rather than `ArDevice.update()`,
   * another deadlock-prone runBlocking) to a `State` carrying the viewer [headPose] and a live
   * tracking state. All arcore access is reflective so the artifacts stay off the compile classpath.
   */
  private fun seedHeadPose(session: Session, headPose: Pose) {
    val arDeviceClass = Class.forName("androidx.xr.arcore.ArDevice")
    val arDevice = arDeviceClass.getMethod("getInstance", Session::class.java).invoke(null, session)

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
}
