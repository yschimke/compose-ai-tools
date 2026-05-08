package ee.schimke.composeai.daemon

import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import ee.schimke.composeai.data.pseudolocale.Pseudolocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies `PseudolocaleResources` preserves `Spanned` metadata when transforming styled string
 * resources. Without this, every preview rendered against a styled `<b>foo</b>` /
 * `<annotation>` resource lost its emphasis under pseudolocale, hiding styling regressions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PseudolocaleResourcesSpanPreservationTest {

  private val baseResources by lazy { RuntimeEnvironment.getApplication().resources }

  @Test
  fun `spans on a styled CharSequence are remapped onto the pseudolocalised output`() {
    val styled = SpannableString("Battery low: charge soon.")
    val span = StyleSpan(android.graphics.Typeface.BOLD)
    // Span the word "low" — input positions [8, 11).
    styled.setSpan(span, 8, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    val result = withSpannedGetText(styled).pseudolocaliseForTest(Pseudolocale.ACCENT)

    assertTrue("output is Spanned: $result", result is Spanned)
    val out = result as Spanned
    val outString = out.toString()
    assertTrue("output contains accented form of 'low': $outString", outString.contains("ļöŵ"))

    val styleSpans = out.getSpans(0, out.length, StyleSpan::class.java)
    assertEquals("exactly one StyleSpan should survive", 1, styleSpans.size)
    assertSame("the original span object should be re-attached", span, styleSpans[0])

    // The span should bracket the accented `ļöŵ`. We don't pin exact indices because the output
    // length depends on accent map lengths, but the substring at [start, end) should match.
    val start = out.getSpanStart(styleSpans[0])
    val end = out.getSpanEnd(styleSpans[0])
    assertEquals("ļöŵ", outString.substring(start, end))
  }

  @Test
  fun `plain CharSequence input takes the fast path and is not Spanned`() {
    val plain = "Battery low: charge soon."
    val result = withSpannedGetText(plain).pseudolocaliseForTest(Pseudolocale.ACCENT)
    assertNotNull(result)
    assertTrue("plain inputs come back as a non-Spanned String", result !is Spanned)
  }

  /**
   * Helper that exposes [PseudolocaleResources]'s private `pseudolocalise` over a test
   * `CharSequence`. We instantiate `PseudolocaleResources` against the application's real
   * `Resources` so the constructor's `assets` / `displayMetrics` / `configuration` are valid,
   * then route the input directly through the same code path `getText(int)` calls.
   */
  private fun withSpannedGetText(raw: CharSequence) =
    object {
      fun pseudolocaliseForTest(mode: Pseudolocale): CharSequence {
        val pseudo = PseudolocaleResources(baseResources, mode)
        // Reflective access into the private helper. Keeps the test focused on the
        // span-preservation contract without exposing the helper publicly.
        val method =
          PseudolocaleResources::class.java.getDeclaredMethod(
            "pseudolocalise",
            CharSequence::class.java,
          )
        method.isAccessible = true
        return method.invoke(pseudo, raw) as CharSequence
      }
    }
}
