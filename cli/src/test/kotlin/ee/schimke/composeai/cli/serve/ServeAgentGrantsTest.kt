package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The scope lattice, the CSRF seal, and the small pure helpers the approval page is built on. */
class ServeAgentGrantsTest {

  // ------------------------------------------------------------------ scopes

  @Test
  fun `scopes imply everything below them and nothing above`() {
    assertTrue(ServeAgentGrantScope.PLAYGROUND.implies(ServeAgentGrantScope.PREVIEW))
    assertTrue(ServeAgentGrantScope.LIVE.implies(ServeAgentGrantScope.PREVIEW))
    assertTrue(ServeAgentGrantScope.PREVIEW.implies(ServeAgentGrantScope.PREVIEW))
    assertFalse(ServeAgentGrantScope.PREVIEW.implies(ServeAgentGrantScope.LIVE))
    assertFalse(ServeAgentGrantScope.LIVE.implies(ServeAgentGrantScope.PLAYGROUND))
  }

  @Test
  fun `a scope list resolves to its highest rung`() {
    assertEquals(ServeAgentGrantScope.LIVE, ServeAgentGrantScope.parseHighest("preview,live"))
    assertEquals(ServeAgentGrantScope.LIVE, ServeAgentGrantScope.parseHighest("live"))
    assertEquals(
      ServeAgentGrantScope.PLAYGROUND,
      ServeAgentGrantScope.parseHighest("preview playground"),
    )
    assertNull(ServeAgentGrantScope.parseHighest(null))
    assertNull(ServeAgentGrantScope.parseHighest("   "))
  }

  @Test
  fun `a typo in the scope list fails loudly instead of silently narrowing`() {
    val e =
      assertFailsWith<IllegalArgumentException> {
        ServeAgentGrantScope.parseHighest("preview,liev")
      }
    assertTrue(e.message!!.contains("liev"))
  }

  @Test
  fun `playground is not in the default ceiling`() {
    assertEquals(ServeAgentGrantScope.LIVE, ServeAgentGrantScope.DEFAULT_MAX)
    assertFalse(ServeAgentGrantScope.DEFAULT_MAX.implies(ServeAgentGrantScope.PLAYGROUND))
  }

  // ---------------------------------------------------------------- approver

  @Test
  fun `an approver without repository access cannot pass playground on`() {
    val approver =
      ServeAgentGrants.Approver.github(
        login = "outsider",
        repositoryAccess = false,
        storeCeiling = ServeAgentGrantScope.PLAYGROUND,
      )
    assertEquals(ServeAgentGrantScope.LIVE, approver.ceiling)
    assertEquals(
      listOf("preview", "live"),
      ServeAgentGrants.selectableScopes(
          ServeAgentGrantScope.PLAYGROUND,
          approver,
          ServeAgentGrantScope.PLAYGROUND,
        )
        .map { it.wire },
    )
  }

  @Test
  fun `an approver with repository access may pass playground on`() {
    val approver =
      ServeAgentGrants.Approver.github("maintainer", true, ServeAgentGrantScope.PLAYGROUND)
    assertEquals(ServeAgentGrantScope.PLAYGROUND, approver.ceiling)
    assertEquals("@maintainer", approver.name)
  }

  @Test
  fun `the page never offers more than the agent asked for`() {
    val approver = ServeAgentGrants.Approver.operator(ServeAgentGrantScope.PLAYGROUND)
    assertEquals(
      listOf("preview"),
      ServeAgentGrants.selectableScopes(
          ServeAgentGrantScope.PREVIEW,
          approver,
          ServeAgentGrantScope.PLAYGROUND,
        )
        .map { it.wire },
    )
  }

  // -------------------------------------------------------------------- CSRF

  @Test
  fun `a seal is bound to the request, the approver and the action`() {
    val csrf = ServeAgentGrants.Csrf()
    val seal = csrf.seal("req-1", "@yuri", ServeAgentGrants.Csrf.ACTION_APPROVE)
    assertTrue(csrf.verify("req-1", "@yuri", ServeAgentGrants.Csrf.ACTION_APPROVE, seal))
    assertFalse(csrf.verify("req-2", "@yuri", ServeAgentGrants.Csrf.ACTION_APPROVE, seal))
    assertFalse(csrf.verify("req-1", "@other", ServeAgentGrants.Csrf.ACTION_APPROVE, seal))
    assertFalse(csrf.verify("req-1", "@yuri", ServeAgentGrants.Csrf.ACTION_DENY, seal))
    assertFalse(csrf.verify("req-1", "@yuri", ServeAgentGrants.Csrf.ACTION_APPROVE, null))
    assertFalse(csrf.verify("req-1", "@yuri", ServeAgentGrants.Csrf.ACTION_APPROVE, ""))
  }

  @Test
  fun `seals from one process do not verify in another`() {
    val seal = ServeAgentGrants.Csrf().seal("req-1", "@yuri", ServeAgentGrants.Csrf.ACTION_APPROVE)
    assertFalse(
      ServeAgentGrants.Csrf().verify("req-1", "@yuri", ServeAgentGrants.Csrf.ACTION_APPROVE, seal)
    )
  }

  // --------------------------------------------------------------- durations

  @Test
  fun `durations parse the forms a human types`() {
    assertEquals(7200, ServeAgentGrants.parseDurationSeconds("2h"))
    assertEquals(2700, ServeAgentGrants.parseDurationSeconds("45m"))
    assertEquals(90, ServeAgentGrants.parseDurationSeconds("90s"))
    assertEquals(3600, ServeAgentGrants.parseDurationSeconds("3600"))
    assertEquals(7200, ServeAgentGrants.parseDurationSeconds(" 2 H "))
    assertNull(ServeAgentGrants.parseDurationSeconds("soon"))
    assertNull(ServeAgentGrants.parseDurationSeconds("0h"))
    assertNull(ServeAgentGrants.parseDurationSeconds(""))
    assertNull(ServeAgentGrants.parseDurationSeconds(null))
  }

  @Test
  fun `durations format the way the page and the CLI print them`() {
    assertEquals("30s", ServeAgentGrants.formatDuration(30))
    assertEquals("15m", ServeAgentGrants.formatDuration(900))
    assertEquals("2h", ServeAgentGrants.formatDuration(7200))
    assertEquals("2h 30m", ServeAgentGrants.formatDuration(9000))
  }

  @Test
  fun `the ttl ladder always contains what was actually asked for`() {
    val choices = ServeWeb.ttlChoices(requestedSeconds = 1234, maxSeconds = 8 * 3600)
    assertTrue(1234L in choices)
    assertTrue(choices.all { it <= 8 * 3600 })
    assertEquals(choices.sorted(), choices)
  }

  @Test
  fun `the ttl ladder never offers more than the ceiling`() {
    val choices = ServeWeb.ttlChoices(requestedSeconds = 99_999, maxSeconds = 600)
    assertTrue(choices.all { it <= 600 })
    assertTrue(choices.isNotEmpty())
  }

  // -------------------------------------------------------------- form bodies

  @Test
  fun `form bodies parse repeated fields and encoded values`() {
    val parsed =
      ServeHttpServer.parseFormBody("scope=preview&scope=live&label=fix+%23123&ttl=2h&junk")
    assertEquals(listOf("preview", "live"), parsed["scope"])
    assertEquals("fix #123", parsed["label"]?.first())
    assertEquals("2h", parsed["ttl"]?.first())
    assertEquals("", parsed["junk"]?.first())
  }
}
