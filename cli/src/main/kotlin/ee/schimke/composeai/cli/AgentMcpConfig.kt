package ee.schimke.composeai.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Pure helpers that produce the on-disk representation each agent host expects for the
 * `compose-preview-mcp` MCP server entry, given the absolute launcher path and project dir.
 *
 * - Antigravity: JSON file at `~/.gemini/antigravity/mcp_config.json`, merged into `mcpServers`.
 * - Codex: TOML file at `~/.codex/config.toml`, with a `[mcp_servers.compose-preview-mcp]` table
 *   replaced in place (or appended when absent). Hand-rolled because the rest of the codebase
 *   doesn't pull in a TOML library, and our table is a fixed, small shape so a section-level edit
 *   is safe enough.
 * - Claude Code: not a config file — invoked via `claude mcp add --scope user`. We expose the argv
 *   here so the caller can shell out and tests can assert the construction.
 */
internal object AgentMcpConfig {

  const val SERVER_NAME = "compose-preview-mcp"

  private val JSON: Json = Json { prettyPrint = true }

  /** Merge a `compose-preview-mcp` entry into Antigravity's `mcpServers` map. */
  fun mergeAntigravityConfig(existing: String?, launcher: String, projectAbsPath: String): String {
    val parsed: JsonObject =
      if (existing.isNullOrBlank()) JsonObject(emptyMap())
      else Json.parseToJsonElement(existing).jsonObject

    val existingServers = parsed["mcpServers"]?.jsonObject ?: JsonObject(emptyMap())
    val server = buildJsonObject {
      put("command", JsonPrimitive(launcher))
      put(
        "args",
        JsonArray(
          listOf(
            JsonPrimitive("mcp"),
            JsonPrimitive("serve"),
            JsonPrimitive("--project=$projectAbsPath"),
          )
        ),
      )
    }
    val mergedServers = JsonObject(existingServers + (SERVER_NAME to server))
    val merged = JsonObject(parsed + ("mcpServers" to mergedServers))
    return JSON.encodeToString(JsonObject.serializer(), merged) + "\n"
  }

  /**
   * Replace (or append) the `[mcp_servers.compose-preview-mcp]` table in a Codex `config.toml`,
   * preserving every other line verbatim. Idempotent: a second call with the same inputs yields the
   * same file contents (modulo a trailing newline). When `existing` is null/empty, returns a file
   * containing only our table.
   */
  fun mergeCodexConfig(existing: String?, launcher: String, projectAbsPath: String): String {
    val block = buildString {
      appendLine("[mcp_servers.$SERVER_NAME]")
      appendLine("command = ${tomlString(launcher)}")
      appendLine("args = [\"mcp\", \"serve\", ${tomlString("--project=$projectAbsPath")}]")
    }

    if (existing.isNullOrBlank()) return block

    val lines = existing.lines()
    val out = StringBuilder()
    var i = 0
    var replaced = false
    while (i < lines.size) {
      val line = lines[i]
      if (line.trim() == "[mcp_servers.$SERVER_NAME]") {
        // Skip our existing block: from this header up to the next top-level header (a line
        // starting with "[" with no leading whitespace) or EOF. Trailing blank lines inside the
        // block are dropped along with it.
        i++
        while (i < lines.size && !lines[i].startsWith("[")) i++
        // Insert the fresh block where the old one stood. Match the surrounding spacing: ensure
        // a single blank line before it if there was content above.
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
   * Argv for `claude mcp add --scope user compose-preview-mcp -- <launcher> mcp serve --project=…`.
   */
  fun claudeMcpAddCommand(launcher: String, projectAbsPath: String): List<String> =
    listOf(
      "claude",
      "mcp",
      "add",
      "--scope",
      "user",
      SERVER_NAME,
      "--",
      launcher,
      "mcp",
      "serve",
      "--project=$projectAbsPath",
    )

  /** Argv for `claude mcp remove --scope user compose-preview-mcp` (used to upsert). */
  fun claudeMcpRemoveCommand(): List<String> =
    listOf("claude", "mcp", "remove", "--scope", "user", SERVER_NAME)

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
}
