package ee.schimke.composeai.daemon

import androidx.compose.ui.input.key.Key

/**
 * Wire-format Android `KEYCODE_*` int → Compose [Key] translation table for the desktop (Skiko)
 * backend. Issue #1203.
 *
 * Wire spelling is the decimal-string Android keycode (see [InteractiveKeyCodes]); the table covers
 * the same set declared there. Skiko's `BaseComposeScene.sendKeyEvent` takes a Compose
 * [androidx.compose.ui.input.key.KeyEvent] built from a
 * [Key] + [androidx.compose.ui.input.key.KeyEventType] — no AWT-component round-trip needed.
 *
 * Unmapped codes return `null`; the dispatch path drops the event so forward-looking clients cannot
 * crash the loop.
 */
internal fun androidKeycodeToComposeKey(wire: String?): Key? {
  val code = InteractiveKeyCodes.parse(wire) ?: return null
  return ANDROID_KEYCODE_TO_COMPOSE_KEY[code]
}

private val ANDROID_KEYCODE_TO_COMPOSE_KEY: Map<Int, Key> = buildMap {
  // Letters.
  put(InteractiveKeyCodes.A, Key.A)
  put(InteractiveKeyCodes.B, Key.B)
  put(InteractiveKeyCodes.C, Key.C)
  put(InteractiveKeyCodes.D, Key.D)
  put(InteractiveKeyCodes.E, Key.E)
  put(InteractiveKeyCodes.F, Key.F)
  put(InteractiveKeyCodes.G, Key.G)
  put(InteractiveKeyCodes.H, Key.H)
  put(InteractiveKeyCodes.I, Key.I)
  put(InteractiveKeyCodes.J, Key.J)
  put(InteractiveKeyCodes.K, Key.K)
  put(InteractiveKeyCodes.L, Key.L)
  put(InteractiveKeyCodes.M, Key.M)
  put(InteractiveKeyCodes.N, Key.N)
  put(InteractiveKeyCodes.O, Key.O)
  put(InteractiveKeyCodes.P, Key.P)
  put(InteractiveKeyCodes.Q, Key.Q)
  put(InteractiveKeyCodes.R, Key.R)
  put(InteractiveKeyCodes.S, Key.S)
  put(InteractiveKeyCodes.T, Key.T)
  put(InteractiveKeyCodes.U, Key.U)
  put(InteractiveKeyCodes.V, Key.V)
  put(InteractiveKeyCodes.W, Key.W)
  put(InteractiveKeyCodes.X, Key.X)
  put(InteractiveKeyCodes.Y, Key.Y)
  put(InteractiveKeyCodes.Z, Key.Z)
  // Digits.
  put(InteractiveKeyCodes.DIGIT_0, Key.Zero)
  put(InteractiveKeyCodes.DIGIT_1, Key.One)
  put(InteractiveKeyCodes.DIGIT_2, Key.Two)
  put(InteractiveKeyCodes.DIGIT_3, Key.Three)
  put(InteractiveKeyCodes.DIGIT_4, Key.Four)
  put(InteractiveKeyCodes.DIGIT_5, Key.Five)
  put(InteractiveKeyCodes.DIGIT_6, Key.Six)
  put(InteractiveKeyCodes.DIGIT_7, Key.Seven)
  put(InteractiveKeyCodes.DIGIT_8, Key.Eight)
  put(InteractiveKeyCodes.DIGIT_9, Key.Nine)
  // Whitespace / editing.
  put(InteractiveKeyCodes.SPACE, Key.Spacebar)
  put(InteractiveKeyCodes.ENTER, Key.Enter)
  put(InteractiveKeyCodes.TAB, Key.Tab)
  put(InteractiveKeyCodes.BACKSPACE, Key.Backspace)
  put(InteractiveKeyCodes.FORWARD_DELETE, Key.Delete)
  put(InteractiveKeyCodes.ESCAPE, Key.Escape)
  // Navigation.
  put(InteractiveKeyCodes.DPAD_LEFT, Key.DirectionLeft)
  put(InteractiveKeyCodes.DPAD_RIGHT, Key.DirectionRight)
  put(InteractiveKeyCodes.DPAD_UP, Key.DirectionUp)
  put(InteractiveKeyCodes.DPAD_DOWN, Key.DirectionDown)
  put(InteractiveKeyCodes.DPAD_CENTER, Key.DirectionCenter)
  put(InteractiveKeyCodes.HOME, Key.MoveHome)
  put(InteractiveKeyCodes.END, Key.MoveEnd)
  put(InteractiveKeyCodes.PAGE_UP, Key.PageUp)
  put(InteractiveKeyCodes.PAGE_DOWN, Key.PageDown)
  // Modifiers.
  put(InteractiveKeyCodes.SHIFT_LEFT, Key.ShiftLeft)
  put(InteractiveKeyCodes.SHIFT_RIGHT, Key.ShiftRight)
  put(InteractiveKeyCodes.CTRL_LEFT, Key.CtrlLeft)
  put(InteractiveKeyCodes.CTRL_RIGHT, Key.CtrlRight)
  put(InteractiveKeyCodes.ALT_LEFT, Key.AltLeft)
  put(InteractiveKeyCodes.ALT_RIGHT, Key.AltRight)
  put(InteractiveKeyCodes.META_LEFT, Key.MetaLeft)
  put(InteractiveKeyCodes.META_RIGHT, Key.MetaRight)
}
