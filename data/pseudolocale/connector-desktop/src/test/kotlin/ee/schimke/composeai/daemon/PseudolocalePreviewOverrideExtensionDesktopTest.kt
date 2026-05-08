package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PseudolocalePreviewOverrideExtensionDesktopTest {

  private val extension = PseudolocalePreviewOverrideExtensionDesktop()

  @Test
  fun `plan returns null for non-pseudo locales`() {
    assertNull(extension.plan(PreviewOverrides(localeTag = "en-US")))
    assertNull(extension.plan(PreviewOverrides(localeTag = null)))
  }

  @Test
  fun `plan returns extension for both pseudolocales`() {
    val accent = extension.plan(PreviewOverrides(localeTag = "en-XA"))
    val bidi = extension.plan(PreviewOverrides(localeTag = "ar-XB"))
    assertNotNull(accent)
    assertNotNull(bidi)
    assertEquals(PseudolocaleOverrideExtensionDesktop.ID, accent!!.id)
    assertEquals(PseudolocaleOverrideExtensionDesktop.ID, bidi!!.id)
  }
}
