package ee.schimke.composeai.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Pure helpers producing each agent host's on-disk `compose-preview-mcp` server entry:
 * - Antigravity: `mcp_config.json` under `~/.gemini/antigravity/` or `~/.gemini/config/`
 *   ([AntigravityConfig]), merged into `mcpServers`.
 * - Codex: `~/.codex/config.toml`, replacing or appending `[mcp_servers.compose-preview-mcp]`.
 *   Hand-rolled (no TOML dependency); the table is small and fixed.
 * - OpenCode v2: `~/.config/opencode/opencode.json` (or project-local), merged into `mcp.servers`.
 *   JSONC isn't rewritten since comments can't be preserved.
 * - Claude Code: `claude mcp add --scope user`; the argv is built here for the caller and tests.
 */
internal object AgentMcpConfig {

  const val SERVER_NAME = "compose-preview-mcp"

  private val JSON: Json = Json { prettyPrint = true }

  /** Merge a `compose-preview-mcp` entry into Antigravity's `mcpServers` map. */
  fun mergeAntigravityConfig(existing: String?, launcher: String, projectAbsPath: String?): String {
    val parsed: JsonObject =
      if (existing.isNullOrBlank()) JsonObject(emptyMap())
      else Json.parseToJsonElement(existing).jsonObject

    val existingServers = parsed["mcpServers"]?.jsonObject ?: JsonObject(emptyMap())
    val server = buildJsonObject {
      put("command", JsonPrimitive(launcher))
      put("args", JsonArray(serveArgs(projectAbsPath).map(::JsonPrimitive)))
    }
    val mergedServers = JsonObject(existingServers + (SERVER_NAME to server))
    val merged = JsonObject(parsed + ("mcpServers" to mergedServers))
    return JSON.encodeToString(JsonObject.serializer(), merged) + "\n"
  }

  /** Merge a local server into OpenCode v2's `mcp.servers` map without dropping sibling keys. */
  fun mergeOpenCodeConfig(existing: String?, launcher: String, projectAbsPath: String?): String {
    val parsed: JsonObject =
      if (existing.isNullOrBlank()) JsonObject(emptyMap())
      else Json.parseToJsonElement(existing).jsonObject

    val existingMcp = parsed["mcp"]?.jsonObject ?: JsonObject(emptyMap())
    val existingServers = existingMcp["servers"]?.jsonObject ?: JsonObject(emptyMap())
    val server = buildJsonObject {
      put("type", JsonPrimitive("local"))
      put(
        "command",
        JsonArray((listOf(launcher) + serveArgs(projectAbsPath)).map(::JsonPrimitive)),
      )
      put("codemode", JsonPrimitive(false))
    }
    val mergedServers = JsonObject(existingServers + (SERVER_NAME to server))
    val mergedMcp = JsonObject(existingMcp + ("servers" to mergedServers))
    val merged = JsonObject(parsed + ("mcp" to mergedMcp))
    return JSON.encodeToString(JsonObject.serializer(), merged) + "\n"
  }

  /** A complete v2 fragment users can paste when their OpenCode config cannot be rewritten. */
  fun openCodeConfigSnippet(launcher: String, projectAbsPath: String?): String =
    mergeOpenCodeConfig(null, launcher, projectAbsPath).trimEnd()

  /**
   * Explain why an OpenCode config must be merged by hand, or return null when it is safe to
   * rewrite. Comment-looking text inside JSON strings (for example a URL) is not a comment.
   */
  fun openCodeRewriteRefusal(fileName: String, existing: String?): String? =
    when {
      fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase() == "jsonc" ->
        "OpenCode config uses JSONC"
      existing != null && containsJsonComment(existing) -> "OpenCode config contains comments"
      !existing.isNullOrBlank() && runCatching { Json.parseToJsonElement(existing) }.isFailure ->
        "OpenCode config is not strict JSON (it may use JSONC syntax)"
      else -> null
    }

  /** Install commands copied from compose-agent-plugins' README, kept together for easy updates. */
  fun pluginInstallHints(detectedHosts: Set<String>, enabled: Boolean): List<PluginInstallHint> {
    if (!enabled) return emptyList()
    return PLUGIN_INSTALL_HINTS.filter { it.host in detectedHosts }
  }

  /** One heading per host, with that host's commands indented beneath it. */
  fun renderPluginHints(hints: List<PluginInstallHint>): String = buildString {
    hints
      .groupBy { it.host }
      .forEach { (host, group) ->
        appendLine("  ${PLUGIN_HOST_LABELS[host] ?: host}:")
        group.forEach { hint ->
          appendLine("      ${hint.command}")
          hint.note?.let { appendLine("        ($it)") }
        }
      }
  }

