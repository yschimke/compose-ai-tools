package ee.schimke.composeai.preview

/**
 * Settles a `@Preview` before its **still** capture: the renderer advances the paused clock until
 * the composable has finished arriving, then captures one frame.
 *
 * A component whose content is driven in by time rather than by a gesture — a `LaunchedEffect {
 * delay(…); animateTo(…) }` reveal, a field whose value is written after first composition, a
 * `updateTransition` label that only starts moving once state lands — captures as its *first* frame
 * without this. The published still is then an empty container, or a resting label painted over the
 * value it should have floated above. `@AnimatedPreview` doesn't fix it: it publishes a motion
 * artefact *beside* the same unsettled still.
 *
 * `@ScrollingPreview(END)` is the existing precedent — it settles post-scroll animations because
 * the scroll drive is what starts them. This is the same idea for a reveal that time, not a
 * gesture, drives.
 *
 * ```kotlin
 * @SettledPreview                      // auto: advance until quiescent, up to 1000ms
 * @Preview
 * @Composable fun ConfirmationDialog() = Sticker { ConfirmationDialogContent(…) }
 *
 * @SettledPreview(afterMs = 600)       // exact: advance 600ms, then capture
 * @Preview
 * @Composable fun Snackbar() = Sticker { … }
 * ```
 *
 * ### Where to put it
 *
 * `@Target` includes [AnnotationTarget.ANNOTATION_CLASS], so a catalog whose stickers all wrap
 * stock design-system composables can hoist it **once** onto its own multi-preview annotation
 * rather than hunting for the affected components one at a time:
 * ```kotlin
 * @SettledPreview
 * @Preview(name = "Light", group = "modes")
 * @Preview(name = "Dark", group = "modes", uiMode = UI_MODE_NIGHT_YES)
 * annotation class StickerPreview
 * ```
 *
 * That matters because the animation is usually **internal to the component**: an author writing a
 * sticker that merely calls `DatePicker()` has no way to know a label tween is running inside it.
 *
 * ### What it costs
 *
 * Only still captures are settled — a `@ScrollingPreview` LONG/GIF product, an `@AnimatedPreview`
 * GIF and an `@InteractionPreview` recording all drive the clock themselves and are left alone.
 *
 * Pairing this with a motion product on the **same function** — `@AnimatedPreview`,
 * `@InteractionPreview`, or `@FocusedPreview(gif = true)` — works, and produces both: the motion
 * artefact recorded from the start of the timeline, and a settled still beside it. They cannot
 * share a timeline (the GIF needs it from its start, the settled still needs a coordinate near the
 * end, and virtual time does not rewind), so the renderer composes the preview a second time for
 * the still rather than picking a winner. That second composition is the cost — a paired function
 * renders twice.
 *
 * On a `@FocusedPreview` an [afterMs] below `32` is raised to `32` with a warning: a focused
 * capture spends its first two frames laying out the tree the focus walk searches, so nothing
 * focusable exists before then and a shorter coordinate would mean two different instants on the
 * two backends.
 *
 * Auto mode walks the window in frame-sized steps, so it is proportional to [maxMs]: keep the
 * default unless a reveal genuinely runs longer, and prefer an explicit [afterMs] on a component
 * whose timing you know. An animation that never ends (an `InfiniteTransition`, an indeterminate
 * progress indicator) can't quiesce, so it simply captures at the [maxMs] bound — the annotation
 * belongs on a reveal, not on a spinner.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@MustBeDocumented
annotation class SettledPreview(
  /**
   * Exact virtual-time window to advance before capturing, in milliseconds.
   *
   * [AUTO_SETTLE_MS] (`0`, the default) asks the renderer to advance until the composition is
   * quiescent instead — no pending `delay`, no outstanding invalidation — bounded by [maxMs]. Use a
   * positive value when the reveal's timing is known: it is one advance instead of a walk, and it
   * says in the source what the component is waiting for.
   *
   * Negative values are clamped to `0` (auto).
   *
   * "Exact" is as exact as a 16ms frame clock allows: the renderer lands the clock on this
   * coordinate, and the composition state captured is the last frame at or before it.
   */
  val afterMs: Int = AUTO_SETTLE_MS,
  /**
   * Upper bound on the auto walk, in milliseconds. Ignored when [afterMs] is positive.
   *
   * Clamped to [MAX_SETTLE_MS] so a typo can't hang a render. A reveal that hasn't landed inside
   * this window captures mid-flight, the same as it does today.
   */
  val maxMs: Int = DEFAULT_SETTLE_MAX_MS,
)

/**
 * Sentinel [SettledPreview.afterMs] asking the renderer to advance until the composition quiesces
 * rather than to a fixed coordinate. See [SettledPreview.afterMs].
 */
const val AUTO_SETTLE_MS: Int = 0

/** Default bound on `@SettledPreview`'s auto walk. */
const val DEFAULT_SETTLE_MAX_MS: Int = 1000

/**
 * Hard ceiling on any settle window, explicit or auto. Five seconds of virtual time is already far
 * past every design-system reveal; beyond it a "settle" is really a request for a motion capture.
 */
const val MAX_SETTLE_MS: Int = 5000
