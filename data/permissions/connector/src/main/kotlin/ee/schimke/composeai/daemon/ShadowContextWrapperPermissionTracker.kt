package ee.schimke.composeai.daemon

import android.content.ContextWrapper
import android.content.pm.PackageManager
import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/**
 * Robolectric shadow on `android.content.ContextWrapper.checkPermission(String, int, int)`.
 *
 * Purpose: every Android permission check that goes through the `checkPermission(perm, pid, uid)`
 * triple (`ContextCompat.checkSelfPermission(context, perm)`, `accompanist`'s
 * `rememberPermissionState`, or any direct `Context.checkPermission(perm, Process.myPid(),
 * Process.myUid())` call) terminates at `ContextWrapper.checkPermission` for any non-trivial
 * context (an `Activity`, `Application`, or any wrapper around them). Shadowing the wrapper catches
 * every consumer-side path that uses that call shape without needing per-context shadows.
 *
 * **Path coverage.** `Context.checkSelfPermission(String)` is implemented directly in `ContextImpl`
 * against `PermissionManager.checkPermission(...)` and bypasses `ContextWrapper.checkPermission` —
 * this shadow does NOT intercept the `context.checkSelfPermission(perm)` form. The production
 * sample (`samples/android/.../PermissionGatedPreview.kt`) uses
 * `ContextCompat.checkSelfPermission`, the recommended AndroidX shape, which goes through the
 * wrapper path this shadow covers.
 *
 * **Why no `Shadow.directlyOn` forward.** Robolectric ships `ShadowActivity` which is a more
 * specific shadow than this one for any `Activity` instance — the common case for preview
 * compositions (`LocalContext.current` is the Activity hosting the `ComposeView`). Calling
 * `Shadow.directlyOn(realWrapper, ContextWrapper::class.java, "checkPermission", …)` re-enters the
 * bytecode-instrumented `ContextWrapper.checkPermission` from the `ReflectionHelpers
 * .callInstanceMethod` side. Robolectric's intercept then resolves the runtime shadow for the
 * `Activity` instance, gets `ShadowActivity`, and tries to cast it to this shadow's type — which
 * fails with `ClassCastException`. The integration regression is pinned by
 * `PermissionsDataFetchE2ETest`.
 *
 * Instead this shadow resolves the result by consulting Robolectric's `ShadowApplication` grant
 * state directly (the same data structure `PermissionsController.syncRobolectricGrants` writes the
 * override into via reflection). `ShadowApplication`'s public API is the documented authority for
 * permission state under Robolectric, so reading from it returns the same value the real
 * `ContextImpl.checkPermission` would, without crossing the broken shadow-cast path.
 *
 * Behaviour: consult [PermissionsController]'s grant state (which mirrors `ShadowApplication`'s)
 * and record the queried permission in `PermissionsController.recordQuery(...)` for the
 * data-product payload. Idempotent — duplicate queries for the same permission don't grow the list,
 * only the first hit lands.
 *
 * **Registration**: this shadow is registered via `SandboxHoldingRunner.getExtraShadows(...)` in
 * `:daemon:android`. The Robolectric sandbox loads the shadow class, sees the `@Implements`
 * annotation, and from then on every `ContextWrapper.checkPermission` call routes through here.
 */
@Implements(ContextWrapper::class)
class ShadowContextWrapperPermissionTracker {

  @RealObject @Suppress("unused") private lateinit var realWrapper: ContextWrapper

  @Implementation
  fun checkPermission(permission: String?, pid: Int, uid: Int): Int {
    if (permission != null) {
      PermissionsController.recordQuery(permission)
    }
    // Consult [PermissionsController]'s grant state directly. The around-composable seeds it from
    // `renderNow.overrides.permissions` before the first composition runs (via
    // `PermissionsOverrideExtension.init`); the controller also mirrors the seed into
    // `ShadowApplication.grantPermissions/denyPermissions` via reflection so the
    // `context.checkSelfPermission(perm)` path (which goes through `ContextImpl` and consults
    // `ShadowApplication` directly, bypassing this shadow) returns the same value. Permissions
    // not pinned in the override fall back to denied — matching Robolectric's default for a
    // preview-rendered Application that hasn't declared the permission in its manifest.
    return when (
      PermissionsController.grantFor(permission ?: return PackageManager.PERMISSION_DENIED)
    ) {
      PermissionGrantStateOverride.GRANTED -> PackageManager.PERMISSION_GRANTED
      PermissionGrantStateOverride.DENIED,
      null -> PackageManager.PERMISSION_DENIED
    }
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
