package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `mcp serve` as a launcher.
 *
 * The MCP server moved to compose-preview-server (#5176), so this command execs a binary the way
 * `serve` does. What is worth pinning is the same thing [ServeLauncherTest] pins — the argv, the
 * launcher's whole job — plus the two ways this one differs from `serve`: no subcommand word is
 * added (`compose-preview-mcp` IS the server), and it is discovered and cached under its own names
 * so a fetched server and a fetched MCP server never collide.
 */
class McpLauncherTest {

  private fun argv(vararg args: String) =
    McpCommand.mcpLaunchCommand("/opt/compose-preview-mcp", args.toList())

  @Test
  fun `no subcommand word is added`() {
    // `serve` passes "serve" to the server binary; the MCP binary has no subcommands at all, so a
    // word added here would be read as an unknown flag.
    assertEquals(listOf("/opt/compose-preview-mcp"), argv())
  }

  @Test
  fun `caller arguments pass through in order`() {
    assertEquals(
      listOf("/opt/compose-preview-mcp", "--project", "/w/app:app", "--streamable-http"),
      argv("--project", "/w/app:app", "--streamable-http"),
    )
  }

  @Test
  fun `the launcher's own flag is dropped with its value`() {
    // `--mcp-binary` is this CLI's, not the MCP server's: forwarding it makes every invocation
    // fail on an unknown argument.
    assertEquals(
      listOf("/opt/compose-preview-mcp", "--project", "/w/app"),
      argv("--mcp-binary", "/tmp/other", "--project", "/w/app"),
    )
  }

  @Test
  fun `a trailing launcher flag consumes nothing that is not there`() {
    assertEquals(listOf("/opt/compose-preview-mcp"), argv("--mcp-binary"))
  }

  @Test
  fun `the flag wins over the environment and PATH`() {
    val choice =
      ServerBinaryDiscovery.choose(
        listOf("--mcp-binary", "/from/flag"),
        ReleasedDistribution.MCP,
        env = { "/from/env" },
        pathLookup = { File("/from/path") },
        cacheLookup = { File("/from/cache") },
      )
    assertEquals("/from/flag", choice?.binary)
    assertEquals("--mcp-binary", choice?.source)
  }

  @Test
  fun `the MCP environment variable is read, not the server's`() {
    val choice =
      ServerBinaryDiscovery.choose(
        emptyList(),
        ReleasedDistribution.MCP,
        env = { name -> "/from/env".takeIf { name == "COMPOSE_PREVIEW_MCP" } },
        pathLookup = { null },
        cacheLookup = { null },
      )
    assertEquals("/from/env", choice?.binary)
    assertEquals("COMPOSE_PREVIEW_MCP", choice?.source)
  }

  @Test
  fun `nothing anywhere is a miss, which the caller turns into a fetch`() {
    assertNull(
      ServerBinaryDiscovery.choose(
        emptyList(),
        ReleasedDistribution.MCP,
        env = { null },
        pathLookup = { null },
        cacheLookup = { null },
      )
    )
  }

  @Test
  fun `the two distributions cache and fetch under separate names`() {
    // One release, two archives. Sharing a cache directory would let an interrupted fetch of one
    // satisfy the completeness check for the other.
    val version = "3.1.0"
    assertEquals(
      "compose-preview-mcp-$version.tar.gz",
      ServerDistributionProvision.assetName(version, ReleasedDistribution.MCP),
    )
    assertEquals(
      "compose-preview-server-$version.tar.gz",
      ServerDistributionProvision.assetName(version, ReleasedDistribution.SERVER),
    )
    assertTrue(
      ServerDistributionProvision.assetUrl(version, ReleasedDistribution.MCP)
        .startsWith("https://github.com/$PREVIEW_SERVER_REPO/releases/download/v$version/")
    )
    val server = ServerDistributionProvision.defaultCacheRoot(ReleasedDistribution.SERVER)
    val mcp = ServerDistributionProvision.defaultCacheRoot(ReleasedDistribution.MCP)
    assertTrue(server.path != mcp.path, "expected separate cache roots, both were ${server.path}")
  }

  @Test
  fun `the installation hint names the MCP binary and what still works without it`() {
    val hint = ServerBinaryDiscovery.installationHint(ReleasedDistribution.MCP)
    assertTrue(hint.contains("compose-preview-mcp"), hint)
    assertTrue(hint.contains("COMPOSE_PREVIEW_MCP"), hint)
    // `mcp install` and `mcp doctor` are offline and stayed in this CLI; saying so is the
    // difference between "MCP is broken" and "one subcommand needs a download".
    assertTrue(hint.contains("mcp install"), hint)
  }
}
