package ee.schimke.composeai.cli

import java.io.File

/**
 * Where Antigravity keeps its global MCP config, and whether the `compose-preview` plugin already
 * provides the server.
 *
 * Two files are in circulation. `~/.gemini/antigravity/mcp_config.json` is the one this CLI has
 * always written. `~/.gemini/config/` is where `agy` 1.2.12 installs plugins
 * (`~/.gemini/config/plugins/<name>`, yschimke/compose-ag-plugin evidence of 2026-09-27), and
 * `~/.gemini/config/mcp_config.json` is the path given for the current Antigravity CLI/IDE. Which
 * one Antigravity reads has not been confirmed (yschimke/compose-ag-plugin#6), so neither is
 * preferred blindly: an existing file wins over a guessed one, and the legacy path stays the
 * default when nothing distinguishes them.
 *
 * When the plugin is installed it registers the server itself (namespaced
 * `compose-preview_compose-preview-mcp`), and a global entry is a second copy of the same tools —
 * the "two `compose-preview` servers" problem in compose-ag-plugin's troubleshooting guide.
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
