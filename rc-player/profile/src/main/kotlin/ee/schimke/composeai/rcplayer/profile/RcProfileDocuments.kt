package ee.schimke.composeai.rcplayer.profile

import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcClickModifier
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDraw3
import ee.schimke.composeai.rcplayer.protocol.RcDraw4
import ee.schimke.composeai.rcplayer.protocol.RcDrawText
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcRippleModifier
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRoundedClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcTimeAttribute
import ee.schimke.composeai.rcplayer.protocol.RcTimeAttributeType
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.runtime.RcFloatExpressionEvaluator

/**
 * The four reference documents the profile measures.
 *
 * They are deliberately *small and typical* rather than a stress test. The question a profile of
 * this player has to answer first is "where does the time go for the shapes of document people
 * actually ship" — a static control, a drawn canvas, that canvas animating, and a control that
 * responds to touch. A synthetic ten-thousand-operation document would move every number without
 * telling you which phase to look at.
 *
 * Each is built as an [RcDocument] model and then encoded to wire bytes by the caller, so the
 * profile exercises the real `RcDocumentCodec.decode` path a hosted document takes rather than
 * skipping straight to the linker.
 */
public object RcProfileDocuments {
  public const val WIDTH: Int = 320
  public const val HEIGHT: Int = 180

  private val END = RcNoArg(RcOpcodes.CONTAINER_END)

  private const val SURFACE = 0xfff6f2ff.toInt()
  private const val ACCENT = 0xff6750a4.toInt()
  private const val ON_ACCENT = 0xffffffff.toInt()
  private const val HIGHLIGHT = 0xffffd8e4.toInt()

  /** Float ids. Kept well clear of the reserved low ids AndroidX assigns to built-ins. */
  private const val ID_ELAPSED_SECONDS = 200
  private const val ID_SWEEP_X = 201
  private const val ID_PRESSED_RADIUS = 202

  /**
   * **Static button with text.** A rounded, filled box with a centred text child — the layout-tree
   * path, no canvas operations at all. This is the document that isolates text: measurement and
   * shaping happen inside Compose's own `TextMeasurer`, and everything else here is trivial.
   */
  public fun staticButtonWithText(): RcDocument =
    RcDocument(
      header(),
      listOf(
        RcTextData(10, "Continue"),
        RcRootLayout(1),
        RcLayoutContent(2),
        RcBoxLayout(
          componentId = 3,
          animationId = 0,
          horizontalPositioning = 2,
          verticalPositioning = 2,
        ),
        width(WIDTH.toFloat()),
        height(HEIGHT.toFloat()),
        RcLayoutContent(4),
        RcBoxLayout(
          componentId = 5,
          animationId = 0,
          horizontalPositioning = 2,
          verticalPositioning = 2,
        ),
        width(200f),
        height(64f),
        roundedClip(32f),
        solidBackground(ACCENT),
        RcLayoutContent(6),
        text(componentId = 7, textId = 10, color = ON_ACCENT, sizeSp = 20f),
        END, // text
        END, // content 6
        END, // box 5
        END, // content 4
        END, // box 3
        END, // content 2
        END, // root 1
      ),
    )

  /**
   * **Static canvas.** The same frame drawn entirely with canvas operations — background fill, an
   * accent plate, a circle and two strokes.
   *
   * There is deliberately no layout root. A document with no `RootLayoutComponent` takes the
   * player's *raw* draw path: one `rc:drawRoot` pass straight down the operation list, with no
   * component tree to build and no per-node canvases. That is both what a captured
   * watch-face-shaped document looks like and the cleanest measurement of paint cost on its own.
   */
  public fun staticCanvas(): RcDocument = RcDocument(header(), staticCanvasOperations())

  /**
   * **Animated canvas.** [staticCanvas] plus a document-load clock driving a swept highlight.
   *
   * `TimeAttribute(FromDocumentLoadSeconds)` is what makes this document *continuously* animated:
   * the player checks for exactly that operation (and marquee, and animated float expressions) to
   * decide whether to keep requesting frames. This is therefore the one scenario that measures the
   * *steady-state* per-frame cost — the three static ones paint once and then stop, because Compose
   * has nothing to invalidate, which is itself worth seeing in the counts.
   */
  public fun animatedCanvas(): RcDocument =
    RcDocument(
      header(),
      // Both operations live in the drawn stream, not above a layout root: the player applies
      // `TimeAttribute` and `FloatExpression` as it walks the operations it is painting, so an
      // operation parked outside the painted tree is simply never evaluated.
      listOf(
        // Seconds since the document was loaded. Its presence is also what tells the player to keep
        // requesting frames — the same check covers marquee and animated float expressions.
        RcTimeAttribute(
          outId = ID_ELAPSED_SECONDS,
          timeId = 0,
          type = RcTimeAttributeType.FromDocumentLoadSeconds,
        ),
        // sweepX = 40 + ((elapsed * 90) % 240) — a highlight crossing the plate every ~2.7s.
        RcFloatExpression(
          id = ID_SWEEP_X,
          expression =
            listOf(
              RcFloatWord.literal(40f),
              reference(ID_ELAPSED_SECONDS),
              RcFloatWord.literal(90f),
              operator(MULTIPLY),
              RcFloatWord.literal(240f),
              operator(MODULO),
              operator(ADD),
            ),
          animation = null,
        ),
      ) +
        staticCanvasOperations() +
        listOf(
          // An explicit fill: paint state carries across draw operations, and the operation before
          // this one left the paint stroking.
          fillPaint(ON_ACCENT),
          RcDraw3(
            RcOpcodes.DRAW_CIRCLE,
            reference(ID_SWEEP_X),
            RcFloatWord.literal(90f),
            RcFloatWord.literal(14f),
          ),
        ),
    )

