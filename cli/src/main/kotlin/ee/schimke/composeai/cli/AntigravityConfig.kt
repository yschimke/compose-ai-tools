package ee.schimke.composeai.cli

import java.io.File

/**
 * Where Antigravity keeps its global MCP config, and whether the `compose-preview` plugin already
 * provides the server. Two candidate files exist (`~/.gemini/antigravity/mcp_config.json`, written
 * historically, and `~/.gemini/config/mcp_config.json`); which one Antigravity reads is
 * unconfirmed, so an existing file wins and the legacy path is the default. An installed plugin
 * registers the server itself, so a global entry would duplicate its tools.
 */
internal class AntigravityConfig(private val home: File) {

  val legacyConfig: File = File(home, ".gemini/antigravity/mcp_config.json")
  val currentConfig: File = File(home, ".gemini/config/mcp_config.json")
  val pluginDir: File = File(home, ".gemini/config/plugins/compose-preview")

  /** Every config file Antigravity may read, legacy first. */
  val candidates: List<File> = listOf(legacyConfig, currentConfig)

  val pluginInstalled: Boolean
    get() = pluginDir.isDirectory

  /** The candidates that exist, with whether each holds a `compose-preview-mcp` entry. */
  fun found(): List<Found> =
    candidates
      .filter { it.isFile }
      .map { file ->
        val text = runCatching { file.readText() }.getOrNull()
        Found(file, hasEntry = McpHostRepair.hasAntigravityEntry(text))
      }

  /**
   * The file `mcp install` should upsert: the one already holding our entry, else the first that
   * exists, else the one whose directory exists (`~/.gemini/antigravity/` first), else the legacy
   * path.
   */
  fun target(): File {
    val existing = found()
    return existing.firstOrNull { it.hasEntry }?.file
      ?: existing.firstOrNull()?.file
      ?: candidates.firstOrNull { it.parentFile.isDirectory }
      ?: legacyConfig
  }

  /** True when any sign of an Antigravity install is under [home]. */
  fun present(): Boolean = candidates.any { it.parentFile.isDirectory }

  data class Found(val file: File, val hasEntry: Boolean)
}

internal fun duplicateAntigravityEntry(file: File): String =
  "remove ${AgentMcpConfig.SERVER_NAME} from ${file.path}: it duplicates the plugin's server"

/**
 * `mcp doctor`'s Antigravity report: which config files exist, which hold our entry, whether the
 * plugin is installed, and a warning when a global entry duplicates the plugin's server. Empty when
 * nothing Antigravity-shaped is present.
 */
internal fun inspectAntigravity(config: AntigravityConfig): List<DoctorFinding> {
  if (!config.present()) return emptyList()
  val found = config.found()
  val findings = mutableListOf<DoctorFinding>()
  config.candidates.forEach { file ->
    val hit = found.firstOrNull { it.file == file }
    val message =
      when {
        hit == null -> "${file.path}: not present"
        hit.hasEntry -> "${file.path}: has ${AgentMcpConfig.SERVER_NAME}"
        else -> "${file.path}: present, no ${AgentMcpConfig.SERVER_NAME} entry"
      }
    findings += DoctorFinding("antigravity.config", "info", message)
  }
  findings +=
    DoctorFinding(
      "antigravity.plugin",
      "info",
      if (config.pluginInstalled) "compose-preview plugin installed at ${config.pluginDir.path}"
      else "compose-preview plugin not installed",
    )
  if (config.pluginInstalled) {
    found
      .filter { it.hasEntry }
      .forEach {
        findings +=
          DoctorFinding("antigravity.duplicate", "warning", duplicateAntigravityEntry(it.file))
      }
  }
  return findings
}
