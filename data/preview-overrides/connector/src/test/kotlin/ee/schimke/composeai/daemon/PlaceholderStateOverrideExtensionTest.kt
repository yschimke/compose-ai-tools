package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Planner behaviour for the `placeholderActive` render override (issue #2646). */
class PlaceholderStateOverrideExtensionTest {

  private val extension = PlaceholderStatePreviewOverrideExtension()

  @Test
  fun `plan returns null when no placeholder state is forced`() {
    // An unforced render must stay byte-identical: no local provided, so the preview keeps whatever
    // state it computes for itself.
    assertNull(extension.plan(PreviewOverrides()))
    assertNull(extension.plan(PreviewOverrides(placeholderActive = null)))
  }

  @Test
  fun `plan returns the extension for the loading state`() {
    val planned = extension.plan(PreviewOverrides(placeholderActive = true))
    assertNotNull(planned)
    assertEquals(PlaceholderStateOverrideExtension.ID, planned!!.id)
    assertTrue(planned is PlaceholderStateOverrideExtension)
  }

  @Test
  fun `the planner is always-on so serve does not need extensions-enable`() {
    // `ExtensionRegistry.activeOverrideExtensions` gates a planner on its owning extension unless
    // it carries this marker. `serve` (the consumer this override exists for) never calls
    // `extensions/enable`, so without it `?placeholderActive=` would parse, cache, and advertise
    // while silently rendering the preview's own state.
    assertTrue(extension is AlwaysOnPreviewOverrideExtension)
    // Always-on planners are also handed an empty bag on a no-override render; abstaining there is
    // what keeps an unforced render byte-identical.
    assertNull(extension.plan(PreviewOverrides()))
  }

  @Test
  fun `plan returns the extension for the loaded state too`() {
    // `false` is a real pin, not "unset": it forces the ideal state even for a preview whose own
    // state would say otherwise, which is what makes the two renders a deterministic pair.
    assertNotNull(extension.plan(PreviewOverrides(placeholderActive = false)))
  }
}
