package ee.schimke.composeai.daemon

/**
 * Wire format for [protocol.InteractiveInputParams.keyCode] /
 * [protocol.RecordingScriptEvent.keyCode]: the decimal-string spelling of an Android
 * `KeyEvent.KEYCODE_*` integer.
 *
 * Android is chosen as the canonical surface (per issue #1203) because the Android backend's
 * dispatch path consumes it natively. The Desktop backend has its own translation table mapping
 * these ints to Compose `Key.*` (`DesktopInteractiveSession.kt`), and the VS Code panel has a
 * sibling DOM-code → Android-keycode table on the TypeScript side.
 *
 * Only the keys the harness scenarios and panel keyboard listener actually emit are listed.
 * Unmapped codes are dropped silently on both backends — a forward-looking client may emit a new
 * key without breaking the dispatch loop; the panel can grow the table independently.
 *
 * Codes match `android.view.KeyEvent.KEYCODE_*` and are stable Android API integers.
 */
object InteractiveKeyCodes {

  // Letters (Android KEYCODE_A = 29, KEYCODE_Z = 54).
  const val A: Int = 29
  const val B: Int = 30
  const val C: Int = 31
  const val D: Int = 32
  const val E: Int = 33
  const val F: Int = 34
  const val G: Int = 35
  const val H: Int = 36
  const val I: Int = 37
  const val J: Int = 38
  const val K: Int = 39
  const val L: Int = 40
  const val M: Int = 41
  const val N: Int = 42
  const val O: Int = 43
  const val P: Int = 44
  const val Q: Int = 45
  const val R: Int = 46
  const val S: Int = 47
  const val T: Int = 48
  const val U: Int = 49
  const val V: Int = 50
  const val W: Int = 51
  const val X: Int = 52
  const val Y: Int = 53
  const val Z: Int = 54

  // Digits (KEYCODE_0 = 7, KEYCODE_9 = 16).
  const val DIGIT_0: Int = 7
  const val DIGIT_1: Int = 8
  const val DIGIT_2: Int = 9
  const val DIGIT_3: Int = 10
  const val DIGIT_4: Int = 11
  const val DIGIT_5: Int = 12
  const val DIGIT_6: Int = 13
  const val DIGIT_7: Int = 14
  const val DIGIT_8: Int = 15
  const val DIGIT_9: Int = 16

  // Whitespace / editing.
  const val SPACE: Int = 62
  const val ENTER: Int = 66
  const val TAB: Int = 61
  const val BACKSPACE: Int = 67 // KEYCODE_DEL
  const val FORWARD_DELETE: Int = 112 // KEYCODE_FORWARD_DEL
  const val ESCAPE: Int = 111

  // Navigation.
  const val DPAD_LEFT: Int = 21
  const val DPAD_RIGHT: Int = 22
  const val DPAD_UP: Int = 19
  const val DPAD_DOWN: Int = 20
  const val DPAD_CENTER: Int = 23
  const val HOME: Int = 122 // KEYCODE_MOVE_HOME
  const val END: Int = 123 // KEYCODE_MOVE_END
  const val PAGE_UP: Int = 92
  const val PAGE_DOWN: Int = 93

  // Modifiers.
  const val SHIFT_LEFT: Int = 59
  const val SHIFT_RIGHT: Int = 60
  const val CTRL_LEFT: Int = 113
  const val CTRL_RIGHT: Int = 114
  const val ALT_LEFT: Int = 57
  const val ALT_RIGHT: Int = 58
  const val META_LEFT: Int = 117
  const val META_RIGHT: Int = 118

  // Function keys (KEYCODE_F1 = 131 … KEYCODE_F12 = 142).
  const val F1: Int = 131
  const val F2: Int = 132
  const val F3: Int = 133
  const val F4: Int = 134
  const val F5: Int = 135
  const val F6: Int = 136
  const val F7: Int = 137
  const val F8: Int = 138
  const val F9: Int = 139
  const val F10: Int = 140
  const val F11: Int = 141
  const val F12: Int = 142

  // Numpad (KEYCODE_NUMPAD_0 = 144 … KEYCODE_NUMPAD_EQUALS = 161).
  const val NUMPAD_0: Int = 144
  const val NUMPAD_1: Int = 145
  const val NUMPAD_2: Int = 146
  const val NUMPAD_3: Int = 147
  const val NUMPAD_4: Int = 148
  const val NUMPAD_5: Int = 149
  const val NUMPAD_6: Int = 150
  const val NUMPAD_7: Int = 151
  const val NUMPAD_8: Int = 152
  const val NUMPAD_9: Int = 153
  const val NUMPAD_DIVIDE: Int = 154
  const val NUMPAD_MULTIPLY: Int = 155
  const val NUMPAD_SUBTRACT: Int = 156
  const val NUMPAD_ADD: Int = 157
  const val NUMPAD_DOT: Int = 158
  const val NUMPAD_ENTER: Int = 160
  const val NUMPAD_EQUALS: Int = 161

  // Punctuation.
  const val MINUS: Int = 69
  const val EQUALS: Int = 70
  const val LEFT_BRACKET: Int = 71
  const val RIGHT_BRACKET: Int = 72
  const val BACKSLASH: Int = 73
  const val SEMICOLON: Int = 74
  const val APOSTROPHE: Int = 75
  const val COMMA: Int = 55
  const val PERIOD: Int = 56
  const val SLASH: Int = 76
  const val GRAVE: Int = 68 // KEYCODE_GRAVE — backtick / DOM `Backquote`.

  // Locks.
  const val CAPS_LOCK: Int = 115
  const val NUM_LOCK: Int = 143
  const val SCROLL_LOCK: Int = 116

  /** Parse the wire spelling. Returns `null` for null / blank / non-numeric input. */
  fun parse(wire: String?): Int? = wire?.trim()?.toIntOrNull()
}
