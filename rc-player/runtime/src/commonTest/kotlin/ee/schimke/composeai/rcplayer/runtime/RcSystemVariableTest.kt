package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcPathExpression
import ee.schimke.composeai.rcplayer.protocol.RcSystemVariables
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.referencesMovingSystemVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ids the player owes a document — AndroidX's `TimeVariables`, in this player's terms.
 *
 * The regression these guard is silent by construction: an unloaded id resolves to its own raw
 * `NaN` bits rather than throwing, so a document that reads one still renders, just without the
 * geometry derived from it. `remote-m3`'s indeterminate circular progress indicator came out as a
 * completely empty frame that way (#4264) — track and arc alike — while every other player animated
 * it. So these assert the *values*, not merely that something was loaded.
 */
class RcSystemVariableTest {

  /** 2026-08-19T16:30:45.250, a Wednesday, in a zone two hours ahead of UTC. */
  private val clock =
    object : RcTimeSource {
      override fun currentTimeMillis(): Long = EPOCH_MILLIS

      override fun snapshot(epochMillis: Long) =
        RcTimeSnapshot(
          epochMillis = epochMillis,
          year = 2026,
          month = 8,
          dayOfMonth = 19,
          dayOfYear = 231,
          hour = 16,
          minute = 30,
          second = 45,
          isoDayOfWeek = 3,
          offsetSeconds = 7_200,
        )
    }

  @Test
  fun everyTimeVariableAndroidXPublishesIsLoadedAtTheTopOfAFrame() {
    val state =
      RcPlayerState(RcDocument(RcHeader(RcVersion(1, 0, 0)), emptyList()), timeSource = clock)

    state.beginFrame(timeSeconds = 0f, epochMillis = EPOCH_MILLIS)

    // `minute * 60 + second` plus the sub-second remainder, which is what makes this one usable as
    // an animation clock at all: the whole-second sibling below steps once a second.
    assertEquals(1845.25f, state.system(RcSystemVariables.CONTINUOUS_SEC))
    assertEquals(1845f, state.system(RcSystemVariables.TIME_IN_SEC))
    assertEquals(990f, state.system(RcSystemVariables.TIME_IN_MIN))
    assertEquals(16f, state.system(RcSystemVariables.TIME_IN_HR))
    assertEquals(8f, state.system(RcSystemVariables.CALENDAR_MONTH))
    assertEquals(7200f, state.system(RcSystemVariables.OFFSET_TO_UTC))
    assertEquals(3f, state.system(RcSystemVariables.WEEK_DAY))
    assertEquals(19f, state.system(RcSystemVariables.DAY_OF_MONTH))
    assertEquals(231f, state.system(RcSystemVariables.DAY_OF_YEAR))
    assertEquals(2026f, state.system(RcSystemVariables.YEAR))
    assertEquals(EPOCH_MILLIS / 1000, state.integer(RcSystemVariables.EPOCH_SECOND)?.toLong())
  }

  @Test
  fun theAnimationClockFollowsTheFrameTimeAndReportsNoDeltaOnTheFirstFrame() {
    val state =
      RcPlayerState(RcDocument(RcHeader(RcVersion(1, 0, 0)), emptyList()), timeSource = clock)

    state.beginFrame(timeSeconds = 0.5f, epochMillis = EPOCH_MILLIS)
    assertEquals(0.5f, state.system(RcSystemVariables.ANIMATION_TIME))
    // Constructing the state already ran one frame at t=0, so this frame has a predecessor.
    assertEquals(0.5f, state.system(RcSystemVariables.ANIMATION_DELTA_TIME))

    state.beginFrame(timeSeconds = 0.75f, epochMillis = EPOCH_MILLIS)
    assertEquals(0.75f, state.system(RcSystemVariables.ANIMATION_TIME))
    assertEquals(0.25f, state.system(RcSystemVariables.ANIMATION_DELTA_TIME))
  }

  @Test
  fun anExpressionOverTheContinuousClockEvaluatesToANumberAndMovesBetweenFrames() {
    // `CONTINUOUS_SEC * 2` — the shape `remote-m3`'s indeterminate progress sweep is built from.
    val expression =
      RcFloatExpression(
        id = 100,
        expression =
          listOf(
            RcFloatWord(NAN_REFERENCE or RcSystemVariables.CONTINUOUS_SEC),
            RcFloatWord.literal(2f),
            RcFloatExpressionEvaluator.operatorWord(RcFloatExpressionEvaluator.OFFSET + 3),
          ),
        animation = null,
      )
    val state =
      RcPlayerState(
        RcDocument(RcHeader(RcVersion(1, 0, 0)), listOf(expression)),
        timeSource = clock,
      )

    state.beginFrame(timeSeconds = 0f, epochMillis = EPOCH_MILLIS)
    state.applyFloatExpression(expression)
    val first = state.resolve(RcFloatWord(NAN_REFERENCE or 100))
    assertEquals(1845.25f * 2f, first)

    state.beginFrame(timeSeconds = 1f, epochMillis = EPOCH_MILLIS + 500)
    state.applyFloatExpression(expression)
    assertTrue(
      state.resolve(RcFloatWord(NAN_REFERENCE or 100)) != first,
      "the expression did not move when the clock did",
    )
  }

  @Test
  fun aDocumentsOwnDeclarationOutranksTheSystemVariableAtTheSameId() {
    // No conforming writer reaches here — `RemoteComposeState.START_ID` is 42 and everything below
    // belongs to `RemoteContext` — but a hand-built document can, and its own constant standing is
    // less surprising than the clock silently replacing it.
    val constant = RcFloatConstant(RcSystemVariables.WEEK_DAY, RcFloatWord.literal(20f))
    val state =
      RcPlayerState(
        RcDocument(RcHeader(RcVersion(1, 0, 0)), listOf(constant)),
        timeSource = clock,
      )

    state.beginFrame(timeSeconds = 0f, epochMillis = EPOCH_MILLIS)

    assertEquals(20f, state.system(RcSystemVariables.WEEK_DAY))
    // Every id it did *not* claim is still the player's to supply.
    assertEquals(1845.25f, state.system(RcSystemVariables.CONTINUOUS_SEC))
  }

  @Test
  fun aHostWriteToASystemIdClaimsItForGood() {
    val state =
      RcPlayerState(RcDocument(RcHeader(RcVersion(1, 0, 0)), emptyList()), timeSource = clock)

    state.setFloat(RcSystemVariables.OFFSET_TO_UTC, 2f)
    state.beginFrame(timeSeconds = 0f, epochMillis = EPOCH_MILLIS)

    // The write survives the next frame rather than being silently replaced by the zone offset.
    assertEquals(2f, state.system(RcSystemVariables.OFFSET_TO_UTC))
    assertEquals(1845.25f, state.system(RcSystemVariables.CONTINUOUS_SEC))
  }

  @Test
  fun aDocumentThatReadsAMovingClockAsksForContinuousFrames() {
    fun documentReading(id: Int) =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0)),
        listOf(RcFloatExpression(100, listOf(RcFloatWord(NAN_REFERENCE or id)), null)),
      )

    assertTrue(documentReading(RcSystemVariables.CONTINUOUS_SEC).referencesMovingSystemVariable())
    assertTrue(documentReading(RcSystemVariables.ANIMATION_TIME).referencesMovingSystemVariable())
    // The date fields settle for a whole day; spinning a frame loop for them would buy nothing.
    assertFalse(documentReading(RcSystemVariables.DAY_OF_MONTH).referencesMovingSystemVariable())
    // A document id, not a system one.
    assertFalse(documentReading(142).referencesMovingSystemVariable())

    val path =
      RcPathExpression(
        id = 100,
        flags = 0,
        min = RcFloatWord.literal(0f),
        max = RcFloatWord.literal(1f),
        count = RcFloatWord.literal(8f),
        expressionX = listOf(RcFloatWord(NAN_REFERENCE or RcSystemVariables.CONTINUOUS_SEC)),
        expressionY = listOf(RcFloatWord.literal(0f)),
      )
    assertTrue(
      RcDocument(RcHeader(RcVersion(1, 0, 0)), listOf(path)).referencesMovingSystemVariable()
    )
  }

  private fun RcPlayerState.system(id: Int): Float = resolve(RcFloatWord(NAN_REFERENCE or id))

  private companion object {
    const val EPOCH_MILLIS = 1_787_243_445_250L
    const val NAN_REFERENCE = 0x7fc00000
  }
}
