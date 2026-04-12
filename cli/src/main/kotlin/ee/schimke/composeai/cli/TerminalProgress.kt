package ee.schimke.composeai.cli

/**
 * OSC 9;4 terminal progress bar protocol.
 *
 * Supported by Windows Terminal, Ghostty, iTerm2, WezTerm, and VTE-based terminals.
 * Displays a native progress bar in the terminal tab and (on Windows) the taskbar.
 *
 * Protocol: ESC ] 9 ; 4 ; <state> ; <progress> ST
 *   state: 0=hidden, 1=default, 2=error, 3=indeterminate, 4=warning
 *   progress: 0-100
 */
object TerminalProgress {
    private const val ESC = "\u001b"
    private const val ST = "$ESC\\"
    private const val OSC = "${ESC}]"

    fun show(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        print("${OSC}9;4;1;${clamped}${ST}")
        System.out.flush()
    }

    fun indeterminate() {
        print("${OSC}9;4;3;0${ST}")
        System.out.flush()
    }

    fun error() {
        print("${OSC}9;4;2;100${ST}")
        System.out.flush()
    }

    fun hide() {
        print("${OSC}9;4;0;0${ST}")
        System.out.flush()
    }
}