  /**
   * **Button with interactions.** The static button, made clickable: a ripple, a click modifier
   * whose action block fires a host action and mutates a float the canvas below reads back.
   *
   * The float write is what makes this more than a click counter — it forces the invalidation the
   * player uses to redraw after an action, so the profile sees the input-to-repaint cost and not
   * just the hit test.
   */
  public fun interactiveButton(): RcDocument =
    RcDocument(
      header(),
      listOf(
        RcTextData(10, "Continue"),
        // Radius of the confirmation dot, rewritten by the click action below. A constant rather
        // than an expression on purpose: an expression is re-evaluated during every paint and would
        // put the radius straight back to 0 on the frame after the click.
        RcFloatConstant(ID_PRESSED_RADIUS, RcFloatWord.literal(0f)),
        RcRootLayout(1),
        RcLayoutContent(2),
        RcBoxLayout(
          componentId = 3,
          animationId = 0,
          horizontalPositioning = 2,
          verticalPositioning = 2,
        ),
        width(WIDTH.toFloat()),
        height(HEIGHT.toFloat()),
        RcLayoutContent(4),
        RcBoxLayout(
          componentId = 5,
          animationId = 0,
          horizontalPositioning = 2,
          verticalPositioning = 2,
        ),
        width(200f),
        height(64f),
        roundedClip(32f),
        solidBackground(ACCENT),
        RcRippleModifier,
        RcClickModifier,
        RcHostAction(90),
        RcValueFloatChangeAction(ID_PRESSED_RADIUS, RcFloatWord.literal(12f)),
        END, // click modifier
        RcLayoutContent(6),
        text(componentId = 7, textId = 10, color = ON_ACCENT, sizeSp = 20f),
        END, // text
        END, // content 6
        END, // box 5
      ) +
        // A canvas sibling *inside the same content container* as the button, reading the float the
        // click writes. Nesting matters: a component parked directly under the Box rather than
        // under
        // its `LayoutContent` is not part of the drawn tree and would silently never paint.
        canvasComponent(
          componentId = 8,
          operations =
            listOf(
              fillPaint(HIGHLIGHT),
              RcDraw3(
                RcOpcodes.DRAW_CIRCLE,
                RcFloatWord.literal(160f),
                RcFloatWord.literal(152f),
                reference(ID_PRESSED_RADIUS),
              ),
            ),
        ) +
        listOf(
          END, // content 4
          END, // box 3
          END, // content 2
          END, // root 1
        ),
    )

  /**
   * Background fill, accent plate, a circle, two strokes and a drawn text run — the shared canvas
   * body.
   *
   * The text run is here rather than in the button document on purpose. Canvas-drawn text
   * (`DrawTextRun`) is the path the player measures itself, and therefore the only one
   * `rc:measureText` can see; the layout-tree button hands its label to Compose's `BasicText`,
   * which owns measurement and shaping and opens no span the player could nest inside.
   */
  private fun staticCanvasOperations(): List<RcOperation> =
    listOf(
      RcTextData(20, "REMOTE"),
      fillPaint(SURFACE),
      rect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
      fillPaint(ACCENT),
      rect(36f, 34f, 284f, 146f),
      fillPaint(HIGHLIGHT),
      RcDraw3(
        RcOpcodes.DRAW_CIRCLE,
        RcFloatWord.literal(160f),
        RcFloatWord.literal(90f),
        RcFloatWord.literal(38f),
      ),
      strokePaint(ON_ACCENT, widthPx = 6f),
      RcDraw4(
        RcOpcodes.DRAW_LINE,
        RcFloatWord.literal(112f),
        RcFloatWord.literal(90f),
        RcFloatWord.literal(208f),
        RcFloatWord.literal(90f),
      ),
      RcDraw4(
        RcOpcodes.DRAW_LINE,
        RcFloatWord.literal(160f),
        RcFloatWord.literal(42f),
        RcFloatWord.literal(160f),
        RcFloatWord.literal(138f),
      ),
      textPaint(ON_ACCENT, sizePx = 18f),
      RcDrawText(
        textId = 20,
        start = 0,
        end = -1,
        contextStart = 0,
        contextEnd = -1,
        // `y` is the baseline, and the run has to sit on the accent plate (34..146) — white text on
        // the light surface below it would render but be invisible in the captured PNG.
        x = RcFloatWord.literal(48f),
        y = RcFloatWord.literal(136f),
        rtl = false,
      ),
    )

