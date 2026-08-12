package ee.schimke.composeai.daemon

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A **canary on upstream Compose**, not a test of this repo's behaviour.
 *
 * `localeTag` is applied by moving the process-global JVM default `Locale`, which is why held
 * sessions have to be serialised behind `RenderEngine.withPreviewLocale` (issues #3718 / #3721).
 * That apparatus rests on one upstream fact: on desktop there is no way to scope a locale to a
 * composition. `androidx.compose.ui.platform.LocalProvidableLocaleList` looks like the way — it is
 * a real `ProvidableCompositionLocal<LocaleList>`, and `LocalLocaleList` reads it back — but
 * nothing consumes it.
 *
 * **Picking the right readout took two wrong ones, and both are worth recording so nobody repeats
 * them:**
 * - Asserting that `Locale.current` ignores the composition local is *vacuous*. It is a plain
 *   getter with no `Composer`, so it can never read a composition local whatever upstream does —
 *   such a test is green forever, including on the day the gap closes.
 * - Asserting on the **paragraph direction** of neutral text is *measuring the wrong mechanism*.
 *   Direction comes from `LocalLayoutDirection`, not from the locale: pin the layout direction to
 *   LTR and an `ar` locale still lays out `Ltr`. `RenderEngine.localeProviders` happens to provide
 *   `LocalLayoutDirection.Rtl` alongside an RTL `localeTag`, which makes a naive control look like
 *   it passes for the right reason when it does not.
 *
 * What is left is the field an upstream fix would actually have to populate:
 * `TextLayoutResult.layoutInput.style.localeList` — the locale the text layer chose to lay out
 * with. Any usable per-composition locale has to arrive there.
 *
 * [anExplicitLocaleReachesTheLaidOutStyle] is the positive control and it is not optional: without
 * it, a green `null` in [compositionLocalDoesNotReachTheLaidOutStyle] could just as well mean the
 * probe reads a field nothing ever fills. Together they say "a locale can reach this field, and the
 * composition local is not a locale that does".
 *
 * When the second test fails because the local *did* reach the style, upstream has closed the gap:
 * the process-global switch — and the gate serialising every composition in the daemon behind it —
 * can be reconsidered. See yschimke/m3-catalog#54 for the upstream report.
 */
class LocaleCompositionLocalCanaryTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  /**
   * Control: an explicitly-named locale *does* reach the style the text lays out with. Establishes
   * that the probe reads a field a locale can populate at all.
   */
  @Test
  fun anExplicitLocaleReachesTheLaidOutStyle() {
    val observed = renderProbe(fixture = "ExplicitLocaleStyleProbeSquare", localeTag = null)

    assertEquals(
      "an explicit TextStyle.localeList must show up in the laid-out style — if it does not, the " +
        "probe is reading nothing and the canary below proves nothing either",
      "ar",
      observed,
    )
  }

  /** The gap: the composition local reaches nothing. */
  @Test
  fun compositionLocalDoesNotReachTheLaidOutStyle() {
    // `LocaleLocalProbeSquare` provides LocalProvidableLocaleList = ar around the same probe, and
    // the spec carries no localeTag, so the JVM default stays where the host left it.
    val observed = renderProbe(fixture = "LocaleLocalProbeSquare", localeTag = null)

    assertEquals(
      "the provider must apply, or this asserts nothing about what reads it",
      "ar",
      LocaleLocalProbe.compositionLocal,
    )
    assertEquals(
      "UPSTREAM CANARY: the text layer still does not plumb LocalProvidableLocaleList into the " +
        "style it lays out with, so a composition-scoped locale reaches nothing and the " +
        "process-global java.util.Locale.setDefault stays the only lever. If this now reports " +
        "\"ar\", upstream wired the composition local through — revisit the localeTag switch and " +
        "the gate around it (#3721), and close yschimke/m3-catalog#54.",
      null,
      observed,
    )
  }

  /** Render [fixture] once and return the locale of the style it laid out with. */
  private fun renderProbe(fixture: String, localeTag: String?): String? {
    val hostDefault = Locale.getDefault()
    val outputDir = tempFolder.newFolder("locale-canary-$fixture")
    val host =
      DesktopHost(
        engine = RenderEngine(outputDir = outputDir),
        previewSpecResolver = { previewId ->
          if (previewId == PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = fixture,
              widthPx = 64,
              heightPx = 64,
              localeTag = localeTag,
              outputBaseName = PREVIEW_ID,
            )
          } else null
        },
      )
    host.start()
    try {
      // Nothing here should matter to the readings; pinned so a machine default cannot.
      Locale.setDefault(Locale.forLanguageTag("en-US"))
      LocaleLocalProbe.reset()
      val session =
        host.acquireInteractiveSession(
          previewId = PREVIEW_ID,
          classLoader = LocaleCompositionLocalCanaryTest::class.java.classLoader!!,
        )
      try {
        session.render(requestId = RenderHost.nextRequestId())
        return LocaleLocalProbe.resolvedStyleLocale
      } finally {
        session.close()
      }
    } finally {
      Locale.setDefault(hostDefault)
      host.shutdown()
    }
  }

  private companion object {
    const val PREVIEW_ID = "locale-local-probe-square"
  }
}
