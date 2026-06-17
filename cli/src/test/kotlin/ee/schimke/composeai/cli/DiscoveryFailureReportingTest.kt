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

  // The AGP-classloader signature (issue #1947): auto-inject puts the plugin on a sibling
  // classloader that can't see AGP when AGP is supplied by an included build's convention plugin.
  private val noClassDefFound =
    "ComposePreviewPlugin failure -> NoClassDefFoundError: " +
      "com/android/build/api/variant/AndroidComponentsExtension -> " +
      "ClassNotFoundException: com.android.build.api.variant.AndroidComponentsExtension"

  @Test
  fun `recognises the AGP NoClassDefFoundError signature in either name form`() {
    assertTrue(isAgpClassloaderFailure(noClassDefFound))
    // NoClassDefFoundError alone (internal slash name).
    assertTrue(
      isAgpClassloaderFailure(
        "NoClassDefFoundError: com/android/build/api/variant/Variant"
      )
    )
    // ClassNotFoundException alone (dotted name).
    assertTrue(
      isAgpClassloaderFailure(
        "ClassNotFoundException: com.android.build.api.variant.AndroidComponentsExtension"
      )
    )
    // Unrelated failures don't match.
    assertTrue(!isAgpClassloaderFailure("Cannot resolve external dependency"))
    assertTrue(
      !isAgpClassloaderFailure("already on the classpath with an unknown version"),
      "the #1855 double-apply collision is a different cause and must not be misclassified",
    )
    // AGP class mentioned without a classloader error → not this signature.
    assertTrue(
      !isAgpClassloaderFailure("com.android.build.api.variant.Variant misconfigured")
    )
  }

  @Test
  fun `emits the convention-plugin guidance instead of the raw stack list when dominated by AGP`() {
    val failures =
      (1..59).map { ProjectDiscoveryFailure(":module$it", noClassDefFound) }
    val lines = mutableListOf<String>()
    printDiscoveryFailures(failures, err = { lines += it })
    // One guidance block, not 59 NoClassDefFoundError lines.
    assertEquals(1, lines.size, "expected a single guidance message; got $lines")
    val msg = lines.single()
    assertTrue(msg.contains("can't see the Android Gradle Plugin"), msg)
    assertTrue(msg.contains("convention plugin"), msg)
    assertTrue(msg.contains("ee.schimke.composeai.preview\") apply false"), msg)
    assertTrue(
      msg.contains("install/#builds-that-apply-agp-via-a-convention-plugin"),
      "guidance should link to the integration docs; got $msg",
    )
    // Reports the proportion so the count from #1939 is preserved.
    assertTrue(msg.contains("59 of 59 project(s)"), msg)
  }

  @Test
  fun `falls back to the per-project list when AGP failures are not dominant`() {
    val failures =
      listOf(
        ProjectDiscoveryFailure(":app", noClassDefFound),
        ProjectDiscoveryFailure(":lib", "Cannot resolve external dependency"),
        ProjectDiscoveryFailure(":data", "Cannot resolve external dependency"),
      )
    val lines = mutableListOf<String>()
    printDiscoveryFailures(failures, err = { lines += it })
    // A lone AGP error among unrelated failures keeps the detailed list.
    assertTrue(lines.first().contains("3 project(s) failed to configure"), "got ${lines.first()}")
    assertTrue(lines.any { it.contains(":lib:") }, "got $lines")
    assertEquals(null, agpClassloaderGuidance(failures))
  }
}
