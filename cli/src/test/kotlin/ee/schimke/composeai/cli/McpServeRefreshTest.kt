package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `mcp serve` replaces a stale cached MCP server with the newest release, at most once a day, and
 * `update` prunes stale copies (#5602). No network: the latest-release lookup and the download are
 * fakes.
 */
class McpServeRefreshTest {
  private val tmp = Files.createTempDirectory("mcp-refresh-test-").toFile()
  private val stamp = File(tmp, ServerBinaryDiscovery.REFRESH_STAMP)
  private val day = ServerBinaryDiscovery.REFRESH_INTERVAL_MS
  private val now = 10 * day

  @AfterTest fun cleanup() = tmp.deleteRecursively().let {}

  private fun cached(version: String) =
    ServerBinaryDiscovery.Choice(
      "/cache/preview-mcp/$version/bin/compose-preview-mcp",
      ServerBinaryDiscovery.CACHE,
    )

  private fun refresh(
    choice: ServerBinaryDiscovery.Choice,
    latest: () -> String? = { "3.78.0" },
    provision: (String) -> ServerBinaryDiscovery.Choice? = { cached(it) },
    requested: String? = null,
    offline: Boolean = false,
    log: MutableList<String> = mutableListOf(),
  ) =
    ServerBinaryDiscovery.refreshed(
      choice,
      requested = requested,
      offline = offline,
      stamp = stamp,
      latest = latest,
      provision = provision,
      now = now,
      log = log::add,
    )

  @Test
  fun `a stale cache is replaced by the newest release`() {
    val fetched = mutableListOf<String>()
    val result =
      refresh(
        cached("3.70.0"),
        provision = {
          fetched += it
          cached(it)
        },
      )
    assertEquals(cached("3.78.0"), result)
    assertEquals(listOf("3.78.0"), fetched)
    assertEquals(now, stamp.lastModified())
  }

  @Test
  fun `a current cache is launched without a download`() {
    val current = cached("3.78.0")
    assertSame(current, refresh(current, provision = { error("must not fetch") }))
    assertTrue(stamp.exists())
  }

  @Test
  fun `the newest release is asked for at most once a day`() {
    stamp.createNewFile()
    stamp.setLastModified(now - day + 1000)
    val old = cached("3.70.0")
    assertSame(old, refresh(old, latest = { error("must not ask") }))
    stamp.setLastModified(now - day - 1000)
    assertEquals(cached("3.78.0"), refresh(old))
  }

  @Test
  fun `a network failure launches the cache with one line`() {
    val old = cached("3.70.0")
    val log = mutableListOf<String>()
    assertSame(old, refresh(old, latest = { null }, log = log))
    assertTrue(log.single().contains("launching the cached 3.70.0"), log.toString())
  }

  @Test
  fun `a failed download launches the cache`() {
    val old = cached("3.70.0")
    val log = mutableListOf<String>()
    assertSame(old, refresh(old, provision = { null }, log = log))
    assertTrue(log.last().contains("launching the cached 3.70.0"), log.toString())
  }

  @Test
  fun `overrides, a pinned version and offline mode are never refreshed`() {
    listOf("--mcp-binary", "COMPOSE_PREVIEW_MCP", "PATH").forEach { source ->
      val chosen = ServerBinaryDiscovery.Choice("/opt/compose-preview-mcp", source)
      assertSame(chosen, refresh(chosen, latest = { error("must not ask") }), source)
    }
    val old = cached("3.60.0")
    assertSame(old, refresh(old, requested = "3.60.0", latest = { error("must not ask") }))
    assertSame(old, refresh(old, offline = true, latest = { error("must not ask") }))
    assertTrue(!stamp.exists())
  }

  @Test
  fun `the launch line names version and source`() {
    val label = ReleasedDistribution.MCP.label
    assertEquals(
      "compose-preview: launching the MCP server 3.70.0 (cache)",
      ServerBinaryDiscovery.describeLaunch(cached("3.70.0"), downloaded = false, label),
    )
    assertEquals(
      "compose-preview: launching the MCP server 3.78.0 (downloaded)",
      ServerBinaryDiscovery.describeLaunch(cached("3.78.0"), downloaded = true, label),
    )
    assertEquals(
      "compose-preview: launching the MCP server /opt/mcp (override: PATH)",
      ServerBinaryDiscovery.describeLaunch(
        ServerBinaryDiscovery.Choice("/opt/mcp", "PATH"),
        downloaded = false,
        label,
      ),
    )
  }

  private fun install(version: String) {
    File(tmp, "$version/bin").mkdirs()
    File(tmp, "$version/bin/compose-preview-mcp").writeText("#!/bin/sh")
    File(tmp, "$version/lib").mkdirs()
    File(tmp, "$version/lib/mcp.jar").writeText("jar")
  }

  @Test
  fun `update prunes cached versions older than the newest`() {
    install("3.9.0")
    install("3.70.0")
    install("3.78.0")
    stamp.createNewFile()
    val line =
      UpdateCommand.pruneStaleMcp(
        requested = null,
        offline = false,
        latest = { "3.78.0" },
        cacheRoot = tmp,
      )
    assertEquals(
      listOf("3.78.0"),
      ServerDistributionProvision.cachedVersions(ReleasedDistribution.MCP, tmp, "Linux"),
    )
    assertTrue(line!!.contains("3.9.0, 3.70.0"), line)
    assertTrue(!stamp.exists(), "the refresh stamp should be reset")
  }

  @Test
  fun `update leaves the cache alone when pinned, offline or unresolved`() {
    install("3.70.0")
    fun prune(requested: String? = null, offline: Boolean = false, latest: String? = "3.78.0") =
      UpdateCommand.pruneStaleMcp(requested, offline, { latest }, tmp)
    assertNull(prune(requested = "3.70.0"))
    assertNull(prune(offline = true))
    assertTrue(prune(latest = null)!!.contains("unchanged"))
    assertEquals(
      listOf("3.70.0"),
      ServerDistributionProvision.cachedVersions(ReleasedDistribution.MCP, tmp, "Linux"),
    )
  }
}
