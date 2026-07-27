package ee.schimke.composeai.renderer

import android.app.Activity
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ProvidedValue

/**
 * Provides `androidx.xr.compose.platform.LocalSession` to the ordinary (non-subspace) render so XR
 * Compose composables taking their **2D fallback** can compose off-device.
 *
 * Why: `Orbiter`, `SpatialElevation`, `SpatialPopup` and friends are written once and render inline
 * when the app isn't in Full Space — that flat fallback is exactly what an ordinary `@Preview`
 * captures. As of `androidx.xr.compose:compose` 1.0.0-alpha16 those composables consume
 * `LocalSession` and `checkNotNull` it *before* branching to the 2D case, so a preview that merely
 * mentions `Orbiter` dies with `IllegalStateException: session must be initialized` where alpha15
 * fell back without asking. The preview then writes no PNG, and under
 * `-PcomposePreview.missingRenders=fail` that fails the whole module's render.
 *
 * Providing the composition local directly is what makes this work offline. `LocalSession`'s own
 * default resolves through `LocalComposeXrOwners`, whose session is populated by a **suspend**
 * `initialize$compose` → `getOrCreateSession(activity, …)`; under the render's paused clock that
 * coroutine never lands, so the default stays null no matter what the host window carries. Seeding
 * the decor-view tag — the hand-off [ee.schimke.composeai.renderer.xr.FakeXrHeadPose] uses for
 * `@XrSubspacePreview`s — does not reach this path.
 *
 * Everything is reflective and best-effort by design:
 * - `renderer-android` must not gain a compile dependency on `androidx.xr.*`; the overwhelming
 *   majority of consumers have no XR artifacts at all, and for them every lookup here misses and
 *   the whole thing is a no-op (the normal case, not an error).
 * - A consumer using `androidx.xr.compose` without our `:renderer-xr` module gets the same repair,
 *   because this reaches the XR runtime directly rather than through that module.
 * - Session creation needs a `PerceptionRuntimeFactory` on the classpath (the `*-testing` fakes,
 *   registered for `ServiceLoader`). Where that's absent, creation fails, this returns null and the
 *   preview behaves exactly as it does today rather than crashing differently.
 */
internal object OfflineXrSession {

  /** Resolved once; null means "XR Compose isn't on this preview's classpath". */
  private val binding: Binding? by lazy { resolve() }

  private class Binding(
    val local: ProvidableCompositionLocal<Any?>,
    val createSession: (Activity) -> Any?,
  )

  /**
   * A `LocalSession provides <offline session>` entry for the render's composition-local array, or
   * null when XR isn't present / no session could be created.
   *
   * Mirrors [LocaleCompositionLocals.providedValue]'s shape so the caller stays a one-liner.
   */
  fun providedValue(activity: Activity): ProvidedValue<*>? {
    val bound = binding ?: return null
    return runCatching {
        val session = bound.createSession(activity) ?: return null
        bound.local provides session
      }
      .getOrNull()
  }

  @Suppress("UNCHECKED_CAST")
  private fun resolve(): Binding? =
    runCatching {
        val local =
          Class.forName("androidx.xr.compose.platform.LocalSessionKt")
            .getDeclaredMethod("getLocalSession")
            .invoke(null) as ProvidableCompositionLocal<Any?>
        val sessionClass = Class.forName("androidx.xr.runtime.Session")
        // `create(Activity)` is the Kotlin default-argument entry point, emitted as a real static
        // on both 1.0.0-alpha15 and 1.0.0-beta01 — no `create$default` synthetic juggling needed.
        val create = sessionClass.getMethod("create", Activity::class.java)
        val successClass = Class.forName("androidx.xr.runtime.SessionCreateSuccess")
        val getSession = successClass.getMethod("getSession")
        Binding(local) { activity ->
          val result = create.invoke(null, activity)
          if (successClass.isInstance(result)) getSession.invoke(result) else null
        }
      }
      .getOrNull()
}
