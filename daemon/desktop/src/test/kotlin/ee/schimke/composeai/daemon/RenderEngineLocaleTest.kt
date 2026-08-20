package ee.schimke.composeai.daemon

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

  /**
   * The other edge of the pseudolocale string cache (#4371 review). CMP's process-wide
   * `stringItemsCache` stores whatever the *first* reader returned for a key, so after a pseudo
   * render it holds accented / bidi text; the next ordinary render would be served that text for a
   * locale it never asked for, without its own reader ever running. The around-composable only
   * clears on entry, so the renderer closes the exit — but only when a render actually leaves
   * pseudolocale mode, so a daemon that never renders one keeps its warm cache.
   *
   * Asserts the state machine rather than the reflective clear itself (that has its own coverage in
   * `PseudolocaleResourceCacheTest`), and tolerates a `false` return from a CMP version drift where
   * the reflective shim can't find the cache — the transition, not the shim, is what this pins.
   */
  @Test
  fun pseudolocaleStringCacheIsClearedOnTheWayOutOfPseudolocaleMode() {
    // Baseline: no pseudolocale render has happened, so an ordinary render clears nothing.
    RenderEngine.guardPseudolocaleStringCache(null)
    assertFalse(
      "an ordinary render with no pseudolocale history must not touch the cache",
      RenderEngine.guardPseudolocaleStringCache("de"),
    )

    // Entering, and staying in, pseudolocale mode is the around-composable's job, not this one's.
    assertFalse(RenderEngine.guardPseudolocaleStringCache("en-XA"))
    assertFalse(
      "a pseudolocale-to-pseudolocale switch is the entry clear's job",
      RenderEngine.guardPseudolocaleStringCache("ar-XB"),
    )

    // Leaving it is: the first ordinary render afterwards clears, and only that one.
    assertTrue(
      "the render after a pseudolocale one must drop the transformed string items",
      RenderEngine.guardPseudolocaleStringCache(null),
    )
    assertFalse(
      "and the cache must then be left alone until the next pseudolocale render",
      RenderEngine.guardPseudolocaleStringCache(null),
    )
  }

  /**
   * A held pseudolocale scene keeps composing between one-shot renders (#4384 review). Arming the
   * flag only when a render *starts* left this hole: an ordinary render clears and disarms, then an
   * interaction on the still-open `en-XA` session composes a string the cache has never seen and
   * refills it with transformed text — under a flag that now says no pseudolocale is in play — so
   * the next ordinary render skips its clear and reads accented text.
   *
   * [RenderEngine.enterPreviewLocale] is the seam every composition passes through (one-shot
   * render, held frame, scroll drive), so arming there makes the flag mean "something
   * pseudolocalised has composed since the last clear", which is the property the guard needs.
   */
  @Test
  fun aHeldPseudolocaleFrameReArmsTheCacheGuardAfterAnOrdinaryRenderCleared() {
    // Settle to a known state: whatever ran before, one ordinary pass leaves the flag disarmed.
    RenderEngine.guardPseudolocaleStringCache(null)

    // A held `en-XA` scene composes a frame, then an ordinary render clears and disarms.
    RenderEngine.enterPreviewLocale("en-XA").close()
    assertTrue(
      "the ordinary render after a held pseudolocale frame must clear",
      RenderEngine.guardPseudolocaleStringCache(null),
    )

    // The session is still open: a later interaction composes again, refilling the cache…
    RenderEngine.enterPreviewLocale("ar-XB").close()
    // …so the next ordinary render has to clear again. Before the fix this returned false.
    assertTrue(
      "a held scene that composed again must re-arm the guard",
      RenderEngine.guardPseudolocaleStringCache(null),
    )

    // An ordinary held frame is not a pseudolocale one, so it must not arm anything.
    RenderEngine.enterPreviewLocale(null).close()
    assertFalse(
      "an unlocalized composition must leave the cache alone",
      RenderEngine.guardPseudolocaleStringCache(null),
    )
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

  // --- The process-wide gate (issue #3721). The concurrency behaviour it exists for is asserted
  // against real held sessions in `DesktopInteractiveSessionTest`; these pin the contract of the
  // helper itself, which needs no Skiko.

  @Test
  fun withPreviewLocaleAppliesAndRestoresAroundTheBlock() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("en-US"))
      val marker = Locale.getDefault()
      val seen = RenderEngine.withPreviewLocale("de") { Locale.getDefault().language }
      assertEquals("the block must run under the override", "de", seen)
      assertSame("and the previous default must be back afterwards", marker, Locale.getDefault())
    } finally {
      Locale.setDefault(original)
    }
  }

  @Test
  fun withPreviewLocaleRestoresWhenTheBlockThrows() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("en-US"))
      val marker = Locale.getDefault()
      try {
        RenderEngine.withPreviewLocale<Unit>("de") { error("boom") }
      } catch (_: IllegalStateException) {}
      assertSame(
        "a throwing render must not leave the daemon's default locale moved",
        marker,
        Locale.getDefault(),
      )
    } finally {
      Locale.setDefault(original)
    }
  }

  /**
   * `renderOnce` nests `driveStaticScrollToEnd` inside itself, and both go through the gate — so
   * same-mode nesting has to be reentrant on both sides or a localized scrolling capture deadlocks
   * against itself.
   */
  @Test
  fun withPreviewLocaleNestsInEitherMode() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("en-US"))
      val nestedWrite =
        RenderEngine.withPreviewLocale("de") {
          RenderEngine.withPreviewLocale("de") { Locale.getDefault().language }
        }
      assertEquals("de", nestedWrite)
      assertEquals(
        "en",
        RenderEngine.withPreviewLocale(null) {
          RenderEngine.withPreviewLocale(null) { Locale.getDefault().language }
        },
      )
      // Restoration unwinds to the outer scope's locale, not to whatever the inner one captured.
      assertEquals("en-US", Locale.getDefault().toLanguageTag())
    } finally {
      Locale.setDefault(original)
    }
  }

  /**
   * A read→write upgrade is the one nesting `ReentrantReadWriteLock` cannot serve: it blocks
   * forever rather than failing. It can't happen while one `SceneState` means one locale for a
   * whole render, but a future caller that breaks that invariant must get a diagnosable exception
   * instead of a hung daemon.
   */
  @Test
  fun withPreviewLocaleRefusesAReadToWriteUpgrade() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("en-US"))
      val failure =
        try {
          RenderEngine.withPreviewLocale(null) {
            RenderEngine.withPreviewLocale("de") { "should not reach here" }
          }
          null
        } catch (e: IllegalStateException) {
          e
        }
      assertNotNull("a localized render nested inside an unlocalized one must fail loudly", failure)
      assertTrue(
        "the message should name the upgrade; got \"${failure?.message}\"",
        failure?.message?.contains("upgrade") == true,
      )
    } finally {
      Locale.setDefault(original)
    }
  }
}
