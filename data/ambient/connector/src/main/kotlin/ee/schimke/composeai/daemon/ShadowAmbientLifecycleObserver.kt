package ee.schimke.composeai.daemon

import android.app.Activity
import androidx.lifecycle.LifecycleOwner
import androidx.wear.ambient.AmbientLifecycleObserver
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/**
 * Robolectric shadow for `androidx.wear.ambient.AmbientLifecycleObserver`.
 *
 * Under Robolectric the system-side `WearableActivityController` isn't present — that's why
 * horologist's `AmbientAware` swallows `NoClassDefFoundError` and the state silently degrades to
 * `AmbientState.Inactive` in previews. This shadow replaces the AOSP class's body with a
 * controller-aware implementation: `isAmbient()` reads [AmbientStateController.current], and the
 * constructor registers the consumer's [AmbientLifecycleObserver.AmbientLifecycleCallback] with
 * the controller so [AmbientStateController.set] can fan out `onEnterAmbient` / `onExitAmbient` /
 * `onUpdateAmbient` calls into the consumer's existing code paths unchanged.
 *
 * The shadow targets the public `AmbientLifecycleObserver` interface; Robolectric resolves the
 * actual concrete class loaded inside the sandbox at instrumenting time. Lifecycle callbacks
 * (`onCreate` / `onResume` / `onPause` / `onDestroy`) are no-ops — the controller drives the
 * ambient transitions directly, and the AOSP impl's lifecycle plumbing (which would have
 * registered with `WearableActivityController`) is intentionally bypassed.
 */
@Implements(AmbientLifecycleObserver::class)
class ShadowAmbientLifecycleObserver {

  @RealObject @Suppress("unused") private lateinit var realObserver: AmbientLifecycleObserver

  private var registeredCallback: AmbientLifecycleObserver.AmbientLifecycleCallback? = null

  @Suppress("FunctionName")
  @Implementation
  protected fun __constructor__(
    @Suppress("UNUSED_PARAMETER") activity: Activity,
    callback: AmbientLifecycleObserver.AmbientLifecycleCallback,
  ) {
    registeredCallback = callback
    AmbientStateController.registerCallback(callback)
  }

  @Implementation
  fun isAmbient(): Boolean = AmbientStateController.current() == AmbientStateOverride.AMBIENT

  @Implementation
  @Suppress("UNUSED_PARAMETER")
  fun onCreate(owner: LifecycleOwner) {
    // No-op — controller drives ambient transitions directly.
  }

  @Implementation
  @Suppress("UNUSED_PARAMETER")
  fun onResume(owner: LifecycleOwner) {
    // No-op — controller drives ambient transitions directly.
  }

  @Implementation
  @Suppress("UNUSED_PARAMETER")
  fun onPause(owner: LifecycleOwner) {
    // No-op — controller drives ambient transitions directly.
  }

  @Implementation
  @Suppress("UNUSED_PARAMETER")
  fun onDestroy(owner: LifecycleOwner) {
    registeredCallback?.let { AmbientStateController.unregisterCallback(it) }
    registeredCallback = null
  }

  companion object {
    /**
     * Fully-qualified name of the shadow class. Exposed as a string so non-connector modules
     * (`renderer-android`'s test runner, the daemon's `SandboxRunner` `@Config`) can refer to the
     * shadow without taking a compile-time dependency on `:data-ambient-connector`.
     */
    const val SHADOW_FQN: String = "ee.schimke.composeai.daemon.ShadowAmbientLifecycleObserver"
  }
}
