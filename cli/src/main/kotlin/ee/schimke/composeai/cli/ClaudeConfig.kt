package ee.schimke.composeai.cli

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Where Claude Code keeps its config, and whether the `compose-preview` plugin already provides the
 * server.
 *
 * Claude Code reads `~/.claude.json` and `~/.claude/` by default, and both from
 * `$CLAUDE_CONFIG_DIR` when that is set. `claude mcp add` honours the variable, so reading a fixed
 * `~/.claude.json` would inspect one file while `claude` writes another.
 *
 * When the compose-agent-plugins `compose-preview` plugin is installed it registers the server
 * itself (`plugin:compose-preview:compose-preview-mcp`), and a user-scope entry is a second copy of
 * the same tools (yschimke/compose-ai-tools#5648, yschimke/compose-agent-plugins#87).
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

  /** User settings, where `claude plugin install --scope user` records `enabledPlugins`. */
  val userSettings: File = File(configDir, "settings.json")

  /** True when `installed_plugins.json` lists a `compose-preview@<marketplace>` plugin. */
  val pluginInstalled: Boolean
    get() = installedPluginRecords().isNotEmpty()

  /**
   * True only when the plugin certainly serves every session: `installed_plugins.json` holds a
   * user-scope install of `compose-preview@<marketplace>` and the user settings enable that same
   * id. This gates *removing* a user-scope entry, so anything less certain (a project- or
   * local-scope install, no `enabledPlugins` record, `false`, an unreadable file) is false and the
   * entry stays.
   */
  val pluginEnabledForUser: Boolean
    get() {
      val enabled = enabledPlugins() ?: return false
      return installedPluginRecords().any { (id, records) ->
        records.any(::isUserScope) && isEnabled(enabled[id])
      }
    }

  /**
   * `plugins` maps `name@marketplace` to install records: an array of `{scope, installPath, ...}`
   * in the current (v2) format, a single user-scope object in v1.
   */
  private fun installedPluginRecords(): Map<String, List<JsonObject>> {
    val plugins = readObject(installedPlugins)?.get("plugins") as? JsonObject ?: return emptyMap()
    return plugins
      .filterKeys { it.startsWith("$PLUGIN_NAME@") }
      .mapValues { (_, value) ->
        when (value) {
          is JsonArray -> value.filterIsInstance<JsonObject>()
          is JsonObject -> listOf(value)
          else -> emptyList()
        }
      }
  }

  private fun enabledPlugins(): JsonObject? =
    readObject(userSettings)?.get("enabledPlugins") as? JsonObject

  private fun isUserScope(record: JsonObject): Boolean {
    val scope = record["scope"] ?: return true // v1 records carry no scope and were user-wide
    return (scope as? JsonPrimitive)?.contentOrNull == "user"
  }

  /** `true`, or the extended (version-constraint) form; never `false` or a missing id. */
  private fun isEnabled(value: JsonElement?): Boolean =
    when (value) {
      null,
      JsonNull -> false
      is JsonPrimitive -> value.booleanOrNull ?: value.isString
      else -> true
    }

  private fun readObject(file: File): JsonObject? {
    val text = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
    return text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
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