  /**
   * Replace or append the `[mcp_servers.compose-preview-mcp]` table in a Codex `config.toml`,
   * keeping every other line verbatim. Idempotent; an empty `existing` yields just our table.
   */
  fun mergeCodexConfig(existing: String?, launcher: String, projectAbsPath: String?): String {
    val block = buildString {
      appendLine("[mcp_servers.$SERVER_NAME]")
      appendLine("command = ${tomlString(launcher)}")
      appendLine("args = [${serveArgs(projectAbsPath).joinToString(", ") { tomlString(it) }}]")
    }

    if (existing.isNullOrBlank()) return block

    val lines = existing.lines()
    val out = StringBuilder()
    var i = 0
    var replaced = false
    while (i < lines.size) {
      val line = lines[i]
      if (line.trim() == "[mcp_servers.$SERVER_NAME]") {
        // Skip our old block up to the next top-level header or EOF.
        i++
        while (i < lines.size && !lines[i].startsWith("[")) i++
        // Insert the fresh block where the old one was, with one blank line before it.
        if (out.isNotEmpty() && !out.endsWith("\n\n")) {
          if (!out.endsWith("\n")) out.append('\n')
          out.append('\n')
        }
        out.append(block)
        replaced = true
        // The next line (if any) starts a new section header, not part of our block.
        continue
      }
      out.append(line)
      if (i < lines.size - 1) out.append('\n')
      i++
    }

    if (!replaced) {
      val base = out.toString()
      val joiner =
        when {
          base.isEmpty() -> ""
          base.endsWith("\n\n") -> ""
          base.endsWith("\n") -> "\n"
          else -> "\n\n"
        }
      return base + joiner + block
    }
    return out.toString()
  }

  /**
   * `mcp serve`, plus `--project=<dir>` only for a project-scoped entry; a global entry must not
   * pin every session to one checkout.
   */
  fun serveArgs(projectAbsPath: String?): List<String> =
    listOfNotNull("mcp", "serve", projectAbsPath?.let { "--project=$it" })

  /** Argv for `claude mcp add --scope user compose-preview-mcp -- <launcher> mcp serve`. */
  fun claudeMcpAddCommand(launcher: String): List<String> =
    listOf("claude", "mcp", "add", "--scope", "user", SERVER_NAME, "--", launcher) + serveArgs(null)

  /** Argv for `claude mcp remove --scope user compose-preview-mcp` (used to upsert). */
  fun claudeMcpRemoveCommand(): List<String> =
    listOf("claude", "mcp", "remove", "--scope", "user", SERVER_NAME)

  private fun containsJsonComment(value: String): Boolean {
    var inString = false
    var escaped = false
    var index = 0
    while (index < value.length) {
      val char = value[index]
      if (inString) {
        when {
          escaped -> escaped = false
          char == '\\' -> escaped = true
          char == '"' -> inString = false
        }
      } else {
        when {
          char == '"' -> inString = true
          char == '/' &&
            index + 1 < value.length &&
            (value[index + 1] == '/' || value[index + 1] == '*') -> return true
        }
      }
      index++
    }
    return false
  }

  /**
   * TOML basic-string encoding. Codex `config.toml` paths are absolute, so this is conservative.
   */
  private fun tomlString(value: String): String {
    val escaped = buildString {
      append('"')
      for (c in value) {
        when (c) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> append(c)
        }
      }
      append('"')
    }
    return escaped
  }

  private val PLUGIN_HOST_LABELS =
    mapOf("claude" to "claude (in Claude Code)", "codex" to "codex (in a shell, then in Codex)")

  private val PLUGIN_INSTALL_HINTS =
    listOf(
      PluginInstallHint(
        "antigravity",
        "git clone https://github.com/yschimke/compose-agent-plugins.git && cd compose-agent-plugins",
      ),
      PluginInstallHint(
        "antigravity",
        "agy plugin install ./plugins/compose-preview && agy plugin enable compose-preview",
      ),
      PluginInstallHint("claude", "/plugin marketplace add yschimke/compose-agent-plugins"),
      PluginInstallHint("claude", "/plugin install compose-preview@compose-agent-plugins"),
      PluginInstallHint("codex", "codex plugin marketplace add yschimke/compose-agent-plugins"),
      PluginInstallHint("codex", "/plugins", "enable compose-preview in the plugin manager"),
    )
}

internal data class PluginInstallHint(
  val host: String,
  val command: String,
  val note: String? = null,
)
