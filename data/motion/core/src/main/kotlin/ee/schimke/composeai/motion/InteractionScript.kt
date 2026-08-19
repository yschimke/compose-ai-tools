package ee.schimke.composeai.motion

/**
 * The gesture an `@InteractionPreview` dispatches at each of its targets. Mirrors
 * `ee.schimke.composeai.preview.InteractionGesture`, duplicated here for the same reason every
 * other annotation mirror is: this module is pure JVM and must not pull the annotation artifact
 * (and Compose) onto a renderer's classpath just to name two constants.
 */
enum class MotionGesture {
  TAP,
  PRESS_AND_HOLD,
}

/** One pointer transition on the virtual timeline: press or release, at [atMs], on [target]. */
data class InteractionPointerEvent(val atMs: Int, val target: Int, val down: Boolean)

/**
 * The expanded script: what to dispatch when, and how long to keep capturing.
 *
 * [durationMs] is what the script asks for; [cappedDurationMs] is what a renderer records. Both are
 * carried so a backend can log the difference rather than silently shortening a long script.
 */
data class InteractionTimeline(val events: List<InteractionPointerEvent>, val durationMs: Int) {
  /** [durationMs], bounded by [MAX_INTERACTION_DURATION_MS] — the window a renderer records. */
  val cappedDurationMs: Int
    get() = durationMs.coerceAtMost(MAX_INTERACTION_DURATION_MS)
}

/**
 * Expands an `@InteractionPreview` script into an ordered pointer-event list plus the window to
 * capture, identically on every backend.
 *
 * ### Why this is shared rather than derived per renderer
 *
 * The recording window is a *derivation*, not a constant. The annotation carries `leadInMs` /
 * `holdMs` / `gapMs` precisely so that nobody has to state a duration twice — the window is the
 * lead-in plus, per target, one press and one settle window. Two backends deriving that separately
 * would reintroduce exactly the duplication the annotation removed, and would reintroduce it
 * silently: a recording cut short mid-gesture is a plausible-looking file, not an error, so a
 * disagreement between the desktop and Robolectric expansions would surface as one backend's
 * catalog quietly losing the end of every spring.
 */
object InteractionScript {

  /**
   * The event list and window for one script.
   *
   * The press dwell is [TAP_PRESS_MS] for a [MotionGesture.TAP] and [holdMs] for a
   * [MotionGesture.PRESS_AND_HOLD]; [gapMs] follows each release, and [leadInMs] precedes the first
   * press so a looping playback opens on the component at rest.
   */
  fun timeline(
    gesture: MotionGesture,
    targets: List<Int>,
    holdMs: Int,
    gapMs: Int,
    leadInMs: Int,
  ): InteractionTimeline {
    val pressMs = if (gesture == MotionGesture.PRESS_AND_HOLD) holdMs else TAP_PRESS_MS
    val events = mutableListOf<InteractionPointerEvent>()
    var cursor = leadInMs
    for (target in targets) {
      events += InteractionPointerEvent(atMs = cursor, target = target, down = true)
      events += InteractionPointerEvent(atMs = cursor + pressMs, target = target, down = false)
      cursor += pressMs + gapMs
    }
    return InteractionTimeline(events = events, durationMs = cursor)
  }
}

/**
 * Pointer-down dwell for a [MotionGesture.TAP], in ms.
 *
 * Long enough that the press is a real, observable state — Compose's ripple and Material's state
 * layer both start on `down`, and a `down`/`up` inside one frame would document a component that
 * changed state without ever appearing to be touched. Short enough to stay a tap: it sits well
 * under the long-press threshold, so a component that distinguishes the two takes the tap branch.
 */
const val TAP_PRESS_MS: Int = 90

/**
 * Hard cap on a captured interaction window. Higher than the animation path's 5s because an
 * interaction is inherently a sequence — five taps with a settle window each is legitimately longer
 * than any single animation — but still bounded, since every frame is a full-size PNG in the
 * output.
 */
const val MAX_INTERACTION_DURATION_MS: Int = 10_000

/**
 * The APNG frame delay for a capture authored at [frameIntervalMs] milliseconds per frame, as the
 * exact `numerator / denominator` fraction of a second that APNG stores.
 *
 * The canonical frame rates are snapped to their exact rational form rather than being carried as
 * `ms/1000`, because the millisecond is an *authoring* unit and the rate is what the reader sees.
 * 60fps is the case that forces this: it is 16.67ms, which no integer number of milliseconds names,
 * so a literal `16/1000` plays at 62.5fps and `17/1000` at 58.8fps. `1/60` is what the author meant
 * and what APNG can hold — and holding it is the reason a 60fps capture is worth having here at
 * all, since a GIF's 1/100s delay quantisation cannot express any of these rates.
 *
 * Anything else is carried literally as `ms/1000`, which is exact for every rate a millisecond can
 * name.
 */
fun apngDelayFor(frameIntervalMs: Int): Pair<Short, Short> =
  when (frameIntervalMs) {
    16,
    17 -> 1.toShort() to 60.toShort() // 60fps
    20 -> 1.toShort() to 50.toShort() // 50fps
    33,
    34 -> 1.toShort() to 30.toShort() // 30fps
    40 -> 1.toShort() to 25.toShort() // 25fps
    else -> frameIntervalMs.toShort() to 1000.toShort()
  }
