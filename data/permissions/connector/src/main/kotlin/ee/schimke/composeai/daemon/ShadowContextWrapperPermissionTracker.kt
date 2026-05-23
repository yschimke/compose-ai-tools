package ee.schimke.composeai.daemon

import android.content.ContextWrapper
import android.content.pm.PackageManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

/**
 * Robolectric shadow on `android.content.ContextWrapper.checkPermission(String, int, int)`.
 *
 * Purpose: every Android permission check eventually flows through either
 * `Context.checkSelfPermission(String)` (which under the hood calls `checkPermission(perm,
 * Process.myPid(), Process.myUid())` on the bound `Context`) or `ContextCompat.checkSelfPermission
 * (context, perm)` (which calls the same `checkPermission(...)` triple). Both paths terminate at
 * `ContextWrapper.checkPermission` for any non-trivial context (the base `Activity`,
 * `Application`, or any wrapper around them) — shadowing the wrapper catches every consumer-side
 * path without needing per-context shadows.
 *
 * Behaviour: forwards to the real implementation (so Robolectric's `ShadowApplication`-grant state
 * still drives the actual return value), then records the queried permission in
 * `PermissionsController.recordQuery(...)` for the data-product payload. Idempotent — duplicate
 * queries for the same permission don't grow the list, only the first hit lands.
 *
 * The shadow targets `android.content.ContextWrapper` rather than `ContextImpl` because the
 * platform's `ContextImpl` is a hidden class (Robolectric maps it in but consumers shouldn't
 * touch). `Activity` extends `ContextThemeWrapper` extends `ContextWrapper`, and so does every
 * `View`-derived context — so this single shadow covers every preview's Compose root.
 *
 * **Registration**: this shadow is registered conditionally on the consumer's classpath shape via
 * `SandboxHoldingRunner.getExtraShadows(...)` in `:daemon:android`. The Robolectric sandbox loads
 * the shadow class, sees the `@Implements` annotation, and from then on every
 * `ContextWrapper.checkPermission` call routes through here.
 */
@Implements(ContextWrapper::class)
class ShadowContextWrapperPermissionTracker {

  @RealObject @Suppress("unused") private lateinit var realWrapper: ContextWrapper

  @Implementation
  fun checkPermission(permission: String?, pid: Int, uid: Int): Int {
    // Forward to the real `ContextWrapper.checkPermission` so Robolectric's `ShadowApplication`
    // grant state still drives the actual return value. `Shadow.directlyOn` re-enters the
    // unwrapped method without going back through this shadow.
    val result: Int =
      Shadow.directlyOn<Int, ContextWrapper>(
        realWrapper,
        ContextWrapper::class.java,
        "checkPermission",
        org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, permission),
        org.robolectric.util.ReflectionHelpers.ClassParameter.from(
          Int::class.javaPrimitiveType,
          pid,
        ),
        org.robolectric.util.ReflectionHelpers.ClassParameter.from(
          Int::class.javaPrimitiveType,
          uid,
        ),
      ) ?: PackageManager.PERMISSION_DENIED
    if (permission != null) {
      PermissionsController.recordQuery(permission)
    }
    return result
  }

  companion object {
    /**
     * Fully-qualified name of the shadow class. Exposed as a string so non-connector modules
     * (`:daemon:android`'s `SandboxHoldingRunner` / `RobolectricHost`) can refer to the shadow
     * without taking a compile-time dependency on `:data-permissions-connector`. Mirrors
     * [ShadowAmbientLifecycleObserver.SHADOW_FQN].
     */
    const val SHADOW_FQN: String =
      "ee.schimke.composeai.daemon.ShadowContextWrapperPermissionTracker"
  }
}
