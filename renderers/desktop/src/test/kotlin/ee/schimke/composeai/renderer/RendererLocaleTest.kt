package ee.schimke.composeai.renderer

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the `localeTag` → JVM-default-`Locale` half of the batch renderer's locale
 * override (the [overrideJvmDefaultLocale] / [effectiveLocaleTag] helpers `renderPreview` uses).
 * These need no Skiko/`ImageComposeScene` render, so they run without native graphics libs.
 *
 * The load-bearing assertion is [overrideReachesComposeUiTextLocale]: CMP string resources resolve
 * their locale through `rememberResourceEnvironment()` → `androidx.compose.ui.text.intl.Locale
 * .current`, which on Skiko/desktop reads the JVM default `Locale`. Proving
 * `overrideJvmDefaultLocale` moves *that* API is what proves a `@Preview(locale = …)` override now
 * reaches `stringResource(...)` on the batch `composePreviewRender` pipeline — the case that
 * previously rendered every locale identical to English.
 */
class RendererLocaleTest {

  @Test
  fun effectiveLocaleTagPassesThroughRealTagsAndFoldsPseudolocales() {
    assertEquals("de", effectiveLocaleTag("de"))
    assertEquals("ar", effectiveLocaleTag("ar"))
    // Both pseudolocales wrap English copy, so they fold to `en` (ar-XB's RTL is a layout flip, not
    // real Arabic text) — a real BCP-47 tag is what `Locale.forLanguageTag` needs.
    assertEquals("en", effectiveLocaleTag("en-XA"))
    assertEquals("en", effectiveLocaleTag("ar-XB"))
    assertNull(effectiveLocaleTag(null))
    assertNull(effectiveLocaleTag(""))
    assertNull(effectiveLocaleTag("   "))
  }

  @Test
  fun overrideAndRestoreRoundTripsTheJvmDefaultLocale() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("en-US"))
      val marker = Locale.getDefault()

      val previous = overrideJvmDefaultLocale("de")
      assertSame("override should return the prior default to restore", marker, previous)
      assertEquals("de", Locale.getDefault().language)

      restoreJvmDefaultLocale(previous)
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
      assertNull(overrideJvmDefaultLocale(null))
      assertSame(marker, Locale.getDefault())
      restoreJvmDefaultLocale(null)
      assertSame(marker, Locale.getDefault())
    } finally {
      Locale.setDefault(original)
    }
  }

  /**
   * The direction half of the override, and the one this path was missing: a **real** RTL locale
   * has to mirror the capture, not just translate it. `@Preview(locale = "ar")` used to render
   * correctly shaped Arabic inside a left-to-right container — navigation icon left, FAB
   * bottom-right, start/end padding unmirrored — because only `Pseudolocale.isRtl` was consulted.
   *
   * The other three render paths (daemon desktop, daemon Android, `RobolectricRenderTest`) already
   * ask [ee.schimke.composeai.data.pseudolocale.LocaleDirection], so this pins the batch renderer
   * to the same answer rather than to its own list.
   */
  @Test
  fun realRtlLocalesMirrorNotJustTheBidiPseudolocale() {
    // Real RTL locales, bare and region-qualified.
    assertTrue(rendersRightToLeft("ar"))
    assertTrue(rendersRightToLeft("ar-EG"))
    assertTrue(rendersRightToLeft("he"))
    assertTrue(rendersRightToLeft("iw")) // legacy Hebrew code
    assertTrue(rendersRightToLeft("fa"))
    assertTrue(rendersRightToLeft("ur-PK"))

    // The bidi pseudolocale keeps flipping — its base tag is `en`, so this only holds because
    // `rendersRightToLeft` asks the pseudolocale before folding the tag.
    assertTrue(rendersRightToLeft("ar-XB"))

    // LTR locales, the accent pseudolocale (English copy, LTR) and "no override" must not flip.
    assertFalse(rendersRightToLeft("de"))
    assertFalse(rendersRightToLeft("ja"))
    assertFalse(rendersRightToLeft("en-XA"))
    assertFalse(rendersRightToLeft(null))
    assertFalse(rendersRightToLeft(""))
  }

  /**
   * The crux: `overrideJvmDefaultLocale` must move `androidx.compose.ui.text.intl.Locale.current` —
   * the exact locale CMP `stringResource(...)` resolves against on desktop. Reading it needs no
   * render, so this proves the mechanism cheaply.
   */
  @Test
  fun overrideReachesComposeUiTextLocale() {
    val original = Locale.getDefault()
    try {
      val previous = overrideJvmDefaultLocale("de")
      assertEquals(
        "the JVM default switch must reach androidx.compose.ui.text.intl.Locale.current, the " +
          "locale CMP string resources read on desktop",
        "de",
        androidx.compose.ui.text.intl.Locale.current.language,
      )
      restoreJvmDefaultLocale(previous)
    } finally {
      Locale.setDefault(original)
    }
  }
}
