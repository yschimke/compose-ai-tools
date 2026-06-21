package ee.schimke.composeai.fakeemulator

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The `adb shell` device-settings commands Android Studio issues to drive its emulator UI toggles
 * must land as [DeviceSettings] changes the app can re-render under.
 */
class ShellSettingsTest {
  private val settings = DeviceSettingsController()
  private val interpreter =
    ShellInterpreter(
      properties = DeviceProperties.defaults("emulator-5554", DisplaySize(1080, 2340, 420)),
      frameSource = MutableFrameSource(DisplaySize(1080, 2340, 420)),
      previewLauncher = PreviewLauncher.NOOP,
      settings = settings,
    )

  @Test
  fun `cmd uimode night toggles dark and light`() {
    interpreter.execute("cmd uimode night yes")
    assertThat(settings.current.uiMode).isEqualTo(UiMode.DARK)
    interpreter.execute("cmd uimode night no")
    assertThat(settings.current.uiMode).isEqualTo(UiMode.LIGHT)
    interpreter.execute("cmd uimode night auto")
    assertThat(settings.current.uiMode).isEqualTo(UiMode.UNSET)
  }

  @Test
  fun `font scale, density and size map through`() {
    interpreter.execute("settings put system font_scale 1.3")
    assertThat(settings.current.fontScale).isEqualTo(1.3f)
    interpreter.execute("wm density 560")
    assertThat(settings.current.densityDpi).isEqualTo(560)
    interpreter.execute("wm size 1080x2400")
    assertThat(settings.current.widthPx).isEqualTo(1080)
    assertThat(settings.current.heightPx).isEqualTo(2400)
    interpreter.execute("wm density reset")
    assertThat(settings.current.densityDpi).isNull()
  }

  @Test
  fun `user_rotation maps to a rotation quadrant`() {
    interpreter.execute("settings put system user_rotation 1")
    assertThat(settings.current.rotation).isEqualTo(RotationQuadrant.LANDSCAPE)
    assertThat(settings.current.rotation.isLandscape).isTrue()
    interpreter.execute("settings put system user_rotation 0")
    assertThat(settings.current.rotation).isEqualTo(RotationQuadrant.PORTRAIT)
  }

  @Test
  fun `enabling TalkBack flips the talkBack setting`() {
    interpreter.execute(
      "settings put secure enabled_accessibility_services " +
        "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
    )
    interpreter.execute("settings put secure accessibility_enabled 1")
    assertThat(settings.current.talkBack).isTrue()

    interpreter.execute("settings put secure accessibility_enabled 0")
    assertThat(settings.current.talkBack).isFalse()
  }

  @Test
  fun `color inversion and daltonizer map to a color mode`() {
    interpreter.execute("settings put secure accessibility_display_inversion_enabled 1")
    assertThat(settings.current.colorMode).isEqualTo(ColorMode.INVERTED)
    interpreter.execute("settings put secure accessibility_display_inversion_enabled 0")
    interpreter.execute("settings put secure accessibility_display_daltonizer_enabled 1")
    interpreter.execute("settings put secure accessibility_display_daltonizer 1")
    assertThat(settings.current.colorMode).isEqualTo(ColorMode.DEUTERANOMALY)
  }

  @Test
  fun `setprop debug layout toggles layout bounds`() {
    interpreter.execute("setprop debug.layout true")
    assertThat(settings.current.showLayoutBounds).isTrue()
  }

  @Test
  fun `locale set-app-locales maps to localeTag`() {
    interpreter.execute(
      "cmd locale set-app-locales com.example --user current --locales fr-FR,en-US"
    )
    assertThat(settings.current.localeTag).isEqualTo("fr-FR")
  }

  @Test
  fun `settings get echoes a put value`() {
    interpreter.execute("settings put system font_scale 1.15")
    val out = String(interpreter.execute("settings get system font_scale").stdout).trim()
    assertThat(out).isEqualTo("1.15")
  }
}
