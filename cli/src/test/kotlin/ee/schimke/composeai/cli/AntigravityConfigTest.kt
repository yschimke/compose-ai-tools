package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Antigravity has two candidate MCP config paths and a plugin that provides the server itself
 * (yschimke/compose-ai-tools#5584); `mcp install` must pick the right file and `mcp doctor` must
 * say what it found.
 */
class AntigravityConfigTest {

  private val home: File = Files.createTempDirectory("agy-home").toFile()
  private val config = AntigravityConfig(home)
  private val entry =
    """{"mcpServers":{"compose-preview-mcp":{"command":"/bin/compose-preview","args":["mcp","serve"]}}}"""

  private fun write(file: File, text: String) {
    file.parentFile.mkdirs()
    file.writeText(text)
  }

  @Test
  fun `nothing installed targets the legacy path and reports nothing`() {
    assertFalse(config.present())
    assertEquals(config.legacyConfig, config.target())
    assertEquals(emptyList(), inspectAntigravity(config))
  }

  @Test
  fun `only the current config directory targets the current path`() {
    config.currentConfig.parentFile.mkdirs()
    assertTrue(config.present())
    assertEquals(config.currentConfig, config.target())
  }

  @Test
  fun `both directories and no files keep the legacy default`() {
    config.legacyConfig.parentFile.mkdirs()
    config.currentConfig.parentFile.mkdirs()
    assertEquals(config.legacyConfig, config.target())
  }

  @Test
  fun `an existing file wins over an empty directory`() {
    config.legacyConfig.parentFile.mkdirs()
    write(config.currentConfig, """{"mcpServers":{}}""")
    assertEquals(config.currentConfig, config.target())
  }

  @Test
  fun `the file holding our entry wins so it is repaired in place`() {
    write(config.legacyConfig, """{"mcpServers":{}}""")
    write(config.currentConfig, entry)
    assertEquals(config.currentConfig, config.target())
  }

  @Test
  fun `doctor lists both paths and the plugin state`() {
    write(config.legacyConfig, entry)
    val messages = inspectAntigravity(config).map { it.level to it.message }
    assertEquals(
      listOf(
        "info" to "${config.legacyConfig.path}: has compose-preview-mcp",
        "info" to "${config.currentConfig.path}: not present",
        "info" to "compose-preview plugin not installed",
      ),
      messages,
    )
  }

  @Test
  fun `doctor warns when a global entry duplicates the installed plugin`() {
    config.pluginDir.mkdirs()
    write(config.currentConfig, entry)
    write(config.legacyConfig, """{"mcpServers":{"other":{"command":"x"}}}""")
    assertTrue(config.pluginInstalled)
    val findings = inspectAntigravity(config)
    assertEquals(
      "${config.legacyConfig.path}: present, no compose-preview-mcp entry",
      findings[0].message,
    )
    val warnings = findings.filter { it.level == "warning" }
    assertEquals(listOf("antigravity.duplicate"), warnings.map { it.id })
    assertTrue(config.currentConfig.path in warnings.single().message)
  }

  @Test
  fun `repairAll scans both antigravity candidates`() {
    assertEquals(
      listOf(config.legacyConfig, config.currentConfig),
      McpHostRepair.defaultHostFiles(home, null).antigravity,
    )
  }
}
