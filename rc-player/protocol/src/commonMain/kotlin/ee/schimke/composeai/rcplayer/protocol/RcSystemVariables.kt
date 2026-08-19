package ee.schimke.composeai.rcplayer.protocol

/**
 * The ids AndroidX's `RemoteContext` reserves for values the **player** supplies, not the document.
 *
 * A document never allocates in this range: writers start their own ids well above it (42 in every
 * catalog this repo publishes), and a `NaN`-boxed word pointing here is a request for something the
 * host knows — the wall clock, the animation clock, the viewport. Because the reference is an
 * ordinary float word, a player that does not load these ids does not fail loudly: the reference
 * resolves to its own raw `NaN` bits and the arithmetic downstream quietly produces `NaN`, so the
 * shape built from it is never drawn. That is exactly how the `remote-m3` catalog's indeterminate
 * circular progress indicator came to render as an empty frame in the CMP/Wasm lane (#4264) while
 * the AndroidX and TypeScript players — both of which load these — animated it.
 *
 * Only the ids this player actually supplies are named here. The sensor, touch and viewport blocks
 * AndroidX also reserves are deliberately absent: naming an id we do not load would suggest a
 * support this player does not have.
 */
public object RcSystemVariables {
  /** Seconds within the hour, fractional — `minute * 60 + second + millis / 1000`. */
  public const val CONTINUOUS_SEC: Int = 1

  /** Seconds within the hour, whole — `minute * 60 + second`. */
  public const val TIME_IN_SEC: Int = 2

  /** Minutes within the day — `hour * 60 + minute`. */
  public const val TIME_IN_MIN: Int = 3

  /** Hour of the day, 0..23. */
  public const val TIME_IN_HR: Int = 4

  /** Calendar month, 1..12. */
  public const val CALENDAR_MONTH: Int = 9

  /** The local zone's offset from UTC, in seconds. */
  public const val OFFSET_TO_UTC: Int = 10

  /** ISO day of week, 1 (Monday)..7 (Sunday). */
  public const val WEEK_DAY: Int = 11

  public const val DAY_OF_MONTH: Int = 12

  /** Seconds since the document was loaded — the player's own animation clock. */
  public const val ANIMATION_TIME: Int = 30

  /** Seconds between this frame and the previous one. */
  public const val ANIMATION_DELTA_TIME: Int = 31

  /** Whole seconds since the Unix epoch, loaded as an integer. */
  public const val EPOCH_SECOND: Int = 32

  public const val DAY_OF_YEAR: Int = 34

  public const val YEAR: Int = 35

  /**
   * The subset whose value moves while a document is on screen, so a reference to one of them means
   * the player has to keep drawing frames rather than paint once and stop.
   *
   * The date fields are excluded: they change at most once a day, which a player that redraws on
   * its own schedule picks up without spinning a frame loop for it.
   */
  public val MOVING: Set<Int> =
    setOf(
      CONTINUOUS_SEC,
      TIME_IN_SEC,
      TIME_IN_MIN,
      TIME_IN_HR,
      ANIMATION_TIME,
      ANIMATION_DELTA_TIME,
      EPOCH_SECOND,
    )
}

/**
 * Whether this document reads a system variable whose value moves — i.e. whether painting it once
 * would freeze an animation.
 *
 * Float expressions and path expressions are the two places a document can name one of these ids
 * and turn it into geometry, and they are what the `remote-m3` progress indicators use. An
 * operation that references a moving id *directly* in one of its own float words (rather than
 * through an expression) is not detected; no writer emits that shape today, and a scan of every
 * word of every operation would need the model to expose them generically.
 */
public fun RcDocument.referencesMovingSystemVariable(): Boolean = operations.any { operation ->
  when (operation) {
    is RcFloatExpression ->
      operation.expression.movesWithSystemTime() ||
        operation.animation?.movesWithSystemTime() == true
    is RcPathExpression ->
      operation.expressionX.movesWithSystemTime() || operation.expressionY.movesWithSystemTime()
    else -> false
  }
}

private fun List<RcFloatWord>.movesWithSystemTime(): Boolean = any {
  it.referencedId in RcSystemVariables.MOVING
}
