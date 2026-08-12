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
 * a real `ProvidableCompositionLocal<LocaleList>`, and `LocalLocaleList` reads it back — but the
 * text pipeline does not consult it. Skiko's paragraph intrinsics resolve a base direction from
 * `localeList ?: Locale.current`, and `Locale.current` goes through the desktop platform delegate,
 * which is hardwired to `java.util.Locale.getDefault()`.
 *
 * **This measures a consumer, not an accessor, and that distinction is the test.** Asserting that
 * `Locale.current` ignores the composition local would be vacuous: it is a plain getter with no
 * `Composer`, so it can never read one, no matter what upstream does. The change actually worth
 * detecting is a *consumer* — paragraph layout, `stringResource` — switching from the static
 * delegate to `LocalLocaleList`. So the readout here is the paragraph direction Compose resolved
 * for direction-neutral text, which is exactly one such consumer.
 *
 * [jvmDefaultDrivesParagraphDirection] is the positive control and it is not optional: without it,
 * a green `Ltr` in [compositionLocalDoesNotDriveParagraphDirection] could just as well mean the
 * probe never measured anything. Together they say "this signal is locale-driven, and the
 * composition local is not the locale that drives it".
 *
 * When the second test fails because the local *did* steer the paragraph, upstream has closed the
 * gap: the process-global switch — and the gate serialising every composition in the daemon behind
 * it — can be reconsidered. See yschimke/m3-catalog#54 for the upstream report.
 */
class LocaleCompositionLocalCanaryTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  /**
   * Control: the JVM default *does* reach paragraph layout. Establishes that the probe measures
   * something locale-driven at all.
   */
  @Test
  fun jvmDefaultDrivesParagraphDirection() {
    val observed = renderProbe(fixture = "LocaleTextDirectionProbeSquare", localeTag = "ar")

    assertEquals(
      "an RTL localeTag must reach paragraph layout — if this is Ltr the probe is measuring " +
        "nothing and the canary below proves nothing either",
      "Rtl",
      observed,
    )
  }

  /** The gap: the composition local alone steers nothing. */
  @Test
  fun compositionLocalDoesNotDriveParagraphDirection() {
    // `LocaleLocalProbeSquare` provides LocalProvidableLocaleList = ar around the same probe, and
    // the spec carries no localeTag, so the JVM default stays where the host left it.
    val observed = renderProbe(fixture = "LocaleLocalProbeSquare", localeTag = null)

    assertEquals(
      "the provider must apply, or this asserts nothing about what reads it",
      "ar",
      LocaleLocalProbe.compositionLocal,
    )
    assertEquals(
      "UPSTREAM CANARY: paragraph layout still ignores LocalProvidableLocaleList and resolves its " +
        "base direction from java.util.Locale.getDefault() (saw Locale.current = " +
        "${LocaleLocalProbe.staticCurrent}). If this is now Rtl, upstream wired the composition " +
        "local into the text pipeline — revisit the process-global localeTag switch and the gate " +
        "around it (#3721), and close yschimke/m3-catalog#54.",
      "Ltr",
      observed,
    )
  }

  /** Render [fixture] once and return the paragraph direction it resolved. */
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
      // Emphatically LTR, so an `Rtl` reading can only have come from the locale under test.
      Locale.setDefault(Locale.forLanguageTag("en-US"))
      LocaleLocalProbe.reset()
      val session =
        host.acquireInteractiveSession(
          previewId = PREVIEW_ID,
          classLoader = LocaleCompositionLocalCanaryTest::class.java.classLoader!!,
        )
      try {
        session.render(requestId = RenderHost.nextRequestId())
        return LocaleLocalProbe.paragraphDirection
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
