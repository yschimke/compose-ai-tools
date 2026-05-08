package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PseudolocalePreviewOverrideExtensionTest {

  private val extension = PseudolocalePreviewOverrideExtension()

  @Test
  fun `plan returns null for non-pseudo locales`() {
    assertNull(extension.plan(PreviewOverrides(localeTag = "en-US")))
    assertNull(extension.plan(PreviewOverrides(localeTag = "fr")))
    assertNull(extension.plan(PreviewOverrides(localeTag = null)))
  }

  @Test
  fun `plan returns extension for accent pseudolocale`() {
    val planned = extension.plan(PreviewOverrides(localeTag = "en-XA"))
    assertNotNull(planned)
    assertEquals(PseudolocaleOverrideExtension.ID, planned!!.id)
  }

  @Test
  fun `plan returns extension for bidi pseudolocale`() {
    val planned = extension.plan(PreviewOverrides(localeTag = "ar-XB"))
    assertNotNull(planned)
    assertEquals(PseudolocaleOverrideExtension.ID, planned!!.id)
  }

  @Test
  fun `plan accepts underscore form`() {
    assertNotNull(extension.plan(PreviewOverrides(localeTag = "en_XA")))
    assertNotNull(extension.plan(PreviewOverrides(localeTag = "ar_XB")))
  }
}
