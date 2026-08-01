package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InteractiveKeyCodesTest {

  @Test
  fun parse_accepts_decimal_wire_spelling_with_whitespace() {
    assertEquals(InteractiveKeyCodes.A, InteractiveKeyCodes.parse(" 29 "))
    assertEquals(InteractiveKeyCodes.F12, InteractiveKeyCodes.parse("142"))
  }

  @Test
  fun parse_returns_null_for_missing_blank_or_non_numeric_wire_values() {
    assertNull(InteractiveKeyCodes.parse(null))
    assertNull(InteractiveKeyCodes.parse(" "))
    assertNull(InteractiveKeyCodes.parse("KEYCODE_A"))
  }

  @Test
  fun constants_pin_android_keycode_ranges_used_by_clients() {
    assertEquals(29, InteractiveKeyCodes.A)
    assertEquals(54, InteractiveKeyCodes.Z)
    assertEquals(7, InteractiveKeyCodes.DIGIT_0)
    assertEquals(16, InteractiveKeyCodes.DIGIT_9)
    assertEquals(131, InteractiveKeyCodes.F1)
    assertEquals(142, InteractiveKeyCodes.F12)
    assertEquals(144, InteractiveKeyCodes.NUMPAD_0)
    assertEquals(161, InteractiveKeyCodes.NUMPAD_EQUALS)
    assertEquals(62, InteractiveKeyCodes.SPACE)
    assertEquals(67, InteractiveKeyCodes.BACKSPACE)
  }
}
