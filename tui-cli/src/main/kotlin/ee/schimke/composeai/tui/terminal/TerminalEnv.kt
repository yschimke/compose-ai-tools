package ee.schimke.composeai.tui.terminal

/**
 * Best-effort terminal-size probe. Tries, in order:
 * 1. `COLUMNS` / `LINES` environment variables (set by interactive shells under `set -o vi`-
 *    enabled bash; absent under `sh`).
 * 2. `stty size` via [Runtime.exec] — works everywhere a controlling TTY exists.
 * 3. A conservative 80×24 fallback so the UI never collapses to width=0.
 *
 * Mosaic doesn't currently expose terminal size to the composition (see `tui-cli/LIMITATIONS.md`
 * item "terminal size as Compose state") — when it gains that, the `App` composable should replace
 * its initial `TerminalSize.probe()` call with the Mosaic-provided value and listen for resizes.
 * Today this is a one-shot read.
 */
data class TerminalSize(val cols: Int, val rows: Int) {

  /** Wide layout shows list + preview + data side-by-side; narrow puts them in tabs. */
  val isWide: Boolean
    get() = cols >= WIDE_BREAKPOINT

  companion object {
    private const val WIDE_BREAKPOINT = 120

    fun probe(): TerminalSize {
      val envCols = System.getenv("COLUMNS")?.toIntOrNull()
      val envRows = System.getenv("LINES")?.toIntOrNull()
      if (envCols != null && envRows != null && envCols > 0 && envRows > 0) {
        return TerminalSize(envCols, envRows)
      }
      val stty = sttySize()
      if (stty != null) return stty
      return TerminalSize(cols = 80, rows = 24)
    }

    private fun sttySize(): TerminalSize? {
      return try {
        // `stty size` prints "rows cols" on stdout when stdin is a TTY. Reading the controlling
        // TTY via `</dev/tty` keeps it working even when stdin is otherwise wired up to
        // something else (test harness, ProcessBuilder pipe). The shell layer is needed for
        // the redirect syntax — direct ProcessBuilder can't do it.
        val process =
          ProcessBuilder("sh", "-c", "stty size </dev/tty").redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        val parts = output.split(' ')
        if (parts.size != 2) return null
        val r = parts[0].toIntOrNull() ?: return null
        val c = parts[1].toIntOrNull() ?: return null
        if (r <= 0 || c <= 0) null else TerminalSize(c, r)
      } catch (_: Throwable) {
        null
      }
    }
  }
}
