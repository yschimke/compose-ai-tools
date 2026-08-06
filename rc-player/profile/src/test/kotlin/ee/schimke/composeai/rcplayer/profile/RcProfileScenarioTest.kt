package ee.schimke.composeai.rcplayer.profile

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.runtime.RcDocumentLinker
import ee.schimke.composeai.rcplayer.runtime.RcLayoutTree
import ee.schimke.composeai.rcplayer.trace.RcTrace
import ee.schimke.composeai.rcplayer.trace.RcTraceCategory
import ee.schimke.composeai.rcplayer.trace.RcTraceRecorder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural checks on the reference documents and on what the player's spans report.
 *
 * Deliberately no timing assertions. A profile is a measurement of the machine it ran on, so an
 * assertion like "decode is under 200 µs" would be a flake generator on shared CI. What must not
 * rot is the shape: that the documents still round-trip and link, and that the phases the report is
 * built from still open the spans it reads.
 */
class RcProfileScenarioTest {
  @AfterTest
  fun clearRecorder() {
    RcTrace.recorder = null
  }

  @Test
  fun everyScenarioRoundTripsThroughTheWire() {
    rcProfileScenarios().forEach { scenario ->
      val decoded = RcDocumentCodec.decode(scenario.bytes)
      assertEquals(
        scenario.document,
        decoded,
        "${scenario.id} did not survive an encode/decode round trip",
      )
    }
  }

  @Test
  fun everyScenarioLinksAndBuildsTheLayoutItClaims() {
    val byId = rcProfileScenarios().associateBy { it.id }

    byId.values.forEach { scenario ->
      RcDocumentLinker.link(RcDocumentCodec.decode(scenario.bytes))
    }

    // The two canvas documents deliberately have no layout root — that is what puts them on the
    // player's raw draw path, and it is the difference the profile's `rc:drawRoot` rows depend on.
    listOf("static-canvas", "animated-canvas").forEach { id ->
      val linked = RcDocumentLinker.link(RcDocumentCodec.decode(byId.getValue(id).bytes))
      assertEquals(null, RcLayoutTree.build(linked), "$id should have no layout root")
    }
    listOf("static-button-text", "interactive-button").forEach { id ->
      val linked = RcDocumentLinker.link(RcDocumentCodec.decode(byId.getValue(id).bytes))
      assertTrue(RcLayoutTree.build(linked) != null, "$id should build a layout root")
    }
  }

  @Test
  fun onlyTheInteractiveScenarioReplaysTaps() {
    val byId = rcProfileScenarios().associateBy { it.id }
    assertTrue(byId.getValue("interactive-button").taps.isNotEmpty())
    listOf("static-button-text", "static-canvas", "animated-canvas").forEach { id ->
      assertTrue(byId.getValue(id).taps.isEmpty(), "$id should not replay taps")
    }
  }

  @Test
  fun theLoadPhasesTheReportReadsStillOpenSpans() {
    val recorder = RcTraceRecorder()
    RcTrace.recorder = recorder
    val scenario = rcProfileScenarios().first { it.id == "interactive-button" }

    RcLayoutTree.build(RcDocumentLinker.link(RcDocumentCodec.decode(scenario.bytes)))

    val names = recorder.summary().map { it.name }
    assertContains(names, "rc:decode")
    assertContains(names, "rc:link")
    assertContains(names, "rc:layoutTree")
    assertTrue(
      recorder.summary().all { it.category == RcTraceCategory.DOCUMENT },
      "load-phase spans must stay in the document category the report groups on: $names",
    )
    assertEquals(scenario.document.operations.size.toLong(), recorder.counters()["rc:operations"])
  }
}
