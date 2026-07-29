package ee.schimke.composeai.renderer

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

/**
 * Robolectric shadow that stops coil 2's `AsyncImagePainter` from short-circuiting into its
 * preview branch, so a preview render actually loads the image (issue #2952).
 *
 * ## Why this is needed at all
 *
 * `rememberAsyncImagePainter` copies `LocalInspectionMode.current` onto the painter's `isPreview`
 * field, and `onRemembered` then does, in effect:
 * ```kotlin
 * if (isPreview) { updateState(State.Loading(request.placeholder()?.toPainter())); return }
 * ```
 * — it paints the *placeholder* and never starts a request. The renderer composes with
 * `LocalInspectionMode = true` on purpose (AS parity, issue #1584: a preview that branches on it to
 * show stub data instead of a network call must hit the same branch it does in the IDE), so coil 2
 * takes that branch on every static preview render. With no placeholder configured — the normal
 * case — the painter holds `State.Loading(null)`: nothing to draw, **and no intrinsic size**, which
 * is what makes a `ContentScale.FillWidth` image swell to its parent's full height and shove
 * sibling content out of frame. That is the "solid black sticker with the caption missing" the
 * issue describes, and no amount of driving coil's coroutines to completion fixes it, because no
 * coroutine was ever started.
 *
 * ## Why a shadow, rather than turning inspection mode off
 *
 * Flipping `LocalInspectionMode` to `false` for the whole render would fix coil and break
 * everything else: it is a root-level composition local, so it would silently move every
 * `if (LocalInspectionMode.current)` branch in the consumer's tree onto its production path
 * (real network calls, real DI) — a much bigger behaviour change than the bug being fixed, and a
 * direct reversal of the deliberate AS-parity default. Shadowing the setter is surgical: it
 * changes exactly one boolean inside coil, and the consumer's own inspection-mode branches keep
 * seeing `true`.
 *
 * This is the same shape as [ShadowFontsContractCompat] — a third-party library that silently
 * degrades under Robolectric, intercepted at the lowest stable seam rather than worked around in
 * consumer code.
 *
 * ## Coil 3 doesn't need this
 *
 * coil 3 routes its inspection-mode branch through `LocalAsyncImagePreviewHandler`, a public,
 * supported hook for exactly this purpose, so [Coil3PreviewInstaller] uses that instead of a
 * shadow. Only coil 2, which has no equivalent, needs bytecode-level help.
 *
 * ## Registration
 *
 * Referenced by class *name*, not by class literal, so this file compiles and loads whether or not
 * the consumer has coil. Registered in the generated `robolectric.properties` (`shadows=` plus
 * `instrumentedPackages=coil.compose`, see `GenerateRobolectricPropertiesTask`) and on the daemon's
 * `SandboxRunner` `@Config`, so both Android render paths behave the same. When coil 2 is absent,
 * nothing in `coil.compose` exists to instrument and the shadow is inert.
 *
 * Flipping the flag is only half the fix: the load now *starts*, and [CoilPreviewSupport]'s
 * inline-dispatcher `ImageLoader` is what makes it *finish* before the capture.
 */
@Implements(className = "coil.compose.AsyncImagePainter", isInAndroidSdk = false)
class ShadowAsyncImagePainter {

  @RealObject lateinit var realPainter: Any

  /**
   * Replaces `AsyncImagePainter.setPreview$coil_compose_base_release(boolean)` — the Kotlin
   * internal setter `rememberAsyncImagePainter` calls with `LocalInspectionMode.current`. The
   * backtick-quoted name is the actual JVM method name after Kotlin's `internal` mangling; it is
   * stable across coil 2.x (the module name it encodes, `coil_compose_base`, hasn't changed since
   * 2.0).
   *
   * Stores `false` so `onRemembered` runs the real load path — unless
   * `-Dcomposeai.coil.previewLoader=false` turned the whole coil integration off, in which case the
   * incoming value is stored verbatim and coil behaves exactly as it did before #2952. Keeping the
   * escape hatch honest matters: an opt-out that disabled the loader swap but left this shadow in
   * force would leave coil starting real loads with no one driving them, which is worse than either
   * end state.
   */
  @Implementation
  fun `setPreview$coil_compose_base_release`(preview: Boolean) {
    val effective = if (CoilPreviewSupport.enabled) false else preview
    ReflectionHelpers.setField(realPainter, "isPreview", effective)
  }
}
