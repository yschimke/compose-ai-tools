package ee.schimke.composeai.daemon

import android.content.res.Resources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies [PlaceholderFallbackResources] substitutes an obvious placeholder for a **missing**
 * resource id while passing **resolvable** framework resources straight through. This is the graceful
 * degradation that keeps a packed-bundle render from aborting with `Resources$NotFoundException` when
 * the app resource table is absent or stale (the live-server `wear-m3` failure mode).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaceholderFallbackResourcesTest {

  private val base: Resources by lazy { RuntimeEnvironment.getApplication().resources }
  private val fallback: PlaceholderFallbackResources by lazy { PlaceholderFallbackResources(base) }

  // An id in the app (0x7f) package that no resource table defines under a bare Robolectric app.
  private val missingId = 0x7f0f9999

  @Test
  fun `a missing string id falls back to an obvious placeholder instead of throwing`() {
    val text = fallback.getText(missingId).toString()
    assertTrue("expected a bracketed placeholder, got: $text", text.startsWith("⟦res 0x"))
    assertTrue("placeholder should name the id in hex, got: $text", text.contains("7f0f9999"))
  }

  @Test
  fun `getString routes through the same getText funnel so it also gets a placeholder`() {
    // getString(int) is getText(int).toString(); overriding getText alone must cover it.
    val text = fallback.getString(missingId)
    assertTrue("expected a bracketed placeholder, got: $text", text.startsWith("⟦res 0x"))
  }

  @Test
  fun `a resolvable framework string is returned untouched`() {
    val real = base.getString(android.R.string.ok)
    val viaFallback = fallback.getString(android.R.string.ok)
    assertEquals("resolvable resources must pass through unchanged", real, viaFallback)
    assertFalse("a resolvable string must not be a placeholder", viaFallback.startsWith("⟦res"))
  }

  @Test
  fun `a missing color id falls back to the placeholder magenta`() {
    assertEquals(
      PlaceholderFallbackResources.PLACEHOLDER_COLOR,
      fallback.getColor(missingId, null),
    )
  }

  @Test
  fun `a resolvable framework color is returned untouched`() {
    val real = base.getColor(android.R.color.white, null)
    assertEquals(real, fallback.getColor(android.R.color.white, null))
  }

  @Test
  fun `a missing dimension id falls back to zero rather than throwing`() {
    assertEquals(0f, fallback.getDimension(missingId), 0f)
    assertEquals(0, fallback.getDimensionPixelSize(missingId))
  }

  @Test
  fun `wrappedForPlaceholderResources exposes the fallback via getResources`() {
    val ctx = RuntimeEnvironment.getApplication().wrappedForPlaceholderResources()
    assertTrue(ctx.resources is PlaceholderFallbackResources)
    // And the wrapped context resolves a miss to a placeholder through the standard accessor.
    assertTrue(ctx.getString(missingId).startsWith("⟦res 0x"))
  }

  @Test
  fun `the resources recorder built over the fallback keeps the fallback active`() {
    // Regression for the shadowing path: `ResourcesRecorderExtension` re-provides `LocalContext`
    // from a `RecordingResources` that delegates to the context it was built with. When that base
    // is the placeholder-wrapped context, a missing lookup must degrade to a placeholder instead
    // of throwing `Resources$NotFoundException` — otherwise the recorder's inner provider silently
    // defeats the fallback for the main daemon/serve render.
    val recordingContext =
      RuntimeEnvironment.getApplication().wrappedForPlaceholderResources().let { fallbackContext ->
        val recorder = ResourcesUsedDataProducer.recorder(fallbackContext)
        ResourcesUsedDataProducer.context(fallbackContext, recorder)
      }
    assertTrue(recordingContext.getString(missingId).startsWith("⟦res 0x"))
    // A resolvable resource still records + returns its real value through the recorder.
    assertEquals(
      RuntimeEnvironment.getApplication().resources.getString(android.R.string.ok),
      recordingContext.getString(android.R.string.ok),
    )
  }
}
