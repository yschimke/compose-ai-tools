package ee.schimke.composeai.rcplayer.compose

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcPathAppend
import ee.schimke.composeai.rcplayer.protocol.RcPathCommands
import ee.schimke.composeai.rcplayer.protocol.RcPathCreate
import ee.schimke.composeai.rcplayer.protocol.RcPathData
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.runtime.RcPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals

class RcPathTweenTest {
  @Test
  fun interpolatesCoordinatesButPreservesAndroidXPathCommandWords() {
    val move = RcFloatWord(0x7fc00000 or RcPathCommands.MOVE)
    val done = RcFloatWord(0x7fc00000 or RcPathCommands.DONE)
    val first = RcPathData(1, listOf(move, RcFloatWord.literal(0f), RcFloatWord.literal(10f), done))
    val second =
      RcPathData(2, listOf(move, RcFloatWord.literal(20f), RcFloatWord.literal(30f), done))
    val state = RcPlayerState(RcDocument(RcHeader(RcVersion(0, 1, 0)), listOf(first, second)))

    val result = tweenPathData(3, 1, 2, 0.25f, state)

    assertEquals(move.bits, result.words[0].bits)
    assertEquals(5f, result.words[1].value)
    assertEquals(15f, result.words[2].value)
    assertEquals(done.bits, result.words[3].bits)
  }

  @Test
  fun dynamicPathOperationsAreRebuiltDeterministicallyEachFrame() {
    val line = RcFloatWord(0x7fc00000 or RcPathCommands.LINE)
    val document =
      RcDocument(
        RcHeader(RcVersion(0, 1, 0)),
        listOf(
          RcPathCreate(7, RcFloatWord.literal(1f), RcFloatWord.literal(2f)),
          RcPathAppend(
            7,
            listOf(
              line,
              RcFloatWord.literal(0f),
              RcFloatWord.literal(0f),
              RcFloatWord.literal(3f),
              RcFloatWord.literal(4f),
            ),
          ),
        ),
      )
    val state = RcPlayerState(document)

    state.setPath(7, RcPathData(7, listOf(RcFloatWord.literal(99f))))
    state.beginFrame()

    assertEquals(null, state.path(7))
  }
}
