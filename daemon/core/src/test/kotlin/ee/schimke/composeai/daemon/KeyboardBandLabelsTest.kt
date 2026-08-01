package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardBandLabelsTest {

  @Test
  fun maps_every_letter_to_its_lowercase_band_label() {
    val labels = ('a'..'z').map(Char::toString)

    labels.forEachIndexed { offset, label ->
      assertEquals(
        label,
        KeyboardBandLabels.fromAndroidKeycode((InteractiveKeyCodes.A + offset).toString()),
      )
    }
  }

  @Test
  fun maps_punctuation_and_special_keys_to_band_tokens() {
    val expected =
      mapOf(
        InteractiveKeyCodes.COMMA to ",",
        InteractiveKeyCodes.PERIOD to ".",
        InteractiveKeyCodes.SPACE to "space",
        InteractiveKeyCodes.ENTER to "enter",
        InteractiveKeyCodes.BACKSPACE to "backspace",
        InteractiveKeyCodes.SHIFT_LEFT to "shift",
        InteractiveKeyCodes.SHIFT_RIGHT to "shift",
      )

    expected.forEach { (keycode, label) ->
      assertEquals(label, KeyboardBandLabels.fromAndroidKeycode(keycode.toString()))
    }
  }

  @Test
  fun accepts_whitespace_around_wire_keycode() {
    assertEquals("a", KeyboardBandLabels.fromAndroidKeycode("  ${InteractiveKeyCodes.A}  "))
  }

  @Test
  fun returns_null_for_absent_invalid_or_unmapped_keycodes() {
    assertNull(KeyboardBandLabels.fromAndroidKeycode(null))
    assertNull(KeyboardBandLabels.fromAndroidKeycode(""))
    assertNull(KeyboardBandLabels.fromAndroidKeycode("KEYCODE_A"))
    assertNull(KeyboardBandLabels.fromAndroidKeycode(InteractiveKeyCodes.DIGIT_0.toString()))
  }
}
