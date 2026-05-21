package ee.schimke.composeai.daemon

/**
 * Maps wire-format Android `KEYCODE_*` ints (the spelling used by `InteractiveInputParams.keyCode`,
 * see [InteractiveKeyCodes]) to the band-label tokens [KeyboardController.notifyKeyDown] /
 * [KeyboardController.notifyKeyUp] expect on the `:data-keyboard-connector` /
 * `:data-keyboard-connector-desktop` side.
 *
 * Shape on the wire is decimal-string Android keycodes; on the band-label side it's lowercase
 * single letters (`"h"`, `"o"`) or the named special tokens the band recognises (`"space"`,
 * `"enter"`, `"shift"`, `"backspace"`). Unmapped codes return `null` so the dispatcher drops the
 * highlight rather than the whole `KEY_*` event — typing keys we don't draw still flows through to
 * the consumer's composition, just without a band animation.
 *
 * Lives on `daemon:core` so both `AndroidInteractiveSession` and `DesktopInteractiveSession` can
 * call into it without either side taking a dep on the other's connector module.
 */
object KeyboardBandLabels {

  fun fromAndroidKeycode(wire: String?): String? {
    val code = InteractiveKeyCodes.parse(wire) ?: return null
    return LABELS[code]
  }

  private val LABELS: Map<Int, String> = buildMap {
    // Letters — band caps are lowercase to match the default IME state.
    for ((code, letter) in
      listOf(
        InteractiveKeyCodes.A to "a",
        InteractiveKeyCodes.B to "b",
        InteractiveKeyCodes.C to "c",
        InteractiveKeyCodes.D to "d",
        InteractiveKeyCodes.E to "e",
        InteractiveKeyCodes.F to "f",
        InteractiveKeyCodes.G to "g",
        InteractiveKeyCodes.H to "h",
        InteractiveKeyCodes.I to "i",
        InteractiveKeyCodes.J to "j",
        InteractiveKeyCodes.K to "k",
        InteractiveKeyCodes.L to "l",
        InteractiveKeyCodes.M to "m",
        InteractiveKeyCodes.N to "n",
        InteractiveKeyCodes.O to "o",
        InteractiveKeyCodes.P to "p",
        InteractiveKeyCodes.Q to "q",
        InteractiveKeyCodes.R to "r",
        InteractiveKeyCodes.S to "s",
        InteractiveKeyCodes.T to "t",
        InteractiveKeyCodes.U to "u",
        InteractiveKeyCodes.V to "v",
        InteractiveKeyCodes.W to "w",
        InteractiveKeyCodes.X to "x",
        InteractiveKeyCodes.Y to "y",
        InteractiveKeyCodes.Z to "z",
      )) {
      put(code, letter)
    }
    // Punctuation the band draws on the action row.
    put(InteractiveKeyCodes.COMMA, ",")
    put(InteractiveKeyCodes.PERIOD, ".")
    // Special tokens.
    put(InteractiveKeyCodes.SPACE, "space")
    put(InteractiveKeyCodes.ENTER, "enter")
    put(InteractiveKeyCodes.BACKSPACE, "backspace")
    put(InteractiveKeyCodes.SHIFT_LEFT, "shift")
    put(InteractiveKeyCodes.SHIFT_RIGHT, "shift")
  }
}
