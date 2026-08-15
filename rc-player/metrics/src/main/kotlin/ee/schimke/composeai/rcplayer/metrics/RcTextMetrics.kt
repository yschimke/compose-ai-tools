package ee.schimke.composeai.rcplayer.metrics

/**
 * The metric vocabulary a Remote Compose document can ask a *player* for about its own text.
 *
 * This is the whole reason the harness needs no new opcode: `TextMeasure` (opcode 155) writes its
 * answer into a float id, and a float id can be a draw coordinate. So a document can measure itself
 * and then draw a line at the answer — each lane drawing *its own* metrics, in its own render, with
 * nothing to reconcile afterwards. See [RcTextMetricDocuments] for the cross-player fixture set.
 *
 * `type` is a packed word: the low byte selects *which* number, the high bits select *how the box
 * is derived*. The authority is `AndroidPaintContext.getTextBounds` in `remote-player-core`, whose
 * whole behaviour is:
 * ```
 * left   = flags&4 ? 0             : rect.left
 * right  = flags&4 ? measureText   : flags&1 ? measureText - rect.left : rect.right
 * top    = flags&2 ? round(fontMetrics.ascent)  : rect.top
 * bottom = flags&2 ? round(fontMetrics.descent) : rect.bottom
 * ```
 *
 * where `rect` is `Paint.getTextBounds` — the **ink** box — and `fontMetrics` is the **font** box.
 * Everything is relative to the text origin, so the vertical numbers are baseline-relative and the
 * horizontal ones are origin-relative. That is what lets the fixtures translate the canvas to the
 * text origin and then use every measured value as a raw coordinate, with no arithmetic in between.
 *
 * Only two of these flags have names upstream ([FLAG_MONOSPACE], [FLAG_FONT_HEIGHT], on
 * `TextMeasure` itself). [FLAG_ADVANCE] is unnamed there but is read by both the AOSP context above
 * and this repo's CMP player, so it is spelled out here rather than left as `0x400` at a call site.
 */
public object RcTextMeasurement {
  /** `right - left`. */
  public const val WIDTH: Int = 0

  /** `bottom - top`. */
  public const val HEIGHT: Int = 1

  /** Left edge, relative to the text origin. Negative when a glyph overhangs its origin. */
  public const val LEFT: Int = 2

  /** Right edge, relative to the text origin. */
  public const val RIGHT: Int = 3

  /** Top edge, relative to the **baseline** — so negative for anything above it. */
  public const val TOP: Int = 4

  /** Bottom edge, relative to the baseline. Positive for descenders. */
  public const val BOTTOM: Int = 5

  /**
   * String length in **UTF-16 code units**, not characters. This selector belongs to
   * `TextAttribute`, not opcode 155 `TextMeasure`; AndroidX leaves a `TextMeasure` destination
   * untouched when its low byte is 6. Included because this vocabulary is shared by both
   * operations; the fixtures don't draw it.
   */
  public const val LENGTH: Int = 6

  /**
   * `MEASURE_MONOSPACE_FLAG`. Leaves the left edge at the ink left and moves the right edge to
   * `advance - inkLeft` — so [RIGHT] reports that shifted edge, and [WIDTH], being `right - left`,
   * comes out as `advance - 2 * inkLeft`. It equals the advance only for a run whose first glyph
   * has no left side bearing, so this is the wrong way to ask for one: use [FLAG_ADVANCE].
   */
  public const val FLAG_MONOSPACE: Int = 0x100

  /**
   * `MEASURE_MAX_HEIGHT_FLAG`. Swaps the ink box's vertical extent for the **font** box — the
   * typographic ascent/descent of the face, which is what makes a line of digits as tall as a line
   * with descenders. The disagreement between this and the unflagged ink box is itself one of the
   * things the fixtures exist to show.
   */
  public const val FLAG_FONT_HEIGHT: Int = 0x200

  /**
   * Unnamed upstream. Reports the run's **advance** (`Paint.measureText`) with a left edge pinned
   * to zero, rather than the ink box. The gap between this and [RIGHT] without it is the right side
   * bearing, and a lane that confuses the two draws text that is correct but mis-positioned.
   */
  public const val FLAG_ADVANCE: Int = 0x400

  /** Packs a measurement selector and its flags into the `type` word `TextMeasure` carries. */
  public fun type(measurement: Int, flags: Int = 0): Int = measurement or flags
}

/** Which way a guide line runs, and therefore which coordinate the measured value supplies. */
public enum class RcGuideOrientation {
  /** A horizontal rule at `y = value`: an ascent, a baseline, an x-height. */
  HORIZONTAL,

  /** A vertical rule at `x = value`: an ink edge, an advance. */
  VERTICAL,
}

/**
 * Which string a guide is measured from.
 *
 * Cap height and x-height have no measurement selector of their own — but they don't need one. The
 * ink top of a capital `H` gives the cap height, and the ink top of a lowercase `x` gives the
 * x-height, both measured by the lane itself with the same paint as the specimen. So two extra
 * strings buy two more metrics for free, and the values are the lane's, not a font table read by
 * this repo and asserted at it.
 *
 * Note the guides are named `capTop` / `xTop` rather than `capHeight` / `xHeight`. Everything in
 * this vocabulary is a **coordinate**, and a vertical one is baseline-relative — so these read
 * `-35.0`, not `35.0`. They are drawn at that coordinate, and a consumer reading a key called
 * `capHeight` would get the metric sign-reversed. The height is the magnitude.
 */
public enum class RcMetricProbe(public val text: String) {
  /** The fixture's own specimen string. */
  SPECIMEN(""),

