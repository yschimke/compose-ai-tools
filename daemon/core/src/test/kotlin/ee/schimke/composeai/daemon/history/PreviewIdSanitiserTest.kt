package ee.schimke.composeai.daemon.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreviewIdSanitiserTest {

  @Test
  fun keeps_letters_digits_dot_underscore_and_dash() {
    assertEquals("com.example_Preview-1", PreviewIdSanitiser.sanitise("com.example_Preview-1"))
    assertEquals("Ångström.预览-1", PreviewIdSanitiser.sanitise("Ångström.预览-1"))
  }

  @Test
  fun replaces_path_separators_whitespace_and_shell_punctuation() {
    val sanitised = PreviewIdSanitiser.sanitise("../com example/Card:primary?state=on")

    assertEquals(".._com_example_Card_primary_state_on", sanitised)
    assertFalse("sanitised ids must not contain path separators", sanitised.contains('/'))
  }

  @Test
  fun empty_preview_id_has_stable_directory_name() {
    assertEquals("_", PreviewIdSanitiser.sanitise(""))
  }
}
