package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Claude Code reads its config from `$CLAUDE_CONFIG_DIR` when set, and the compose-ag-plugin plugin
 * provides the server itself; `mcp install` must not add a second copy
 * (yschimke/compose-ai-tools#5648).
 */
class ClaudeConfigTest {

  private val home: File = Files.createTempDirectory("claude-home").toFile()

  private fun write(file: File, text: String) {
    file.parentFile.mkdirs()
    file.writeText(text)
  }

  private fun installed(vararg keys: String) =
    """{"version":2,"plugins":{${keys.joinToString(",") { "\"$it\":[{\"scope\":\"user\"}]" }}}}"""

  @Test
  fun `default paths are under home`() {
    val config = ClaudeConfig(home, configDirOverride = null)
    assertEquals(File(home, ".claude.json"), config.claudeJson)
    assertEquals(File(home, ".claude/plugins/installed_plugins.json"), config.installedPlugins)
  }

  @Test
  fun `CLAUDE_CONFIG_DIR moves both the json and the plugin list`() {
    val dir = File(home, "isolated")
    val config = ClaudeConfig(home, configDirOverride = dir.path)
    assertEquals(File(dir, ".claude.json"), config.claudeJson)
    assertEquals(File(dir, "plugins/installed_plugins.json"), config.installedPlugins)
  }

  @Test
  fun `a blank CLAUDE_CONFIG_DIR is ignored`() {
    assertEquals(File(home, ".claude.json"), ClaudeConfig(home, configDirOverride = " ").claudeJson)
  }

  @Test
  fun `no plugin list means the plugin is not installed`() {
    assertFalse(ClaudeConfig(home, configDirOverride = null).pluginInstalled)
  }

  @Test
  fun `the compose-preview plugin from any marketplace counts`() {
    val config = ClaudeConfig(home, configDirOverride = null)
    write(
      config.installedPlugins,
      installed("yschimke-skills@yschimke-skills", "compose-preview@compose-ag-plugin"),
    )
    assertTrue(config.pluginInstalled)
  }

  @Test
  fun `other plugins with a similar name do not count`() {
    val config = ClaudeConfig(home, configDirOverride = null)
    write(
      config.installedPlugins,
      installed("compose-catalogs@compose-ag-plugin", "compose-preview-ci@x"),
    )
    assertFalse(config.pluginInstalled)
  }

  @Test
  fun `an unreadable plugin list is not installed`() {
    val config = ClaudeConfig(home, configDirOverride = null)
    write(config.installedPlugins, "not json")
    assertFalse(config.pluginInstalled)
  }

  @Test
  fun `a user-scope entry is reported as a duplicate`() {
    val config = ClaudeConfig(home, configDirOverride = null)
    assertFalse(config.hasUserEntry())
    write(
      config.claudeJson,
      """{"mcpServers":{"compose-preview-mcp":{"command":"/bin/compose-preview","args":["mcp","serve"]}}}""",
    )
    assertTrue(config.hasUserEntry())
    assertEquals(
      "remove compose-preview-mcp from ${config.claudeJson.path}: it duplicates the plugin's server " +
        "(claude mcp remove compose-preview-mcp --scope user)",
      duplicateClaudeEntry(config.claudeJson),
    )
  }

  @Test
  fun `mcp repair scans the resolved claude json`() {
    val dir = File(home, "isolated")
    assertEquals(
      File(dir, ".claude.json"),
      McpHostRepair.defaultHostFiles(home, null, ClaudeConfig(home, dir.path)).claudeJson,
    )
  }
}
