package ee.schimke.composeai.rcplayer.trace

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RcTraceRecorderTest {
  @AfterTest
  fun clearRecorder() {
    RcTrace.recorder = null
  }

  @Test
  fun noRecorderAndNoPlatformTracerOpensNoSpan() {
    // The disabled path is the one that runs in production, so it is the one worth pinning: it must
    // allocate nothing and hand back nothing to close.
    assertNull(RcTrace.begin(RcTraceCategory.DOCUMENT, "decode"))
    RcTrace.end(null)
  }

  @Test
  fun recorderCollectsSpansPerName() {
    val recorder = RcTraceRecorder()
    RcTrace.recorder = recorder

    repeat(3) { rcTrace(RcTraceCategory.FRAME, "draw") {} }
    rcTrace(RcTraceCategory.DOCUMENT, "decode") {}

    val summary = recorder.summary().associateBy { it.name }
    assertEquals(3, summary.getValue("draw").count)
    assertEquals(RcTraceCategory.FRAME, summary.getValue("draw").category)
    assertEquals(1, summary.getValue("decode").count)
    assertEquals(4, recorder.events().size)
  }

  @Test
  fun spanIsClosedWhenTheBlockThrows() {
    val recorder = RcTraceRecorder()
    RcTrace.recorder = recorder

    runCatching { rcTrace(RcTraceCategory.INPUT, "click") { error("boom") } }

    assertEquals(1, recorder.summary().single().count)
  }

  @Test
  fun summaryIsOrderedByTotalTimeDescending() {
    val recorder = RcTraceRecorder()
    // Feed the recorder directly so the ordering assertion does not depend on real elapsed time.
    recorder.record(RcTraceCategory.FRAME, "cheap", startNanos = 0, endNanos = 10)
    recorder.record(RcTraceCategory.FRAME, "expensive", startNanos = 0, endNanos = 100)
    recorder.record(RcTraceCategory.FRAME, "cheap", startNanos = 0, endNanos = 10)

    assertEquals(listOf("expensive", "cheap"), recorder.summary().map { it.name })
    val cheap = recorder.summary().last()
    assertEquals(20, cheap.totalNanos)
    assertEquals(10, cheap.meanNanos)
    assertEquals(10, cheap.maxNanos)
  }

  @Test
  fun percentilesUseNearestRankSoTheyAreObservedDurations() {
    val sorted = (1L..100L).toList()
    assertEquals(51L, sorted.percentile(50))
    assertEquals(96L, sorted.percentile(95))
    assertEquals(100L, sorted.percentile(100))
    assertEquals(1L, sorted.percentile(0))
  }

  @Test
  fun eventsBeyondTheCapAreCountedButNotRetained() {
    val recorder = RcTraceRecorder(maxEvents = 2)
    repeat(5) { recorder.record(RcTraceCategory.FRAME, "draw", startNanos = 0, endNanos = 5) }

    assertEquals(2, recorder.events().size)
    assertEquals(3, recorder.droppedEvents)
    assertEquals(5, recorder.summary().single().count)
  }

  @Test
  fun chromeTraceJsonCarriesEveryRetainedSpan() {
    val recorder = RcTraceRecorder()
    recorder.record(RcTraceCategory.DOCUMENT, "decode", startNanos = 1_000, endNanos = 3_000)
    recorder.recordInstant(RcTraceCategory.INPUT, "click")

    val json = recorder.toChromeTraceJson(processName = "rc-player \"desktop\"")
    assertTrue(json.startsWith("{\"displayTimeUnit\":\"ms\""), json)
    assertTrue(json.contains("\\\"desktop\\\""), json)
    assertTrue(
      json.contains("\"name\":\"decode\",\"cat\":\"rc-player.document\",\"ph\":\"X\""),
      json,
    )
    assertTrue(json.contains("\"ts\":1.0,\"dur\":2.0"), json)
    assertTrue(json.contains("\"name\":\"click\""), json)
  }

  @Test
  fun resetClearsEverything() {
    val recorder = RcTraceRecorder(maxEvents = 1)
    repeat(3) { recorder.record(RcTraceCategory.FRAME, "draw", startNanos = 0, endNanos = 1) }
    recorder.recordCounter("operations", 42)
    recorder.reset()

    assertTrue(recorder.events().isEmpty())
    assertTrue(recorder.summary().isEmpty())
    assertTrue(recorder.counters().isEmpty())
    assertEquals(0, recorder.droppedEvents)
  }

  @Test
  fun countersKeepTheLastSampledValue() {
    val recorder = RcTraceRecorder()
    RcTrace.recorder = recorder

    RcTrace.counter(RcTraceCategory.DOCUMENT, "operations", 12)
    RcTrace.counter(RcTraceCategory.DOCUMENT, "operations", 34)

    assertEquals(mapOf("operations" to 34L), recorder.counters())
  }

  @Test
  fun installedRecorderMakesSpansEnabled() {
    RcTrace.recorder = RcTraceRecorder()
    assertTrue(RcTrace.isEnabled(RcTraceCategory.FRAME))
    assertNotNull(RcTrace.begin(RcTraceCategory.FRAME, "draw"))
  }
}
