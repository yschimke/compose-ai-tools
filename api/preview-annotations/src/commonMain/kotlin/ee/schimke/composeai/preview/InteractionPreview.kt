package ee.schimke.composeai.preview

/**
 * Opts a `@Preview` composable into **interaction capture** — a short, deterministic recording of a
 * real pointer gesture driven against the composition, published as an animated APNG.
 *
 * The problem it solves is narrow and specific: a static sticker cannot show motion, and a lot of
 * what a modern design system *is* lives in the motion. Material 3 Expressive is the live example —
 * its selection indicators travel on spatial springs, its containers morph between shapes on press,
 * its switches resolve through `FastSpatial`. A PNG of a navigation bar shows where the indicator
 * ended up; it says nothing about the spring that carried it there, which is the part Expressive
 * changed. The interaction is the documentation.
 *
 * Discovery picks this up by FQN, exactly like [AnimatedPreview] / [ScrollingPreview] /
 * [FocusedPreview]: consumers depend on `ee.schimke.composeai:preview-annotations`. The capture
 * rides the ordinary render lane — no daemon, no recording server, no second pipeline — because the
 * renderer's test harness already has both halves of what a scripted interaction needs: a *pausable
 * clock* (what [AnimatedPreview] drives) and *real pointer injection* (what
 * [FocusedPreview.pressed] dispatches). This annotation is those two capabilities pointed at each
 * other.
 *
 * ```kotlin
 * @CatalogComponent(id = "NavigationBar/Short", caption = "The expressive compact bar.")
 * @InteractionPreview(
 *   targets = [2, 0, 4],
 *   caption = "Tap between distant destinations — the selection indicator rides Expressive's " +
 *     "spatial spring, so the further the travel the more visible the response.",
 * )
 * @CatalogModes412
 * @Composable
 * fun ShortNavigationBarSticker() = Sticker { … }
 * ```
 *
 * ### Sibling to `@AnimatedPreview`, not a replacement for it
 *
 * The two are the pointer-driven and self-driven halves of the same idea, and both publish into a
 * catalog component's **Motion** section:
 *
 * | Annotation            | Time advances | Pointer input | Documents                             |
 * |-----------------------|---------------|---------------|---------------------------------------|
 * | [AnimatedPreview]     | yes           | none          | motion the component runs *by itself* |
 * | `@InteractionPreview` | yes           | scripted      | motion a *user* provokes              |
 *
 * A progress indicator spins on its own — that's [AnimatedPreview]. A switch only moves because
 * someone flipped it — that's this. Reach for the one that matches why the pixels move.
 *
 * ### Targeting: indices into the preview's clickable nodes
 *
 * [targets] are zero-based indices into the composable's clickable nodes **in layout order** —
 * index 2 of a five-destination navigation bar is its third destination. The renderer resolves them
 * against the live semantics tree at capture time and dispatches to each node's centre, so the
 * script survives a component growing padding, changing size, or being re-themed. It is
 * deliberately *not* pixel coordinates (which every density and breakpoint would invalidate) and
 * deliberately not label text (which every one of this repo's 17 locales would invalidate).
 *
 * A target index that resolves to nothing fails the capture loudly rather than recording a gesture
 * that landed on empty space — a recording of nothing happening is indistinguishable from a
 * component that doesn't respond, and the whole point of the artifact is to answer that question.
 *
 * ### There is no `Toggle` gesture, on purpose
 *
 * "Toggle it repeatedly" is [InteractionGesture.Tap] with the same target repeated — `targets =
 * [0, 0, 0]` taps one switch three times. A separate gesture would need its own repeat count, which
 * is the exact thing repeating the target already says, and two ways to spell one script is how the
 * two spellings end up disagreeing.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class InteractionPreview(
  /** The gesture dispatched at each entry of [targets]. See [InteractionGesture]. */
  val gesture: InteractionGesture = InteractionGesture.Tap,
  /**
   * Zero-based indices into the preview's clickable nodes, in layout order — one gesture per entry,
   * dispatched in the order given. Repeating an index repeats the gesture on that node, which is
   * how a toggle is spelled (`[0, 0, 0]`).
   *
   * Defaults to `[0]`: the single-control case (a switch, an icon button) needs no argument.
   */
  val targets: IntArray = [0],
  /**
   * One line describing what the reader should watch for, shown under the capture in the catalog's
   * Motion section.
   *
   * Worth writing carefully. A caption that restates the gesture ("taps the button") tells a reader
   * what they can already see; the useful caption names the *property* the motion demonstrates —
   * which spring, which shape transition, what it looks like when the theme doesn't have it.
   */
  val caption: String = "",
  /**
   * Milliseconds a [InteractionGesture.PressAndHold] holds the pointer down before releasing.
   * Ignored by [InteractionGesture.Tap], whose press is momentary.
   *
   * The default is long enough to clear Compose's long-press threshold, so a component that
   * distinguishes a hold from a tap shows the hold branch.
   */
  val holdMs: Int = 600,
  /**
   * Milliseconds of settle time after each gesture before the next one is dispatched. This is the
   * window the animation actually plays in, so it wants to outlast the motion being documented — a
   * spatial spring that overshoots and returns needs longer than the tween it replaced.
   */
  val gapMs: Int = 700,
  /**
   * Milliseconds of resting frames captured before the first gesture, so a looping playback opens
   * on the component at rest rather than mid-motion.
   */
  val leadInMs: Int = 250,
  /**
   * Per-frame interval in milliseconds. Default 16 ≈ 60fps — see [MotionFormat.Apng] for why that
   * figure is exactly representable here and is not in a GIF.
   */
  val frameIntervalMs: Int = DEFAULT_MOTION_FRAME_INTERVAL_MS,
  /**
   * Container format for the capture. Defaults to [MotionFormat.Apng]; see that value's KDoc for
   * why an interaction capture in particular wants it.
   */
  val format: MotionFormat = MotionFormat.Apng,
)

