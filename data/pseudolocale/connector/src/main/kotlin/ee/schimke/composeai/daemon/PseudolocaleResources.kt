package ee.schimke.composeai.daemon

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import ee.schimke.composeai.data.pseudolocale.Pseudolocale
import ee.schimke.composeai.data.pseudolocale.Pseudolocalizer

/**
 * `Resources` subclass that pseudolocalises string lookups on the fly.
 *
 * **Why we only override `getText(int)` and `getQuantityText(int, int)`.** The string-flavoured
 * overloads in `Resources` ultimately route through their `Text` counterparts:
 * `getString(int)` is `getText(int).toString()`, and `getString(int, Object...)` calls
 * `String.format(getString(id), args)` which re-enters `this.getString(id)` (and thus
 * `this.getText(id)`). Overriding both layers double-applies the transform — it shows up as
 * `[[Ĥêļļö, ŵöŕļđ ···] ·····]` instead of `[Ĥêļļö, ŵöŕļđ ···]`. By intercepting at the bottom of
 * the chain only, every string accessor pseudolocalises exactly once. The base class's
 * `AssetManager` / `DisplayMetrics` / `Configuration` are reused so any non-string resource
 * (`getDrawable`, `getColor`, dimensions, etc.) still resolves through the normal Android path.
 *
 * Format-arg overloads (`getString(int, vararg)`, `getQuantityString(int, int, vararg)`) compose
 * naturally — the template is pseudolocalised once, then `String.format` substitutes args into it.
 * Placeholders like `%1$s` survive the transform unchanged, see `Pseudolocalizer`.
 */
internal class PseudolocaleResources(
  base: Resources,
  private val mode: Pseudolocale,
) : Resources(base.assets, base.displayMetrics, base.configuration) {

  override fun getText(id: Int): CharSequence =
    Pseudolocalizer.transform(super.getText(id).toString(), mode)

  override fun getText(id: Int, def: CharSequence): CharSequence {
    val raw = super.getText(id, def)
    return if (raw === def) def else Pseudolocalizer.transform(raw.toString(), mode)
  }

  override fun getQuantityText(id: Int, quantity: Int): CharSequence =
    Pseudolocalizer.transform(super.getQuantityText(id, quantity).toString(), mode)
}

/**
 * `ContextWrapper` that returns a [PseudolocaleResources] from `getResources()`. `LocalContext.current`
 * is what `androidx.compose.ui.res.stringResource` walks to resolve string ids, so wrapping it in
 * the around-composable is enough to pseudolocalise every resource lookup the preview makes through
 * the standard Compose API.
 */
internal class PseudolocaleContext(
  base: Context,
  private val pseudoResources: PseudolocaleResources,
) : ContextWrapper(base) {
  override fun getResources(): Resources = pseudoResources
}

internal fun Context.wrappedForPseudolocale(mode: Pseudolocale): Context =
  PseudolocaleContext(this, PseudolocaleResources(resources, mode))
