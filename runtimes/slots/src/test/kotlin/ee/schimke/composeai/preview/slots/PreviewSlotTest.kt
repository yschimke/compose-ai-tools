package ee.schimke.composeai.preview.slots

import ee.schimke.composeai.data.layoutinspector.PreviewSlots
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSlotTest {

  @Test
  fun `slotTag prefixes the name with the dp-slot marker`() {
    assertEquals("dp-slot:leadingIcon", slotTag("leadingIcon"))
    assertEquals("dp-slot:", slotTag(""))
  }

  @Test
  fun `the marker prefix agrees with the reader's source of truth`() {
    // The extractor keys off this exact prefix; if the two drift, slots stop being discovered.
    assertEquals(PreviewSlots.SLOT_TAG_PREFIX, SLOT_TAG_PREFIX)
  }
}