/** The gesture an [InteractionPreview] dispatches at each of its targets. */
enum class InteractionGesture {
  /**
   * Press and release, momentarily — an ordinary click. Drives selection changes, toggles, and any
   * state transition whose animation begins on release.
   */
  Tap,
  /**
   * Press, hold for `holdMs`, release. Documents the *pressed* state itself — Material 3
   * Expressive's containers morph into a pressed shape and stay there for as long as the finger is
   * down, which a momentary [Tap] passes through too fast to show.
   */
  PressAndHold,
}

/** Container format for a motion capture ([InteractionPreview] / [AnimatedPreview]). */
enum class MotionFormat {
  /**
   * Animated PNG. Full 8-bit colour and alpha, plays in a plain `<img>`, no video plumbing.
   *
   * Two properties make it the right default for a motion capture, and both are things GIF cannot
   * do rather than things it does worse:
   * * **Exact frame timing.** A GIF frame delay is an integer count of 1/100 s, so 60fps (16.67ms)
   *   is not representable — it quantises to 20ms, and unevenly. An APNG delay is a rational
   *   `num/den`, so `1/60` is exact and a 60fps capture plays at 60fps.
   * * **Full colour.** GIF's 256-entry palette bands precisely the things these captures exist to
   *   show: state-layer fades, ripple gradients, and the anti-aliased edge of a shape mid-morph.
   *
   * The cost is file size, which for a one-second component capture is the cheap side of the trade.
   */
  Apng,
  /**
   * Animated GIF. Wider reach in old tooling and the historical output of [AnimatedPreview], kept
   * so existing captures keep their bytes and their paths. Subject to both limits described on
   * [Apng] — prefer [Apng] for anything documenting colour or sub-20ms timing.
   */
  Gif,
}

/**
 * Default per-frame interval for a motion capture: 16ms ≈ 60fps.
 *
 * Higher than [DEFAULT_ANIMATION_FRAME_INTERVAL_MS] (33ms ≈ 30fps, which [AnimatedPreview] keeps
 * for backwards compatibility) because a captured file is the one place smooth playback is actually
 * achievable — a held live session renders on demand and drops frames under load, which is exactly
 * the jank a baked capture exists to replace.
 */
const val DEFAULT_MOTION_FRAME_INTERVAL_MS: Int = 16
