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
 * That whole apparatus exists for one reason: on desktop there is no way to scope a locale to a
 * composition. `androidx.compose.ui.platform.LocalProvidableLocaleList` looks like the way — it is
 * a `ProvidableCompositionLocal<LocaleList>`, and `LocalLocaleList` reads it back — but the text
 * pipeline does not consult it. `Locale.current` resolves through the platform delegate, which on
 * desktop is hardwired:
 * ```
 * DesktopPlatformLocale_desktopKt$createPlatformLocaleDelegate$1.getCurrent()
 *   → LocaleList(listOf(Locale(java.util.Locale.getDefault())))
 * ```
 *
 * and `SkiaParagraphIntrinsics` / `StringKt`'s casing helpers fall back to exactly that whenever a
 * `TextStyle` carries no explicit `localeList`.
 *
 * So this test asserts a **limitation**. If it ever fails because `staticCurrent` followed the
 * composition local, upstream has closed the gap and the process-global switch — along with the
 * gate serialising every composition in the daemon behind it — can be reconsidered. That is a good
 * day, and this test is how we find out about it. See yschimke/m3-catalog#54 for the upstream
 * report.
 *
 * The positive half of the pair — that moving the JVM default *does* move `Locale.current` — is
 * [RenderEngineLocaleTest.overrideJvmDefaultLocaleReachesComposeUiTextLocale], which needs no
 * render. This one composes, so it lives here.
 */
class LocaleCompositionLocalCanaryTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun providing_the_locale_composition_local_does_not_move_the_locale_text_resolves_against() {
    val hostDefault = Locale.getDefault()
    val outputDir = tempFolder.newFolder("locale-canary-renders")
    val host =
      DesktopHost(
        engine = RenderEngine(outputDir = outputDir),
        previewSpecResolver = { previewId ->
          if (previewId == PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "LocaleLocalProbeSquare",
              widthPx = 64,
              heightPx = 64,
              // No localeTag on purpose: the fixture provides the composition local itself, and
              // nothing here touches the JVM default. The two levers have to be tested apart.
              outputBaseName = PREVIEW_ID,
            )
          } else null
        },
      )
    host.start()
    try {
      Locale.setDefault(Locale.forLanguageTag("en-US"))
      LocaleLocalProbe.reset()
      val session =
        host.acquireInteractiveSession(
          previewId = PREVIEW_ID,
          classLoader = LocaleCompositionLocalCanaryTest::class.java.classLoader!!,
        )
      try {
        session.render(requestId = RenderHost.nextRequestId())

        assertEquals(
          "the provider itself must work, or this test proves nothing about what reads it",
          "ar",
          LocaleLocalProbe.compositionLocal,
        )
        assertEquals(
          "UPSTREAM CANARY: androidx.compose.ui.text.intl.Locale.current still ignores " +
            "LocalProvidableLocaleList and reads java.util.Locale.getDefault(). If this now " +
            "reports \"ar\", upstream wired the composition local into the text pipeline — revisit " +
            "the process-global localeTag switch and the gate around it (#3721), and close " +
            "yschimke/m3-catalog#54.",
          "en-US",
          LocaleLocalProbe.staticCurrent,
        )
      } finally {
        session.close()
      }
    } finally {
      LocaleLocalProbe.reset()
      Locale.setDefault(hostDefault)
      host.shutdown()
    }
  }

  private companion object {
    const val PREVIEW_ID = "locale-local-probe-square"
  }
}
