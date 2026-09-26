package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A launcher for a command newer than the cached server replaces that cache with the newest
 * release, and leaves every server someone chose alone.
 */
class ServerMinimumVersionTest {
  private fun cached(version: String) =
    ServerBinaryDiscovery.Choice(
      "/cache/preview-server/$version/bin/compose-preview-server",
      ServerBinaryDiscovery.CACHE,
    )

  private val fresh = cached("3.72.0")

  private fun check(
    choice: ServerBinaryDiscovery.Choice,
    minimum: String? = "3.72.0",
    requested: String? = null,
    provision: () -> ServerBinaryDiscovery.Choice? = { fresh },
    log: MutableList<String> = mutableListOf(),
  ) = ServerBinaryDiscovery.meetsMinimum(choice, minimum, "a2ui", requested, provision, log::add)

  @Test
  fun `a cached server older than the command is replaced by the newest`() {
    val log = mutableListOf<String>()
    assertSame(fresh, check(cached("3.71.0"), log = log))
    assertTrue(log.single().contains("predates `a2ui` (added in 3.72.0)"), log.toString())
  }

  @Test
  fun `a cached server at or past the minimum is launched without a fetch`() {
    val current = cached("3.72.0")
    assertSame(current, check(current, provision = { error("must not fetch") }))
    val newer = cached("3.80.1")
    assertSame(newer, check(newer, provision = { error("must not fetch") }))
  }

  @Test
  fun `a server someone chose is never second-guessed`() {
    listOf(ServerBinaryDiscovery.FLAG, ServerBinaryDiscovery.ENV, "PATH").forEach { source ->
      val chosen = ServerBinaryDiscovery.Choice("/opt/old/compose-preview-server", source)
      assertSame(chosen, check(chosen, provision = { error("must not fetch") }), source)
    }
    // A release pinned with COMPOSE_PREVIEW_SERVER_VERSION is a choice too.
    val pinned = cached("3.60.0")
    assertSame(pinned, check(pinned, requested = "3.60.0", provision = { error("must not fetch") }))
  }

  @Test
  fun `a failed fetch still launches the cached copy, and says so`() {
    val old = cached("3.71.0")
    val log = mutableListOf<String>()
    assertSame(old, check(old, provision = { null }, log = log))
    assertEquals(2, log.size)
    assertTrue(log.last().contains("launching the cached 3.71.0"))
  }

  @Test
  fun `a launcher with no minimum changes nothing`() {
    val old = cached("3.10.0")
    assertSame(old, check(old, minimum = null, provision = { error("must not fetch") }))
  }
}
