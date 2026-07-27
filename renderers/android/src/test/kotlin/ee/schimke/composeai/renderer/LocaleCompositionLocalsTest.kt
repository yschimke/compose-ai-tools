package ee.schimke.composeai.renderer

import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The [Configuration]-reading half. The reflective half needs a stub classpath, which the
 * Robolectric sandbox can't provide (it resolves `Class.forName` against its own loader and ignores
 * the one passed in), so that lives in [LocaleCompositionLocalsResolutionTest] — a plain JVM test.
 */
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
}
