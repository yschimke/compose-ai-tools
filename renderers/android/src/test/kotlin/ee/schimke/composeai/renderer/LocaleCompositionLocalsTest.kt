package ee.schimke.composeai.renderer

import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocaleCompositionLocalsTest {
  @Test
  fun `configuration locales become a compose locale list language tag string`() {
    val configuration = Configuration().apply { setLocales(LocaleList(Locale.UK, Locale.JAPAN)) }

    assertEquals("en-GB,ja-JP", LocaleCompositionLocals.languageTags(configuration))
  }

  @Test
  fun `empty preview configuration falls back to a nonempty platform locale list`() {
    val previous = Locale.getDefault()
    try {
      Locale.setDefault(Locale.CANADA_FRENCH)

      assertEquals("fr-CA", LocaleCompositionLocals.languageTags(Configuration()))
    } finally {
      Locale.setDefault(previous)
    }
  }

  @Test
  fun `older compose versions without locale locals remain supported`() {
    val configuration = Configuration().apply { setLocale(Locale.GERMANY) }
    val emptyLoader = object : ClassLoader(null) {}

    assertNull(LocaleCompositionLocals.providedValue(configuration, emptyLoader))
  }
}
