package ee.schimke.composeai.daemon

import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import ee.schimke.composeai.data.pseudolocale.Pseudolocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [PseudolocaleResources] must resolve **through the `Resources` instance it wraps**, not through
 * `super`.
 *
 * The live daemon stacks resource wrappers, and `PlaceholderFallbackResources` — which turns a
 * missing id into a visible placeholder instead of a `NotFoundException` that aborts the whole
 * render — is one of them. Because this class extends `Resources` over the wrapped instance's
 * `AssetManager`, a `super.getText(id)` reads the raw table directly and steps over that fallback:
 * a preview that renders fine at `en` dies at `en-XA`, which is the opposite of what a debugging
 * aid should do. The same applies to the value families the fallback guards (colour, dimension,
 * drawable), which this class passes through untransformed but must still delegate.
 *
 * Sibling of `:daemon:android`'s `PlaceholderFallbackResourcesTest.fallback delegates through a
 * wrapped Resources implementation before substituting`, which pins the rule from the other side.
 * The two wrappers have to compose in either order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PseudolocaleResourcesDelegationTest {

  private val base: Resources by lazy { RuntimeEnvironment.getApplication().resources }

  /** An id no table resolves — what an absent / stale packed resource table hands the renderer. */
  private val missingId = 0x7f0f0000

  private val handledText = "Battery low"
  private val handledColor = 0xFF123456.toInt()

  /**
   * Stands in for `PlaceholderFallbackResources`: answers [missingId] itself and forwards every
   * other lookup to the real table. Declared here rather than depending on `:daemon:android` (the
   * dependency runs the other way), which is also the point — the contract is "delegate to the
   * wrapped instance", not "know about that one class".
   */
  private fun fallback(): Resources =
    object : Resources(base.assets, base.displayMetrics, base.configuration) {
      override fun getText(id: Int): CharSequence =
        if (id == missingId) handledText else base.getText(id)

      override fun getColor(id: Int, theme: Theme?): Int =
        if (id == missingId) handledColor else base.getColor(id, theme)

      override fun getDimension(id: Int): Float =
        if (id == missingId) 42f else base.getDimension(id)

      override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? =
        if (id == missingId) ColorDrawable(handledColor)
        else base.getDrawableForDensity(id, density, theme)
    }

  @Test
  fun `a string the wrapped Resources handles is pseudolocalised, not re-resolved`() {
    val pseudo = PseudolocaleResources(fallback(), Pseudolocale.ACCENT)
    val text = pseudo.getText(missingId).toString()
    // Delegation gives us the wrapper's answer; the transform then runs over it. Through `super`
    // this id resolves nowhere and throws instead.
    assertTrue("expected the wrapped value, pseudolocalised; got '$text'", text.contains("ļöŵ"))
  }

  @Test
  fun `getString funnels through the delegating getText too`() {
    // `getString(int)` is `getText(int).toString()`, so it inherits both the delegation and the
    // transform — no separate override, same as the class has always relied on.
    val pseudo = PseudolocaleResources(fallback(), Pseudolocale.ACCENT)
    assertTrue(pseudo.getString(missingId).contains("ļöŵ"))
  }

  @Test
  fun `value resources the wrapped Resources handles come back untransformed but delegated`() {
    val pseudo = PseudolocaleResources(fallback(), Pseudolocale.BIDI)
    assertEquals(handledColor, pseudo.getColor(missingId, null))
    assertEquals(42f, pseudo.getDimension(missingId), 0f)
    val drawable = pseudo.getDrawableForDensity(missingId, 160, null)
    assertTrue("expected the wrapper's drawable, got $drawable", drawable is ColorDrawable)
    assertEquals(handledColor, (drawable as ColorDrawable).color)
  }

  @Test
  fun `an ordinary lookup still resolves against the real table`() {
    // Delegation must not change the plain case: wrapping the app's own Resources resolves every
    // present id exactly as before, transform included.
    val pseudo = PseudolocaleResources(base, Pseudolocale.ACCENT)
    val ok = pseudo.getString(android.R.string.ok)
    assertTrue("framework string must still resolve, pseudolocalised; got '$ok'", ok != "OK")
    assertTrue(ok.startsWith("["))
  }
}
