package ee.schimke.composeai.renderer

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow that makes Wear's clock deterministic, so a preview showing `TimeText` stops
 * producing a different PNG every minute (issue #3239).
 *
 * ## What it replaces
 *
 * `androidx.wear.compose.materialcore.ResourcesKt.currentTimeMillis()` is a one-line
 * `System.currentTimeMillis()`, and it is the single point both Wear Material's and Wear
 * Material3's `TimeText` read the clock through — `DefaultTimeSource.currentTime()` is, in effect:
 * ```kotlin
 * currentTime({ currentTimeMillis() }, timeFormat).value
 * ```
 *
 * Neither major has an inspection-mode branch (checked through `1.7.0-alpha07`), so there is no
 * `LocalInspectionMode` seam here the way there is elsewhere: a preview gets a fixed clock only by
 * passing its own `TimeSource`, which an activity hero — rendering the app's real screen — has no
 * way to do. Hence a shadow.
 *
 * ## Registration
 *
 * Referenced by class *name*, not class literal, so this file compiles and loads whether or not the
 * consumer has wear-compose — the same precaution [ShadowAsyncImagePainter] and
 * `ShadowAmbientLifecycleObserver` take. Registered in the generated `robolectric.properties`
 * (`shadows=` plus `instrumentedPackages=androidx.wear.compose.materialcore.ResourcesKt`, see
 * `GenerateRobolectricPropertiesTask`) and in the daemon's `SandboxHoldingRunner`, so both Android
 * render paths behave the same.
 *
 * **Both halves are load-bearing.** Robolectric cannot shadow a class it did not instrument, and
 * that package isn't in the default instrumented set — dropping the `instrumentedPackages` entry
 * leaves this class loaded, inert, and Wear clocks back on the host's wall clock. The entry names a
 * *class*, not a package, because Robolectric prefix-matches: exactly this one file gets rewritten
 * and the rest of Wear's rendering path is untouched.
 *
 * When wear-compose is absent there is nothing in that package to instrument and the shadow is
 * inert.
 */
@Implements(className = "androidx.wear.compose.materialcore.ResourcesKt", isInAndroidSdk = false)
class ShadowWearTimeSource {
  companion object {
    /**
     * Returns [PreviewClock.currentTimeMillis] — the pinned instant, or the host's real clock when
     * `-Dcomposeai.render.fixedTime=off` switched pinning off, which is what makes that escape
     * hatch mean what it says rather than "some other fixed time".
     *
     * Read at call time and holding no state of its own, so it is immune to the ordering hazards a
     * `SystemClock.setCurrentTimeMillis` pin would carry — see [PreviewClock]'s KDoc.
     */
    @JvmStatic @Implementation fun currentTimeMillis(): Long = PreviewClock.currentTimeMillis()
  }
}
