package ee.schimke.composeai.data.pseudolocale

/**
 * Pure-string transform that mirrors AAPT2's `Pseudolocalizer` accent + bidi modes.
 *
 * Implementation notes:
 * - **Accent map** is the same ASCII-to-lookalike table AAPT2 ships in
 *   `frameworks/base/tools/aapt2/compile/PseudoMethod.cpp`. Only `[A-Za-z]` are remapped; digits,
 *   punctuation and whitespace are preserved so layout debugging signal isn't drowned in noise.
 * - **Placeholder preservation** — `%[0-9]*\$?[a-zA-Z]`, `{0}` / `{name}`, and `<…>` XML tags pass
 *   through untouched. AAPT2 does the same: pseudolocalising `%1$s` would make the formatter throw
 *   at `String.format` time, and pseudolocalising tag names would break HTML.
 * - **Expansion padding** — accent mode wraps the result in `[ … ]` brackets and adds extra dots
 *   until the output is roughly 130% of the input length. Matches the size budget Android docs
 *   recommend for translation-expansion testing.
 * - **Bidi mode** — wraps each whitespace-separated word in `‮…‬` (RLO + PDF). Words inside
 *   placeholders are skipped because the placeholder substitution happens later. The framework's
 *   `LayoutDirection.Rtl` is what actually flips layouts; the bidi marks are belt-and- braces for
 *   code paths that bypass `LocalLayoutDirection`.
 */
object Pseudolocalizer {
  /**
   * Pseudolocalised text plus the input-to-output index map needed to remap span ranges over the
   * original [input] onto the transformed [text].
   *
   * `indexMap[i]` is the output position corresponding to input position `i`, with size
   * `input.length + 1` so a span's `endExclusive` index can be looked up unambiguously
   * (`indexMap[input.length]` is the position immediately after the last input character — before
   * any trailing accent padding / closing bracket).
   *
   * Callers consume this when the input came from a `Spanned`/`SpannedString`: walk the original
   * spans, look up start/end through `indexMap`, and re-attach to a `SpannableStringBuilder` built
   * from [text]. Without that, calling `toString()` on the styled `CharSequence` strips the spans
   * before transformation and any preview using bold / annotation / URLSpan resources would render
   * unstyled — see `PseudolocaleResources` in `:data-pseudolocale-connector`.
   */
  class TransformResult(val text: String, val indexMap: IntArray)

  fun accent(input: CharSequence): String = transform(input.toString(), Pseudolocale.ACCENT)

  fun bidi(input: CharSequence): String = transform(input.toString(), Pseudolocale.BIDI)

  fun transform(input: String, mode: Pseudolocale): String = transformWithIndices(input, mode).text

  fun transformWithIndices(input: String, mode: Pseudolocale): TransformResult =
    when (mode) {
      Pseudolocale.ACCENT -> accentWithIndices(input)
      Pseudolocale.BIDI -> bidiWithIndices(input)
    }

  private fun accentWithIndices(input: String): TransformResult {
    if (input.isEmpty()) return TransformResult("", IntArray(1))
    val sb = StringBuilder(input.length + 8)
    val map = IntArray(input.length + 1)
    sb.append('[')
    var i = 0
    while (i < input.length) {
      val ch = input[i]
      // Preserve placeholder tokens — `%1$s`, `%d`, `%s`, etc.
      if (ch == '%') {
        val end = consumePrintfPlaceholder(input, i)
        for (k in i until end) map[k] = sb.length + (k - i)
        sb.append(input, i, end)
        i = end
        continue
      }
      // Preserve `{0}` / `{name}` ICU/MessageFormat-style placeholders.
      if (ch == '{') {
        val end = input.indexOf('}', i)
        if (end != -1) {
          for (k in i..end) map[k] = sb.length + (k - i)
          sb.append(input, i, end + 1)
          i = end + 1
          continue
        }
      }
      // Preserve XML tags — naive, but matches what `Resources.getText` carries through.
      if (ch == '<') {
        val end = input.indexOf('>', i)
        if (end != -1) {
          for (k in i..end) map[k] = sb.length + (k - i)
          sb.append(input, i, end + 1)
          i = end + 1
          continue
        }
      }
      map[i] = sb.length
      sb.append(ACCENT_MAP[ch.code] ?: ch)
      i++
    }
    // End-of-content position before padding / closing bracket — span endExclusive lookups land
    // here when they cover the full input.
    map[input.length] = sb.length
    // Expand to ~130% via dots — the AAPT2 default. Skipping when the input is whitespace-only
    // avoids producing bracket-only artefacts for empty resources.
    val target = (input.length * 1.3).toInt().coerceAtLeast(input.length + 2)
    val padding = target - (sb.length - 1)
    if (padding > 0) {
      sb.append(' ')
      repeat(padding) { sb.append('·') }
    }
    sb.append(']')
    return TransformResult(sb.toString(), map)
  }

