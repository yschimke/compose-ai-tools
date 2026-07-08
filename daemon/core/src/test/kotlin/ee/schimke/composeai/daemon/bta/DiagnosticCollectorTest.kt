@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package ee.schimke.composeai.daemon.bta

import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DiagnosticCollector]. Covers the line-shape parser against the BTA diagnostic
 * formats the spike captured in real runs, plus the delegation
 * contract for non-error events.
 *
 * `KotlinLogger.error(msg, throwable)` is the only call that feeds the diagnostic list; everything
 * else (`info`, `debug`, `lifecycle`, `warn`) passes through to the optional delegate logger
 * without touching the collected diagnostics.
 */
class DiagnosticCollectorTest {

  @Test
  fun `parses file-URL prefixed diagnostic`() {
    val parsed =
      DiagnosticCollector.parseError("file:///abs/path/Foo.kt:42:5 Unresolved reference: Modfier")
    assertEquals("/abs/path/Foo.kt", parsed?.file)
    assertEquals(42, parsed?.line)
    assertEquals(5, parsed?.column)
    assertEquals("Unresolved reference: Modfier", parsed?.message)
  }

  @Test
  fun `parses bare absolute-path diagnostic`() {
    val parsed = DiagnosticCollector.parseError("/abs/path/Foo.kt:42:5 Unresolved reference: x")
    assertEquals("/abs/path/Foo.kt", parsed?.file)
    assertEquals(42, parsed?.line)
    assertEquals(5, parsed?.column)
  }

  @Test
  fun `parses Kotlin 2_0+ form with the error_ infix`() {
    // Kotlin 2.0+ kotlinc sometimes emits `e: file://...:N:M: error: message`. BTA's
    // KotlinLogger.error drops the leading `e:` prefix, so we see the `: error:` infix
    // between the column and the message text.
    val parsed =
      DiagnosticCollector.parseError("file:///abs/Foo.kt:12:7: error: Unresolved reference 'bar'")
    assertEquals("/abs/Foo.kt", parsed?.file)
    assertEquals(12, parsed?.line)
    assertEquals(7, parsed?.column)
    assertEquals("Unresolved reference 'bar'", parsed?.message)
  }

  @Test
  fun `tolerates the explicit e_ prefix when BTA leaves it on`() {
    val parsed = DiagnosticCollector.parseError("e: file:///abs/Foo.kt:1:1 oops")
    assertEquals("/abs/Foo.kt", parsed?.file)
    assertEquals(1, parsed?.line)
    assertEquals(1, parsed?.column)
    assertEquals("oops", parsed?.message)
  }

  @Test
  fun `rejects malformed diagnostic lines`() {
    // No line/col → no diagnostic. Such lines typically come from `COMPILER_INTERNAL_ERROR`
    // outcomes; the service maps those to Outcome.Fallback via the empty-collector check.
    assertNull(DiagnosticCollector.parseError("an exception occurred"))
    assertNull(DiagnosticCollector.parseError("file:///abs/Foo.kt no line or col"))
    assertNull(DiagnosticCollector.parseError(""))
  }

  @Test
  fun `error_ call appends to the collected list`() {
    val collector = DiagnosticCollector()
    collector.error("file:///abs/Foo.kt:1:1 first", null)
    collector.error("file:///abs/Foo.kt:2:2 second", null)
    collector.error("not a diagnostic", null) // dropped silently
    assertEquals(2, collector.errors.size)
    assertEquals("first", collector.errors[0].message)
    assertEquals("second", collector.errors[1].message)
  }

  @Test
  fun `non-error events go through the delegate but never become diagnostics`() {
    val delegate = RecordingLogger()
    val collector = DiagnosticCollector(delegate)
    collector.info("info text")
    collector.warn("warn text")
    collector.warn("warn with cause", IllegalStateException("boom"))
    collector.debug("debug text")
    collector.lifecycle("life text")
    collector.error("file:///x/F.kt:1:1 oops", null)
    // Every event reached the delegate — collection of diagnostics is additive, not
    // a redirect. Production wiring uses this to keep stderr daemon logs intact while
    // also surfacing structured errors back through `compileSources`.
    assertEquals(1, delegate.errors)
    assertEquals(1, delegate.infos)
    assertEquals(2, delegate.warns)
    assertEquals(1, delegate.debugs)
    assertEquals(1, delegate.lifecycles)
    // Diagnostic still collected by the parser path.
    assertEquals(1, collector.errors.size)
  }

  @Test
  fun `error events also forward to the delegate when one is wired`() {
    // Production wiring may want both: collect + still log to stderr. The delegate's
    // error() is invoked unconditionally; only the diagnostic list filtering happens here.
    val delegate = RecordingLogger()
    val collector = DiagnosticCollector(delegate)
    collector.error("file:///x.kt:1:1 oops", null)
    collector.error("not parseable", null)
    assertEquals(2, delegate.errors)
  }

  @Test
  fun `errors snapshot is a defensive copy`() {
    val collector = DiagnosticCollector()
    collector.error("file:///F.kt:1:1 first", null)
    val before = collector.errors
    collector.error("file:///F.kt:2:2 second", null)
    assertEquals(1, before.size)
    assertEquals(2, collector.errors.size)
  }

  @Test
  fun `BtaCompileDiagnosticException carries the captured errors verbatim`() {
    val errors =
      listOf(
        ee.schimke.composeai.daemon.protocol.CompileErrorDetail(
          file = "/x.kt",
          line = 1,
          column = 1,
          message = "oops",
        )
      )
    val ex = BtaCompileDiagnosticException(errors)
    assertEquals(errors, ex.errors)
    assertTrue(ex.message!!.contains("1 diagnostic"))
  }

  private class RecordingLogger : KotlinLogger {
    var errors = 0
    var warns = 0
    var infos = 0
    var debugs = 0
    var lifecycles = 0

    override val isDebugEnabled: Boolean = true

    override fun error(msg: String, throwable: Throwable?) {
      errors++
    }

    override fun warn(msg: String) {
      warns++
    }

    override fun warn(msg: String, throwable: Throwable?) {
      warns++
    }

    override fun info(msg: String) {
      infos++
    }

    override fun debug(msg: String) {
      debugs++
    }

    override fun lifecycle(msg: String) {
      lifecycles++
    }
  }
}
