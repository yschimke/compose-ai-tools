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
 * `serve` and `browse` as launchers: the argv is the launcher's whole job, so it is what's pinned
 * (a flag dropped or mis-forwarded starts the server wrong with no compiler to catch it).
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

  /** `ui-builder` launches the server's `ui` command with no defaults of its own. */
  @Test
  fun `ui-builder launches the server's ui command with the caller's argv`() {
    assertEquals(
      listOf("/opt/compose-preview-server", "ui", "--module", "app", "--no-open"),
      ServeCommand(
          listOf("--module", "app", "--no-open"),
          serverCommand = UiBuilderCommand.SERVER_COMMAND,
        )
        .launchCommand("/opt/compose-preview-server"),
    )
  }

  /**
   * `design` forwards the verb and design id untouched; the returned exit code says whether an
   * export was refused.
   */
  @Test
  fun `design launches the server's design command with the caller's argv`() {
    assertEquals(
      listOf("/opt/compose-preview-server", "design", "render", "my-widget", "--out", "cover.png"),
      ServeCommand(
          listOf("render", "my-widget", "--out", "cover.png"),
          serverCommand = DesignCommand.SERVER_COMMAND,
        )
        .launchCommand("/opt/compose-preview-server"),
    )
  }

  /** `--server-binary` is this side's own flag and is the only thing dropped on the way through. */
  @Test
  fun `design drops the launcher's own flag rather than forwarding it`() {
    assertEquals(
      listOf("/opt/from-flag", "design", "list", "--server", "https://preview.coo.ee"),
      ServeCommand(
          listOf(
            "list",
            ServerBinaryDiscovery.FLAG,
            "/opt/from-flag",
            "--server",
            "https://preview.coo.ee",
          ),
          serverCommand = DesignCommand.SERVER_COMMAND,
        )
        .launchCommand("/opt/from-flag"),
    )
  }
}

class ServerBinaryDiscoveryTest {

  private val nothingOnPath: (String) -> File? = { null }
  private val nothingCached: () -> File? = { null }

  @Test
  fun `the flag wins over the environment`() {
    val choice =
      ServerBinaryDiscovery.choose(
        listOf(ServerBinaryDiscovery.FLAG, "/opt/from-flag"),
        env = { "/opt/from-env" },
        pathLookup = nothingOnPath,
        cacheLookup = nothingCached,
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
        cacheLookup = nothingCached,
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
        cacheLookup = {
          File("/home/u/.cache/composeai/preview-server/3.0.0/bin/compose-preview-server")
        },
      )

    assertEquals("/usr/bin/compose-preview-server", assertNotNull(choice).binary)
    assertEquals("PATH", choice.source)
  }

  /**
   * Unlike the server's build-host discovery, a miss here is a failure — `serve` has nothing to
   * fall back to. The caller reports [ServerBinaryDiscovery.installationHint] and exits.
   */
  /**
   * The CLI's own fetched copy is the LAST resort, behind `PATH`: an operator who installed a
   * server chose that one, and a download must never quietly win over a deliberate choice.
   */
  @Test
  fun `the provisioned cache is the last resort`() {
    val cached = File("/home/u/.cache/composeai/preview-server/3.0.0/bin/compose-preview-server")
    val choice =
      ServerBinaryDiscovery.choose(
        emptyList(),
        env = { null },
        pathLookup = nothingOnPath,
        cacheLookup = { cached },
      )

    assertEquals(cached.path, assertNotNull(choice).binary)
    assertEquals(ServerBinaryDiscovery.CACHE, choice.source)
  }

  @Test
  fun `nothing found is null`() {
    assertNull(
      ServerBinaryDiscovery.choose(
        emptyList(),
        env = { null },
        pathLookup = nothingOnPath,
        cacheLookup = nothingCached,
      )
    )
  }

  @Test
  fun `a flag with no value falls through rather than consuming the next flag`() {
    assertNull(
      ServerBinaryDiscovery.choose(
        listOf(ServerBinaryDiscovery.FLAG),
        env = { null },
        pathLookup = nothingOnPath,
        cacheLookup = nothingCached,
      )
    )
  }

  /** The missing-binary hint must say how to get it and that an automatic fetch was attempted. */
  @Test
  fun `the installation hint names the binary, the variable and the flag`() {
    val hint = ServerBinaryDiscovery.installationHint()

    assertContains(hint, ServerBinaryDiscovery.BINARY)
    assertContains(hint, ServerBinaryDiscovery.ENV)
    assertContains(hint, ServerBinaryDiscovery.FLAG)
    assertTrue(hint.contains("compose-preview-server"), "the hint does not say where it comes from")
    assertTrue(hint.contains("fetches it for you"), "the hint does not say a fetch was attempted")
  }
}
