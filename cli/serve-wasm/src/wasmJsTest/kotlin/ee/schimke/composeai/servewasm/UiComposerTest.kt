package ee.schimke.composeai.servewasm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiComposerTest {
  private val items =
    listOf(
      ComposerItem(1, "button-filled"),
      ComposerItem(2, "checkbox-checked"),
      ComposerItem(3, "switch-on"),
    )

  @Test
  fun `moving an item uses insertion positions from the original canvas`() {
    assertEquals(
      listOf(2, 3, 1),
      moveComposerItem(items, sourceIndex = 0, targetIndex = 3).map { it.key },
    )
    assertEquals(
      listOf(3, 1, 2),
      moveComposerItem(items, sourceIndex = 2, targetIndex = 0).map { it.key },
    )
  }

  @Test
  fun `drop index selects the gap nearest the pointer`() {
    val centers = listOf(100f, 200f, 300f)

    assertEquals(0, composerDropIndex(40f, centers))
    assertEquals(1, composerDropIndex(150f, centers))
    assertEquals(3, composerDropIndex(340f, centers))
  }

  @Test
  fun `component can move from the canvas into a named slot`() {
    val target = ComposerSlotTarget(hostKey = 4, slotName = "content")
    val host = ComposerItem(4, "card-slots")
    val moving = ComposerItem(5, "button-filled")

    val nested =
      putComposerItemInSlot(removeComposerItem(listOf(host, moving), moving.key), target, moving)

    assertEquals(listOf(4), nested.map { it.key })
    assertEquals(moving, nested.single().slots["content"])
    assertEquals(2, composerItemCount(nested))
    assertEquals(moving, composerItemByKey(nested, moving.key))
  }

  @Test
  fun `clearing a slot preserves its host`() {
    val host =
      ComposerItem(
        4,
        "card-slots",
        slots = mapOf("content" to ComposerItem(5, "button-filled")),
      )

    val cleared = putComposerSlots(listOf(host), host.key, emptyMap())

    assertTrue(cleared.single().slots.isEmpty())
  }

  @Test
  fun `smallest overlapping slot wins hit testing`() {
    val outer = ComposerSlotTarget(1, "outer")
    val inner = ComposerSlotTarget(2, "inner")

    assertEquals(
      inner,
      composerSlotAt(
        Offset(50f, 50f),
        mapOf(outer to Rect(0f, 0f, 100f, 100f), inner to Rect(25f, 25f, 75f, 75f)),
      ),
    )
  }

  @Test
  fun `composer has a direct url mode`() {
    val config = ClientConfig.fromLocation("?session=compose-m3&compose=1")

    assertEquals("compose-m3", config.session)
    assertTrue(config.initialComposer)
  }
}
