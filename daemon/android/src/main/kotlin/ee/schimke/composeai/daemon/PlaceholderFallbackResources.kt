package ee.schimke.composeai.daemon

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/**
 * `Resources` subclass that turns a **missing** app-resource lookup into an *obvious placeholder*
 * instead of letting `Resources.NotFoundException` abort the whole preview render.
 *
 * ## Why
 *
 * A classic `@Composable @Preview` that calls `stringResource(R.string.…)` / `colorResource(…)` /
 * `context.getString(…)` needs the app's compiled resource table (the `0x7f` package). A **detached
 * render** — a packed bundle spawned by `bundle daemon` / a `serve --catalogs` live bundle — only
 * has that table when the bundle actually carries it (see [AndroidBundleResources] on the CLI side).
 * When the table is absent, stale, or simply missing one id, the very first `0x7f…` lookup throws
 * `Resources$NotFoundException` and the *entire* preview fails to render — the viewer shows a broken
 * image rather than the (otherwise fine) UI.
 *
 * This wrapper makes that failure mode graceful and legible: a resolvable resource is returned
 * untouched (transparent pass-through), and only a **miss** falls back to an obvious marker — a
 * `⟦res 0x7f…⟧` token for strings, a magenta fill for colors/drawables, zero for dimensions. The
 * preview still renders; the missing resource is visible at a glance rather than a hard crash.
 *
 * ## What's overridden, and why only these
 *
 * The string overloads all funnel through the `Text` accessors: `getString(int)` is
 * `getText(int).toString()`, `getString(int, args…)` is `String.format(getString(int), args…)`, and
 * the quantity variants route through `getQuantityText`. Intercepting at the bottom of the chain
 * ([getText] / [getQuantityText]) therefore covers every string flavour exactly once — the same
 * rationale [PseudolocaleResources] documents. The placeholder token carries no `%` conversions, so
 * a later `String.format` with args leaves it unchanged. Value resources ([getColor], the dimension
 * family, [getDrawable]) don't route through a shared accessor, so each is guarded directly. The
 * base class's `AssetManager` / `DisplayMetrics` / `Configuration` are reused, so every *resolvable*
 * resource still takes the normal Android path. Drawables are guarded on **all four**
 * `loadDrawable` entry points — `getDrawable(id)`, `getDrawable(id, theme)`, and both
 * `getDrawableForDensity(...)` overloads — because a density-aware caller
 * (`ResourcesCompat.getDrawableForDensity`, RemoteViews / ImageView inflation, image loaders)
 * reaches the throwing `ResourcesImpl.loadDrawable` without funnelling through the plain
 * `getDrawable`, so guarding only the latter left the miss to abort the render (issue #2976).
 *
 * Not (yet) covered: raw `getValue` / `openRawResource` decode paths (e.g. a bitmap whose id
 * resolves but whose backing file is absent) can't be substituted at the `Resources` seam without
 * fabricating a `TypedValue` / stream, so a miss read that way still throws. The value families
 * above (`getText`, `getColor`, the dimension family) and every `getDrawable*` overload are the
 * common crashes the live server hits.
 */
@Suppress("DEPRECATION")
internal class PlaceholderFallbackResources(base: Resources) :
  Resources(base.assets, base.displayMetrics, base.configuration) {

  override fun getText(id: Int): CharSequence =
    try {
      super.getText(id)
    } catch (_: NotFoundException) {
      placeholderText(id)
    }

  override fun getText(id: Int, def: CharSequence?): CharSequence =
    try {
      super.getText(id, def)
    } catch (_: NotFoundException) {
      def ?: placeholderText(id)
    }

  override fun getQuantityText(id: Int, quantity: Int): CharSequence =
    try {
      super.getQuantityText(id, quantity)
    } catch (_: NotFoundException) {
      placeholderText(id)
    }

  override fun getColor(id: Int, theme: Theme?): Int =
    try {
      super.getColor(id, theme)
    } catch (_: NotFoundException) {
      PLACEHOLDER_COLOR
    }

  override fun getColor(id: Int): Int =
    try {
      super.getColor(id)
    } catch (_: NotFoundException) {
      PLACEHOLDER_COLOR
    }

  override fun getDimension(id: Int): Float =
    try {
      super.getDimension(id)
    } catch (_: NotFoundException) {
      0f
    }

  override fun getDimensionPixelOffset(id: Int): Int =
    try {
      super.getDimensionPixelOffset(id)
    } catch (_: NotFoundException) {
      0
    }

  override fun getDimensionPixelSize(id: Int): Int =
    try {
      super.getDimensionPixelSize(id)
    } catch (_: NotFoundException) {
      0
    }

  override fun getDrawable(id: Int, theme: Theme?): Drawable =
    try {
      super.getDrawable(id, theme)
    } catch (_: NotFoundException) {
      placeholderDrawable()
    }

  override fun getDrawable(id: Int): Drawable =
    try {
      super.getDrawable(id)
    } catch (_: NotFoundException) {
      placeholderDrawable()
    }

  // The density-aware variants are a *separate* entry into `ResourcesImpl.loadDrawable` — the same
  // method that throws `NotFoundException: Drawable <pkg>:drawable/<name> with resource ID #0x…`.
  // `getDrawable(id[, theme])` funnels here internally, so overriding those two catches an app-code
  // `getDrawable(...)`; but a caller that reaches `getDrawableForDensity(...)` directly (density-aware
  // image-loading paths, `ResourcesCompat.getDrawableForDensity`, RemoteViews/ImageView inflation)
  // bypasses those overrides and the miss escapes as a hard render abort (issue #2976:
  // `splash_background` on the `pocketcasts-wear` live server). Guard both overloads too so every
  // `loadDrawable` path degrades to the placeholder.
  override fun getDrawableForDensity(id: Int, density: Int): Drawable? =
    try {
      super.getDrawableForDensity(id, density)
    } catch (_: NotFoundException) {
      placeholderDrawable()
    }

  override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? =
    try {
      super.getDrawableForDensity(id, density, theme)
    } catch (_: NotFoundException) {
      placeholderDrawable()
    }

  private fun placeholderText(id: Int): CharSequence = "⟦res 0x%08x⟧".format(id)

  private fun placeholderDrawable(): Drawable = ColorDrawable(PLACEHOLDER_COLOR)

  companion object {
    /** Opaque magenta — reads as "this resource was missing" at a glance in a render. */
    const val PLACEHOLDER_COLOR: Int = 0xFFFF00FF.toInt()
  }
}

/**
 * `ContextWrapper` that returns a [PlaceholderFallbackResources] from `getResources()`.
 * `LocalContext.current` is what `androidx.compose.ui.res.stringResource` (and user code calling
 * `context.getString(...)`) walks to resolve resource ids, so providing this wrapper for
 * `LocalContext` in the render's around-composable seam makes every miss fall back to a placeholder.
 * Mirrors [PseudolocaleContext].
 */
internal class PlaceholderFallbackContext(
  base: Context,
  private val fallbackResources: PlaceholderFallbackResources,
) : ContextWrapper(base) {
  override fun getResources(): Resources = fallbackResources
}

internal fun Context.wrappedForPlaceholderResources(): Context =
  PlaceholderFallbackContext(this, PlaceholderFallbackResources(resources))
