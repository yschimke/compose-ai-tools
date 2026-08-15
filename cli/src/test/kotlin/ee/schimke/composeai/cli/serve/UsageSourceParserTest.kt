package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the parser boundary itself — that it **loads**, and that the facts crossing it are the ones
 * the cleaner reasons from.
 *
 * Worth its own test because the failure mode is silence: [UsageSourceParser.of] returns null when
 * the sidecar is missing and [PlaygroundSourceCleaner] quietly keeps its text passes. Every cleaner
 * test would still pass, having exercised none of this. The build forwards the staged jars via
 * `composeai.usagePsi.jars` (see `cli/build.gradle.kts`) precisely so that cannot happen unnoticed,
 * and the first assertion here is that the forwarding worked.
 */
class UsageSourceParserTest {

  private val parser = UsageSourceParser.of { println("usage-source-psi: $it") }

  @Test
  fun `the analyzer loads from the staged sidecar`() {
    assertNotNull(
      parser,
      "usage-source-psi did not load; the parsed path would silently not be under test",
    )
  }

  /** Named arguments arrive with their labels; positional ones arrive bare. */
  @Test
  fun `arguments carry their labels`() {
    val facts =
      assertNotNull(
        parser?.facts("""fun x() = Text(previewOverrideString(key = "k", default = "v"))""")
      )
    val call = assertNotNull(facts.calls.firstOrNull { it.callee == "previewOverrideString" })
    assertEquals(
      listOf("key" to "\"k\"", "default" to "\"v\""),
      call.args.map { it.name to it.text },
    )
  }

  /**
   * A trailing lambda is *not* a value argument as far as binding is concerned.
   *
   * PSI disagrees — `KtLambdaArgument` extends `KtValueArgument`, so an unfiltered list hands the
   * lambda a positional slot and `{ … }` lands where `default` belongs. Found by reading the facts
   * for a real call rather than by a failing rewrite, which is the cheaper order.
   */
  @Test
  fun `a trailing lambda is not one of the bound arguments`() {
    val facts = assertNotNull(parser?.facts("""fun x() = counted("label") { Text("hi") }"""))
    val call = assertNotNull(facts.calls.firstOrNull { it.callee == "counted" })
    assertEquals(listOf("\"label\""), call.args.map { it.text })
    assertTrue(call.lambdaStart >= 0, "the lambda should still be reported, as its own range")
  }

  /** A call with no parentheses at all is still a call. */
  @Test
  fun `a bare trailing-lambda call is reported`() {
    val facts = assertNotNull(parser?.facts("""fun x() = counted { Text("hi") }"""))
    val call = assertNotNull(facts.calls.firstOrNull { it.callee == "counted" })
    assertEquals(-1, call.argsStart, "there is no argument list to report")
    assertTrue(call.args.isEmpty())
  }

  /** The receiver comes across whole, which is what makes the package allow-list a lookup. */
  @Test
  fun `a qualified call reports its receiver`() {
    val facts =
      assertNotNull(
        parser?.facts(
          """
          fun x() {
            ee.schimke.composeai.overrides.previewOverrideString("k", "v")
            state.metrics.counted { }
          }
          """
            .trimIndent()
        )
      )
    val byName = facts.calls.associateBy { it.callee }
    assertEquals("ee.schimke.composeai.overrides", byName["previewOverrideString"]?.receiver)
    assertEquals("state.metrics", byName["counted"]?.receiver)
  }

  /**
   * Binding is Kotlin's rule, and it needs the callee's parameter names — the parse supplies
   * labels, not signatures. See `docs/design/PSI_PARSE_SPIKE.md`.
   */
  @Test
  fun `binding follows Kotlin, and declines what it cannot place`() {
    val params = listOf("key", "default", "index")
    val facts =
      assertNotNull(
        parser?.facts(
          """
          fun x() {
            previewOverrideString(default = "b", key = "a")
            previewOverrideString("a", "b")
          }
          """
            .trimIndent()
        )
      )
    val calls = facts.calls.filter { it.callee == "previewOverrideString" }
    assertEquals(2, calls.size)
    // Reordered labels and plain positions both land `"b"` at index 1.
    for (call in calls) assertEquals("\"b\"", facts.bind(call, params)?.get(1))
    // With no declared parameters a labelled call cannot be placed, so it is declined rather than
    // guessed — the alternative emits `default = "b"` where a value belongs.
    assertNull(facts.bind(calls.first(), emptyList()))
    assertEquals(listOf("\"a\"", "\"b\""), facts.bind(calls.last(), emptyList()))
  }
}
