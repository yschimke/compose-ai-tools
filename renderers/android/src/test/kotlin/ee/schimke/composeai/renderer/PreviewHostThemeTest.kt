package ee.schimke.composeai.renderer

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [PreviewHostTheme]'s resolution order (issue #2957): an explicitly configured
 * `composeai.render.hostTheme` wins, otherwise the manifest's `<application android:theme>`,
 * otherwise nothing.
 *
 * `ApplicationInfo.theme` is a plain public field, so the manifest declaration is simulated by
 * writing it directly — the same value Robolectric populates from a real `<application>` element.
 * Platform styles stand in for a consumer's own themes: this module has no resources of its own,
 * and the resolution logic doesn't care which package a style came from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewHostThemeTest {

  private val app
    get() = ApplicationProvider.getApplicationContext<android.app.Application>()

  private val originalTheme = app.applicationInfo.theme

  @After
  fun restoreGlobals() {
    app.applicationInfo.theme = originalTheme
    System.clearProperty(PreviewHostTheme.HOST_THEME_PROPERTY)
    PreviewHostTheme.warnedUnresolved = false
  }

  @Test
  fun `falls back to the manifest application theme`() {
    app.applicationInfo.theme = android.R.style.Theme_Material_Light

    assertEquals(android.R.style.Theme_Material_Light, PreviewHostTheme.resolveThemeResId(app))
  }

  @Test
  fun `applying the manifest theme changes what the host activity resolves`() {
    app.applicationInfo.theme = android.R.style.Theme_Material_Light

    val activity = launchHost()
    assertEquals(android.R.style.Theme_Material_Light, PreviewHostTheme.applyTo(activity))
    // Assert the resolved *value*, not the id we just set: that proves the theme is live on the
    // activity, which is what an `AndroidView` inflation actually reads.
    assertEquals(LIGHT_WINDOW_BACKGROUND, colorBackground(activity))
  }

  @Test
  fun `no manifest theme and no property leaves the host activity alone`() {
    app.applicationInfo.theme = 0

    val activity = launchHost()
    val before = colorBackground(activity)

    assertEquals(0, PreviewHostTheme.applyTo(activity))
    assertEquals(before, colorBackground(activity))
  }

  @Test
  fun `a configured theme name wins over the manifest theme`() {
    // The library-module case: `<application android:theme>` is absent (0 here), or present but
    // not the theme whose attributes the module's AndroidView previews need.
    app.applicationInfo.theme = android.R.style.Theme_Material
    System.setProperty(PreviewHostTheme.HOST_THEME_PROPERTY, "@android:style/Theme.Material.Light")

    val activity = launchHost()

    assertNotEquals(0, PreviewHostTheme.applyTo(activity))
    assertEquals(LIGHT_WINDOW_BACKGROUND, colorBackground(activity))
  }

  @Test
  fun `accepts the spellings a consumer would reach for`() {
    listOf(
        "@android:style/Theme.Material.Light",
        "android:style/Theme.Material.Light",
        "@android:Theme.Material.Light",
      )
      .forEach { spelling ->
        System.setProperty(PreviewHostTheme.HOST_THEME_PROPERTY, spelling)
        assertEquals(
          "spelling \"$spelling\" should resolve",
          android.R.style.Theme_Material_Light,
          PreviewHostTheme.resolveThemeResId(app),
        )
      }
  }

  @Test
  fun `an unresolvable configured theme reports itself instead of silently falling back`() {
    // Falling back to the manifest theme here would hide the typo, and the only symptom would be
    // the render failure the property was set to prevent.
    app.applicationInfo.theme = android.R.style.Theme_Material_Light
    System.setProperty(PreviewHostTheme.HOST_THEME_PROPERTY, "@style/Theme.Nope")

    assertEquals(0, PreviewHostTheme.resolveThemeResId(app))
    val message = PreviewHostTheme.describeUnresolved(app)
    assertNotNull(message)
    assertEquals(true, message!!.contains("Theme.Nope"))
  }

  @Test
  fun `nothing to report when the configured theme resolves`() {
    System.setProperty(PreviewHostTheme.HOST_THEME_PROPERTY, "@android:style/Theme.Material.Light")

    assertNull(PreviewHostTheme.describeUnresolved(app))
  }

  @Test
  fun `light and dark platform themes resolve differently`() {
    // Guards the colorBackground assertions above from passing because every theme agrees.
    val activity = launchHost()
    app.applicationInfo.theme = android.R.style.Theme_Material
    PreviewHostTheme.applyTo(activity)

    assertNotEquals(LIGHT_WINDOW_BACKGROUND, colorBackground(activity))
  }

  private fun launchHost(): Activity =
    Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().get()

  private fun colorBackground(activity: Activity): Int {
    val attrs = intArrayOf(android.R.attr.colorBackground)
    val typed = activity.theme.obtainStyledAttributes(attrs)
    return try {
      typed.getColor(0, 0)
    } finally {
      typed.recycle()
    }
  }

  private companion object {
    /** `android:colorBackground` in `Theme.Material.Light`. */
    const val LIGHT_WINDOW_BACKGROUND = 0xFFFAFAFA.toInt()
  }
}