  private fun canvasComponent(componentId: Int, operations: List<RcOperation>): List<RcOperation> =
    listOf(
      RcCanvasLayout(componentId, 0),
      width(WIDTH.toFloat()),
      height(HEIGHT.toFloat()),
      RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
    ) + operations + listOf(END, END)

  private fun header(): RcHeader =
    RcHeader(RcVersion(1, 0, 0), legacyWidth = WIDTH, legacyHeight = HEIGHT, modern = false)

  private fun width(value: Float) =
    RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(value))

  private fun height(value: Float) =
    RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(value))

  private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
    RcDraw4(
      RcOpcodes.DRAW_RECT,
      RcFloatWord.literal(left),
      RcFloatWord.literal(top),
      RcFloatWord.literal(right),
      RcFloatWord.literal(bottom),
    )

  private fun text(componentId: Int, textId: Int, color: Int, sizeSp: Float) =
    RcTextLayout(
      componentId = componentId,
      animationId = 0,
      textId = textId,
      color = color,
      fontSize = RcFloatWord.literal(sizeSp),
      fontStyle = 0,
      fontWeight = RcFloatWord.literal(500f),
      fontFamilyId = -1,
      textAlignAndFlags = RcTextLayout.ALIGN_CENTER,
      overflow = RcTextLayout.OVERFLOW_CLIP,
      maxLines = 1,
    )

  private fun solidBackground(argb: Int) =
    RcBackgroundModifier(
      flags = 0,
      colorId = 0,
      reserved1 = 0,
      reserved2 = 0,
      red = RcFloatWord.literal(((argb shr 16) and 0xff) / 255f),
      green = RcFloatWord.literal(((argb shr 8) and 0xff) / 255f),
      blue = RcFloatWord.literal((argb and 0xff) / 255f),
      alpha = RcFloatWord.literal(((argb ushr 24) and 0xff) / 255f),
      shapeType = 0,
    )

  /** A NaN-boxed reference to the float with [id] — how the wire names a dynamic value. */
  private fun reference(id: Int) = RcFloatWord(0x7fc00000 or id)

  private fun operator(code: Int) = RcFloatExpressionEvaluator.operatorWord(code)

  // `PaintBundle` field ids, from AndroidX `remote-core` — the same numbers `applyPaint` in the
  // player switches on. A field's argument follows it in the word list, except for the flag-shaped
  // ones (STYLE) which carry their value in the command word's upper 16 bits.
  private const val PAINT_TEXT_SIZE = 1
  private const val PAINT_COLOR = 4
  private const val PAINT_STROKE_WIDTH = 5
  private const val PAINT_STYLE = 8
  private const val PAINT_STYLE_FILL = 0
  private const val PAINT_STYLE_STROKE = 1

  private fun fillPaint(argb: Int) =
    RcPaintData(listOf(PAINT_COLOR, argb, PAINT_STYLE or (PAINT_STYLE_FILL shl 16)))

  private fun textPaint(argb: Int, sizePx: Float) =
    RcPaintData(
      listOf(
        PAINT_COLOR,
        argb,
        PAINT_STYLE or (PAINT_STYLE_FILL shl 16),
        PAINT_TEXT_SIZE,
        sizePx.toRawBits(),
      )
    )

  private fun strokePaint(argb: Int, widthPx: Float) =
    RcPaintData(
      listOf(
        PAINT_COLOR,
        argb,
        PAINT_STYLE or (PAINT_STYLE_STROKE shl 16),
        PAINT_STROKE_WIDTH,
        widthPx.toRawBits(),
      )
    )

  private fun roundedClip(radius: Float) =
    RcRoundedClipRectModifier(
      topStart = RcFloatWord.literal(radius),
      topEnd = RcFloatWord.literal(radius),
      bottomStart = RcFloatWord.literal(radius),
      bottomEnd = RcFloatWord.literal(radius),
    )

  private val ADD = RcFloatExpressionEvaluator.OFFSET + 1
  private val MULTIPLY = RcFloatExpressionEvaluator.OFFSET + 3
  private val MODULO = RcFloatExpressionEvaluator.OFFSET + 5
}
