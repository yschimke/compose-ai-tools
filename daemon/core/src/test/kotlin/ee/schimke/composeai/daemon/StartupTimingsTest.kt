package ee.schimke.composeai.daemon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTimingsTest {
  @After
  fun cleanUp() {
    System.clearProperty(StartupTimings.QUIET_PROP)
    StartupTimings.reset()
  }

  @Test
  fun `marks retain their label thread and monotonic JVM-relative time`() {
    System.setProperty(StartupTimings.QUIET_PROP, "true")
    StartupTimings.reset()

    StartupTimings.mark("configuration loaded")
    StartupTimings.mark("sandbox ready")

    val marks = StartupTimings.marks()
    assertEquals(listOf("configuration loaded", "sandbox ready"), marks.map { it.label })
    assertEquals(List(2) { Thread.currentThread().name }, marks.map { it.thread })
    assertTrue(marks.all { it.elapsedMs >= 0 })
    assertTrue(marks.zipWithNext().all { (a, b) -> a.elapsedMs <= b.elapsedMs })
    assertTrue(StartupTimings.jvmStartMs <= System.currentTimeMillis())
  }

  @Test
  fun `quiet summary is idempotent and reset clears the timeline`() {
    System.setProperty(StartupTimings.QUIET_PROP, "true")
    StartupTimings.mark("one")

    StartupTimings.summary()
    StartupTimings.summary()
    StartupTimings.reset()

    assertEquals(emptyList<StartupTimings.Mark>(), StartupTimings.marks())
  }
}
