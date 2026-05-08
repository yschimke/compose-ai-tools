package ee.schimke.composeai.daemon

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.text.SpannableStringBuilder
import android.text.Spanned
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
 *
 * **Span preservation.** `Resources.getText(int)` returns `CharSequence`, which is a `Spanned`
 * (`SpannedString`) when the resource is styled — `<b>`, `<i>`, `<a>`, custom `<annotation>` tags
 * all surface as `Object`-typed spans on a single underlying string. A naive
 * `Pseudolocalizer.transform(text.toString(), mode)` flattens those spans because `toString()`
 * drops them. To keep styled previews accurate, route through `Pseudolocalizer.transformWithIndices`,
 * build a `SpannableStringBuilder` from the transformed text, and remap each span's start / end via
 * the index map. Plain (non-`Spanned`) inputs still take the cheaper `transform` fast path with no
 * `SpannableStringBuilder` allocation.
 */
internal class PseudolocaleResources(
  base: Resources,
  private val mode: Pseudolocale,
) : Resources(base.assets, base.displayMetrics, base.configuration) {

  override fun getText(id: Int): CharSequence = pseudolocalise(super.getText(id))

  override fun getText(id: Int, def: CharSequence): CharSequence {
    val raw = super.getText(id, def)
    return if (raw === def) def else pseudolocalise(raw)
  }

  override fun getQuantityText(id: Int, quantity: Int): CharSequence =
    pseudolocalise(super.getQuantityText(id, quantity))

  private fun pseudolocalise(raw: CharSequence): CharSequence {
    if (raw !is Spanned) return Pseudolocalizer.transform(raw.toString(), mode)
    val transformed = Pseudolocalizer.transformWithIndices(raw.toString(), mode)
    val sb = SpannableStringBuilder(transformed.text)
    val map = transformed.indexMap
    val length = raw.length
    val outText = transformed.text
    for (span in raw.getSpans(0, length, Any::class.java)) {
      val srcStart = raw.getSpanStart(span).coerceIn(0, length)
      val srcEnd = raw.getSpanEnd(span).coerceIn(srcStart, length)
      val flags = raw.getSpanFlags(span)
      var dstStart = map[srcStart]
      var dstEnd = map[srcEnd]
      // SPAN_PARAGRAPH spans (BulletSpan, LeadingMarginSpan, …) must start at 0 or right after a
      // `\n`, and end at length or right after a `\n`. The transform prepends marker characters
      // (`[` in accent mode, RLO in bidi) which would shift a span starting at input 0 to output
      // position 1 and trip SpannableStringBuilder.setSpan's paragraph-boundary check at runtime.
      // Snap each end to the nearest valid paragraph boundary before re-attaching; drop the span
      // entirely if snapping produces a degenerate range.
      if ((flags and Spanned.SPAN_PARAGRAPH) == Spanned.SPAN_PARAGRAPH) {
        dstStart = snapToParagraphStart(outText, dstStart)
        dstEnd = snapToParagraphEnd(outText, dstEnd)
        if (dstStart >= dstEnd) continue
      }
      sb.setSpan(span, dstStart, dstEnd, flags)
    }
    return sb
  }

  private fun snapToParagraphStart(text: String, pos: Int): Int {
    if (pos <= 0) return 0
    for (i in pos - 1 downTo 0) {
      if (text[i] == '\n') return i + 1
    }
    return 0
  }

  private fun snapToParagraphEnd(text: String, pos: Int): Int {
    if (pos >= text.length) return text.length
    for (i in pos until text.length) {
      if (text[i] == '\n') return i + 1
    }
    return text.length
  }
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
