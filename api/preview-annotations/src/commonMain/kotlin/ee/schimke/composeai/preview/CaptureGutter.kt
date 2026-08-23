package ee.schimke.composeai.preview

/**
 * Extends a `@Preview`'s **capture bounds** by a margin of transparent dp, without changing what
 * the composable measures.
 *
 * A wrapped capture is cropped to the composable's measured size, so anything the component draws
 * *outside* its own bounds is cut off at the edge of the image: an elevation shadow, a focus ring,
 * a badge that overhangs its anchor. The usual workaround is to pad the preview body —
 * `Box(Modifier.padding(4.dp)) { ElevatedButton(…) }` — and it works, but it pays for the shadow in
 * the wrong currency. The padding is *inside* the bounds, so the component now measures in a
 * smaller box, the render's canvas grows by the gutter, and every consumer that fits a render to a
 * column scales the component down to make room for margin it cannot see. On a sticker sheet that
 * lays five emphases of one button side by side, the one that pads for its shadow draws ~7% smaller
 * than its four siblings for a reason that has nothing to do with the design
 * ([m3-catalog#179](https://github.com/yschimke/m3-catalog/issues/179)).
 *
 * This moves the gutter out of the component tree and into the capture. The renderer enlarges the
 * scene, measures the composable against its **original** constraints, and places it inset by the
 * gutter — so the shadow has room, the component's own measured size is byte-identical to what it
 * was without the annotation, and the gutter travels in `previews.json` as a declared fact rather
 * than as anonymous transparent pixels a consumer has to guess at.
 *
 * ```kotlin
 * @CaptureGutter(all = 4, bottom = 5)   // Level 1 shadow, offset downward
 * @Preview
 * @Composable fun ElevatedButtonSticker() = Sticker { ElevatedButton(onClick = {}) { … } }
 * ```
 *
 * ### Where to put it
 *
 * `@Target` includes [AnnotationTarget.ANNOTATION_CLASS], so a catalog whose stickers share one
 * shadow level can declare the gutter once on its own multi-preview annotation — ClassGraph
 * flattens a method's meta-annotation closure, so a hoisted gutter reaches every `@Preview` the
 * annotation expands to:
 * ```kotlin
 * @CaptureGutter(all = 4, bottom = 5)
 * @Preview(name = "Light", group = "modes")
 * @Preview(name = "Dark", group = "modes", uiMode = UI_MODE_NIGHT_YES)
 * annotation class ElevatedStickerPreview
 * ```
 *
 * ### What it is not
 *
 * Not decorative padding, and not a substitute for a `@Preview(widthDp = …)` frame. A gutter says
 * "the component draws this far past its bounds"; it is measured off the design's own shadow / ring
 * spec, and a consumer is entitled to treat the inner box — the canvas minus the gutter — as the
 * component. Padding chosen to make a sticker sheet breathe belongs to the sheet, outside the
 * capture, where it doesn't move anybody's render bounds.
 *
 * ### What it reaches
 *
 * Every render lane — Android (Robolectric) and CMP Desktop, batch and live daemon alike — grows
 * the canvas by the same dp, so the published bounds never differ by lane and a preview does not
 * change size when a viewer toggles PNG↔Live. It applies to:
 * * a preview's **still** capture, a `@FocusedPreview` still included;
 * * its **motion products** — an `@AnimatedPreview` GIF, an `@InteractionPreview` recording. Every
 *   frame of a recording shares one canvas, exactly as a still does, so a gutter is as well-defined
 *   there, and a component that declares one publishes its still and its recording on the same
 *   canvas rather than two artefacts disagreeing about its bounds.
 *
 * It is **not meant to apply** to a `@ScrollingPreview` capture, and that is a decision rather than
 * a gap. A LONG capture's bounds are the stitched scroll extent — many viewports of a list, not a
 * component — and a scroll GIF's are the declared viewport. Neither is "the component plus what it
 * draws outside itself", so there is no edge for a gutter to sit on, and adding transparent dp
 * around a scroll strip would be decorative padding wearing this annotation's name. A scroll drive
 * that declines falls through to an ordinary still, which does carry the gutter.
 *
 * The CMP Desktop lane implements that exclusion directly — its scroll renderer takes no gutter.
 * The **Android** lane does not yet: it grows one hosting window per preview and measures every
 * capture of that preview in it, so a scrolling product of a preview that also declares a gutter
 * inherits it. Two smaller Android caveats sit alongside it: a fixed-axis motion product is trimmed
 * to the hosting window rather than to `frame + gutter` in pixels, so at a fractional density it
 * can differ from the still by a pixel; and a held desktop recording of a **wrapped** preview is
 * sized from the sandbox bound rather than the measured content, which predates gutters entirely.
 * All three are tracked in compose-ai-tools#4467 — read them as the current limits of the contract
 * above, not as licence to rely on them.
 *
 * A **rotated** capture (`orientation = landscape`, which the daemon reduces to a width↔height
 * swap) keeps the declared edges verbatim: a gutter edge names a direction the component draws in —
 * [bottom] is deepest because Material's shadows fall downward — and swapping the frame's axes does
 * not turn the component over.
 *
 * ### Fixed axes grow too
 *
 * Both kinds of axis gain the gutter, and for the same reason: the promise is that the component
 * measures exactly what it measured before. A wrapped axis wraps the component and the canvas comes
 * out `measured + gutter`; a fixed axis (`widthDp` / `heightDp` / a device) still measures the
 * component against its declared frame, and the canvas comes out `frame + gutter`. So
 * `@Preview(widthDp = 360)` plus a 4dp gutter renders 368dp wide with a 360dp component in it —
 * which is what a catalog that hand-rolled the gutter was already doing by declaring a 368dp frame,
 * only now the arithmetic is in one place and says what it is for.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@MustBeDocumented
annotation class CaptureGutter(
  /**
   * Gutter applied to every edge, in dp. Each per-edge value below overrides it; leaving them
   * [INHERIT_GUTTER] (the default) keeps this value on that edge.
   *
   * Negative values are clamped to `0` (no gutter). `0` on every edge is the same as not annotating
   * at all — discovery drops it rather than recording an empty gutter.
   */
  val all: Int = 0,
  /** Leading-edge gutter in dp, resolved against the render's layout direction. */
  val start: Int = INHERIT_GUTTER,
  /** Top-edge gutter in dp. */
  val top: Int = INHERIT_GUTTER,
  /** Trailing-edge gutter in dp, resolved against the render's layout direction. */
  val end: Int = INHERIT_GUTTER,
  /**
   * Bottom-edge gutter in dp. Usually the largest of the four: Material's elevation shadows are
   * offset downward, so a symmetric gutter crops the bottom of a shadow it clears everywhere else.
   */
  val bottom: Int = INHERIT_GUTTER,
)

/**
 * Sentinel per-edge [CaptureGutter] value meaning "take [CaptureGutter.all]". Negative so it can
 * never collide with a real gutter, which is a distance and cannot be.
 */
const val INHERIT_GUTTER: Int = -1

/**
 * Hard ceiling on any single edge of a [CaptureGutter], in dp. A gutter is room for a shadow, not a
 * layout: past this it is asking for a framed canvas, which is what `@Preview(widthDp = …)` is for.
 * Clamped at discovery so a typo can't blow up a scene on one backend and be clamped differently on
 * the other.
 */
const val MAX_CAPTURE_GUTTER_DP: Int = 64
