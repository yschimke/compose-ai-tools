package ee.schimke.composeai.guidelines

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One fact about a preview, computed deterministically before any model is asked. [kind] groups it
 * ([GuidelineFacts.TOUCH_TARGET], …) so rules get the facts that bear on them; [nodeId] names the
 * accessibility node it's about, if any; [decisive] marks a fact that alone shows a likely problem
 * (listed first).
 */
public data class GuidelineFact(
  val kind: String,
  val text: String,
  val nodeId: String? = null,
  val decisive: Boolean = false,
)

/**
 * The facts a text-only checker sees instead of the picture: sizes, clipping, overlap and scrolling
 * from the accessibility nodes; contrast and target sizes from ATF; literal sizes, colours, button
 * calls, theme use and hard-coded strings from a regex scan of the source. Worded in English and
 * whole numbers where possible (Jev reads those better) and capped to a few hundred tokens.
 */
public object GuidelineFacts {
  public const val TOUCH_TARGET: String = "touch-target"
  public const val NODE_SIZE: String = "node-size"
  public const val VIEWPORT_CLIP: String = "viewport-clip"
  public const val ROUND_MASK: String = "round-mask"
  public const val OVERLAP: String = "overlap"
  public const val TEXT_CUT: String = "text-cut"
  public const val SCROLL: String = "scroll"
  public const val CONTRAST: String = "contrast"
  public const val ATF: String = "atf"
  public const val FONT_SIZE: String = "font-size"
  public const val COLOUR: String = "hard-coded-colour"
  public const val FIXED_SIZE: String = "fixed-size"
  public const val BUTTONS: String = "button-calls"
  public const val THEME: String = "theme-usage"
  public const val STRINGS: String = "hard-coded-strings"
  public const val DESCRIPTIONS: String = "content-descriptions"
  public const val TEXT_OVERFLOW: String = "text-overflow"
  public const val CAPTURE: String = "capture"

  /** Wear's and Material's minimum touch target, in dp. */
  public const val MIN_TOUCH_DP: Int = 48

  private const val MAX_PER_KIND = 8

  /**
   * Every fact about [subject]. [round] says whether the screen is round (Wear); null infers it
   * from [platform] and the render's shape.
   */
  public fun of(
    subject: PreviewSubject,
    platform: String? = null,
    round: Boolean? = null,
  ): List<GuidelineFact> {
    val picture = subject.pictures.firstOrNull()
    val viewport = picture?.let { PreviewGuidelineRequests.pngSize(it.png) }
    val density =
      if (picture != null && viewport != null && picture.widthDp > 0)
        viewport.first.toDouble() / picture.widthDp
      else null
    val isRound =
      round
        ?: (platform == "wear" &&
          subject.surface == GuidelineSurfaces.SCREEN &&
          viewport != null &&
          viewport.first == viewport.second)
    val facts = mutableListOf<GuidelineFact>()
    picture?.description?.let { description ->
      if (description.contains("scroll", ignoreCase = true)) {
        facts += GuidelineFact(CAPTURE, "The render is $description.")
      }
    }
    facts += fromNodes(subject.nodes, viewport, density, isRound)
    facts += fromChecks(subject.checks)
    subject.source?.let { facts += fromSource(it) }
    return facts
  }

  /** One line naming the kinds of fact there are, and how many of each: ~20–40 tokens. */
  public fun summary(facts: List<GuidelineFact>): String =
    if (facts.isEmpty()) "no computed facts"
    else
      facts
        .groupBy { it.kind }
        .entries
        .joinToString(prefix = "computed facts: ") { (kind, list) ->
          val decisive = list.count { it.decisive }
          "$kind ${list.size}" + if (decisive > 0) " ($decisive flagged)" else ""
        }

  // ---- accessibility nodes and the render's size

  private val INTERACTIVE_ROLES =
    setOf("Button", "Checkbox", "Switch", "RadioButton", "Tab", "DropdownList", "Slider")

  private fun PreviewNode.interactive(): Boolean =
    "clickable" in states || role in INTERACTIVE_ROLES

  private fun PreviewNode.name(): String =
    "$id (${role ?: "node"}" +
      (label.takeIf { it.isNotBlank() }?.let { " '${it.take(40)}'" } ?: "") +
      ")"

  private fun PreviewNode.width() = right - left

  private fun PreviewNode.height() = bottom - top

  private fun PreviewNode.contains(other: PreviewNode) =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

