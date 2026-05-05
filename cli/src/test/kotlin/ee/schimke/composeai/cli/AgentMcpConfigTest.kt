package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Codex writer hand-rolls a section-level TOML edit (no TOML library on the classpath), so
 * exercise the boundary cases: empty file, append, replace-in-place, and idempotent re-write. The
 * Claude argv has no file I/O — assert the exact shape so a typo can't slip past review.
 */
class AgentMcpConfigTest {

  private val launcher = "/abs/bin/compose-preview"
  private val project = "/abs/repo"

  @Test
  fun `codex empty file produces only our table`() {
    val out = AgentMcpConfig.mergeCodexConfig(null, launcher, project)
    assertEquals(
      """
      [mcp_servers.compose-preview-mcp]
      command = "/abs/bin/compose-preview"
      args = ["mcp", "serve", "--project=/abs/repo"]

      """
        .trimIndent(),
      out,
    )
  }

  @Test
  fun `codex appends our table without touching prior content`() {
    val existing =
      """
      # Codex config
      model = "gpt-5"

      [model_providers.openai]
      base_url = "https://api.openai.com/v1"
      """
        .trimIndent()
    val out = AgentMcpConfig.mergeCodexConfig(existing, launcher, project)
    assertTrue(out.startsWith(existing), "prior content preserved verbatim:\n$out")
    assertTrue(out.contains("[mcp_servers.compose-preview-mcp]"), "appended our table:\n$out")
    assertTrue(
      out.contains("args = [\"mcp\", \"serve\", \"--project=/abs/repo\"]"),
      "appended args line:\n$out",
    )
  }

  @Test
  fun `codex replaces existing table in place`() {
    val existing =
      """
      # Codex config
      model = "gpt-5"

      [mcp_servers.compose-preview-mcp]
      command = "/old/path/compose-preview"
      args = ["mcp", "serve", "--project=/old/repo"]

      [mcp_servers.other]
      command = "/usr/bin/other"
      """
        .trimIndent()
    val out = AgentMcpConfig.mergeCodexConfig(existing, launcher, project)
    assertFalse(out.contains("/old/path/compose-preview"), "old path replaced:\n$out")
    assertFalse(out.contains("/old/repo"), "old project replaced:\n$out")
    assertTrue(out.contains(launcher), "new launcher present:\n$out")
    assertTrue(out.contains("[mcp_servers.other]"), "sibling table preserved:\n$out")
    assertEquals(
      1,
      Regex("\\[mcp_servers\\.compose-preview-mcp]").findAll(out).count(),
      "exactly one of our tables: $out",
    )
  }

  @Test
  fun `codex is idempotent`() {
    val first = AgentMcpConfig.mergeCodexConfig(null, launcher, project)
    val second = AgentMcpConfig.mergeCodexConfig(first, launcher, project)
    assertEquals(first, second)
  }

  @Test
  fun `codex escapes special characters in launcher path`() {
    val odd = "/abs/with \"quote\"/compose-preview"
    val out = AgentMcpConfig.mergeCodexConfig(null, odd, project)
    // The escaped form `\"quote\"` must appear; the raw `"quote"` must not break the TOML string.
    assertTrue(out.contains("\\\"quote\\\""), "quote escaped:\n$out")
  }

  @Test
  fun `antigravity merges into mcpServers without dropping siblings`() {
    val existing = """{"mcpServers":{"other-mcp":{"command":"/usr/bin/other"}},"theme":"dark"}"""
    val out = AgentMcpConfig.mergeAntigravityConfig(existing, launcher, project)
    assertTrue(out.contains("\"other-mcp\""), "sibling preserved:\n$out")
    assertTrue(out.contains("\"compose-preview-mcp\""), "ours added:\n$out")
    assertTrue(out.contains("\"theme\""), "top-level keys preserved:\n$out")
  }

  @Test
  fun `claude mcp add argv is the documented shape`() {
    val argv = AgentMcpConfig.claudeMcpAddCommand(launcher, project)
    assertEquals(
      listOf(
        "claude",
        "mcp",
        "add",
        "--scope",
        "user",
        "compose-preview-mcp",
        "--",
        "/abs/bin/compose-preview",
        "mcp",
        "serve",
        "--project=/abs/repo",
      ),
      argv,
    )
  }

  @Test
  fun `claude mcp remove argv targets the same scope and name`() {
    assertEquals(
      listOf("claude", "mcp", "remove", "--scope", "user", "compose-preview-mcp"),
      AgentMcpConfig.claudeMcpRemoveCommand(),
    )
  }
}
