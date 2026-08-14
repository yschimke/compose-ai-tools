package ee.schimke.composeai.renderer

import androidx.test.core.app.ApplicationProvider
import ee.schimke.composeai.data.render.previewSizeQualifiers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The other half of [ee.schimke.composeai.data.render.PreviewSizeQualifiersTest]: that test pins
 * the qualifier *string*, this one pins what Robolectric does with it — that the emitted `sw<n>dp`
 * really lands on `Configuration.smallestScreenWidthDp`, and that omitting it really does leave the
 * stale baseline behind.
 *
 * That second assertion is the actual bug from issue #3309:
 * `RuntimeEnvironment.setQualifiers("+…")` is incremental, so a render that set only `w`/`h`
 * produced a 227dp round Wear viewport that still reported `smallestScreenWidthDp == 320`.
 * `fillMaxRectangle()`-style geometry derived from that field came out inscribed in the wrong
 * circle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreviewSizeQualifiersConfigurationTest {

  private fun apply(qualifiers: List<String>) {
    RuntimeEnvironment.setQualifiers("+${qualifiers.joinToString("-")}")
  }

  private val configuration
    get() =
      ApplicationProvider.getApplicationContext<android.content.Context>().resources.configuration

  @Test
  fun `wear device qualifiers leave every screen dimension agreeing`() {
    apply(previewSizeQualifiers(widthDp = 227, heightDp = 227))

    assertEquals(227, configuration.screenWidthDp)
    assertEquals(227, configuration.screenHeightDp)
    assertEquals(227, configuration.smallestScreenWidthDp)
  }

  @Test
  fun `without the smallest-width token the stale baseline survives`() {
    // The pre-fix qualifier set: available width and height only.
    apply(listOf("w227dp", "h227dp"))

    assertEquals(227, configuration.screenWidthDp)
    // Robolectric's baseline sw320dp is untouched by an incremental "+w…-h…" — this is exactly the
    // inconsistency the sw token removes.
    assertEquals(320, configuration.smallestScreenWidthDp)
  }

  @Test
  fun `a landscape phone reports its narrow axis as the smallest width`() {
    apply(previewSizeQualifiers(widthDp = 891, heightDp = 411))

    assertEquals(891, configuration.screenWidthDp)
    assertEquals(411, configuration.screenHeightDp)
    assertEquals(411, configuration.smallestScreenWidthDp)
  }
}
