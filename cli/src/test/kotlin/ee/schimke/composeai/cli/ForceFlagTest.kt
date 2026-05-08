package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for the `--force=<reason>` flag wired into [Command]. The flag is the sanctioned
 * alternative to agents reaching for `rm -rf build/classes/...`; this test pins:
 * - `--force=<reason>` produces `--rerun-tasks` as the leading Gradle argument.
 * - No `--force` produces no extra Gradle argument (preserves the cache-respecting default).
 * - The first call emits a one-line stderr notice mentioning the reason and issue #924, so the
 *   transcript carries the report-this-please breadcrumb.
 *
 * See https://github.com/yschimke/compose-ai-tools/issues/924.
 */
class ForceFlagTest {

  /** Trivial [Command] subclass that exposes [gradleArgsWithForce] for direct assertion. */
  private class Probe(args: List<String>) : Command(args) {
    override fun run() = error("not used")

    fun args(extra: List<String> = emptyList()): List<String> = gradleArgsWithForce(extra)
  }

  @Test
  fun `force=reason prepends rerun-tasks and warns once with issue link`() {
    val probe = Probe(listOf("--force=mtime not advancing"))
    val captured = captureStderr { probe.args() }
    assertEquals(listOf("--rerun-tasks", "-Pextra=1"), probe.args(listOf("-Pextra=1")))
    assertTrue("mtime not advancing" in captured, "captured=$captured")
    assertTrue("924" in captured, "captured=$captured")
  }

  @Test
  fun `force flag with space-separated value also works`() {
    val probe = Probe(listOf("--force", "edit didn't reflect"))
    assertEquals(listOf("--rerun-tasks"), probe.args())
  }

  @Test
  fun `no force produces no extra args`() {
    val probe = Probe(emptyList())
    assertEquals(listOf("-Pkeep=true"), probe.args(listOf("-Pkeep=true")))
  }

  @Test
  fun `blank force reason is ignored to prevent silent --rerun-tasks`() {
    // `--force=` with no value or only whitespace doesn't qualify as a reason — the field exists
    // so the operator can find the report on issue #924, so an empty string defeats the purpose.
    val probe = Probe(listOf("--force="))
    assertEquals(emptyList(), probe.args())
  }

  private fun captureStderr(block: () -> Unit): String {
    val originalErr = System.err
    val buf = ByteArrayOutputStream()
    System.setErr(PrintStream(buf))
    try {
      block()
    } finally {
      System.setErr(originalErr)
    }
    return buf.toString()
  }
}
