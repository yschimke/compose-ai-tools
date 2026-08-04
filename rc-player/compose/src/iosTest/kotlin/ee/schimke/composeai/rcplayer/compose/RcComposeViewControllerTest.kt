package ee.schimke.composeai.rcplayer.compose

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextStyle
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RcComposeViewControllerTest {
  @Test
  fun malformedDocumentReportsAHostErrorAndReturnsAFallbackController() {
    val errors = mutableListOf<String>()

    val controller = RcComposeViewController(byteArrayOf(1, 2, 3), onError = errors::add)

    assertNotNull(controller)
    assertEquals(1, errors.size)
  }

  @Test
  fun unavailableExternalFontReportsAResourceError() {
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0)),
        listOf(
          RcTextData(42, "google:Missing Face"),
          RcTextStyle(
            listOf(RcTextStyleProperty.IntValue(1, 100), RcTextStyleProperty.IntValue(8, 42))
          ),
        ),
      )
    val errors = mutableListOf<String>()

    RcComposeViewController(RcDocumentCodec.encode(document), onError = errors::add)

    assertTrue(errors.single().contains("Missing Face"), errors.single())
  }

  @Test
  fun playerEventsReachTheIosHostCallback() {
    val events = mutableListOf<RcPlayerEvent>()
    val event = RcPlayerEvent.HostAction(17)

    forwardIosPlayerEvent(events::add, event)

    assertEquals(event, events.single())
  }
}
