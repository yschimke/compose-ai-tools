package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `serve` and `browse` as launchers.
 *
 * Replaces `ServeCommandTest` and `ServeOptionsDelegationTest`, whose subjects left with the
 * server: both pinned wiring between `ServeCommand` and `ServeRunner`/`ServeCommandOptions`, and
 * there is no such wiring now. What is worth pinning instead is the argv — the launcher's whole job
 * — because a flag dropped or forwarded wrongly here is a server that starts with the wrong options
 * and no compiler to say so.
 *
 * The init-script case those tests also covered is not reproduced. Its subject was `ServeCommand`
 * overriding `autoInjectInitScriptArgs` with the same name as the package-level function it
 * delegated to — the test existed because that pair can silently self-resolve into infinite
 * recursion. `BuildHostCommand`, which answers that question now, declares no such override and
 * calls the function directly, so there is no longer a pair that can collapse.
 */
class ServeLauncherTest {

  private fun argv(vararg args: String, browse: Boolean = false) =
    ServeCommand(args.toList(), browseProject = browse).launchCommand("/opt/compose-preview-server")

  @Test
  fun `the server is invoked with its serve subcommand`() {
    assertEquals(listOf("/opt/compose-preview-server", "serve"), argv())
  }

  @Test
  fun `caller arguments pass through in order`() {
    assertEquals(
      listOf("/opt/compose-preview-server", "serve", "--module", "app", "--discover"),
      argv("--module", "app", "--discover"),
    )
  }

  /**
   * `--server-binary` is the launcher's own flag. The server has no such option, so forwarding it
   * would make every invocation that used it fail on an unknown argument.
   */
  @Test
  fun `the launcher's own flag is not forwarded`() {
    assertEquals(
      listOf("/opt/compose-preview-server", "serve", "--port", "9000"),
      argv("--server-binary", "/opt/x", "--port", "9000"),
    )
  }

  @Test
  fun `the launcher's own flag is dropped from any position`() {
    assertEquals(
      listOf("/opt/compose-preview-server", "serve", "--port", "9000"),
      argv("--port", "9000", "--server-binary", "/opt/x"),
    )
  }

  /** `--help` is the server's to answer — that is where the flags it accepts are documented. */
  @Test
  fun `help reaches the server`() {
    assertContains(argv("--help"), "--help")
  }

  @Test
  fun `browse adds its defaults exactly once`() {
    val browse = argv(browse = true)

    assertEquals(1, browse.count { it == "--discover" }, browse.toString())
    assertEquals(1, browse.count { it == "--component-browser" }, browse.toString())
    assertEquals(1, browse.count { it == "--no-history" }, browse.toString())
  }

  @Test
  fun `browse honours an explicit no-open`() {
    val browse = argv("--no-open", browse = true)

    assertFalse(browse.contains("--open-browser"), browse.toString())
  }

  @Test
  fun `serve adds no browse defaults`() {
    assertFalse(argv().contains("--component-browser"))
  }
}

class ServerBinaryDiscoveryTest {

  private val nothingOnPath: (String) -> File? = { null }

  @Test
  fun `the flag wins over the environment`() {
    val choice =
      ServerBinaryDiscovery.choose(
        listOf(ServerBinaryDiscovery.FLAG, "/opt/from-flag"),
        env = { "/opt/from-env" },
        pathLookup = nothingOnPath,
      )

    assertEquals("/opt/from-flag", assertNotNull(choice).binary)
    assertEquals(ServerBinaryDiscovery.FLAG, choice.source)
  }

  @Test
  fun `the environment wins over PATH`() {
    val choice =
      ServerBinaryDiscovery.choose(
        emptyList(),
        env = { "/opt/from-env" },
        pathLookup = { File("/usr/bin/compose-preview-server") },
      )

    assertEquals("/opt/from-env", assertNotNull(choice).binary)
    assertEquals(ServerBinaryDiscovery.ENV, choice.source)
  }

  @Test
  fun `PATH is the last resort`() {
    val choice =
      ServerBinaryDiscovery.choose(
        emptyList(),
        env = { null },
        pathLookup = { File("/usr/bin/compose-preview-server") },
      )

    assertEquals("/usr/bin/compose-preview-server", assertNotNull(choice).binary)
    assertEquals("PATH", choice.source)
  }

  /**
   * Unlike the server's build-host discovery, a miss here is a failure — `serve` has nothing to
   * fall back to. The caller reports [ServerBinaryDiscovery.installationHint] and exits.
   */
  @Test
  fun `nothing found is null`() {
    assertNull(
      ServerBinaryDiscovery.choose(emptyList(), env = { null }, pathLookup = nothingOnPath)
    )
  }

  @Test
  fun `a flag with no value falls through rather than consuming the next flag`() {
    assertNull(
      ServerBinaryDiscovery.choose(
        listOf(ServerBinaryDiscovery.FLAG),
        env = { null },
        pathLookup = nothingOnPath,
      )
    )
  }

  /**
   * Someone who has not got the binary needs to be told how to get it, not just that it is absent.
   */
  @Test
  fun `the installation hint names the binary, the variable and the flag`() {
    val hint = ServerBinaryDiscovery.installationHint()

    assertContains(hint, ServerBinaryDiscovery.BINARY)
    assertContains(hint, ServerBinaryDiscovery.ENV)
    assertContains(hint, ServerBinaryDiscovery.FLAG)
    assertTrue(hint.contains("compose-preview-server"), "the hint does not say where it comes from")
  }
}
