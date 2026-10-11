package ee.schimke.composeai.cli

/**
 * One of the distributions this CLI launches but doesn't contain: the preview server (`serve`,
 * `browse`, `ui-builder`) or the MCP server (`mcp serve`), both from the same [PREVIEW_SERVER_REPO]
 * release. One type because only the names differ. One resolved version covers both, keeping the
 * pair in step.
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
      )

    val MCP =
      ReleasedDistribution(
        binary = "compose-preview-mcp",
        cacheDirName = "preview-mcp",
        label = "the MCP server",
        flag = "--mcp-binary",
        env = "COMPOSE_PREVIEW_MCP",
        usedBy = "compose-preview mcp serve",
      )
  }
}
