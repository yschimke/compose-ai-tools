package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcColorConstant
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTheme
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The document ROOT is a content scope, like the layout and canvas scopes nested inside it.
 *
 * `applyLayoutContentStateOperations` used to start with `applyDirect = false`, so an operation
 * declared at top level — legal, and what AndroidX executes first, since it runs one flat operation
 * list in wire order — was never evaluated. Its out id stayed unset, `color(outId)` answered 0, and
 * anything reading it drew fully transparent.
 *
 * The reason that is worth a test rather than a comment: it is **invisible from both ends**.
 * `composeSupportReport` counts a `ColorExpression`'s `outId` as a declared colour, so the document
 * passes the renderability gate; and transparent text on a coloured container reads as a rendering
 * bug in the text, not as an unevaluated colour. Same shape as the canvas-scope bug
 * `applyLayoutContentStateOperations` already records, one level up.
 */
class RcTopLevelContentStateTest {

  private val opaqueTeal = 0xFF008080.toInt()

  private fun documentWithTopLevelExpression(): RcDocument {
    val operations = mutableListOf<RcOperation>()
    operations += RcColorConstant(40, opaqueTeal)
    // Declared at top level rather than inside the RootLayout below.
    operations +=
      RcColorExpression(outId = 41, modeAndAlpha = 0, first = 40, second = 40, third = 0)
    operations += RcRootLayout(1)
    operations += RcLayoutContent(2)
    operations += RcNoArg(RcOpcodes.CONTAINER_END)
    operations += RcNoArg(RcOpcodes.CONTAINER_END)
    return RcDocument(RcHeader(RcVersion(0, 1, 0)), operations)
  }

  @Test
  fun aTopLevelColorExpressionIsEvaluatedRatherThanLeftAtZero() {
    val document = documentWithTopLevelExpression()
    val state = RcPlayerState(document)

    state.applyLayoutContentStateOperations(
      RcDocumentLinker.link(document).operations,
      RcTheme.UNSPECIFIED,
    )

    // 0 is the tell: an unset colour id and "fully transparent black" are the same value, which is
    // why this failed silently. The literal it is computed from is asserted alongside, so a change
    // that broke ordinary constants could not make this pass by accident.
    assertEquals(opaqueTeal, state.color(40), "the literal it reads from")
    assertNotEquals(0, state.color(41), "the top-level expression was never evaluated")
  }
}
