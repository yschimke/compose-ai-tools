package ee.schimke.composeai.cli

/**
 * One of the distributions this CLI launches but does not contain.
 *
 * Two of them now, and both ship from the same repository and the same release
 * ([PREVIEW_SERVER_REPO], tagged `v<`[SERVE_VERSION]`>`): the preview server behind `serve`,
 * `browse` and `ui-builder`, and the MCP server behind `mcp serve`. They arrived for the same
 * reason at two different times — `serve` stopped linking the server when the dependency cycle
 * closed (#5177), and `mcp` stopped linking `:mcp` when the layer rule placed that module in the
 * server's repository (#5176) — and they are described by one type rather than two parallel objects
 * because everything except the names below is identical: the same discovery ordering, the same
 * fetch-on-first-use, the same cache layout, the same failure text shape.
 *
 * One pin covers both. The MCP tarball is attached to the *same* release as the server
 * distribution, so a single reviewed pin move keeps the pair in step; a second version would let
 * them skew, and there is no cadence on which they would skew usefully.
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
