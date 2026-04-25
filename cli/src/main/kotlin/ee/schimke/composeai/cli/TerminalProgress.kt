package ee.schimke.composeai.cli

/**
 * OSC 9;4 terminal progress bar protocol.
 *
 * Supported by Windows Terminal, Ghostty, iTerm2, WezTerm, and VTE-based terminals. Displays a
 * native progress bar in the terminal tab and (on Windows) the taskbar.
 *
 * Protocol: ESC ] 9 ; 4 ; <state> ; <progress> ST state: 0=hidden, 1=default, 2=error,
 * 3=indeterminate, 4=warning progress: 0-100
 */
object TerminalProgress {
  private const val ESC = "\u001b"
  private const val ST = "$ESC\\"
  private const val OSC = "${ESC}]"

  // System.console() is null when stdout is redirected to a file or pipe,
  // so OSC escapes would appear as literal garbage in captured output.
  // TERM=dumb is the conventional signal to disable terminal styling.
  private val enabled: Boolean = run {
    if (System.console() == null) return@run false
    val term = System.getenv("TERM")
    term != null && term != "dumb"
  }

  fun show(percent: Int) {
    if (!enabled) return
    val clamped = percent.coerceIn(0, 100)
    print("${OSC}9;4;1;${clamped}${ST}")
    System.out.flush()
  }

  fun indeterminate() {
    if (!enabled) return
    print("${OSC}9;4;3;0${ST}")
    System.out.flush()
  }

  fun error() {
    if (!enabled) return
    print("${OSC}9;4;2;100${ST}")
    System.out.flush()
  }

  fun hide() {
    if (!enabled) return
    print("${OSC}9;4;0;0${ST}")
    System.out.flush()
  }
}
