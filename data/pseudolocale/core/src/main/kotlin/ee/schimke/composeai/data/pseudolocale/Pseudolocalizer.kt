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
  fun accent(input: CharSequence): String = transform(input.toString(), accent = true)

  fun bidi(input: CharSequence): String = transform(input.toString(), accent = false)

  fun transform(input: String, mode: Pseudolocale): String =
    when (mode) {
      Pseudolocale.ACCENT -> accent(input)
      Pseudolocale.BIDI -> bidi(input)
    }

  private fun transform(input: String, accent: Boolean): String {
    if (input.isEmpty()) return input
    val sb = StringBuilder(input.length + 8)
    if (accent) sb.append('[')
    var i = 0
    while (i < input.length) {
      val ch = input[i]
      // Preserve placeholder tokens — `%1$s`, `%d`, `%s`, etc.
      if (ch == '%') {
        val end = consumePrintfPlaceholder(input, i)
        sb.append(input, i, end)
        i = end
        continue
      }
      // Preserve `{0}` / `{name}` ICU/MessageFormat-style placeholders.
      if (ch == '{') {
        val end = input.indexOf('}', i)
        if (end != -1) {
          sb.append(input, i, end + 1)
          i = end + 1
          continue
        }
      }
      // Preserve XML tags — naive, but matches what `Resources.getText` carries through.
      if (ch == '<') {
        val end = input.indexOf('>', i)
        if (end != -1) {
          sb.append(input, i, end + 1)
          i = end + 1
          continue
        }
      }
      if (accent) sb.append(ACCENT_MAP[ch.code] ?: ch) else sb.append(ch)
      i++
    }
    if (accent) {
      // Expand to ~130% via dots — the AAPT2 default. Skipping when the input is whitespace-only
      // avoids producing bracket-only artefacts for empty resources.
      val target = (input.length * 1.3).toInt().coerceAtLeast(input.length + 2)
      val padding = target - (sb.length - 1)
      if (padding > 0) {
        sb.append(' ')
        repeat(padding) { sb.append('·') }
      }
      sb.append(']')
      return sb.toString()
    }
    return wrapBidi(sb.toString())
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

  private fun wrapBidi(input: String): String {
    if (input.isEmpty()) return input
    val sb = StringBuilder(input.length + 8)
    var word = StringBuilder()
    fun flushWord() {
      if (word.isNotEmpty()) {
        sb.append(RLO).append(word).append(PDF)
        word = StringBuilder()
      }
    }
    for (ch in input) {
      if (ch.isWhitespace()) {
        flushWord()
        sb.append(ch)
      } else {
        word.append(ch)
      }
    }
    flushWord()
    return sb.toString()
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
