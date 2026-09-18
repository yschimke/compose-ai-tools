package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `design` launcher's grant bridge: a token this CLI holds for an explicit `--server` origin
 * travels to the server-side verb runner as `$COMPOSE_PREVIEW_TOKEN`, because the verbs resolve
 * credentials from the environment and a child process cannot read this CLI's store.
 *
 * The narrowness is the point, so the tests pin the edges: a caller's own token env wins, no
 * `--server` means no injection (the origin would be a guess), an unknown origin means no
 * injection, and a machine with no credential home degrades to today's behaviour instead of failing
 * the command.
 */
class DesignCommandTest {

  private val dir: File =
    Files.createTempDirectory("design-grant-bridge").toFile().also { it.deleteOnExit() }
  private val file = File(dir, "agent-access.json")

  private fun storeWith(vararg entries: AgentAccessStore.Entry): AgentAccessStore {
    val store = AgentAccessStore(file = file, clock = { 1_000_000L }, warn = {})
    entries.forEach { store.save(it) }
    return store
  }

  @Test
  fun `an explicit server with a live grant injects the token`() {
    val env =
      DesignCommand(listOf("list", "--server", "http://127.0.0.1:8791"))
        .storedGrantEnv(
          env = { null },
          storeFactory = {
            storeWith(
              AgentAccessStore.Entry(
                origin = "http://127.0.0.1:8791",
                token = "cpat_live",
                expiresAtMillis = 2_000_000L,
              )
            )
          },
        )
    assertEquals(mapOf("COMPOSE_PREVIEW_TOKEN" to "cpat_live"), env)
  }

  @Test
  fun `an origin with no entry injects nothing`() {
    val env =
      DesignCommand(listOf("export", "my-widget", "--server", "https://preview.coo.ee"))
        .storedGrantEnv(env = { null }, storeFactory = { storeWith() })
    assertTrue(env.isEmpty(), "no entry for the origin, so nothing to inject")
  }

  @Test
  fun `no explicit server injects nothing even when a grant exists`() {
    val env =
      DesignCommand(listOf("list"))
        .storedGrantEnv(
          env = { null },
          storeFactory = {
            storeWith(
              AgentAccessStore.Entry(
                origin = "http://127.0.0.1:8723",
                token = "cpat_live",
                expiresAtMillis = 2_000_000L,
              )
            )
          },
        )
    assertTrue(
      env.isEmpty(),
      "the effective origin is the server's default — a guess here could hand the wrong server a credential",
    )
  }

  @Test
  fun `a token the caller exported always wins`() {
    val env =
      DesignCommand(listOf("list", "--server", "http://127.0.0.1:8791"))
        .storedGrantEnv(
          env = { name -> if (name == "COMPOSE_PREVIEW_TOKEN") "cpat_mine" else null },
          storeFactory = {
            storeWith(
              AgentAccessStore.Entry(
                origin = "http://127.0.0.1:8791",
                token = "cpat_stored",
                expiresAtMillis = 2_000_000L,
              )
            )
          },
        )
    assertTrue(env.isEmpty(), "the caller's own environment must not be overridden")
  }

  @Test
  fun `the legacy token env also suppresses the bridge`() {
    val env =
      DesignCommand(listOf("list", "--server", "http://127.0.0.1:8791"))
        .storedGrantEnv(
          env = { name -> if (name == "COMPOSE_PREVIEW_UI_BUILDER_TOKEN") "cpat_mine" else null },
          storeFactory = {
            storeWith(
              AgentAccessStore.Entry(
                origin = "http://127.0.0.1:8791",
                token = "cpat_stored",
                expiresAtMillis = 2_000_000L,
              )
            )
          },
        )
    assertTrue(env.isEmpty())
  }

  @Test
  fun `attached and detached server spellings both resolve`() {
    val attached =
      DesignCommand(listOf("--server=https://preview.coo.ee", "list"))
        .storedGrantEnv(
          env = { null },
          storeFactory = {
            storeWith(
              AgentAccessStore.Entry(
                origin = "https://preview.coo.ee",
                token = "cpat_hosted",
                expiresAtMillis = 2_000_000L,
              )
            )
          },
        )
    assertEquals(mapOf("COMPOSE_PREVIEW_TOKEN" to "cpat_hosted"), attached)
  }

  @Test
  fun `no credential home degrades to no injection`() {
    val env =
      DesignCommand(listOf("list", "--server", "http://127.0.0.1:8791"))
        .storedGrantEnv(
          env = { null },
          storeFactory = { throw NoCredentialHomeException() },
        )
    assertTrue(env.isEmpty())
  }

  @Test
  fun `the bridge names the variables the server's runner reads`() {
    // Drift between the two spellings is the failure mode: the bridge would keep working while
    // silently handing over a variable the server no longer reads.
    assertEquals("COMPOSE_PREVIEW_TOKEN", DesignCommand.DESIGN_TOKEN_ENV)
    assertEquals("COMPOSE_PREVIEW_UI_BUILDER_TOKEN", DesignCommand.DESIGN_LEGACY_TOKEN_ENV)
  }
}
