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
public object InteractiveKeyCodes {

  // Letters (Android KEYCODE_A = 29, KEYCODE_Z = 54).
  public const val A: Int = 29
  public const val B: Int = 30
  public const val C: Int = 31
  public const val D: Int = 32
  public const val E: Int = 33
  public const val F: Int = 34
  public const val G: Int = 35
  public const val H: Int = 36
  public const val I: Int = 37
  public const val J: Int = 38
  public const val K: Int = 39
  public const val L: Int = 40
  public const val M: Int = 41
  public const val N: Int = 42
  public const val O: Int = 43
  public const val P: Int = 44
  public const val Q: Int = 45
  public const val R: Int = 46
  public const val S: Int = 47
  public const val T: Int = 48
  public const val U: Int = 49
  public const val V: Int = 50
  public const val W: Int = 51
  public const val X: Int = 52
  public const val Y: Int = 53
  public const val Z: Int = 54

  // Digits (KEYCODE_0 = 7, KEYCODE_9 = 16).
  public const val DIGIT_0: Int = 7
  public const val DIGIT_1: Int = 8
  public const val DIGIT_2: Int = 9
  public const val DIGIT_3: Int = 10
  public const val DIGIT_4: Int = 11
  public const val DIGIT_5: Int = 12
  public const val DIGIT_6: Int = 13
  public const val DIGIT_7: Int = 14
  public const val DIGIT_8: Int = 15
  public const val DIGIT_9: Int = 16

  // Whitespace / editing.
  public const val SPACE: Int = 62
  public const val ENTER: Int = 66
  public const val TAB: Int = 61
  public const val BACKSPACE: Int = 67 // KEYCODE_DEL
  public const val FORWARD_DELETE: Int = 112 // KEYCODE_FORWARD_DEL
  public const val ESCAPE: Int = 111

  // Navigation.
  public const val DPAD_LEFT: Int = 21
  public const val DPAD_RIGHT: Int = 22
  public const val DPAD_UP: Int = 19
  public const val DPAD_DOWN: Int = 20
  public const val DPAD_CENTER: Int = 23
  public const val HOME: Int = 122 // KEYCODE_MOVE_HOME
  public const val END: Int = 123 // KEYCODE_MOVE_END
  public const val PAGE_UP: Int = 92
  public const val PAGE_DOWN: Int = 93

  // Modifiers.
  public const val SHIFT_LEFT: Int = 59
  public const val SHIFT_RIGHT: Int = 60
  public const val CTRL_LEFT: Int = 113
  public const val CTRL_RIGHT: Int = 114
  public const val ALT_LEFT: Int = 57
  public const val ALT_RIGHT: Int = 58
  public const val META_LEFT: Int = 117
  public const val META_RIGHT: Int = 118

  // Function keys (KEYCODE_F1 = 131 … KEYCODE_F12 = 142).
  public const val F1: Int = 131
  public const val F2: Int = 132
  public const val F3: Int = 133
  public const val F4: Int = 134
  public const val F5: Int = 135
  public const val F6: Int = 136
  public const val F7: Int = 137
  public const val F8: Int = 138
  public const val F9: Int = 139
  public const val F10: Int = 140
  public const val F11: Int = 141
  public const val F12: Int = 142

  // Numpad (KEYCODE_NUMPAD_0 = 144 … KEYCODE_NUMPAD_EQUALS = 161).
  public const val NUMPAD_0: Int = 144
  public const val NUMPAD_1: Int = 145
  public const val NUMPAD_2: Int = 146
  public const val NUMPAD_3: Int = 147
  public const val NUMPAD_4: Int = 148
  public const val NUMPAD_5: Int = 149
  public const val NUMPAD_6: Int = 150
  public const val NUMPAD_7: Int = 151
  public const val NUMPAD_8: Int = 152
  public const val NUMPAD_9: Int = 153
  public const val NUMPAD_DIVIDE: Int = 154
  public const val NUMPAD_MULTIPLY: Int = 155
  public const val NUMPAD_SUBTRACT: Int = 156
  public const val NUMPAD_ADD: Int = 157
  public const val NUMPAD_DOT: Int = 158
  public const val NUMPAD_COMMA: Int = 159
  public const val NUMPAD_ENTER: Int = 160
  public const val NUMPAD_EQUALS: Int = 161

  // Punctuation.
  public const val MINUS: Int = 69
  public const val EQUALS: Int = 70
  public const val LEFT_BRACKET: Int = 71
  public const val RIGHT_BRACKET: Int = 72
  public const val BACKSLASH: Int = 73
  public const val SEMICOLON: Int = 74
  public const val APOSTROPHE: Int = 75
  public const val COMMA: Int = 55
  public const val PERIOD: Int = 56
  public const val SLASH: Int = 76
  public const val GRAVE: Int = 68 // KEYCODE_GRAVE — backtick / DOM `Backquote`.

  // Locks.
  public const val CAPS_LOCK: Int = 115
  public const val NUM_LOCK: Int = 143
  public const val SCROLL_LOCK: Int = 116

  /** Parse the wire spelling. Returns `null` for null / blank / non-numeric input. */
  public fun parse(wire: String?): Int? = wire?.trim()?.toIntOrNull()
}
