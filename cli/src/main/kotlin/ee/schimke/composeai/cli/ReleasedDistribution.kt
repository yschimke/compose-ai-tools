package ee.schimke.composeai.cli

/**
 * One of the distributions this CLI launches but doesn't contain: the preview server (`serve`,
 * `browse`, `ui-builder`) from [PREVIEW_SERVER_REPO], or the MCP server (`mcp serve`) from
 * [PREVIEW_MCP_REPO]. One type because only the names differ. Each is on its own release train, so
 * each resolves its own newest release and has its own pin.
 */
internal data class ReleasedDistribution(
  /** Launcher script name inside `bin/`, and the name looked up on `PATH`. */
  val binary: String,
  /** Cache segment under `${XDG_CACHE_HOME:-~/.cache}/composeai/`. */
  val cacheDirName: String,
  /** How the thing is named in messages: "the preview server", "the MCP server". */
  val label: String,
  /** This CLI's own flag naming a binary to use instead. Dropped before forwarding. */
  val flag: String,
  /** Environment variable naming a binary to use instead. */
  val env: String,
  /** Which commands need it, for the hint text a miss produces. */
  val usedBy: String,
  /** The GitHub repository (`owner/name`) whose releases carry it. */
  val repository: String,
  /** Environment variable naming the release to fetch instead of the newest. */
  val versionEnv: String,
) {
  internal companion object {
    val SERVER =
      ReleasedDistribution(
        binary = "compose-preview-server",
        cacheDirName = "preview-server",
        label = "the preview server",
        flag = "--server-binary",
        env = "COMPOSE_PREVIEW_SERVER",
        usedBy = "compose-preview serve, browse and ui-builder",
        repository = PREVIEW_SERVER_REPO,
        versionEnv = "COMPOSE_PREVIEW_SERVER_VERSION",
      )

    val MCP =
      ReleasedDistribution(
        binary = "compose-preview-mcp",
        cacheDirName = "preview-mcp",
        label = "the MCP server",
        flag = "--mcp-binary",
        env = "COMPOSE_PREVIEW_MCP",
        usedBy = "compose-preview mcp serve",
        repository = PREVIEW_MCP_REPO,
        versionEnv = "COMPOSE_PREVIEW_MCP_VERSION",
      )
  }
}
