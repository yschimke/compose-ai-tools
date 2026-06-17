package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for [printDiscoveryFailures] — the stderr surfacing that turns an empty discovery
 * into an explanation of *which* modules failed to configure and *why* (issue #3). Pure-function
 * style: a capturing sink stands in for `System.err`.
 */
class DiscoveryFailureReportingTest {

  @Test
  fun `no output when there are no failures`() {
    val lines = mutableListOf<String>()
    printDiscoveryFailures(emptyList(), err = { lines += it })
    assertTrue(lines.isEmpty(), "expected silence for a genuinely plugin-free build; got $lines")
  }

  @Test
  fun `prints a header plus one line per failing project`() {
    val lines = mutableListOf<String>()
    printDiscoveryFailures(
      listOf(
        ProjectDiscoveryFailure(":app", "PluginApplicationException: already on the classpath"),
        ProjectDiscoveryFailure(":lib", "Cannot resolve external dependency"),
      ),
      err = { lines += it },
    )
    assertTrue(lines.first().contains("2 project(s) failed to configure"), "got ${lines.first()}")
    assertTrue(lines.any { it.contains(":app:") && it.contains("already on the classpath") })
    assertTrue(
      lines.any { it.contains(":lib:") && it.contains("Cannot resolve external dependency") }
    )
  }

  @Test
  fun `caps the per-project lines and reports the remainder`() {
    val failures = (1..15).map { ProjectDiscoveryFailure(":m$it", "boom") }
    val lines = mutableListOf<String>()
    printDiscoveryFailures(failures, limit = 10, err = { lines += it })
    // 1 header + 10 detail lines + 1 "and N more" line.
    assertEquals(12, lines.size, "got $lines")
    assertTrue(lines.last().contains("and 5 more"), "got ${lines.last()}")
  }
}