  /** `H` — the magnitude of its ink top is the cap height. */
  CAP("H"),

  /** `x` — the magnitude of its ink top is the x-height. */
  X_HEIGHT("x"),
}

/**
 * One guide line: a metric to measure, and how to draw it.
 *
 * The colours are part of the contract rather than decoration — the fixtures are read side by side
 * across five lanes, so "the blue pair moved and the green pair didn't" has to mean the same thing
 * in every image. Blue is the **font** box (typographic), green is the **ink** box (what the glyphs
 * actually cover), and they are deliberately the two that disagree.
 */
public enum class RcTextGuide(
  public val key: String,
  public val probe: RcMetricProbe,
  public val measurement: Int,
  public val flags: Int,
  public val orientation: RcGuideOrientation,
  public val colorArgb: Int,
  /** Short tag drawn next to the value, so the render is readable without this file. */
  public val label: String,
  public val description: String,
) {
  FONT_ASCENT(
    key = "fontAscent",
    probe = RcMetricProbe.SPECIMEN,
    measurement = RcTextMeasurement.TOP,
    flags = RcTextMeasurement.FLAG_FONT_HEIGHT,
    orientation = RcGuideOrientation.HORIZONTAL,
    colorArgb = FONT_BOX_COLOR,
    label = "asc",
    description = "Typographic ascent of the face, from the font box.",
  ),
  FONT_DESCENT(
    key = "fontDescent",
    probe = RcMetricProbe.SPECIMEN,
    measurement = RcTextMeasurement.BOTTOM,
    flags = RcTextMeasurement.FLAG_FONT_HEIGHT,
    orientation = RcGuideOrientation.HORIZONTAL,
    colorArgb = FONT_BOX_COLOR,
    label = "desc",
    description = "Typographic descent of the face, from the font box.",
  ),
  INK_TOP(
    key = "inkTop",
    probe = RcMetricProbe.SPECIMEN,
    measurement = RcTextMeasurement.TOP,
    flags = 0,
    orientation = RcGuideOrientation.HORIZONTAL,
    colorArgb = INK_BOX_COLOR,
    label = "ink top",
    description = "Highest ink in the specimen — a face-and-string property, not a face one.",
  ),
  INK_BOTTOM(
    key = "inkBottom",
    probe = RcMetricProbe.SPECIMEN,
    measurement = RcTextMeasurement.BOTTOM,
    flags = 0,
    orientation = RcGuideOrientation.HORIZONTAL,
    colorArgb = INK_BOX_COLOR,
    label = "ink bot",
    description = "Lowest ink in the specimen.",
  ),
  CAP_TOP(
    key = "capTop",
    probe = RcMetricProbe.CAP,
    measurement = RcTextMeasurement.TOP,
    flags = 0,
    orientation = RcGuideOrientation.HORIZONTAL,
    colorArgb = 0xffb26b00.toInt(),
    label = "cap top",
    description =
      "Ink top of `H`. Baseline-relative, so negative; the cap height is its magnitude.",
  ),
  X_TOP(
    key = "xTop",
    probe = RcMetricProbe.X_HEIGHT,
    measurement = RcTextMeasurement.TOP,
    flags = 0,
    orientation = RcGuideOrientation.HORIZONTAL,
    colorArgb = 0xff7b3fbf.toInt(),
    label = "x top",
    description = "Ink top of `x`. Baseline-relative, so negative; the x-height is its magnitude.",
  ),
  INK_LEFT(
    key = "inkLeft",
    probe = RcMetricProbe.SPECIMEN,
    measurement = RcTextMeasurement.LEFT,
    flags = 0,
    orientation = RcGuideOrientation.VERTICAL,
    colorArgb = INK_BOX_COLOR,
    label = "ink L",
    description = "Left side bearing: how far the first glyph's ink sits from the origin.",
  ),
  INK_RIGHT(
    key = "inkRight",
    probe = RcMetricProbe.SPECIMEN,
    measurement = RcTextMeasurement.RIGHT,
    flags = 0,
    orientation = RcGuideOrientation.VERTICAL,
    colorArgb = INK_BOX_COLOR,
    label = "ink R",
    description = "Right edge of the last glyph's ink.",
  ),
  ADVANCE(
    key = "advance",
    probe = RcMetricProbe.SPECIMEN,
    measurement = RcTextMeasurement.RIGHT,
    flags = RcTextMeasurement.FLAG_ADVANCE,
    orientation = RcGuideOrientation.VERTICAL,
    colorArgb = 0xffc2185b.toInt(),
    label = "adv",
    description =
      "Advance width — where the next run would start. Usually right of `ink R`, but they " +
        "coincide when the last glyph has no right side bearing, or when the float advance and " +
        "the integer-quantised ink box round to the same coordinate.",
  );

  /** The packed `TextMeasure` type word for this guide. */
  public val type: Int
    get() = RcTextMeasurement.type(measurement, flags)

  public companion object {
    /** Guides drawn as horizontal rules, in the order they are labelled. */
    public val HORIZONTAL: List<RcTextGuide>
      get() = entries.filter { it.orientation == RcGuideOrientation.HORIZONTAL }

    /** Guides drawn as vertical rules, in the order they are labelled. */
    public val VERTICAL: List<RcTextGuide>
      get() = entries.filter { it.orientation == RcGuideOrientation.VERTICAL }
  }
}

/** Typographic (font-box) metrics share one colour so the pair reads as a pair. */
private const val FONT_BOX_COLOR: Int = 0xff1565c0.toInt()

/** Ink-box metrics share another. */
private const val INK_BOX_COLOR: Int = 0xff2e7d32.toInt()
