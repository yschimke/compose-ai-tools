package ee.schimke.composeai.rcplayer.metrics

import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcColorConstant
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDraw4
import ee.schimke.composeai.rcplayer.protocol.RcDrawText
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeaderProperty
import ee.schimke.composeai.rcplayer.protocol.RcHeaderValue
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextFromFloat
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextMeasure
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcTransform2
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier

/**
 * Remote Compose documents that **measure their own text and draw the answer as guide lines**.
 *
 * The problem these exist for: comparing text across the five player lanes has only ever produced a
 * pixel percentage. "wear-m3 renders heavier", "compose-m3 renders narrower" — true, unactionable,
 * and equally consistent with a substituted face, a wrong weight instance, a wrong advance, a wrong
 * ascent, or leading placed on the other side of the line. A diff cannot separate those. So instead
 * of diffing the *glyphs*, these fixtures make each lane draw the *numbers it laid the glyphs out
 * with*, and diff those.
 *
 * Nothing here needs a player change, because the mechanism is already universal:
 * 1. `TextMeasure` (opcode 155, implemented on every lane) measures the current paint's text and
 *    writes one number into a float id;
 * 2. a float id is a legal draw coordinate;
 * 3. so a line drawn *at* that float is the lane's own measurement, rendered by the lane itself.
 *
 * The canvas is translated to the text origin before the guides are drawn, which is what keeps this
 * arithmetic-free: `getTextBounds` reports origin-relative horizontals and baseline-relative
 * verticals, so after the translate every measured value *is* the coordinate to draw at. No float
 * expression sits between the measurement and the line, and therefore no question about whether an
 * expression was evaluated before or after the measure op.
 *
 * ## The two text paths
 *
 * RC measures text in two unrelated places, and this is the fault line most likely to explain lane
 * disagreement — `RcProfileDocuments` states it for the profiler and it matters more here:
 * canvas-drawn text (`DrawTextRun`) is measured by the *player*, and is therefore the only path
 * `TextMeasure` can see; layout-tree text (`TextLayout`) hands the string to the host stack, which
 * owns measurement, shaping, wrapping and ellipsis and opens no seam to ask.
 *
 * Both paths are covered for the same string and style. [metricCard] and [weightSweep] draw the
 * canvas path with its metrics exposed; [layoutMode] draws the layout path inside a box whose rect
 * is known and whose single-line advance is measured on the canvas path beside it. So a mode
 * fixture shows where the host stack *actually* broke, clipped or ellipsised the line, against
 * where the player's own measurement says the line ends. A divergence that appears on one path and
 * not the other is localised the moment it appears.
 */
public object RcTextMetricDocuments {

  /** The specimen: ascenders, descenders, round and flat sidebearings, and digits. */
  public const val SPECIMEN: String = "Hamburgefons gjpqy 018"

  /** Overflows the mode fixtures' box on one line, but stays inside the frame. */
  public const val SINGLE_LINE_SPECIMEN: String = "Remote Compose truncates this line here!"

  /** Long enough to wrap past the mode fixtures' `maxLines`, so truncation is visible. */
  public const val WRAPPING_SPECIMEN: String =
    "Remote Compose keeps laying this paragraph out line after line until the box has no room left " +
      "for another one, and then it has to decide what to do"

  /** Short enough to leave slack in the box, without which an alignment is invisible. */
  public const val ALIGNMENT_SPECIMEN: String = "Align this"

  /**
   * The same job in Hebrew, for the two *semantic* alignments.
   *
   * `ALIGN_START` and `ALIGN_END` are the only two alignments whose meaning depends on direction,
   * and on an English string they land exactly where `ALIGN_LEFT` and `ALIGN_RIGHT` do. A lane that
   * had simply hard-coded start→left would pass a six-alignment matrix built only from LTR text
   * while being wrong for every RTL user, so the pair is drawn twice: once in each script.
   *
   * **This asks the question; it does not answer it.** `CoreText` carries no layout direction —
   * AOSP derives its only flags word from `textAlign >>> 16` — so a document cannot state that its
   * container is RTL, and the render harnesses all run their default LTR one. Resolving `START`
   * against an LTR container and resolving it to `LEFT` outright therefore look identical here.
   * Separating them needs a lane rendered under an RTL layout direction, which is a harness change
   * rather than a fixture one. The pair is still worth drawing: it is the half that can be
   * expressed, and both host stacks would ordinarily flip on content alone.
   *
   * If a lane has no face covering the script the fixture renders tofu — which still shows *where*
   * the line was placed, and is itself a font-coverage finding rather than a broken fixture.
   */
  public const val RTL_ALIGNMENT_SPECIMEN: String =
    "\u05e9\u05dc\u05d5\u05dd \u05e2\u05d5\u05dc\u05dd"

  /** The weights the sweep draws, including the two non-100 steps that matter in practice. */
  public val SWEEP_WEIGHTS: List<Int> = listOf(400, 500, 550, 599, 700)

  /** Every fixture this object can build, in the order the harness should render them. */
  public fun all(): List<RcTextMetricFixture> =
    listOf(metricCard()) +
      SWEEP_WEIGHTS.map(::weightSweepEntry) +
      listOf(weightSweep()) +
      LAYOUT_MODES.map { layoutMode(it) }

  // ---------------------------------------------------------------------------------------------
  // The metric card
  // ---------------------------------------------------------------------------------------------

  private const val CARD_WIDTH = 720
  private const val CARD_HEIGHT = 400
  private const val CARD_ORIGIN_X = 88f
  private const val CARD_BASELINE_Y = 190f
  private const val CARD_SPECIMEN_SIZE = 48f

