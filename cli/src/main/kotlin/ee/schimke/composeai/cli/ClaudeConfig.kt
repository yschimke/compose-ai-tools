package ee.schimke.composeai.cli

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Where Claude Code keeps its config, and whether the `compose-preview` plugin already provides the
 * server.
 *
 * Claude Code reads `~/.claude.json` and `~/.claude/` by default, and both from
 * `$CLAUDE_CONFIG_DIR` when that is set. `claude mcp add` honours the variable, so reading a fixed
 * `~/.claude.json` would inspect one file while `claude` writes another.
 *
 * When the compose-ag-plugin `compose-preview` plugin is installed it registers the server itself
 * (`plugin:compose-preview:compose-preview-mcp`), and a user-scope entry is a second copy of the
 * same tools (yschimke/compose-ai-tools#5648, yschimke/compose-ag-plugin#87).
 */
internal class ClaudeConfig(
  private val home: File,
  configDirOverride: String? = System.getenv("CLAUDE_CONFIG_DIR"),
) {

  private val override: File? = configDirOverride?.takeIf { it.isNotBlank() }?.let(::File)

  val configDir: File = override ?: File(home, ".claude")

  /** The file holding user- and local-scope MCP entries. */
  val claudeJson: File =
    if (override != null) File(override, ".claude.json") else File(home, ".claude.json")

  val installedPlugins: File = File(configDir, "plugins/installed_plugins.json")

  /** True when `installed_plugins.json` lists a `compose-preview@<marketplace>` plugin. */
  val pluginInstalled: Boolean
    get() {
      val text =
        installedPlugins.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
      val root =
        text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
      val plugins = root?.get("plugins") as? JsonObject ?: return false
      return plugins.keys.any { it.startsWith("$PLUGIN_NAME@") }
    }

  /** True when [claudeJson] holds a user-scope `compose-preview-mcp` entry. */
  fun hasUserEntry(): Boolean =
    McpHostRepair.hasClaudeUserEntry(claudeJson.takeIf { it.isFile }?.readText())

  companion object {
    const val PLUGIN_NAME = "compose-preview"
  }
}

internal fun duplicateClaudeEntry(file: File): String =
  "remove ${AgentMcpConfig.SERVER_NAME} from ${file.path}: it duplicates the plugin's server " +
    "(claude mcp remove ${AgentMcpConfig.SERVER_NAME} --scope user)"
