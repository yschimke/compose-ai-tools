package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonStartupLogTest {

  @Test
  fun `records newest-first with a fixed clock`() {
    var now = 1_000L
    val log = DaemonStartupLog(clock = { now })
    assertTrue(log.isEmpty())

    log.record("compose-m3", "boom one")
    now = 2_000L
    log.record("wear-m3", "boom two")

    val recent = log.recent()
    assertFalse(log.isEmpty())
    assertEquals(listOf("wear-m3", "compose-m3"), recent.map { it.session }, "newest first")
    assertEquals(2_000L, recent.first().atEpochMillis)
    assertEquals("boom two", recent.first().reason)
  }

  @Test
  fun `caps at capacity, dropping the oldest`() {
    val log = DaemonStartupLog(capacity = 3, clock = { 0L })
    repeat(5) { log.record("s$it", "r$it") }
    val recent = log.recent()
    assertEquals(3, recent.size, "only the last 3 are kept")
    // Newest-first: s4, s3, s2 survive; s0/s1 were dropped.
    assertEquals(listOf("s4", "s3", "s2"), recent.map { it.session })
  }

  @Test
  fun `collapses a multiline reason to its first non-blank line`() {
    val log = DaemonStartupLog(clock = { 0L })
    log.record("s", "\n\n  the real cause  \nat Foo.kt:42\nat Bar.kt:9")
    assertEquals("the real cause", log.recent().single().reason)
  }

  @Test
  fun `a null or blank reason becomes a placeholder`() {
    val log = DaemonStartupLog(clock = { 0L })
    log.record("s", null)
    log.record("s", "   ")
    assertTrue(log.recent().all { it.reason == "unknown error" })
  }
}