  /**
   * The reference fixture: one specimen with every guide drawn and every value printed.
   *
   * Reading it is the point of the whole exercise. Blue is the **font** box (the face's typographic
   * ascent and descent), green is the **ink** box (what these particular glyphs actually cover),
   * and the two disagreeing is normal — *which* of them a lane uses to place a line is not. Magenta
   * is the advance: where the next run starts, which is never the same as the right edge of the ink
   * and is the number that decides whether text overflows.
   */
  public fun metricCard(): RcTextMetricFixture {
    val operations = buildList {
      add(RcTextData(TEXT_SPECIMEN, SPECIMEN))
      add(RcTextData(TEXT_CAP_PROBE, RcMetricProbe.CAP.text))
      add(RcTextData(TEXT_X_PROBE, RcMetricProbe.X_HEIGHT.text))
      addAll(guideLabelData())
      add(RcTextData(TEXT_TITLE, "canvas DrawTextRun · ${CARD_SPECIMEN_SIZE.toInt()}px · wght 400"))
      add(RcTextData(TEXT_BASELINE_NOTE, "baseline = 0 (red)"))

      addAll(background(CARD_WIDTH, CARD_HEIGHT))

      // Title, in the label paint, before the specimen paint is installed.
      addAll(labelPaint(TITLE_COLOR, LABEL_SIZE + 2f))
      add(drawText(TEXT_TITLE, 24f, 32f))
      addAll(labelPaint(BASELINE_COLOR, LABEL_SIZE))
      add(drawText(TEXT_BASELINE_NOTE, 24f, 52f))

      // The specimen paint has to be current for every measurement — `TextMeasure` measures
      // whatever the paint says *now*, so a size or weight change between here and the measures
      // would silently report a different face's numbers than the one drawn below.
      addAll(textPaint(SPECIMEN_COLOR, CARD_SPECIMEN_SIZE, weight = 400))
      addAll(RcTextGuide.entries.map { measure(it) })

      add(RcNoArg(RcOpcodes.MATRIX_SAVE))
      add(
        RcTransform2(RcOpcodes.MATRIX_TRANSLATE, literal(CARD_ORIGIN_X), literal(CARD_BASELINE_Y))
      )

      // Glyphs first, guides over them: a rule hidden behind a stem cannot be compared.
      add(drawText(TEXT_SPECIMEN, 0f, 0f))

      val left = -CARD_ORIGIN_X + 24f
      val right = CARD_WIDTH - CARD_ORIGIN_X - 24f
      val top = -80f
      val bottom = 60f

      addAll(strokePaint(BASELINE_COLOR, 1.5f))
      add(line(literal(left), literal(0f), literal(right), literal(0f)))
      add(line(literal(0f), literal(top), literal(0f), literal(bottom)))

      RcTextGuide.entries.forEach { guide ->
        addAll(strokePaint(guide.colorArgb, 1f))
        val value = reference(floatId(guide))
        add(
          when (guide.orientation) {
            RcGuideOrientation.HORIZONTAL -> line(literal(left), value, literal(right), value)
            RcGuideOrientation.VERTICAL -> line(value, literal(top), value, literal(bottom))
          }
        )
      }
      add(RcNoArg(RcOpcodes.MATRIX_RESTORE))

      addAll(legend(RcTextGuide.HORIZONTAL, columnX = 32f))
      addAll(legend(RcTextGuide.VERTICAL, columnX = 400f))
    }
    return RcTextMetricFixture(
      id = "text-metrics-card",
      width = CARD_WIDTH,
      height = CARD_HEIGHT,
      summary = "Every metric of one canvas-drawn specimen, measured and labelled by the lane.",
      document = RcDocument(header(CARD_WIDTH, CARD_HEIGHT), operations),
    )
  }

