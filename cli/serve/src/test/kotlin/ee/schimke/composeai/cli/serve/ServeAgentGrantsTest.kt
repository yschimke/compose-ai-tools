package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.agentgrants.AgentGrantScope
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
    assertTrue(AgentGrantScope.PLAYGROUND.implies(AgentGrantScope.PREVIEW))
    assertTrue(AgentGrantScope.LIVE.implies(AgentGrantScope.PREVIEW))
    assertTrue(AgentGrantScope.PREVIEW.implies(AgentGrantScope.PREVIEW))
    assertFalse(AgentGrantScope.PREVIEW.implies(AgentGrantScope.LIVE))
    assertFalse(AgentGrantScope.LIVE.implies(AgentGrantScope.PLAYGROUND))
  }

  @Test
  fun `a scope list resolves to its highest rung`() {
    assertEquals(AgentGrantScope.LIVE, AgentGrantScope.parseHighest("preview,live"))
    assertEquals(AgentGrantScope.LIVE, AgentGrantScope.parseHighest("live"))
    assertEquals(
      AgentGrantScope.PLAYGROUND,
      AgentGrantScope.parseHighest("preview playground"),
    )
    assertNull(AgentGrantScope.parseHighest(null))
    assertNull(AgentGrantScope.parseHighest("   "))
  }

  @Test
  fun `a typo in the scope list fails loudly instead of silently narrowing`() {
    val e =
      assertFailsWith<IllegalArgumentException> { AgentGrantScope.parseHighest("preview,liev") }
    assertTrue(e.message!!.contains("liev"))
  }

  @Test
  fun `playground is not in the default ceiling`() {
    assertEquals(AgentGrantScope.LIVE, AgentGrantScope.DEFAULT_MAX)
    assertFalse(AgentGrantScope.DEFAULT_MAX.implies(AgentGrantScope.PLAYGROUND))
  }

  // ---------------------------------------------------------------- approver

  @Test
  fun `an approver without repository access cannot pass playground on`() {
    val approver =
      ServeAgentGrants.Approver.github(
        login = "outsider",
        repositoryAccess = false,
        storeCeiling = AgentGrantScope.PLAYGROUND,
      )
    assertEquals(AgentGrantScope.LIVE, approver.ceiling)
    assertEquals(
      listOf("preview", "live"),
      ServeAgentGrants.selectableScopes(
          AgentGrantScope.PLAYGROUND,
          approver,
          AgentGrantScope.PLAYGROUND,
        )
        .map { it.wire },
    )
  }

  @Test
  fun `an approver with repository access may pass playground on`() {
    val approver = ServeAgentGrants.Approver.github("maintainer", true, AgentGrantScope.PLAYGROUND)
    assertEquals(AgentGrantScope.PLAYGROUND, approver.ceiling)
    assertEquals("@maintainer", approver.name)
  }

  @Test
  fun `the page never offers more than the agent asked for`() {
    val approver = ServeAgentGrants.Approver.operator(AgentGrantScope.PLAYGROUND)
    assertEquals(
      listOf("preview"),
      ServeAgentGrants.selectableScopes(
          AgentGrantScope.PREVIEW,
          approver,
          AgentGrantScope.PLAYGROUND,
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
    assertEquals(7200, AgentGrantProtocol.parseDurationSeconds("2h"))
    assertEquals(2700, AgentGrantProtocol.parseDurationSeconds("45m"))
    assertEquals(90, AgentGrantProtocol.parseDurationSeconds("90s"))
    assertEquals(3600, AgentGrantProtocol.parseDurationSeconds("3600"))
    assertEquals(7200, AgentGrantProtocol.parseDurationSeconds(" 2 H "))
    assertNull(AgentGrantProtocol.parseDurationSeconds("soon"))
    assertNull(AgentGrantProtocol.parseDurationSeconds("0h"))
    assertNull(AgentGrantProtocol.parseDurationSeconds(""))
    assertNull(AgentGrantProtocol.parseDurationSeconds(null))
  }

  @Test
  fun `durations format the way the page and the CLI print them`() {
    assertEquals("30s", AgentGrantProtocol.formatDuration(30))
    assertEquals("15m", AgentGrantProtocol.formatDuration(900))
    assertEquals("2h", AgentGrantProtocol.formatDuration(7200))
    assertEquals("2h 30m", AgentGrantProtocol.formatDuration(9000))
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
