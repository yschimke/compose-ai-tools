package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
  fun `opencode empty file uses the v2 servers shape`() {
    val root = Json.parseToJsonElement(AgentMcpConfig.mergeOpenCodeConfig(null, launcher, project))
    val server =
      root.jsonObject["mcp"]!!
        .jsonObject["servers"]!!
        .jsonObject["compose-preview-mcp"]!!
        .jsonObject
    assertEquals("local", server["type"]!!.jsonPrimitive.content)
    assertEquals(
      listOf(launcher, "mcp", "serve", "--project=$project"),
      server["command"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    assertFalse(server["codemode"]!!.jsonPrimitive.boolean)
    assertFalse("enabled" in server, "OpenCode v2 uses disabled/codemode rather than enabled")
  }

  @Test
  fun `opencode preserves top level mcp and sibling server keys`() {
    val existing =
      """{"theme":"dark","mcp":{"timeout":9000,"servers":{"other":{"type":"remote","url":"https://example.test/mcp"}}}}"""
    val root =
      Json.parseToJsonElement(AgentMcpConfig.mergeOpenCodeConfig(existing, launcher, project))
        .jsonObject
    assertEquals("dark", root["theme"]!!.jsonPrimitive.content)
    assertEquals(9000, root["mcp"]!!.jsonObject["timeout"]!!.jsonPrimitive.content.toInt())
    assertTrue("other" in root["mcp"]!!.jsonObject["servers"]!!.jsonObject)
    assertTrue("compose-preview-mcp" in root["mcp"]!!.jsonObject["servers"]!!.jsonObject)
  }

  @Test
  fun `opencode upserts an existing server`() {
    val existing =
      """{"mcp":{"servers":{"compose-preview-mcp":{"type":"local","command":["old"]}}}}"""
    val out = AgentMcpConfig.mergeOpenCodeConfig(existing, launcher, project)
    val servers =
      Json.parseToJsonElement(out).jsonObject["mcp"]!!.jsonObject["servers"]!!.jsonObject
    assertEquals(setOf("compose-preview-mcp"), servers.keys)
    assertFalse(out.contains("old"), "old entry replaced:\n$out")
    assertTrue(out.contains(launcher), "new launcher present:\n$out")
  }

  @Test
  fun `opencode refuses jsonc and commented json without mistaking strings for comments`() {
    assertEquals(
      "OpenCode config uses JSONC",
      AgentMcpConfig.openCodeRewriteRefusal("opencode.jsonc", "{}"),
    )
    assertEquals(
      "OpenCode config contains comments",
      AgentMcpConfig.openCodeRewriteRefusal("opencode.json", "{\n// keep this\n}"),
    )
    assertEquals(
      "OpenCode config contains comments",
      AgentMcpConfig.openCodeRewriteRefusal("opencode.json", "{/* keep this */}"),
    )
    assertEquals(
      "OpenCode config is not strict JSON (it may use JSONC syntax)",
      AgentMcpConfig.openCodeRewriteRefusal("opencode.json", "{\"theme\": \"dark\",}"),
    )
    assertNull(
      AgentMcpConfig.openCodeRewriteRefusal(
        "opencode.json",
        """{"url":"https://example.test/mcp","note":"not /* a comment */"}""",
      )
    )
  }

  @Test
  fun `opencode detection accepts each documented signal`() {
    assertTrue(McpCommand.isOpenCodeDetected(true, false, null))
    assertTrue(McpCommand.isOpenCodeDetected(false, true, null))
    assertTrue(McpCommand.isOpenCodeDetected(false, false, "1"))
    assertFalse(McpCommand.isOpenCodeDetected(false, false, "true"))
    assertFalse(McpCommand.isOpenCodeDetected(false, false, null))
  }

  @Test
  fun `opencode config directory honors XDG config home`() {
    assertEquals(
      File("/custom/config/opencode"),
      McpCommand.openCodeConfigDirectory(File("/account/home"), "/runtime/home", "/custom/config"),
    )
    assertEquals(
      File("/runtime/home/.config/opencode"),
      McpCommand.openCodeConfigDirectory(File("/account/home"), "/runtime/home", null),
    )
    assertEquals(
      File("/home/test/.config/opencode"),
      McpCommand.openCodeConfigDirectory(File("/home/test"), null, null),
    )
    assertEquals(
      File("/runtime/home/.config/opencode"),
      McpCommand.openCodeConfigDirectory(File("/account/home"), "/runtime/home", "."),
    )
    assertEquals(
      File("/account/home/.config/opencode"),
      McpCommand.openCodeConfigDirectory(File("/account/home"), "relative-home", null),
    )
    assertNull(McpCommand.openCodeConfigDirectory(File("relative-jvm-home"), ".", "?"))
  }

  @Test
  fun `opencode config target reuses existing jsonc at either scope`() {
    val root = Files.createTempDirectory("opencode-config-test").toFile()
    try {
      val userHome = File(root, "home")
      val xdgHome = File(root, "xdg")
      val globalJsonc = File(xdgHome, "opencode/opencode.jsonc")
      globalJsonc.parentFile.mkdirs()
      globalJsonc.writeText("{}")
      val project = File(root, "project")
      project.mkdirs()
      val projectJsonc = File(project, "opencode.jsonc")
      projectJsonc.writeText("{}")

      assertEquals(
        globalJsonc,
        McpCommand.openCodeConfigFile(userHome, null, xdgHome.path, project, "user"),
      )
      assertEquals(
        projectJsonc,
        McpCommand.openCodeConfigFile(userHome, null, xdgHome.path, project, "project"),
      )
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `opencode config resolution is lazy when installation is disabled`() {
    var defaultResolved = false
    assertNull(
      McpCommand.selectOpenCodeConfig(null, installOpenCode = false) {
        defaultResolved = true
        error("no absolute OpenCode config home")
      }
    )
    assertFalse(defaultResolved, "disabled OpenCode must not require a default config home")

    val explicit = File("/explicit/opencode.json")
    assertEquals(
      explicit,
      McpCommand.selectOpenCodeConfig(explicit, installOpenCode = false) {
        error("explicit config should win")
      },
    )
  }

  @Test
  fun `plugin hints are selected by detected host and can be disabled`() {
    val hints = AgentMcpConfig.pluginInstallHints(setOf("claude", "codex"), enabled = true)
    assertEquals(listOf("claude", "claude", "codex", "codex"), hints.map { it.host })
    assertTrue(
      hints.any {
        it.command == "/plugins" && it.note == "Enable compose-preview in the plugin manager."
      }
    )
    assertTrue(
      AgentMcpConfig.pluginInstallHints(setOf("claude", "codex"), enabled = false).isEmpty()
    )
  }

  @Test
  fun `antigravity plugin hint is a complete install and enable sequence`() {
    val commands =
      AgentMcpConfig.pluginInstallHints(setOf("antigravity"), enabled = true).map { it.command }
    assertEquals(
      listOf(
        "git clone https://github.com/yschimke/compose-ag-plugin.git",
        "cd compose-ag-plugin",
        "agy plugin install ./plugins/compose-preview",
        "agy plugin enable compose-preview",
      ),
      commands,
    )
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
