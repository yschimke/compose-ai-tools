package ee.schimke.composeai.daemon

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.onehandedgesture.LocalOneHandedGestureEnabled

/**
 * Whether a one-handed gesture can actually reach this composable (issue #5102).
 *
 * ## Why this exists
 *
 * There was no public way to ask it. `LocalOneHandedGestureEnabled` is the **app's opt-out** — it
 * says whether this subtree wants gestures, not whether anything can deliver one. AndroidX computes
 * the other half internally (`GestureRegistry.shouldShowIndicator` is `isActionSupported &&
 * isActionEnabled && shouldShowHint`) and keeps it private, so a catalog wanting to say "double
 * pinch does nothing here, there is no gesture source" ended up reflecting on the SDK's presence:
 * ```kotlin
 * // What a downstream catalog had to write, and should not have to.
 * try { Class.forName("com.google.wear.Sdk"); true } catch (_: Throwable) { false }
 * ```
 *
 * That is conclusive in exactly one direction. Absent means no gesture source; **present does not
 * mean a wearer can gesture**, because the action may be unsupported or switched off. This answers
 * both directions instead, and does it in one place rather than in every consumer.
 *
 * ## How it is right in both directions
 *
 * By asking the library's own bridge, `SdkGestureInputManagerImpl.isAvailable(context)` — the same
 * class [ShadowSdkGestureInputManager] shadows:
 *
 * * **on a device**, that is the real implementation, which creates the `com.google.wear` manager
 *   if it can and answers from it. No `com.google.wear` dependency is taken here, exactly as the
 *   library takes none: its own `createSdkWearManagerIfNeeded` swallows the lookup, because the
 *   classes come from the watch's system image.
 * * **under the renderer / daemon**, Robolectric has replaced that class with the connector's
 *   shadow, so the answer is what the harness decided (`GestureStateController.detectionArmed`) —
 *   which is precisely what a preview should report, since the harness is the gesture source there.
 * * **anywhere else** (a plain JVM, a desktop render, a classpath without wear-compose 1.7), the
 *   lookup fails and the answer is `false`: no source, which is true.
 *
 * Reflection rather than a direct call because the type is `internal` to wear-compose-material3.
 * That coupling is not new and not extra: this connector already names the same class in
 * [ShadowSdkGestureInputManager]'s `@Implements(className = …)`, and if AndroidX ever renames it,
 * the shadow stops applying — loudly — before this signal quietly goes wrong.
 *
 * ## Deliberately not done: stubbing `com.google.wear`
 *
 * A Robolectric shadow only exists where the shadow is installed, so it can never be the answer to
 * "what should app code read"; and `Sdk` + `GestureInputManager` is a far wider surface than the
 * one six-method bridge already shadowed, for no gain, since the library funnels all of it through
 * that bridge. The gap was a missing **public signal**, not a missing fake.
 */
@Composable
public fun oneHandedGestureAvailable(): Boolean {
  // Both halves, and in this order: an app that opted out has no gesture reaching it however
  // capable the device is, and that costs nothing to answer.
  if (!LocalOneHandedGestureEnabled.current) return false
  val context = LocalContext.current
  return remember(context) { oneHandedGestureSourceAvailable(context) }
}

/**
 * The device/harness half of [oneHandedGestureAvailable], for a caller that is not composing — a
 * data product, a test, a decision made before composition starts.
 *
 * Does NOT consider `LocalOneHandedGestureEnabled`, which is a composition-scoped opt-out and
 * cannot be read from here; a consumer inside a composition wants [oneHandedGestureAvailable].
 */
public fun oneHandedGestureSourceAvailable(context: Context): Boolean = runCatching {
  val impl = Class.forName(SDK_GESTURE_INPUT_MANAGER_IMPL).getDeclaredConstructor().newInstance()
  impl.javaClass.getMethod("isAvailable", Context::class.java).invoke(impl, context) as Boolean
}
  // Any failure is the honest `false`: a classpath without wear-compose 1.7 has no gesture source,
  // and a bridge this reader can no longer call is one it must not answer for.
  .getOrDefault(false)

/**
 * The bridge class, named here once. [ShadowSdkGestureInputManager] shadows this same name — the
 * two must agree, which is what makes the harness arm of [oneHandedGestureSourceAvailable] work at
 * all, so the test asserts they do rather than leaving it to a reader.
 */
internal const val SDK_GESTURE_INPUT_MANAGER_IMPL: String =
  "androidx.wear.compose.material3.onehandedgesture.SdkGestureInputManagerImpl"
