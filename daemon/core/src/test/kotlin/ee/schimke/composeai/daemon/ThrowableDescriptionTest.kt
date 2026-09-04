package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property that matters: a throwable whose own message is null must still say what went wrong.
 * Each test below fails against the old `"${t.javaClass.simpleName}: ${t.message}"` spelling.
 */
class ThrowableDescriptionTest {

  @Test
  fun rendersNameAndMessage() {
    assertEquals("IllegalStateException: boom", describeThrowable(IllegalStateException("boom")))
  }

  @Test
  fun omitsTheColonWhenThereIsNoMessage() {
    // The old spelling produced "IllegalStateException: null" here.
    assertEquals("IllegalStateException", describeThrowable(IllegalStateException()))
  }

  @Test
  fun blankMessageIsTreatedAsAbsent() {
    assertEquals("IllegalStateException", describeThrowable(IllegalStateException("   ")))
  }

  /**
   * The real case from the field: `recording/start` reported `ExceptionInInitializerError: null`,
   * which names neither the class that failed to initialise nor why. The cause carries both.
   */
  @Test
  fun carriesTheCauseOfAMessagelessThrowable() {
    val boom = ExceptionInInitializerError(NoClassDefFoundError("kotlinx/coroutines/GlobalScope"))
    assertEquals(
      "ExceptionInInitializerError ← caused by NoClassDefFoundError: kotlinx/coroutines/GlobalScope",
      describeThrowable(boom),
    )
  }

  @Test
  fun walksSeveralLinks() {
    val root = IllegalArgumentException("bad arg")
    val mid = RuntimeException("wrapping", root)
    val top = IllegalStateException("outer", mid)
    assertEquals(
      "IllegalStateException: outer ← caused by RuntimeException: wrapping ← " +
        "caused by IllegalArgumentException: bad arg",
      describeThrowable(top),
    )
  }

  /** A long chain is truncated rather than allowed to fill a log line or a protocol field. */
  @Test
  fun capsDepth() {
    var t = RuntimeException("level-0")
    repeat(12) { t = RuntimeException("level-${it + 1}", t) }
    val described = describeThrowable(t)
    assertTrue("must mark the truncation: $described", described.endsWith("← caused by …"))
    // Four rendered causes after the head, then the ellipsis: five separators in total.
    assertEquals(
      "head + 4 causes + the truncation marker",
      5,
      described.split("← caused by ").size - 1,
    )
    assertTrue("head must survive: $described", described.startsWith("RuntimeException: level-12"))
  }

  /** A cause cycle must terminate rather than spin. */
  @Test
  fun terminatesOnACycle() {
    val a = RuntimeException("a")
    val b = RuntimeException("b", a)
    a.initCause(b)
    val described = describeThrowable(a)
    assertTrue("must report the cycle: $described", described.contains("(cycle)"))
  }

  /** An anonymous throwable subclass has an empty `simpleName`; the line must still name it. */
  @Test
  fun namesAnAnonymousThrowable() {
    val anon = object : RuntimeException("anon") {}
    val described = describeThrowable(anon)
    assertTrue("must not start with a bare colon: $described", !described.startsWith(":"))
    assertTrue("must carry the message: $described", described.contains("anon"))
  }
}
