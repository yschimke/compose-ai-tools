package ee.schimke.composeai.cli

import java.io.File

/**
 * Finds the published `compose-preview-server` binary that `serve` and `browse` exec.
 *
 * The mirror image of the server's own build-host discovery, and deliberately the same shape, so an
 * operator who has learned one has learned both: an explicit flag, then the environment, then
 * `PATH`. Most explicit first, because the failure this ordering prevents is running a binary the
 * user did not mean.
 *
 * Unlike the server's, a miss here IS a failure. `serve` has nothing to fall back to — the server
 * body left this repository — so the caller reports how to install it rather than degrading.
 */
internal object ServerBinaryDiscovery {

  const val FLAG: String = "--server-binary"
  const val ENV: String = "COMPOSE_PREVIEW_SERVER"
  const val BINARY: String = "compose-preview-server"

  data class Choice(val binary: String, val source: String)

  fun choose(
    args: List<String>,
    env: (String) -> String? = System::getenv,
    pathLookup: (String) -> File? = ::onPath,
  ): Choice? {
    flagValue(args)?.let {
      return Choice(it, FLAG)
    }
    env(ENV)
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return Choice(it.trim(), ENV)
      }
    return pathLookup(BINARY)?.let { Choice(it.path, "PATH") }
  }

  private fun flagValue(args: List<String>): String? {
    val index = args.indexOf(FLAG)
    if (index < 0 || index + 1 >= args.size) return null
    return args[index + 1].takeIf { it.isNotBlank() }?.trim()
  }

  /**
   * The first executable named [binary] on `PATH`.
   *
   * The working directory is deliberately not consulted: resolving a server from `.` would let a
   * checked-out repository decide what this command executes.
   */
  private fun onPath(binary: String): File? =
    System.getenv("PATH")
      ?.split(File.pathSeparator)
      ?.asSequence()
      ?.filter { it.isNotBlank() }
      ?.map { File(it, binary) }
      ?.firstOrNull { it.isFile && it.canExecute() }

  /** What to tell someone who has not got one. */
  fun installationHint(): String =
    """
    compose-preview serve needs the preview server binary, which ships separately.

    The server moved to yschimke/compose-preview-server and is published as
    `ee.schimke.composeai:compose-preview-serve`; its distribution provides `$BINARY`.
    Install it, then either put it on PATH, set $ENV=/path/to/$BINARY, or pass
    $FLAG /path/to/$BINARY.

    Everything else this CLI does — render, show, bundle, history, a11y — is unaffected and
    needs no server.
    """
      .trimIndent()
}