  private fun bidiWithIndices(input: String): TransformResult {
    if (input.isEmpty()) return TransformResult("", IntArray(1))
    val sb = StringBuilder(input.length + 8)
    val map = IntArray(input.length + 1)
    var i = 0
    while (i < input.length) {
      if (input[i].isWhitespace()) {
        map[i] = sb.length
        sb.append(input[i])
        i++
        continue
      }
      val wordStart = i
      while (i < input.length && !input[i].isWhitespace()) i++
      sb.append(RLO)
      for (k in wordStart until i) {
        map[k] = sb.length
        sb.append(input[k])
      }
      sb.append(PDF)
    }
    map[input.length] = sb.length
    return TransformResult(sb.toString(), map)
  }

  private fun consumePrintfPlaceholder(input: String, start: Int): Int {
    // %[arg_index$][flags][width][.precision]conversion — match the same subset
    // AAPT2's `PseudoMethodNone` skips: digits, `$`, `.`, then the conversion letter.
    var i = start + 1
    if (i >= input.length) return i
    while (i < input.length) {
      val c = input[i]
      if (c.isLetter()) return i + 1
      if (
        c.isDigit() ||
          c == '$' ||
          c == '.' ||
          c == '-' ||
          c == '+' ||
          c == '#' ||
          c == ' ' ||
          c == '%'
      ) {
        i++
        continue
      }
      // Unknown char — bail; the `%` was probably literal.
      return i
    }
    return i
  }

  private const val RLO: Char = '‮'
  private const val PDF: Char = '‬'

  // AAPT2 accent map (`PseudoMethodAccent::kAccentMap`). Only mapping the entries we need —
  // anything not present passes through unchanged.
  private val ACCENT_MAP: Map<Int, Char> =
    mapOf(
      'a'.code to 'à',
      'b'.code to 'ƀ',
      'c'.code to 'ç',
      'd'.code to 'đ',
      'e'.code to 'ê',
      'f'.code to 'ƒ',
      'g'.code to 'ġ',
      'h'.code to 'ĥ',
      'i'.code to 'î',
      'j'.code to 'ĵ',
      'k'.code to 'ķ',
      'l'.code to 'ļ',
      'm'.code to 'ɱ',
      'n'.code to 'ñ',
      'o'.code to 'ö',
      'p'.code to 'þ',
      'q'.code to 'ǫ',
      'r'.code to 'ŕ',
      's'.code to 'š',
      't'.code to 'ţ',
      'u'.code to 'ü',
      'v'.code to 'ʌ',
      'w'.code to 'ŵ',
      'x'.code to 'ׄ',
      'y'.code to 'ý',
      'z'.code to 'ž',
      'A'.code to 'À',
      'B'.code to 'Ɓ',
      'C'.code to 'Ç',
      'D'.code to 'Ð',
      'E'.code to 'É',
      'F'.code to 'Ƒ',
      'G'.code to 'Ĝ',
      'H'.code to 'Ĥ',
      'I'.code to 'Î',
      'J'.code to 'Ĵ',
      'K'.code to 'Ķ',
      'L'.code to 'Ł',
      'M'.code to 'Ɯ',
      'N'.code to 'Ñ',
      'O'.code to 'Ö',
      'P'.code to 'Þ',
      'Q'.code to 'Ǫ',
      'R'.code to 'Ŕ',
      'S'.code to 'Š',
      'T'.code to 'Ţ',
      'U'.code to 'Û',
      'V'.code to 'Ʌ',
      'W'.code to 'Ŵ',
      'X'.code to 'Ӽ',
      'Y'.code to 'Ý',
      'Z'.code to 'Ž',
    )
}
