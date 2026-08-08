package ee.schimke.composeai.renderer

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the row-height estimate `CatalogSpecimenSheet` packs columns by.
 *
 * The estimate is what decides column breaks, and it has to decide them *before* anything is
 * measured. Under-counting is invisible in review and silent at render time — the row simply falls
 * off the bottom of the canvas, which is the bug the multi-column sheet exists to remove. So the
 * contract these assert is one-directional: an estimate may exceed the real row (slack at the
 * bottom of a column) but must never fall short of it.
 */
class CatalogTypeRowHeightTest {

  @Test
  fun `a declared sp line height is honoured rather than assumed from font size`() {
    // The regression Codex flagged on #3541: a legal theme can declare a line height far larger
    // than its font size, and `CatalogTypeRow` draws the style unchanged.
    val roomy = TextStyle(fontSize = 16.sp, lineHeight = 100.sp)

    assertTrue(catalogTypeRowHeight(roomy).value >= 100f)
  }

  @Test
  fun `an em line height is resolved against the font size`() {
    val twoEm = TextStyle(fontSize = 20.sp, lineHeight = 2.em)

    // 20sp x 2em = 40dp of line box, plus caption/padding/gap.
    assertTrue(catalogTypeRowHeight(twoEm).value >= 40f)
  }

  @Test
  fun `a tight declared line height still gets descender slack`() {
    // 1.0x would clip descenders; the 1.5x fallback doubles as a floor.
    val tight = TextStyle(fontSize = 20.sp, lineHeight = 20.sp)

    assertTrue(catalogTypeRowHeight(tight).value >= 30f)
  }

  @Test
  fun `a display-sized sample is budgeted for the second line it wraps onto`() {
    val display = TextStyle(fontSize = 36.sp)
    val body = TextStyle(fontSize = 16.sp)

    // The pangram wraps above 24sp in a sheet column, so the estimate covers two lines.
    assertTrue(catalogTypeRowHeight(display).value >= 36f * 1.5f * 2)
    assertTrue(catalogTypeRowHeight(body).value < 16f * 1.5f * 2 + 30f)
  }

  @Test
  fun `an unspecified size falls back rather than collapsing to zero`() {
    val bare = TextStyle(fontSize = TextUnit.Unspecified, lineHeight = TextUnit.Unspecified)

    assertTrue(catalogTypeRowHeight(bare).value >= 24f)
  }

  @Test
  fun `packing never leaves a column taller than the canvas`() {
    // The fallback the sheet degrades to when a theme's rows can't fit the designed block layout.
    // Its whole job is that no row falls off the canvas, however tall the rows are.
    val tall = List(12) { SpecimenCell(120.dp) {} }

    val columns = packColumns(tall, available = 400f)

    assertTrue(columns.isNotEmpty())
    for (column in columns) {
      assertTrue(column.fold(0f) { sum, cell -> sum + cell.height.value } <= 400f)
    }
    assertEquals(tall.size, columns.sumOf { it.size })
  }

  @Test
  fun `a heading is never left stranded at the foot of a column`() {
    val cells =
      listOf(
        SpecimenCell(100.dp) {},
        SpecimenCell(100.dp) {},
        SpecimenCell(24.dp, keepWithNext = true) {},
        SpecimenCell(100.dp) {},
      )

    val columns = packColumns(cells, available = 240f)

    for (column in columns) {
      assertTrue(column.isEmpty() || !column.last().keepWithNext)
    }
  }
}
