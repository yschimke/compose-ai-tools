package ee.schimke.composeai.daemon

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit coverage for the `localeTag` → JVM-default-`Locale` half of the render locale override (the
 * companion helpers on [RenderEngine]). These need no Skiko/`ImageComposeScene` render, so they run
 * without native graphics libs.
 *
 * The load-bearing assertion is [overrideJvmDefaultLocaleReachesComposeUiTextLocale]: CMP string
 * resources resolve their locale through `rememberResourceEnvironment()` →
 * `androidx.compose.ui.text.intl.Locale.current`, which on Skiko/desktop reads the JVM default
 * `Locale`. Proving `overrideJvmDefaultLocale(...)` moves *that* API is what proves a
 * `@Preview(locale = …)` override now reaches `stringResource(...)` — the pixel-level end-to-end is
 * [OverrideIntegrationTest.localeTagOverrideReachesComposeResourceLocale].
 */
class RenderEngineLocaleTest {

  @Test
  fun effectiveLocaleTagPassesThroughRealTagsAndFoldsPseudolocales() {
    assertEquals("de", RenderEngine.effectiveLocaleTag("de"))
    assertEquals("ar", RenderEngine.effectiveLocaleTag("ar"))
    // Pseudolocales fold to their base — `LocaleList("en-XA")` / `Locale.forLanguageTag("en-XA")`
    // would otherwise throw or degrade depending on the JVM's ICU build. Both fold to `en`: the
    // accent (`en-XA`) and bidi (`ar-XB`) pseudolocales both wrap English copy — `ar-XB`'s RTL is a
    // layout flip (`Pseudolocale.BIDI.isRtl`), not real Arabic text (see `Pseudolocale`).
    assertEquals("en", RenderEngine.effectiveLocaleTag("en-XA"))
    assertEquals("en", RenderEngine.effectiveLocaleTag("ar-XB"))
    // No override → nothing to apply.
    assertNull(RenderEngine.effectiveLocaleTag(null))
    assertNull(RenderEngine.effectiveLocaleTag(""))
    assertNull(RenderEngine.effectiveLocaleTag("   "))
  }

  @Test
  fun overrideAndRestoreRoundTripsTheJvmDefaultLocale() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("en-US"))
      val marker = Locale.getDefault()

      val previous = RenderEngine.overrideJvmDefaultLocale("de")
      assertSame(
        "override should return the prior default so tearDown can restore it",
        marker,
        previous,
      )
      assertEquals("de", Locale.getDefault().language)

      RenderEngine.restoreJvmDefaultLocale(previous)
      assertSame("restore should put the prior default back", marker, Locale.getDefault())
    } finally {
      Locale.setDefault(original)
    }
  }

  @Test
  fun overrideIsANoOpWithoutATag() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("en-US"))
      val marker = Locale.getDefault()
      // No effective tag → no switch, nothing to restore.
      assertNull(RenderEngine.overrideJvmDefaultLocale(null))
      assertSame(marker, Locale.getDefault())
      // Restoring a null previous must not touch the default either.
      RenderEngine.restoreJvmDefaultLocale(null)
      assertSame(marker, Locale.getDefault())
    } finally {
      Locale.setDefault(original)
    }
  }

  /**
   * The crux: `overrideJvmDefaultLocale` must move `androidx.compose.ui.text.intl.Locale.current` —
   * the exact locale CMP `stringResource(...)` resolves against on desktop. Reading it needs no
   * render, so this proves the mechanism cheaply.
   */
  @Test
  fun overrideJvmDefaultLocaleReachesComposeUiTextLocale() {
    val original = Locale.getDefault()
    try {
      val previous = RenderEngine.overrideJvmDefaultLocale("de")
      assertEquals(
        "the JVM default switch must reach androidx.compose.ui.text.intl.Locale.current, the " +
          "locale CMP string resources read on desktop",
        "de",
        androidx.compose.ui.text.intl.Locale.current.language,
      )
      RenderEngine.restoreJvmDefaultLocale(previous)
    } finally {
      Locale.setDefault(original)
    }
  }
}
