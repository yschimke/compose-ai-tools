package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeClockOverrideExtensionTest {

  private val extension = FakeClockPreviewOverrideExtension()

  @Test
  fun `plan returns null when no fake clock is set`() {
    assertNull(extension.plan(PreviewOverrides()))
    assertNull(extension.plan(PreviewOverrides(clockEpochMillis = null)))
  }

  @Test
  fun `plan returns null for a negative instant`() {
    // A negative epoch is nonsensical for a pinned wall clock; abstain so the render stays
    // byte-identical rather than pinning to a pre-1970 instant.
    assertNull(extension.plan(PreviewOverrides(clockEpochMillis = -1)))
  }

  @Test
  fun `plan returns the fake-clock extension when an instant is set`() {
    val planned = extension.plan(PreviewOverrides(clockEpochMillis = 1_700_000_000_000))
    assertNotNull(planned)
    assertEquals(FakeClockOverrideExtension.ID, planned!!.id)
    assertTrue(planned is FakeClockOverrideExtension)
  }

  @Test
  fun `plan accepts the epoch zero instant`() {
    // Zero (1970-01-01T00:00:00Z) is a valid pin — only negatives are rejected.
    assertNotNull(extension.plan(PreviewOverrides(clockEpochMillis = 0)))
  }
}
