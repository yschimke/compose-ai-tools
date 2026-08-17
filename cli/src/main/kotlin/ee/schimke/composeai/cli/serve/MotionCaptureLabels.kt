package ee.schimke.composeai.cli.serve

/**
 * What a motion capture is called in the picker, and what it says once picked.
 *
 * ### Why this exists
 *
 * The picker used to print the annotation's whole caption on a button. That is fine for "Tap the
 * avatar" and ruinous for the captions real catalogs write, which are a sentence of instruction
 * followed by a paragraph of what to watch for:
 *
 * > Toggle repeatedly. The container morphs between its unchecked and checked shapes through the
 * > theme's spatial animation — Baseline swaps the shape, Expressive travels between them.
 *
 * Two of those side by side in a segmented group is a wall of prose above the stage, wider than the
 * render it is introducing, and the reader still has to compare two near-identical blocks word by
 * word to tell which button is which. The words themselves are worth keeping — they name the
 * property the recording exists to show — so this splits them rather than truncating them away:
 *
 * - [title] is what the closed menu shows and what distinguishes one capture from its neighbours —
 *   the caption's first clause, ellipsized if even that runs long.
 * - [detail] is the caption in full, printed beside the frames once a capture is on the stage,
 *   where there is a whole row to spend on it and only ONE of them is on screen at a time.
 *
 * Deliberately free of HTML and of [ServeWeb] so the rule that decides where a caption is cut is
 * unit-testable on its own.
 */
internal data class MotionCaptureLabel(
  /** The brief name: what the menu shows, always non-blank. */
  val title: String,
  /** The caption in full, whitespace-normalised. Blank when the annotation declared none. */
  val detail: String,
)

internal object MotionCaptureLabels {
  /**
   * Past this the closed menu starts setting the width of the control row, which is the problem
   * this whole split exists to solve. Long enough for the instruction clauses catalogs actually
   * write ("Toggle repeatedly", "Press and hold the card") to survive uncut.
   */
  private const val TITLE_MAX = 42

  /**
   * Label every capture of one preview, numbering only where a title would otherwise repeat.
   *
   * Numbering is a property of the SET, not of a capture, which is why this takes the list: two
   * caption-less interaction captures are permitted by the manifest and are what the annotation
   * defaults produce, and they used to give the picker two entries both reading "Interaction" — no
   * way, by eye or by screen reader, to tell which recording either one selects. Numbering
   * unconditionally would instead put a "1" on every single-capture preview, which is a count
   * nobody asked for.
   *
   * Cutting captions to a first clause makes a collision *more* likely rather than less — two
   * recordings of one component very often open with the same instruction and differ only in the
   * detail — so the number distinguishes them in the menu and the detail line says what actually
   * differs.
   */
  fun of(captures: List<ServeMotion>): List<MotionCaptureLabel> {
    val base = captures.map { capture ->
      MotionCaptureLabel(
        title = briefTitle(capture.caption, capture.kind),
        detail = normalise(capture.caption),
      )
    }
    val totals = base.groupingBy { it.title }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return base.map { label ->
      if (totals[label.title] == 1) label
      else {
        val n = (seen[label.title] ?: 0) + 1
        seen[label.title] = n
        label.copy(title = "${label.title} $n")
      }
    }
  }

  /**
   * The caption's opening clause — or the capture's kind when it declared no caption. A weak label
   * is honest; it appears only when the annotation said nothing to be honest about.
   */
  private fun briefTitle(caption: String?, kind: String?): String {
    val text = normalise(caption)
    if (text.isEmpty())
      return when (kind) {
        "interaction" -> "Interaction"
        "animation" -> "Animation"
        else -> "Capture"
      }
    return ellipsize(firstClause(text))
  }

  /**
   * Where the caption stops being a name and starts being an explanation: the end of its first
   * sentence, or the first dash / colon / semicolon that introduces one, whichever comes first.
   *
   * Every separator has to be FOLLOWED by a space (or end the caption) — otherwise "1.5dp", "e.g."
   * and "Crop to 1:1" would each cut the title to nothing — and the dashes need a space in front of
   * them too, so hyphenated words ("press-and-hold") stay whole. A terminator that ends the whole
   * caption is dropped rather than cut at: a menu entry reading "Tap the avatar." is a sentence
   * pretending to be a label.
   */
  private fun firstClause(text: String): String {
    var cut = text.length
    for (i in text.indices) {
      val c = text[i]
      val ends = i + 1 >= text.length || text[i + 1] == ' '
      val boundary =
        when {
          c == '.' || c == '!' || c == '?' || c == ';' || c == ':' -> ends
          c == '—' || c == '–' || c == '-' -> ends && i > 0 && text[i - 1] == ' '
          else -> false
        }
      if (boundary) {
        cut = i
        break
      }
    }
    return text.substring(0, cut).trim().trimEnd('.', ',')
  }

  /** Cut long, on a word boundary, with the ellipsis that says the rest is in the detail line. */
  private fun ellipsize(text: String): String {
    if (text.length <= TITLE_MAX) return text
    val head = text.substring(0, TITLE_MAX)
    val lastSpace = head.lastIndexOf(' ')
    // A single word longer than the budget has no boundary to break on, so it is cut mid-word
    // rather than collapsed to an ellipsis on its own.
    val kept = if (lastSpace >= TITLE_MAX / 2) head.substring(0, lastSpace) else head
    return kept.trimEnd().trimEnd(',', '.') + "…"
  }

  /**
   * One line, single-spaced. A caption is authored in Kotlin source and reaches here with whatever
   * wrapping the author's formatter left in it; both the menu and the readout are one-line
   * controls, so a newline in the middle of one is markup noise rather than meaning.
   */
  private fun normalise(text: String?): String = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
}
