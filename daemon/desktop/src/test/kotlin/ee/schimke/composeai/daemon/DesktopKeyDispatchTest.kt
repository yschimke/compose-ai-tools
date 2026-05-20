package ee.schimke.composeai.daemon

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1230 — pinning test for the desktop wire-format keycode → Compose [Key] table. The
 * end-to-end `key_down_input_flips_state_and_repaints` test in [DesktopInteractiveSessionTest]
 * covers the dispatch path for a single keycode; this fixture cheaply pins the larger surface area
 * (F-keys, numpad, punctuation, locks) so a drift between [InteractiveKeyCodes] /
 * [ANDROID_KEYCODE_TO_COMPOSE_KEY] and the Skiko `Key.*` constants trips here, not on a render.
 */
class DesktopKeyDispatchTest {

  @Test
  fun translates_letters_and_digits() {
    assertEquals(Key.A, androidKeycodeToComposeKey("29"))
    assertEquals(Key.Z, androidKeycodeToComposeKey("54"))
    assertEquals(Key.Zero, androidKeycodeToComposeKey("7"))
    assertEquals(Key.Nine, androidKeycodeToComposeKey("16"))
  }

  @Test
  fun translates_function_keys() {
    assertEquals(Key.F1, androidKeycodeToComposeKey("131"))
    assertEquals(Key.F12, androidKeycodeToComposeKey("142"))
  }

  @Test
  fun translates_numpad_keys() {
    assertEquals(Key.NumPad0, androidKeycodeToComposeKey("144"))
    assertEquals(Key.NumPad9, androidKeycodeToComposeKey("153"))
    assertEquals(Key.NumPadDivide, androidKeycodeToComposeKey("154"))
    assertEquals(Key.NumPadMultiply, androidKeycodeToComposeKey("155"))
    assertEquals(Key.NumPadSubtract, androidKeycodeToComposeKey("156"))
    assertEquals(Key.NumPadAdd, androidKeycodeToComposeKey("157"))
    assertEquals(Key.NumPadDot, androidKeycodeToComposeKey("158"))
    assertEquals(Key.NumPadEnter, androidKeycodeToComposeKey("160"))
    assertEquals(Key.NumPadEquals, androidKeycodeToComposeKey("161"))
  }

  @Test
  fun translates_punctuation() {
    assertEquals(Key.Minus, androidKeycodeToComposeKey("69"))
    assertEquals(Key.Equals, androidKeycodeToComposeKey("70"))
    assertEquals(Key.LeftBracket, androidKeycodeToComposeKey("71"))
    assertEquals(Key.RightBracket, androidKeycodeToComposeKey("72"))
    assertEquals(Key.Backslash, androidKeycodeToComposeKey("73"))
    assertEquals(Key.Semicolon, androidKeycodeToComposeKey("74"))
    assertEquals(Key.Apostrophe, androidKeycodeToComposeKey("75"))
    assertEquals(Key.Comma, androidKeycodeToComposeKey("55"))
    assertEquals(Key.Period, androidKeycodeToComposeKey("56"))
    assertEquals(Key.Slash, androidKeycodeToComposeKey("76"))
    assertEquals(Key.Grave, androidKeycodeToComposeKey("68"))
  }

  @Test
  fun translates_locks() {
    assertEquals(Key.CapsLock, androidKeycodeToComposeKey("115"))
    assertEquals(Key.NumLock, androidKeycodeToComposeKey("143"))
    assertEquals(Key.ScrollLock, androidKeycodeToComposeKey("116"))
  }

  @Test
  fun null_or_unmapped_input_returns_null() {
    assertNull(androidKeycodeToComposeKey(null))
    assertNull(androidKeycodeToComposeKey(""))
    assertNull(androidKeycodeToComposeKey("not-a-number"))
    // KEYCODE_F13 = 183 — the issue caps the table at F1–F12 so F13+ stays unmapped.
    assertNull(androidKeycodeToComposeKey("183"))
  }
}
