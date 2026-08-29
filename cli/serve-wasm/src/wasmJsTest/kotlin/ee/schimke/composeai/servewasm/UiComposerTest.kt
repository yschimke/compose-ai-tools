package ee.schimke.composeai.servewasm

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
  fun `composer has a direct url mode`() {
    val config = ClientConfig.fromLocation("?session=compose-m3&compose=1")

    assertEquals("compose-m3", config.session)
    assertTrue(config.initialComposer)
  }
}
