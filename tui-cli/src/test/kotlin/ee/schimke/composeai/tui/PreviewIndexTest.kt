package ee.schimke.composeai.tui

import ee.schimke.composeai.cli.PreviewInfo
import ee.schimke.composeai.cli.PreviewModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage for [PreviewIndex.refresh] — the selection-preserving swap that backs live
 * discovery updates (issue #1594). The watcher plumbing is exercised separately in
 * [DiscoveryWatcherTest]; here we pin the cursor semantics that make a refresh non-destructive.
 */
class PreviewIndexTest {
  private val module = PreviewModule(gradlePath = ":sample", projectDir = File("/tmp/sample"))

  private fun rows(vararg ids: String): List<PreviewRow> = ids.map { id ->
    PreviewRow(module = module, info = PreviewInfo(id = id, functionName = id, className = "C"))
  }

  @Test
  fun refreshKeepsCursorOnSamePreviewWhenItStillExists() {
    val index = PreviewIndex(rows("A", "B", "C"))
    index.moveCursor(1) // select B
    assertEquals("B", index.current()?.id)

    // A new preview is inserted ahead of B; B's index shifts but selection should follow it.
    index.refresh(rows("A", "A2", "B", "C"))

    assertEquals("B", index.current()?.id)
    assertEquals(2, index.cursorIndex())
  }

  @Test
  fun refreshClampsWhenSelectedPreviewWasRemoved() {
    val index = PreviewIndex(rows("A", "B", "C"))
    index.moveCursor(2) // select C (index 2)
    assertEquals("C", index.current()?.id)

    // C is deleted. Cursor can't follow it, so it clamps to the new last index rather than
    // resetting to the top.
    index.refresh(rows("A", "B"))

    assertEquals(1, index.cursorIndex())
    assertEquals("B", index.current()?.id)
  }

  @Test
  fun refreshSurvivesEmptyResultAndRepopulation() {
    val index = PreviewIndex(rows("A", "B"))
    index.moveCursor(1)

    index.refresh(emptyList())
    assertEquals(0, index.size())
    assertNull(index.current())
    assertEquals(0, index.cursorIndex())

    index.refresh(rows("X", "Y"))
    assertEquals(2, index.size())
    // No prior id to restore (list was empty), cursor stays clamped at 0.
    assertEquals("X", index.current()?.id)
  }

  @Test
  fun refreshReappliesActiveFilter() {
    val index = PreviewIndex(rows("ButtonPreview", "CardPreview", "DialogPreview"))
    index.setFilter("Card")
    assertEquals(1, index.size())
    assertEquals("CardPreview", index.current()?.id)

    // Discovery adds another "Card*" preview; the filter must apply to the fresh rows.
    index.refresh(rows("ButtonPreview", "CardPreview", "CardHeaderPreview", "DialogPreview"))

    assertEquals(2, index.size())
    // Selection stays on CardPreview (still present), now at filtered index 0.
    assertEquals("CardPreview", index.current()?.id)
  }
}