  private fun intersection(a: PreviewNode, b: PreviewNode): Int {
    val w = min(a.right, b.right) - max(a.left, b.left)
    val h = min(a.bottom, b.bottom) - max(a.top, b.top)
    return if (w > 0 && h > 0) w * h else 0
  }

  internal fun fromNodes(
    nodes: List<PreviewNode>,
    viewport: Pair<Int, Int>?,
    density: Double?,
    round: Boolean,
  ): List<GuidelineFact> {
    if (nodes.isEmpty()) return emptyList()
    val facts = mutableListOf<GuidelineFact>()
    fun dp(px: Int): String = density?.let { "${(px / it).roundToInt()}dp" } ?: "${px}px"
    fun dpValue(px: Int): Int? = density?.let { (px / it).roundToInt() }

    val interactive = nodes.filter { it.interactive() }
    interactive.take(MAX_PER_KIND).forEach { node ->
      facts +=
        GuidelineFact(
          NODE_SIZE,
          "${node.name()} is ${dp(node.width())}×${dp(node.height())}.",
          node.id,
        )
    }
    interactive.forEach { node ->
      val w = dpValue(node.width()) ?: return@forEach
      val h = dpValue(node.height()) ?: return@forEach
      val short = MIN_TOUCH_DP - min(w, h)
      if (short > 0) {
        facts +=
          GuidelineFact(
            TOUCH_TARGET,
            "${node.name()} is ${w}×${h}dp: ${short}dp short of the ${MIN_TOUCH_DP}dp minimum " +
              "touch target.",
            node.id,
            decisive = true,
          )
      }
    }

    val scrollables = nodes.filter { "scrollable" in it.states }
    if (viewport != null) {
      val (vw, vh) = viewport
      nodes.forEach { node ->
        val edges = PreviewGuidelineRequests.offEdges(node, vw, vh)
        if (edges.isEmpty() || node.width() <= 0 || node.height() <= 0) return@forEach
        // A node past the screen is scrolled away when a scrolling container holds it on that axis.
        val holder = scrollables.firstOrNull { it != node && intersection(it, node) > 0 }
        val (scrolled, clipped) =
          edges.partition { edge ->
            holder != null && (edge == "top" || edge == "bottom" || "horizontal" in holder.states)
          }
        if (clipped.isNotEmpty()) {
          facts +=
            GuidelineFact(
              VIEWPORT_CLIP,
              "${node.name()} extends past the ${clipped.joinToString(" and ")} edge of the " +
                "screen, and no scrolling container holds it there: it is cut off.",
              node.id,
              decisive = true,
            )
        }
        if (scrolled.isNotEmpty()) {
          facts +=
            GuidelineFact(
              VIEWPORT_CLIP,
              "${node.name()} extends past the ${scrolled.joinToString(" and ")} edge, inside " +
                "scrolling ${holder!!.id}: it scrolls into view rather than being cut off.",
              node.id,
            )
        }
      }
      scrollables.take(MAX_PER_KIND).forEach { container ->
        val below = nodes.count {
          it != container &&
            it.top < container.bottom &&
            it.bottom > min(container.bottom, vh) &&
            intersection(it, container) > 0
        }
        val above = nodes.count {
          it != container &&
            it.bottom > container.top &&
            it.top < max(container.top, 0) &&
            intersection(it, container) > 0
        }
        facts +=
          GuidelineFact(
            SCROLL,
            "${container.name()} scrolls: " +
              when {
                below > 0 && above > 0 ->
                  "$above node(s) continue above and $below below the screen, so it is mid-scroll"
                below > 0 ->
                  "$below node(s) continue below the screen, so it can scroll further down"
                above > 0 ->
                  "$above node(s) continue above the screen, so it is scrolled to its end"
                else -> "nothing continues past the screen, so its content fits or it is at its end"
              } +
              ".",
            container.id,
          )
      }

      if (round) {
        val cx = vw / 2.0
        val cy = vh / 2.0
        val r = min(vw, vh) / 2.0
        nodes
          .filter { it.interactive() || it.label.isNotBlank() }
          .filter { node -> PreviewGuidelineRequests.offEdges(node, vw, vh).isEmpty() }
          .forEach { node ->
            // The midpoints of the node's edges: a rectangle's corners are off a round screen
            // for any full-width text, but an edge's middle past the circle is content cut off.
            val midpoints =
              listOf(
                "top" to ((node.left + node.right) / 2.0 to node.top.toDouble()),
                "bottom" to ((node.left + node.right) / 2.0 to node.bottom.toDouble()),
                "left" to (node.left.toDouble() to (node.top + node.bottom) / 2.0),
                "right" to (node.right.toDouble() to (node.top + node.bottom) / 2.0),
              )
            val outside =
              midpoints
                .filter { (_, p) -> hypot(p.first - cx, p.second - cy) > r + 1 }
                .map { it.first }
            if (outside.isNotEmpty()) {
              facts +=
                GuidelineFact(
                  ROUND_MASK,
                  "${node.name()} reaches outside the round screen at its " +
                    "${outside.joinToString(" and ")} edge: that part is not on the display.",
                  node.id,
                  decisive = true,
                )
            }
          }
      }
    }

    // Overlapping controls: two tap targets sharing pixels, neither inside the other.
    val overlaps = mutableListOf<GuidelineFact>()
    for (i in interactive.indices) for (j in i + 1 until interactive.size) {
      val a = interactive[i]
      val b = interactive[j]
      if (intersection(a, b) > 0 && !a.contains(b) && !b.contains(a)) {
        overlaps +=
          GuidelineFact(OVERLAP, "${a.name()} and ${b.name()} overlap.", a.id, decisive = true)
      }
    }
    facts += overlaps.take(MAX_PER_KIND)

    // Text running past its container: the smallest larger node covering most of it.
    nodes
      .filter { it.label.isNotBlank() && !it.interactive() && it.width() > 0 && it.height() > 0 }
      .forEach { node ->
        val area = node.width() * node.height()
        val parent =
          nodes
            .filter {
              it != node && it.width() * it.height() > area && intersection(it, node) * 2 >= area
            }
            .minByOrNull { it.width() * it.height() } ?: return@forEach
        val over =
          listOfNotNull(
            "left".takeIf { node.left < parent.left - 2 },
            "right".takeIf { node.right > parent.right + 2 },
            "top".takeIf { node.top < parent.top - 2 },
            "bottom".takeIf { node.bottom > parent.bottom + 2 && "scrollable" !in parent.states },
          )
        if (over.isNotEmpty()) {
          facts +=
            GuidelineFact(
              TEXT_CUT,
              "${node.name()} runs past its container ${parent.name()} on the " +
                "${over.joinToString(" and ")}: its text is likely cut.",
              node.id,
              decisive = true,
            )
        }
      }
    return facts
      .groupBy { it.kind }
      .values
      .flatMap { it.sortedByDescending { f -> f.decisive }.take(MAX_PER_KIND) }
  }