  /** One legend column: the guide's tag in its own colour, then the value the lane measured. */
  private fun legend(guides: List<RcTextGuide>, columnX: Float): List<RcOperation> = buildList {
    guides.forEachIndexed { row, guide ->
      val y = LEGEND_TOP + row * LEGEND_ROW_HEIGHT
      addAll(labelPaint(guide.colorArgb, LABEL_SIZE))
      add(drawText(labelTextId(guide), columnX, y))
      // The value is a second run rather than a `TextMerge` of the two: merging would chain a
      // second listener hop behind the measured float, and a fixture whose numbers can lag the
      // lines it labels by a frame is worse than no numbers at all.
      add(
        RcTextFromFloat(
          outId = valueTextId(guide),
          value = reference(floatId(guide)),
          digitsBefore = 3,
          digitsAfter = 1,
          flags = NO_LEADING_PAD,
        )
      )
      add(drawText(valueTextId(guide), columnX + LEGEND_VALUE_OFFSET, y))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The weight sweep
  // ---------------------------------------------------------------------------------------------

  private const val SWEEP_WIDTH = 760
  private const val SWEEP_ROW_HEIGHT = 62f
  private const val SWEEP_ORIGIN_X = 108f
  private const val SWEEP_SPECIMEN_SIZE = 32f

  /**
   * The same string at five weights, each row reporting **two** numbers: the advance, and the width
   * of the ink box.
   *
   * This is how the variable-font question gets *measured* rather than inferred. #3579 concluded
   * that Google Fonts' CSS API had flattened `wght@450` and `wght@550` to one instance because the
   * two downloads were byte-identical — a strong hint, but a file-size argument. Here the question
   * is asked of the renderer directly.
   *
   * Two numbers rather than one, because **equal advances do not prove a reused face**. Families
   * are routinely drawn duplexed on purpose, keeping identical advances across weights while only
   * the stems thicken. So the advance answers a narrower question than it looks like it answers:
   * "would this weight reflow the layout". The ink box — `right - left` of `getTextBounds`, a
   * different measurement off a different code path — fails independently of it.
   *
   * Neither number is a proof on its own. What the pair gives you, read together with the glyphs
   * beside them, is a signature:
   * - both move → the weight reached a metric-distinct instance;
   * - both flat, glyphs identical → the weight changed nothing at all;
   * - both flat, glyphs visibly bolder → the weight is **metrically identical** while the glyphs
   *   are not.
   *
   * The reference render lands on the third: 362.0 advance and 359.0 ink for 500, 550, 599 and 700
   * alike, with 700 plainly heavier than 500. Resist reading that as "synthesised rather than
   * resolved" — `getTextBounds` reports the run's outer rectangle, so a genuinely resolved bold
   * that thickens its stems *inward*, keeping the advance and the extrema, gives the same numbers.
   * Every row is a statement about metrics, which is what decides layout; identifying why the
   * metrics match needs the resolved face, which this harness cannot report. The ink box is also
   * integer-quantised (`Paint.getTextBounds` returns a `Rect`), so it is a coarse instrument at
   * this size — one more reason to read it as corroboration rather than as the answer.
   *
   * 550 and 599 are in the sweep on purpose. They are the values that fall between the static
   * instances a weight-enumerated stylesheet ships, so they are where a "nearest static instance"
   * fallback becomes visible; Wear's `TimeText` really does land on 599.
   */
  public fun weightSweep(): RcTextMetricFixture {
    val height = (SWEEP_WEIGHTS.size * SWEEP_ROW_HEIGHT + 70f).toInt()
    val operations = buildList {
      add(RcTextData(TEXT_SPECIMEN, SPECIMEN))
      add(
        RcTextData(
          TEXT_TITLE,
          "per requested weight · ${SWEEP_SPECIMEN_SIZE.toInt()}px · " +
            "magenta = advance, green = ink width",
        )
      )
      SWEEP_WEIGHTS.forEachIndexed { index, weight ->
        add(RcTextData(TEXT_SWEEP_LABEL + index, "wght $weight"))
      }
      addAll(background(SWEEP_WIDTH, height))
      addAll(labelPaint(TITLE_COLOR, LABEL_SIZE + 2f))
      add(drawText(TEXT_TITLE, 24f, 32f))

      SWEEP_WEIGHTS.forEachIndexed { index, weight ->
        val baseline = 78f + index * SWEEP_ROW_HEIGHT
        val floatId = SWEEP_FLOAT_BASE + index
        val inkFloatId = SWEEP_INK_FLOAT_BASE + index

        addAll(textPaint(SPECIMEN_COLOR, SWEEP_SPECIMEN_SIZE, weight = weight))
        add(RcTextMeasure(outId = floatId, textId = TEXT_SPECIMEN, type = RcTextGuide.ADVANCE.type))
        add(RcTextMeasure(outId = inkFloatId, textId = TEXT_SPECIMEN, type = INK_WIDTH_TYPE))

        add(RcNoArg(RcOpcodes.MATRIX_SAVE))
        add(RcTransform2(RcOpcodes.MATRIX_TRANSLATE, literal(SWEEP_ORIGIN_X), literal(baseline)))
        add(drawText(TEXT_SPECIMEN, 0f, 0f))
        addAll(strokePaint(RcTextGuide.ADVANCE.colorArgb, 1f))
        add(line(reference(floatId), literal(-30f), reference(floatId), literal(12f)))
        add(RcNoArg(RcOpcodes.MATRIX_RESTORE))

        addAll(labelPaint(TITLE_COLOR, LABEL_SIZE))
        add(drawText(TEXT_SWEEP_LABEL + index, 24f, baseline))
        addAll(labelPaint(RcTextGuide.ADVANCE.colorArgb, LABEL_SIZE))
        add(
          RcTextFromFloat(
            outId = TEXT_SWEEP_VALUE + index,
            value = reference(floatId),
            digitsBefore = 3,
            digitsAfter = 1,
            flags = NO_LEADING_PAD,
          )
        )
        add(drawText(TEXT_SWEEP_VALUE + index, (SWEEP_WIDTH - 150).toFloat(), baseline))
        addAll(labelPaint(RcTextGuide.INK_RIGHT.colorArgb, LABEL_SIZE))
        add(
          RcTextFromFloat(
            outId = TEXT_SWEEP_INK_VALUE + index,
            value = reference(inkFloatId),
            digitsBefore = 3,
            digitsAfter = 1,
            flags = NO_LEADING_PAD,
          )
        )
        add(drawText(TEXT_SWEEP_INK_VALUE + index, (SWEEP_WIDTH - 76).toFloat(), baseline))
      }
    }
    return RcTextMetricFixture(
      id = "text-metrics-weight-sweep",
      width = SWEEP_WIDTH,
      height = height,
      summary = "One string at five weights, each reporting its measured advance and ink width.",
      document = RcDocument(header(SWEEP_WIDTH, height), operations),
    )
  }

  /**
   * A single row of [weightSweep] as its own fixture.
   *
   * The sweep is the readable artifact; these are the diffable ones. A per-weight document keeps
   * the harness's row granularity meaningful — a lane that gets 400 right and 599 wrong shows up as
   * one failing row rather than one image that is "4% different" for reasons the number can't name.
   */
  public fun weightSweepEntry(weight: Int): RcTextMetricFixture {
    val height = 120
    val baseline = 78f
    val operations = buildList {
      add(RcTextData(TEXT_SPECIMEN, SPECIMEN))
      add(
        RcTextData(
          TEXT_TITLE,
          "wght $weight · ${SWEEP_SPECIMEN_SIZE.toInt()}px · advance, ink width",
        )
      )
      addAll(background(SWEEP_WIDTH, height))
      addAll(labelPaint(TITLE_COLOR, LABEL_SIZE + 2f))
      add(drawText(TEXT_TITLE, 24f, 32f))

      addAll(textPaint(SPECIMEN_COLOR, SWEEP_SPECIMEN_SIZE, weight = weight))
      add(
        RcTextMeasure(
          outId = SWEEP_FLOAT_BASE,
          textId = TEXT_SPECIMEN,
          type = RcTextGuide.ADVANCE.type,
        )
      )
      add(
        RcTextMeasure(outId = SWEEP_INK_FLOAT_BASE, textId = TEXT_SPECIMEN, type = INK_WIDTH_TYPE)
      )
      add(RcNoArg(RcOpcodes.MATRIX_SAVE))
      add(RcTransform2(RcOpcodes.MATRIX_TRANSLATE, literal(SWEEP_ORIGIN_X), literal(baseline)))
      add(drawText(TEXT_SPECIMEN, 0f, 0f))
      addAll(strokePaint(RcTextGuide.ADVANCE.colorArgb, 1f))
      add(
        line(reference(SWEEP_FLOAT_BASE), literal(-32f), reference(SWEEP_FLOAT_BASE), literal(14f))
      )
      add(RcNoArg(RcOpcodes.MATRIX_RESTORE))

      addAll(labelPaint(RcTextGuide.ADVANCE.colorArgb, LABEL_SIZE))
      add(
        RcTextFromFloat(
          outId = TEXT_SWEEP_VALUE,
          value = reference(SWEEP_FLOAT_BASE),
          digitsBefore = 3,
          digitsAfter = 1,
          flags = NO_LEADING_PAD,
        )
      )
      add(drawText(TEXT_SWEEP_VALUE, (SWEEP_WIDTH - 150).toFloat(), baseline))
      addAll(labelPaint(RcTextGuide.INK_RIGHT.colorArgb, LABEL_SIZE))
      add(
        RcTextFromFloat(
          outId = TEXT_SWEEP_INK_VALUE,
          value = reference(SWEEP_INK_FLOAT_BASE),
          digitsBefore = 3,
          digitsAfter = 1,
          flags = NO_LEADING_PAD,
        )
      )
      add(drawText(TEXT_SWEEP_INK_VALUE, (SWEEP_WIDTH - 76).toFloat(), baseline))
    }
    return RcTextMetricFixture(
      id = "text-metrics-weight-$weight",
      width = SWEEP_WIDTH,
      height = height,
      summary = "Measured advance and ink width of the specimen at wght $weight.",
      document = RcDocument(header(SWEEP_WIDTH, height), operations),
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Layout-tree modes
  // ---------------------------------------------------------------------------------------------

  private const val MODE_WIDTH = 460
  private const val MODE_HEIGHT = 240
  private const val MODE_BOX_WIDTH = 300
  private const val MODE_BOX_HEIGHT = 120
  private const val MODE_TEXT_SIZE = 17f

  /** A layout-tree text mode: what to ask `TextLayout` for, and what to call it in the render. */
  public data class LayoutMode(
    val id: String,
    val maxLines: Int,
    val overflow: Int,
    val align: Int,
    val specimen: String,
    val title: String,
    /** `CoreText` property 13 — extra leading, in px. */
    val lineHeightAdd: Float = 0f,
    /** `CoreText` property 14 — the line box as a multiple of the font's own height. */
    val lineHeightMultiplier: Float = 1f,
  )

  /**
   * The matrix, driven straight off `RcTextLayout`'s own constants.
   *
   * Single-line first, because that is where clipping and the three ellipsis placements differ
   * visibly; then the wrapping group, where the break points and *which* line gets truncated show;
   * then the six alignments. Each group gets the specimen its question needs — an alignment is
   * invisible on a line that fills its box, and an ellipsis is invisible on one that doesn't.
   */
  public val LAYOUT_MODES: List<LayoutMode> =
    listOf(
      layoutModeSpec("single-clip", 1, RcTextLayout.OVERFLOW_CLIP, "1 line · clip"),
      layoutModeSpec("single-visible", 1, RcTextLayout.OVERFLOW_VISIBLE, "1 line · visible"),
      layoutModeSpec("single-ellipsis", 1, RcTextLayout.OVERFLOW_ELLIPSIS, "1 line · ellipsis"),
      layoutModeSpec(
        "single-start-ellipsis",
        1,
        RcTextLayout.OVERFLOW_START_ELLIPSIS,
        "1 line · start ellipsis",
      ),
      layoutModeSpec(
        "single-middle-ellipsis",
        1,
        RcTextLayout.OVERFLOW_MIDDLE_ELLIPSIS,
        "1 line · middle ellipsis",
      ),
      LayoutMode(
        "wrap-clip",
        3,
        RcTextLayout.OVERFLOW_CLIP,
        RcTextLayout.ALIGN_START,
        WRAPPING_SPECIMEN,
        "3 lines · clip",
      ),
      LayoutMode(
        "wrap-ellipsis",
        3,
        RcTextLayout.OVERFLOW_ELLIPSIS,
        RcTextLayout.ALIGN_START,
        WRAPPING_SPECIMEN,
        "3 lines · ellipsis",
      ),
      LayoutMode(
        "wrap-justify",
        3,
        RcTextLayout.OVERFLOW_CLIP,
        RcTextLayout.ALIGN_JUSTIFY,
        WRAPPING_SPECIMEN,
        "3 lines · justify",
      ),
      alignmentModeSpec("align-left", RcTextLayout.ALIGN_LEFT, "align left"),
      alignmentModeSpec("align-center", RcTextLayout.ALIGN_CENTER, "align center"),
      alignmentModeSpec("align-right", RcTextLayout.ALIGN_RIGHT, "align right"),
      alignmentModeSpec("align-start", RcTextLayout.ALIGN_START, "align start"),
      alignmentModeSpec("align-end", RcTextLayout.ALIGN_END, "align end"),
      // The same two semantic alignments against an RTL paragraph. Start must flip to the right
      // edge and end to the left; a lane where these match their LTR twins has resolved `START` to
      // `LEFT` rather than to the paragraph direction.
      LayoutMode(
        "align-start-rtl",
        2,
        RcTextLayout.OVERFLOW_CLIP,
        RcTextLayout.ALIGN_START,
        RTL_ALIGNMENT_SPECIMEN,
        "align start · RTL",
      ),
      LayoutMode(
        "align-end-rtl",
        2,
        RcTextLayout.OVERFLOW_CLIP,
        RcTextLayout.ALIGN_END,
        RTL_ALIGNMENT_SPECIMEN,
        "align end · RTL",
      ),
      // Line height is the metric most likely to explain a "heavier"/"looser" block: it moves every
      // baseline after the first without touching a single glyph, so a pixel diff sees a wall of
      // difference and cannot say which of the two knobs moved. Two fixtures, one knob each.
      LayoutMode(
        "line-height-add",
        3,
        RcTextLayout.OVERFLOW_CLIP,
        RcTextLayout.ALIGN_START,
        WRAPPING_SPECIMEN,
        "3 lines · lineHeight +8px",
        lineHeightAdd = 8f,
      ),
      LayoutMode(
        "line-height-multiplier",
        3,
        RcTextLayout.OVERFLOW_CLIP,
        RcTextLayout.ALIGN_START,
        WRAPPING_SPECIMEN,
        "3 lines · lineHeight ×1.5",
        lineHeightMultiplier = 1.5f,
      ),
    )

  private fun layoutModeSpec(id: String, maxLines: Int, overflow: Int, title: String) =
    LayoutMode(id, maxLines, overflow, RcTextLayout.ALIGN_START, SINGLE_LINE_SPECIMEN, title)

  private fun alignmentModeSpec(id: String, align: Int, title: String) =
    LayoutMode(id, 2, RcTextLayout.OVERFLOW_CLIP, align, ALIGNMENT_SPECIMEN, title)

  /**
   * The layout-tree text component, as a captured document spells it.
   *
   * `CoreText` rather than the older `TextLayout` for two reasons: it is what the connector
   * actually emits, so a divergence found here is a divergence that ships; and it is the only one
   * of the two carrying line height (properties 13 and 14), which is a metric this harness exists
   * to isolate. The colour travels as a `ColorConstant` id rather than a literal for the same
   * reason — that is the shape real documents use, and a fixture that exercises a path nothing else
   * takes is a fixture that can pass while the shipping path is broken.
   *
   * The family is deliberately left unnamed. `resolveFontFamily` maps both a missing id and the
   * literal `"default"` to the same branch, so naming it bought nothing while implying the fixture
   * pins a face. It does not, and cannot yet: that branch consults the host's font manifest for the
   * `default` role, whereas the overlay's canvas paint decodes built-in family 0 straight to
   * `FontFamily.Default`. With no manifest — every lane rendered so far — the two agree; on a lane
   * that installs one they may not, which is a limit on comparing the two paths there until a face
   * is pinned through embedded `FontData`.
   */
  private fun coreText(componentId: Int, mode: LayoutMode) =
    RcCoreText(
      textId = TEXT_SPECIMEN,
      properties =
        listOf(
          RcTextStyleProperty.IntValue(CORE_TEXT_COMPONENT_ID, componentId),
          RcTextStyleProperty.IntValue(CORE_TEXT_COLOR_ID, COLOR_SPECIMEN),
          RcTextStyleProperty.FloatValue(CORE_TEXT_FONT_SIZE, literal(MODE_TEXT_SIZE)),
          RcTextStyleProperty.FloatValue(CORE_TEXT_FONT_WEIGHT, literal(400f)),
          RcTextStyleProperty.IntValue(CORE_TEXT_ALIGN, mode.align),
          RcTextStyleProperty.IntValue(CORE_TEXT_OVERFLOW, mode.overflow),
          RcTextStyleProperty.IntValue(CORE_TEXT_MAX_LINES, mode.maxLines),
          RcTextStyleProperty.FloatValue(CORE_TEXT_LINE_HEIGHT_ADD, literal(mode.lineHeightAdd)),
          RcTextStyleProperty.FloatValue(
            CORE_TEXT_LINE_HEIGHT_MULTIPLIER,
            literal(mode.lineHeightMultiplier),
          ),
        ),
    )

  /**
   * One layout-tree mode, with the box it was given and the player's own advance drawn over it.
   *
   * The box is positioned by **padding on a full-size parent** rather than by a Box alignment. That
   * is not a style preference: the overlay has to know the box's rect to draw it, and a rect
   * derived from "wherever the player chose to centre it" is a rect that can be wrong on one lane
   * and right on another — the overlay would then be drawing *its own* bug over the finding.
   * Padding pins the box to a coordinate this file already knows.
   *
   * The overlay is a canvas *sibling* of the text's box inside the same content container — a
   * component parked directly under the Box rather than under its `LayoutContent` is not part of
   * the drawn tree and would silently never paint. It draws the two things the layout path cannot
   * be asked for: the box the text was handed, and where a single unwrapped line of the same string
   * would end according to the *player's* own measurement. Between them, "wrapped early", "clipped
   * late" and "ellipsised at a different character" stop being adjectives.
   */
  public fun layoutMode(mode: LayoutMode): RcTextMetricFixture {
    val operations = buildList {
      add(RcTextData(TEXT_SPECIMEN, mode.specimen))
      add(RcTextData(TEXT_TITLE, "CoreText · ${mode.title}"))
      add(RcTextData(TEXT_MODE_BOX_NOTE, "one-line advance · box ${MODE_BOX_WIDTH}px"))
      add(RcColorConstant(COLOR_SPECIMEN, SPECIMEN_COLOR))

      // `RootLayoutComponent` is followed *directly* by its component, with no `LayoutContent`
      // between them. That is not cosmetic: an interposed content container makes the AOSP view
      // player build a tree whose children it then never paints — the background modifiers still
      // land, so the frame looks plausible and simply has no text in it, which is the most
      // expensive kind of wrong for a fixture whose whole output is text.
      add(RcRootLayout(1))
      addAll(topStartBox(componentId = 3, boxWidth = MODE_WIDTH, boxHeight = MODE_HEIGHT))
      add(solidBackground(BACKGROUND))
      add(RcLayoutContent(4))

      // The text's box, then the overlay over it — in that order, because a guide line under the
      // glyphs it measures cannot be compared to them.
      //
      // It sits at the frame's top-left corner rather than being inset, and that is a deliberate
      // retreat from a nicer-looking layout: `PaddingModifier` is in **dp**, not px, so an inset
      // authored as 80 becomes 160px on the xhdpi harness and 80 in a density-1 lane — the box
      // moves, the overlay drawn at the authored coordinate doesn't, and the guides end up
      // measuring the fixture's own bug. Every other length in these documents is px, so the frame
      // corner is the one origin that means the same thing on every lane.
      addAll(topStartBox(componentId = 5, boxWidth = MODE_BOX_WIDTH, boxHeight = MODE_BOX_HEIGHT))
      add(solidBackground(BOX_FILL))
      add(RcLayoutContent(6))
      add(coreText(componentId = 7, mode = mode))
      // The text component fills its box. Without this it wraps to its own intrinsic width, and an
      // alignment inside a component exactly as wide as its text is a no-op — every alignment
      // fixture would render identically and look like five lanes agreeing.
      add(fillWidth())
      add(fillHeight())
      add(RcLayoutContent(11))
      add(END) // content 11
      add(END) // text 7
      add(END) // content 6
      add(END) // box 5

      addAll(
        canvasComponent(
          componentId = 8,
          canvasWidth = MODE_WIDTH,
          canvasHeight = MODE_HEIGHT,
          operations =
            buildList {
              // Measured with a canvas paint deliberately matching the layout style above, so the
              // advance is this lane's answer for the *same* string at the *same* size — the only
              // way the two paths' numbers are comparable at all.
              addAll(textPaint(SPECIMEN_COLOR, MODE_TEXT_SIZE, weight = 400))
              add(
                RcTextMeasure(
                  outId = MODE_ADVANCE_FLOAT,
                  textId = TEXT_SPECIMEN,
                  type = RcTextGuide.ADVANCE.type,
                )
              )
              addAll(strokePaint(BOX_GUIDE_COLOR, 1f))
              add(
                RcDraw4(
                  RcOpcodes.DRAW_RECT,
                  literal(0f),
                  literal(0f),
                  literal(MODE_BOX_WIDTH.toFloat()),
                  literal(MODE_BOX_HEIGHT.toFloat()),
                )
              )

              // The advance rule shares the box's left edge, so it is directly comparable to the
              // box's right edge beside it. A wrapping specimen's single-line advance is wider than
              // the frame and simply runs off it — which is the honest picture — while the printed
              // number below stays exact either way.
              addAll(strokePaint(RcTextGuide.ADVANCE.colorArgb, 1f))
              add(
                line(
                  reference(MODE_ADVANCE_FLOAT),
                  literal(-6f),
                  reference(MODE_ADVANCE_FLOAT),
                  literal(MODE_BOX_HEIGHT + 6f),
                )
              )

              addAll(labelPaint(TITLE_COLOR, LABEL_SIZE + 2f))
              add(drawText(TEXT_TITLE, 12f, MODE_BOX_HEIGHT + 44f))
              addAll(labelPaint(RcTextGuide.ADVANCE.colorArgb, LABEL_SIZE))
              add(
                RcTextFromFloat(
                  outId = TEXT_MODE_ADVANCE_VALUE,
                  value = reference(MODE_ADVANCE_FLOAT),
                  digitsBefore = 4,
                  digitsAfter = 1,
                  flags = NO_LEADING_PAD,
                )
              )
              add(drawText(TEXT_MODE_ADVANCE_VALUE, 12f, MODE_BOX_HEIGHT + 72f))
              addAll(labelPaint(BOX_GUIDE_COLOR, LABEL_SIZE))
              add(drawText(TEXT_MODE_BOX_NOTE, 72f, MODE_BOX_HEIGHT + 72f))
            },
        )
      )

      add(END) // content 4
      add(END) // box 3
      // ...and the root. The three AndroidX-backed harnesses tolerate an unterminated container at
      // EOF, so leaving this off renders perfectly on the very lanes the fixtures were developed
      // against and then throws `Unclosed RcRootLayout container` the moment this repo's own
      // `RcDocumentLinker` — the path the CMP lanes take — tries to link it.
      add(END) // root 1
    }
    return RcTextMetricFixture(
      id = "text-metrics-layout-${mode.id}",
      width = MODE_WIDTH,
      height = MODE_HEIGHT,
      summary = "Layout-tree text, ${mode.title}, against its box and the player's own advance.",
      document = RcDocument(header(MODE_WIDTH, MODE_HEIGHT), operations),
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Shared construction
  // ---------------------------------------------------------------------------------------------

  private val END = RcNoArg(RcOpcodes.CONTAINER_END)

  /**
   * `TextFromFloat` flags: no leading pad.
   *
   * The default pads the integer part out to `digitsBefore` with spaces, which prints `-45.0` as `-
   * 45.0` — a minus sign detached from its number, in a legend whose whole job is to be read at a
   * glance next to a coloured rule.
   */
  private const val NO_LEADING_PAD = 4

  /**
   * A box that positions its children at the top-start corner.
   *
   * `(horizontal, vertical)` is an AndroidX pair with a *fixed* set of legal combinations —
   * `boxAlignment` in the CMP player enumerates all nine and errors on anything else, so `(1, 1)`
   * is not "start, start" but a crash on three of the five lanes. Top-start is `(1, 4)`.
   */
  private fun topStartBox(componentId: Int, boxWidth: Int, boxHeight: Int): List<RcOperation> =
    listOf(
      RcBoxLayout(
        componentId = componentId,
        animationId = 0,
        horizontalPositioning = 1,
        verticalPositioning = 4,
      ),
      width(boxWidth.toFloat()),
      height(boxHeight.toFloat()),
    )

  private const val BACKGROUND = 0xfffdfcff.toInt()
  private const val BOX_FILL = 0xffeceff4.toInt()
  private const val BOX_GUIDE_COLOR = 0xff37474f.toInt()
  private const val SPECIMEN_COLOR = 0xff1a1a1a.toInt()
  private const val TITLE_COLOR = 0xff5f6368.toInt()
  private const val BASELINE_COLOR = 0xffd32f2f.toInt()

  private const val LABEL_SIZE = 12f
  private const val LEGEND_TOP = 292f
  private const val LEGEND_ROW_HEIGHT = 18f
  private const val LEGEND_VALUE_OFFSET = 76f

  // Text ids. Kept clear of the low ids AndroidX reserves for built-ins, and grouped so a fixture's
  // ids never collide with a guide's.
  private const val TEXT_SPECIMEN = 10
  private const val TEXT_CAP_PROBE = 11
  private const val TEXT_X_PROBE = 12
  private const val TEXT_TITLE = 13
  private const val TEXT_BASELINE_NOTE = 14
  private const val TEXT_MODE_ADVANCE_VALUE = 15
  private const val TEXT_MODE_BOX_NOTE = 16
  private const val COLOR_SPECIMEN = 18

  // `CoreText` style property ids, from AndroidX `CoreText`. The player resolves them by number.
  private const val CORE_TEXT_COMPONENT_ID = 1
  private const val CORE_TEXT_COLOR_ID = 4
  private const val CORE_TEXT_FONT_SIZE = 5
  private const val CORE_TEXT_FONT_WEIGHT = 7
  private const val CORE_TEXT_ALIGN = 9
  private const val CORE_TEXT_OVERFLOW = 10
  private const val CORE_TEXT_MAX_LINES = 11
  private const val CORE_TEXT_LINE_HEIGHT_ADD = 13
  private const val CORE_TEXT_LINE_HEIGHT_MULTIPLIER = 14
  private const val TEXT_GUIDE_LABEL_BASE = 100
  private const val TEXT_GUIDE_VALUE_BASE = 140
  private const val TEXT_SWEEP_LABEL = 180
  private const val TEXT_SWEEP_VALUE = 190
  private const val TEXT_SWEEP_INK_VALUE = 200

  // Float ids the measurements write into.
  private const val GUIDE_FLOAT_BASE = 300
  private const val SWEEP_FLOAT_BASE = 340
  private const val SWEEP_INK_FLOAT_BASE = 350
  private const val MODE_ADVANCE_FLOAT = 360

  /**
   * `right - left` of the **ink** box — the second, independent number every sweep row reports.
   *
   * It comes off `Paint.getTextBounds` rather than `measureText`, so it can disagree with the
   * advance; that independence, not any claim that it tracks weight reliably, is why it is here. It
   * is integer-quantised and therefore coarse.
   */
  private val INK_WIDTH_TYPE = RcTextMeasurement.type(RcTextMeasurement.WIDTH)

  private fun floatId(guide: RcTextGuide) = GUIDE_FLOAT_BASE + guide.ordinal

  private fun labelTextId(guide: RcTextGuide) = TEXT_GUIDE_LABEL_BASE + guide.ordinal

  private fun valueTextId(guide: RcTextGuide) = TEXT_GUIDE_VALUE_BASE + guide.ordinal

  private fun guideLabelData(): List<RcOperation> =
    RcTextGuide.entries.map { RcTextData(labelTextId(it), it.label) }

  private fun measure(guide: RcTextGuide) =
    RcTextMeasure(
      outId = floatId(guide),
      textId =
        when (guide.probe) {
          RcMetricProbe.SPECIMEN -> TEXT_SPECIMEN
          RcMetricProbe.CAP -> TEXT_CAP_PROBE
          RcMetricProbe.X_HEIGHT -> TEXT_X_PROBE
        },
      type = guide.type,
    )

  /**
   * The header a *captured* document carries, not the minimal legacy one.
   *
   * This matters more than it looks. AndroidX keeps several operation registries — a base map plus
   * `sMapV7AndroidX` / `sMapV7Widgets` — and the document **profile** (header property 27) is what
   * decides which of them the reader installs. `CoreText` (opcode 239) lives only in the profiled
   * maps, so a legacy header makes the AOSP player raise `Unknown operation encountered 239` and
   * abandon the rest of the buffer. Since these fixtures exist to be compared across lanes, they
   * carry the same header shape the connector emits for real previews rather than a smaller one
   * that happens to work for the three ops a canvas fixture needs.
   */
  private fun header(width: Int, height: Int) =
    RcHeader(
      RcVersion(1, 1, 0),
      properties =
        listOf(
          RcHeaderProperty(RcHeader.DOC_WIDTH, RcHeaderValue.IntValue(width)),
          RcHeaderProperty(RcHeader.DOC_HEIGHT, RcHeaderValue.IntValue(height)),
          RcHeaderProperty(
            RcHeader.DOC_DENSITY_AT_GENERATION,
            RcHeaderValue.FloatValue(literal(1f)),
          ),
          RcHeaderProperty(HEADER_CONTENT_DESCRIPTION, RcHeaderValue.StringValue("")),
          RcHeaderProperty(HEADER_CAPABILITIES, RcHeaderValue.IntValue(512)),
          RcHeaderProperty(HEADER_PROFILE, RcHeaderValue.IntValue(PROFILE_ANDROIDX)),
        ),
      // No legacy width/height: a modern header does not serialize them, so setting them here
      // would only make the model disagree with its own bytes on the way back in.
      modern = true,
    )

  private const val HEADER_CONTENT_DESCRIPTION = 9
  private const val HEADER_CAPABILITIES = 14
  private const val HEADER_PROFILE = 27
  private const val PROFILE_ANDROIDX = 2

  private fun background(width: Int, height: Int): List<RcOperation> =
    fillPaint(BACKGROUND) +
      RcDraw4(
        RcOpcodes.DRAW_RECT,
        literal(0f),
        literal(0f),
        literal(width.toFloat()),
        literal(height.toFloat()),
      )

  private fun canvasComponent(
    componentId: Int,
    canvasWidth: Int,
    canvasHeight: Int,
    operations: List<RcOperation>,
  ): List<RcOperation> =
    listOf(
      RcCanvasLayout(componentId, 0),
      width(canvasWidth.toFloat()),
      height(canvasHeight.toFloat()),
      RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
    ) + operations + listOf(END, END)

  private fun drawText(textId: Int, x: Float, y: Float) =
    RcDrawText(
      textId = textId,
      start = 0,
      end = -1,
      contextStart = 0,
      contextEnd = -1,
      x = literal(x),
      y = literal(y),
      rtl = false,
    )

  private fun line(x1: RcFloatWord, y1: RcFloatWord, x2: RcFloatWord, y2: RcFloatWord) =
    RcDraw4(RcOpcodes.DRAW_LINE, x1, y1, x2, y2)

  private fun width(value: Float) =
    RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(value))

  private fun height(value: Float) =
    RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(value))

  private fun fillWidth() = RcWidthModifier(RcDimensionType.FILL, literal(1f))

  private fun fillHeight() = RcHeightModifier(RcDimensionType.FILL, literal(1f))

  private fun literal(value: Float) = RcFloatWord.literal(value)

  /** A NaN-boxed reference to the float with [id] — how the wire names a dynamic value. */
  private fun reference(id: Int) = RcFloatWord(0x7fc00000 or id)

  private fun solidBackground(argb: Int) =
    RcBackgroundModifier(
      flags = 0,
      colorId = 0,
      reserved1 = 0,
      reserved2 = 0,
      red = literal(((argb shr 16) and 0xff) / 255f),
      green = literal(((argb shr 8) and 0xff) / 255f),
      blue = literal((argb and 0xff) / 255f),
      alpha = literal(((argb ushr 24) and 0xff) / 255f),
      shapeType = 0,
    )

  // `PaintBundle` field ids, from AndroidX `remote-core`. A field's argument follows it in the word
  // list, except for the flag-shaped ones (STYLE, TYPEFACE) which carry theirs in the command
  // word's
  // upper 16 bits.
  private const val PAINT_TEXT_SIZE = 1
  private const val PAINT_COLOR = 4
  private const val PAINT_STROKE_WIDTH = 5
  private const val PAINT_STYLE = 8
  private const val PAINT_TYPEFACE = 16
  private const val PAINT_STYLE_FILL = 0
  private const val PAINT_STYLE_STROKE = 1

  private fun fillPaint(argb: Int): List<RcOperation> =
    listOf(RcPaintData(listOf(PAINT_COLOR, argb, PAINT_STYLE or (PAINT_STYLE_FILL shl 16))))

  private fun strokePaint(argb: Int, widthPx: Float): List<RcOperation> =
    listOf(
      RcPaintData(
        listOf(
          PAINT_COLOR,
          argb,
          PAINT_STYLE or (PAINT_STYLE_STROKE shl 16),
          PAINT_STROKE_WIDTH,
          widthPx.toRawBits(),
        )
      )
    )

  private fun labelPaint(argb: Int, sizePx: Float): List<RcOperation> =
    textPaint(argb, sizePx, weight = 400)

  /**
   * Fill paint, text size and typeface in one bundle.
   *
   * The typeface word is `TYPEFACE | (style << 16)` followed by the built-in family id, where
   * `style` is the weight in its low ten bits and italic at `0x800` — the encoding
   * `PaintBundle.setTextStyle` writes and every lane's `applyPaint` decodes. Weight travels here
   * rather than as a `wght` font axis on purpose: the axis path is only implemented for canvas text
   * on some lanes, and a fixture whose *point* is to be comparable across all five must not use an
   * operation two of them decline.
   */
  private fun textPaint(
    argb: Int,
    sizePx: Float,
    weight: Int,
    italic: Boolean = false,
    fontFamilyId: Int = 0,
  ): List<RcOperation> {
    val style = (weight and 0x3ff) or (if (italic) 0x800 else 0)
    return listOf(
      RcPaintData(
        listOf(
          PAINT_COLOR,
          argb,
          PAINT_STYLE or (PAINT_STYLE_FILL shl 16),
          PAINT_TEXT_SIZE,
          sizePx.toRawBits(),
          PAINT_TYPEFACE or (style shl 16),
          fontFamilyId,
        )
      )
    )
  }
}

/** One renderable fixture: a document, the size to play it at, and what it is for. */
public data class RcTextMetricFixture(
  val id: String,
  val width: Int,
  val height: Int,
  val summary: String,
  val document: RcDocument,
)
