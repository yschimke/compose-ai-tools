package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit coverage for the fatal-vs-transient classifier and the two breaker trips (issue #3448). */
class RenderCircuitBreakerTest {

  private val linkage =
    "render failed: UnsatisfiedLinkError: 'long " +
      "org.jetbrains.skia.PathBuilderKt.PathBuilder_nMakeFromPath(long)'"

  @Test
  fun `linkage faults classify as fatal, ordinary render errors do not`() {
    assertEquals("UnsatisfiedLinkError", RenderFailureClassifier.fatalMarker(linkage))
    assertTrue(RenderFailureClassifier.isFatal("render failed: NoClassDefFoundError: androidx/Foo"))
    assertTrue(RenderFailureClassifier.isFatal("render failed: NoSuchMethodError: Bar.baz()"))
    assertFalse(RenderFailureClassifier.isFatal("render failed: java.lang.NullPointerException"))
    assertFalse(RenderFailureClassifier.isFatal("timed out after 10s waiting for render"))
  }

  @Test
  fun `a fatal trip carries the diagnosis the caller supplies`() {
    // The open breaker's reason IS the body of every refused render, so it is the only diagnosis
    // anyone outside the box sees — #4220 was reported from exactly that text, naming the missing
    // symbol and nothing that explains it.
    val breaker =
      RenderCircuitBreaker(
        linkageDiagnosis = { reason ->
          "Skiko bindings 0.148.2 will link against libskiko 0.144.6"
            .takeIf { "org.jetbrains.ski" in reason }
        }
      )

    breaker.recordFailure(linkage)

    val reason = assertNotNull(breaker.peekReason())
    assertTrue(reason.endsWith("Skiko bindings 0.148.2 will link against libskiko 0.144.6"), reason)
    assertTrue(reason.contains("UnsatisfiedLinkError"), "the raw failure is still there: $reason")
  }

  @Test
  fun `a diagnosis that throws or declines costs the trip nothing`() {
    val thrower = RenderCircuitBreaker(linkageDiagnosis = { error("boom") })
    thrower.recordFailure(linkage)
    assertTrue(assertNotNull(thrower.peekReason()).contains("UnsatisfiedLinkError"))

    // A coherent classpath has nothing to add, and must not leave a dangling space behind.
    val quiet = RenderCircuitBreaker(linkageDiagnosis = { null })
    quiet.recordFailure(linkage)
    assertTrue(assertNotNull(quiet.peekReason()).endsWith(linkage))
  }

  @Test
  fun `one fatal failure opens the breaker terminally`() {
    // The #3448 behaviour: 3794 retries of an UnsatisfiedLinkError in ~14 minutes. One is enough.
    var now = 0L
    val breaker = RenderCircuitBreaker(clock = { now })
    assertNull(breaker.blockedReason())

    breaker.recordFailure(linkage)

    val reason = assertNotNull(breaker.blockedReason())
    assertTrue(reason.contains("UnsatisfiedLinkError"), "reason should name the fault: $reason")
    val snapshot = assertNotNull(breaker.snapshot())
    assertTrue(snapshot.open)
    assertTrue(snapshot.fatal)

    // No cooldown, no probe: a linkage fault cannot succeed later, so time buys nothing.
    now += 10 * RenderCircuitBreaker.PROBE_COOLDOWN_MILLIS
    assertNotNull(breaker.blockedReason())
    // And a stray success cannot re-open the lane — the classpath is still broken.
    breaker.recordOk()
    assertNotNull(breaker.blockedReason())
  }

  @Test
  fun `a sustained failure rate trips the breaker even for unclassified errors`() {
    var now = 0L
    val breaker = RenderCircuitBreaker(clock = { now })
    // Below the sample floor nothing trips, however bad the rate looks.
    repeat(RenderCircuitBreaker.MIN_SAMPLES - 1) { breaker.recordFailure("render produced no PNG") }
    assertNull(breaker.peekReason())

    breaker.recordFailure("render produced no PNG")

    val snapshot = assertNotNull(breaker.snapshot())
    assertTrue(snapshot.open)
    assertFalse(snapshot.fatal, "an unclassified error is a rate trip, not a fatal one")
    assertNotNull(breaker.peekReason())
  }

  @Test
  fun `a healthy run keeps the rate breaker closed`() {
    val breaker = RenderCircuitBreaker(clock = { 0L })
    repeat(100) {
      breaker.recordOk()
      breaker.recordFailure("render produced no PNG")
    }
    assertNull(breaker.peekReason(), "a 50% failure rate is not the sustained failure this catches")
  }

  @Test
  fun `a rate-tripped breaker probes after the cooldown and closes on success`() {
    var now = 0L
    val breaker = RenderCircuitBreaker(clock = { now })
    repeat(RenderCircuitBreaker.MIN_SAMPLES) { breaker.recordFailure("render produced no PNG") }
    assertNotNull(breaker.blockedReason())

    // Still shut inside the cooldown: this is what turns a retry storm into one render a minute.
    now += RenderCircuitBreaker.PROBE_COOLDOWN_MILLIS - 1
    assertNotNull(breaker.blockedReason())

    // Past it, exactly one render is admitted — the next is shut out until the cooldown re-elapses.
    now += 1
    assertNull(breaker.blockedReason())
    assertNotNull(breaker.blockedReason())

    // The probe succeeded: the lane is healthy again and reports itself so.
    breaker.recordOk()
    assertNull(breaker.blockedReason())
    assertNull(breaker.snapshot())
  }

  @Test
  fun `short-circuited renders are counted for status`() {
    val breaker = RenderCircuitBreaker(clock = { 0L })
    breaker.recordFailure(linkage)
    repeat(5) { breaker.blockedReason() }
    // The first blockedReason above the repeat is the trip check itself; count what we refused.
    assertEquals(5, assertNotNull(breaker.snapshot()).shortCircuitedRenders)
    // A status poll must never spend a probe or inflate the count.
    breaker.peekReason()
    assertEquals(5, assertNotNull(breaker.snapshot()).shortCircuitedRenders)
  }
}
