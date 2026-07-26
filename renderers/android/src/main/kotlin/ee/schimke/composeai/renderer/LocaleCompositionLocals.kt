package ee.schimke.composeai.renderer

import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.ProvidableCompositionLocal

/**
 * Bridges the locale from the preview's Robolectric [Configuration] into the locale composition
 * locals added in newer Compose UI releases.
 *
 * The renderer deliberately compiles against the Compose 1.9 compatibility BOM, while
 * `LocalLocaleList` and `LocalLocale` were added later. Resolve the providable backing local and
 * `LocaleList` reflectively so previews using a newer Compose UI can read both APIs without making
 * older consumers fail class loading. `LocalLocale` is computed from the first entry in
 * `LocalLocaleList`, so providing `LocalProvidableLocaleList` supports both public locals.
 */
object LocaleCompositionLocals {
  @Suppress("UNCHECKED_CAST")
  fun providedValue(
    configuration: Configuration,
    classLoader: ClassLoader =
      LocaleCompositionLocals::class.java.classLoader ?: ClassLoader.getSystemClassLoader(),
  ): ProvidedValue<*>? =
    runCatching {
        val compositionLocals =
          Class.forName("androidx.compose.ui.platform.CompositionLocalsKt", false, classLoader)
        val local =
          compositionLocals.getMethod("getLocalProvidableLocaleList").invoke(null)
            as ProvidableCompositionLocal<Any>
        val localeListClass =
          Class.forName("androidx.compose.ui.text.intl.LocaleList", false, classLoader)
        val localeList =
          localeListClass.getConstructor(String::class.java).newInstance(languageTags(configuration))
        local provides localeList
      }
      .getOrNull()

  internal fun languageTags(configuration: Configuration): String {
    if (Build.VERSION.SDK_INT >= 24) {
      return (0 until configuration.locales.size())
        .joinToString(",") { configuration.locales[it].toLanguageTag() }
    }
    @Suppress("DEPRECATION")
    return configuration.locale.toLanguageTag()
  }
}
