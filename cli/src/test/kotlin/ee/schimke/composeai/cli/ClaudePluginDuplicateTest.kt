package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * One Claude Code session ran two `compose-preview mcp serve` processes: the `compose-preview`
 * plugin's own `.mcp.json` entry and a user-scope `compose-preview-mcp` entry an earlier `mcp
 * install` had written (yschimke/compose-ai-tools#6363). `mcp repair` and `update` remove the
 * user-scope entry once the plugin is installed and enabled — but only an entry this CLI wrote, and
 * only in Claude Code.
 */
class ClaudePluginDuplicateTest {

  private val home: File = Files.createTempDirectory("claude-home").toFile()
  private val claude = ClaudeConfig(home, configDirOverride = null)
  private val stable = File(home, ".local/bin/compose-preview").path
  private val exists: (String) -> Boolean = { it == stable }

  private fun write(file: File, text: String) {
    file.parentFile.mkdirs()
    file.writeText(text)
  }

  private fun installPlugin(
    scope: String = "user",
    enabled: String? = "true",
    id: String = "compose-preview@compose-agent-plugins",
  ) {
    write(
      claude.installedPlugins,
      """{"version":2,"plugins":{"$id":[{"scope":"$scope","installPath":"/x","version":"1.0.0"}]}}""",
    )
    if (enabled != null) {
      write(claude.userSettings, """{"enabledPlugins":{"$id":$enabled}}""")
    }
  }

  private fun cliEntry(command: String = stable, extra: String = "") =
    """{"type":"stdio","command":"$command","args":["mcp","serve"],"env":{}$extra}"""

  private fun writeClaudeJson(userEntry: String?) {
    val user = userEntry?.let { """"compose-preview-mcp":$it,""" } ?: ""
    write(
      claude.claudeJson,
      """{"mcpServers":{$user"other":{"command":"x"}},""" +
        """"projects":{"/w":{"mcpServers":{"compose-preview-mcp":""" +
        """{"command":"$stable","args":["mcp","serve","--project=/w"]}}}}}""",
    )
  }

  private fun servers(): JsonObject =
    Json.parseToJsonElement(claude.claudeJson.readText()).jsonObject["mcpServers"]!!.jsonObject

  private fun files(): McpHostRepair.HostFiles {
    val codex = File(home, ".codex/config.toml")
    write(
      codex,
      "[mcp_servers.compose-preview-mcp]\ncommand = \"$stable\"\nargs = [\"mcp\", \"serve\"]\n",
    )
    val antigravity = File(home, ".gemini/antigravity/mcp_config.json")
    write(antigravity, """{"mcpServers":{"compose-preview-mcp":${cliEntry()}}}""")
    return McpHostRepair.HostFiles(claude.claudeJson, listOf(antigravity), codex, null)
  }

  // -- plugin detection --------------------------------------------------------------------------

  @Test
  fun `a user-scope install enabled in user settings is enabled`() {
    installPlugin()
    assertTrue(claude.pluginEnabledForUser)
  }

  @Test
  fun `the extended enabledPlugins form counts as enabled`() {
    installPlugin(enabled = "\"^1.0.0\"")
    assertTrue(claude.pluginEnabledForUser)
  }

  @Test
  fun `a v1 plugin list has no scope and is user-wide`() {
    write(
      claude.installedPlugins,
      """{"version":1,"plugins":{"compose-preview@m":{"version":"1","installPath":"/x"}}}""",
    )
    write(claude.userSettings, """{"enabledPlugins":{"compose-preview@m":true}}""")
    assertTrue(claude.pluginEnabledForUser)
  }

  @Test
  fun `a disabled, unrecorded or project-scope plugin is not enabled for the user`() {
    installPlugin(enabled = "false")
    assertTrue(claude.pluginInstalled)
    assertFalse(claude.pluginEnabledForUser)
    installPlugin(enabled = null)
    claude.userSettings.delete()
    assertFalse(claude.pluginEnabledForUser)
    installPlugin(scope = "project")
    assertFalse(claude.pluginEnabledForUser)
    installPlugin(id = "compose-preview-ci@m")
    assertFalse(claude.pluginEnabledForUser)
    write(claude.userSettings, "not json")
    assertFalse(claude.pluginEnabledForUser)
  }

  // -- which entries are ours --------------------------------------------------------------------

  private fun ours(entry: String) =
    McpHostRepair.isCliWrittenClaudeUserEntry("""{"mcpServers":{"compose-preview-mcp":$entry}}""")

  @Test
  fun `only the shape mcp install writes is ours`() {
    assertTrue(ours(cliEntry()))
    assertTrue(ours("""{"command":"/opt/bin/compose-preview","args":["mcp","serve"]}"""))
    assertTrue(ours("""{"command":"$stable","args":["mcp","serve","--project=/w"]}"""))
    assertTrue(
      ours("""{"command":"C:\\Users\\u\\bin\\compose-preview.bat","args":["mcp","serve"]}""")
    )

    assertFalse(ours(cliEntry(command = "/usr/bin/my-wrapper")))
    assertFalse(ours(cliEntry(extra = ""","cwd":"/w"""")))
    assertFalse(ours("""{"command":"$stable","args":["mcp","serve"],"env":{"X":"1"}}"""))
    assertFalse(ours("""{"command":"$stable","args":["mcp","serve","--verbose"]}"""))
    assertFalse(ours("""{"type":"http","url":"http://localhost"}"""))
    assertFalse(ours("""{"command":"$stable"}"""))
    assertFalse(McpHostRepair.isCliWrittenClaudeUserEntry("""{"mcpServers":{}}"""))
  }

  // -- repairAll ---------------------------------------------------------------------------------

  @Test
  fun `repair removes our user entry when the plugin is enabled, leaving everything else`() {
    installPlugin()
    writeClaudeJson(cliEntry())
    val files = files()
    val codexBefore = files.codex.readText()
    val antigravityBefore = files.antigravity.single().readText()

    val changes =
      McpHostRepair.repairAll(
        files,
        stable,
        exists,
        claude = null,
        claudePluginEnabled = claude.pluginEnabledForUser,
      )

    assertEquals(1, changes.size, changes.toString())
    assertTrue(changes.single().startsWith("removed claude (user) compose-preview-mcp"))
    assertEquals("==> ${changes.single()}", McpHostRepair.report(changes.single()))
    assertFalse("compose-preview-mcp" in servers())
    assertTrue("other" in servers())
    // The local-scope (per-project) entry and the other hosts are untouched.
    assertTrue("compose-preview-mcp" in claude.claudeJson.readText())
    assertEquals(codexBefore, files.codex.readText())
    assertEquals(antigravityBefore, files.antigravity.single().readText())
    // Idempotent.
    assertTrue(
      McpHostRepair.repairAll(files, stable, exists, claude = null, claudePluginEnabled = true)
        .isEmpty()
    )
  }

  @Test
  fun `repair goes through claude mcp remove when claude is on PATH`() {
    writeClaudeJson(cliEntry())
    val calls = mutableListOf<List<String>>()
    val changes =
      McpHostRepair.repairAll(files(), stable, exists, claudePluginEnabled = true) { argv, dir ->
        calls += argv
        assertEquals(null, dir)
        writeClaudeJson(null) // what `claude mcp remove` does to the file
        0
      }
    assertEquals(
      listOf(listOf("claude", "mcp", "remove", "--scope", "user", "compose-preview-mcp")),
      calls,
    )
    assertTrue(changes.single().endsWith("(claude mcp remove --scope user)"), changes.toString())
  }

  @Test
  fun `repair keeps an entry it did not write and says so`() {
    val custom = """{"command":"/usr/bin/my-wrapper","args":["--debug"]}"""
    writeClaudeJson(custom)
    val changes =
      McpHostRepair.repairAll(files(), stable, { true }, claude = null, claudePluginEnabled = true)
    assertTrue(changes.single().startsWith("kept claude (user)"), changes.toString())
    assertEquals(Json.parseToJsonElement(custom), servers()["compose-preview-mcp"])
  }

  @Test
  fun `without the plugin our user entry stays`() {
    writeClaudeJson(cliEntry())
    val changes =
      McpHostRepair.repairAll(files(), stable, exists, claude = null, claudePluginEnabled = false)
    assertTrue(changes.isEmpty(), changes.toString())
    assertTrue("compose-preview-mcp" in servers())
  }

  @Test
  fun `a broken user entry is removed rather than repaired when the plugin is enabled`() {
    writeClaudeJson(cliEntry(command = "/home/u/cli/compose-preview-0.9.3/bin/compose-preview"))
    val changes =
      McpHostRepair.repairAll(files(), stable, exists, claude = null, claudePluginEnabled = true)
    assertEquals(1, changes.size, changes.toString())
    assertTrue(changes.single().startsWith("removed"))
    assertFalse("compose-preview-mcp" in servers())
  }
}