  // ---- the Accessibility Test Framework's measured checks

  private val RATIO = Regex("""ratio (?:is |of )?(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
  private val NEEDED_RATIO =
    Regex("""(\d+(?:\.\d+)?)\s*(?::1)?\s*or (?:greater|more|higher)""", RegexOption.IGNORE_CASE)
  private val DP = Regex("""(\d+(?:\.\d+)?)\s*dp""", RegexOption.IGNORE_CASE)

  internal fun fromChecks(checks: List<PreviewCheck>): List<GuidelineFact> {
    // No results at all may mean ATF never ran: say nothing rather than claim a clean run.
    if (checks.isEmpty()) return emptyList()
    val reported = checks.filter { it.level.uppercase() != "INFO" }
    if (reported.isEmpty()) {
      return listOf(
        GuidelineFact(
          ATF,
          "The Accessibility Test Framework reported no errors or warnings on this render " +
            "(touch targets and text contrast measured clean).",
        )
      )
    }
    return reported.take(MAX_PER_KIND * 2).map { check ->
      val on = check.element?.takeIf { it.isNotBlank() }?.let { " on '${it.take(40)}'" } ?: ""
      val level = check.level.uppercase()
      val error = level == "ERROR" || level == "WARNING"
      when {
        check.type.contains("Contrast", ignoreCase = true) -> {
          val ratio = RATIO.find(check.message)?.groupValues?.get(1)
          val needed = NEEDED_RATIO.find(check.message)?.groupValues?.get(1)
          GuidelineFact(
            CONTRAST,
            "ATF ${check.type} $level$on: " +
              (if (ratio != null) "contrast $ratio:1" + (needed?.let { ", needs $it:1" } ?: "")
              else check.message.take(160)) +
              ".",
            decisive = error,
          )
        }
        check.type.contains("TouchTarget", ignoreCase = true) -> {
          val sizes = DP.findAll(check.message).map { it.groupValues[1] + "dp" }.toList()
          GuidelineFact(
            TOUCH_TARGET,
            "ATF ${check.type} $level$on: " +
              (if (sizes.isNotEmpty())
                "measured ${sizes.first()}" + (sizes.getOrNull(1)?.let { ", needs $it" } ?: "")
              else check.message.take(160)) +
              ".",
            decisive = error,
          )
        }
        else ->
          GuidelineFact(
            ATF,
            "ATF ${check.type} $level$on: ${check.message.take(160)}",
            decisive = error,
          )
      }
    }
  }

  // ---- the source

  private val FONT_SIZE_LITERAL = Regex("""(?<![\w.])(\d+(?:\.\d+)?)\s*\.\s*(sp|rsp|em)\b""")
  private val HEX_COLOUR = Regex("""Color\(\s*0x([0-9A-Fa-f]{6,8})\s*\)""")
  private val NAMED_COLOUR =
    Regex(
      """(?<![\w.])Color\.(Red|Green|Blue|Black|White|Gray|Grey|DarkGray|LightGray|Yellow|Cyan|Magenta)\b"""
    )
  private val FIXED =
    Regex(
      """\.(size|width|height|requiredSize|requiredWidth|requiredHeight)\(\s*(\d+(?:\.\d+)?)\s*\.\s*(dp|rdp)"""
    )
  private val BUTTON_CALL =
    Regex(
      """(?<![\w.])(Button|FilledTonalButton|OutlinedButton|TextButton|ChildButton|CompactButton|""" +
        """IconButton|FilledIconButton|FilledTonalIconButton|OutlinedIconButton|EdgeButton|""" +
        """RemoteButton)\s*\("""
    )
  private val TYPOGRAPHY = Regex("""MaterialTheme\.typography\.""")
  private val COLOR_SCHEME = Regex("""MaterialTheme\.colorScheme\.""")
  private val STRING_LITERAL =
    Regex("""(?:Text\(\s*(?:text\s*=\s*)?|text\s*=\s*|label\s*=\s*)"([^"\\$]{1,60})"""")
  private val STRING_RESOURCE = Regex("""stringResource\(""")
  private val NULL_DESCRIPTION = Regex("""contentDescription\s*=\s*null""")
  private val MAX_LINES = Regex("""maxLines\s*=\s*(\d+)""")
  private val OVERFLOW = Regex("""TextOverflow\.(\w+)""")

  internal fun fromSource(source: String): List<GuidelineFact> {
    // Comments say what the code once did; facts are about what it does.
    val code = withoutComments(source)
    val facts = mutableListOf<GuidelineFact>()
    FONT_SIZE_LITERAL.findAll(code)
      .map { it.value.replace(" ", "") }
      .distinct()
      .take(MAX_PER_KIND)
      .forEach {
        facts +=
          GuidelineFact(
            FONT_SIZE,
            "The source sets a literal text size of $it, not a theme type style.",
            decisive = true,
          )
      }
    HEX_COLOUR.findAll(code)
      .map { it.groupValues[1] }
      .distinct()
      .take(MAX_PER_KIND)
      .forEach { hex ->
        facts +=
          GuidelineFact(
            COLOUR,
            "The source hard-codes the colour Color(0x$hex), ${describeColour(hex)}, not a theme " +
              "colour role.",
            decisive = true,
          )
      }
    NAMED_COLOUR.findAll(code)
      .map { it.value }
      .distinct()
      .take(MAX_PER_KIND)
      .forEach {
        facts +=
          GuidelineFact(
            COLOUR,
            "The source hard-codes the colour $it, not a theme colour role.",
            decisive = true,
          )
      }
    FIXED.findAll(code)
      .map { "${it.groupValues[1]}(${it.groupValues[2]}.${it.groupValues[3]})" }
      .distinct()
      .take(MAX_PER_KIND)
      .forEach {
        facts +=
          GuidelineFact(FIXED_SIZE, "The source fixes a size with Modifier.$it.", decisive = true)
      }
    val buttons = BUTTON_CALL.findAll(code).map { it.groupValues[1] }.groupingBy { it }.eachCount()
    if (buttons.isNotEmpty()) {
      facts +=
        GuidelineFact(
          BUTTONS,
          "Button calls in the source: " +
            buttons.entries
              .sortedByDescending { it.value }
              .joinToString { (name, n) ->
                "$n $name" + if (name == "Button") " (filled)" else ""
              } +
            ".",
          decisive = (buttons["Button"] ?: 0) >= 2,
        )
    }
    val typography = TYPOGRAPHY.findAll(code).count()
    val colours = COLOR_SCHEME.findAll(code).count()
    facts +=
      GuidelineFact(
        THEME,
        "The source reads MaterialTheme.typography $typography time(s) and " +
          "MaterialTheme.colorScheme $colours time(s).",
      )
    val strings =
      STRING_LITERAL.findAll(code)
        .map { it.groupValues[1] }
        .filter { it.any(Char::isLetter) }
        .distinct()
        .toList()
    val resources = STRING_RESOURCE.findAll(code).count()
    if (strings.isNotEmpty() || resources > 0) {
      facts +=
        GuidelineFact(
          STRINGS,
          "${strings.size} hard-coded user-visible string(s)" +
            (if (strings.isEmpty()) "" else " (" + strings.take(4).joinToString { "'$it'" } + ")") +
            " and $resources stringResource call(s).",
          decisive = strings.isNotEmpty() && resources == 0,
        )
    }
    NULL_DESCRIPTION.findAll(code)
      .count()
      .takeIf { it > 0 }
      ?.let { facts += GuidelineFact(DESCRIPTIONS, "$it contentDescription = null in the source.") }
    val maxLines = MAX_LINES.findAll(code).map { it.groupValues[1] }.distinct().toList()
    val overflow = OVERFLOW.findAll(code).map { it.groupValues[1] }.distinct().toList()
    if (maxLines.isNotEmpty() || overflow.isNotEmpty()) {
      facts +=
        GuidelineFact(
          TEXT_OVERFLOW,
          "Text limits in the source: " +
            listOfNotNull(
                maxLines.takeIf { it.isNotEmpty() }?.joinToString(prefix = "maxLines "),
                overflow.takeIf { it.isNotEmpty() }?.joinToString(prefix = "TextOverflow."),
              )
              .joinToString("; ") +
            ".",
        )
    }
    return facts
  }

  /**
   * [source] with its comments blanked: line comments, trailing ones included, and block comments,
   * which nest in Kotlin. String literals are kept as they are, so a `//` inside one is not a
   * comment. Newlines survive, so the code keeps its lines.
   */
  internal fun withoutComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    var depth = 0
    var inString = false
    while (i < source.length) {
      val c = source[i]
      val next = source.getOrNull(i + 1)
      when {
        depth > 0 -> {
          when {
            c == '/' && next == '*' -> {
              depth++
              i++
            }
            c == '*' && next == '/' -> {
              depth--
              i++
            }
            c == '\n' -> out.append(c)
          }
        }
        inString -> {
          out.append(c)
          if (c == '\\' && next != null) {
            out.append(next)
            i++
          } else if (c == '"') inString = false
        }
        c == '"' -> {
          inString = true
          out.append(c)
        }
        c == '/' && next == '/' -> {
          while (i < source.length && source[i] != '\n') i++
          continue
        }
        c == '/' && next == '*' -> {
          depth = 1
          i++
        }
        else -> out.append(c)
      }
      i++
    }
    return out.toString()
  }

  /** A colour's hex in words: lightness and hue, since a decision model reads names better. */
  internal fun describeColour(hex: String): String {
    val rgb = hex.takeLast(6).toLong(16)
    val r = ((rgb shr 16) and 0xFF) / 255.0
    val g = ((rgb shr 8) and 0xFF) / 255.0
    val b = (rgb and 0xFF) / 255.0
    // WCAG's relative luminance, so "dark" means what a contrast check would say it means.
    fun linear(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    val luminance = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    val lightness =
      when {
        luminance < 0.03 -> "very dark"
        luminance < 0.12 -> "dark"
        luminance < 0.4 -> "mid-tone"
        luminance < 0.75 -> "light"
        else -> "very light"
      }
    val hi = maxOf(r, g, b)
    val lo = minOf(r, g, b)
    if (hi - lo < 0.08) return "$lightness grey"
    val hue =
      when (hi) {
        r -> 60 * (((g - b) / (hi - lo)).mod(6.0))
        g -> 60 * ((b - r) / (hi - lo) + 2)
        else -> 60 * ((r - g) / (hi - lo) + 4)
      }
    val name =
      when {
        hue < 15 || hue >= 345 -> "red"
        hue < 45 -> "orange"
        hue < 70 -> "yellow"
        hue < 160 -> "green"
        hue < 200 -> "cyan"
        hue < 255 -> "blue"
        hue < 290 -> "purple"
        else -> "pink"
      }
    return "$lightness $name"
  }
}
