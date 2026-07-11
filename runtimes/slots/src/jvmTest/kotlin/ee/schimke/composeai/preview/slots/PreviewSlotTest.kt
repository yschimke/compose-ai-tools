package ee.schimke.composeai.preview.slots

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.PreviewSlots
import ee.schimke.composeai.data.layoutinspector.SlotScope
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSlotTest {

  @Test
  fun `slotTag prefixes the name with the dp-slot marker`() {
    assertEquals("dp-slot:leadingIcon", slotTag("leadingIcon"))
    assertEquals("dp-slot:", slotTag(""))
  }

  @Test
  fun `slotTag appends scope and scroll attributes`() {
    assertEquals("dp-slot:content;scope=column", slotTag("content", PreviewSlotScope.Column, false))
    assertEquals("dp-slot:list;scope=lazy;scroll=1", slotTag("list", PreviewSlotScope.Lazy, true))
    assertEquals("dp-slot:fab;scope=box", slotTag("fab", PreviewSlotScope.Box, false))
    // Unknown scope emits no scope attribute — a bare tag when not scrolling.
    assertEquals("dp-slot:x", slotTag("x", PreviewSlotScope.Unknown, false))
    assertEquals("dp-slot:x;scroll=1", slotTag("x", PreviewSlotScope.Unknown, true))
  }

  @Test
  fun `the marker prefix agrees with the reader's source of truth`() {
    // The extractor keys off this exact prefix; if the two drift, slots stop being discovered.
    assertEquals(PreviewSlots.SLOT_TAG_PREFIX, SLOT_TAG_PREFIX)
  }

  @Test
  fun `every scope's tag round-trips through the reader`() {
    // The writer (this module) and parser (the reader) must agree on the wire tokens, or a slot's
    // scope silently reads back UNKNOWN. Assert each scope survives tag → extractSlots.
    val expected =
      mapOf(
        PreviewSlotScope.Unknown to SlotScope.UNKNOWN,
        PreviewSlotScope.Row to SlotScope.ROW,
        PreviewSlotScope.Column to SlotScope.COLUMN,
        PreviewSlotScope.Box to SlotScope.BOX,
        PreviewSlotScope.Lazy to SlotScope.LAZY,
      )
    for ((runtimeScope, readerScope) in expected) {
      val tag = slotTag("s", runtimeScope, scrolling = false)
      val node = ComposeSemanticsNode(nodeId = "0", boundsInRoot = "0,0,1,1", testTag = tag)
      val slot = PreviewSlots.extractSlots(ComposeSemanticsPayload(node)).single()
      assertEquals("scope $runtimeScope", readerScope, slot.scope)
    }
  }
}
