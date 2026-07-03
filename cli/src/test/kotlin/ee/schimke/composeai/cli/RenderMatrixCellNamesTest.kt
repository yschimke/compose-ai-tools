package ee.schimke.composeai.cli

import ee.schimke.composeai.mcp.MatrixCell
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract for the `--cells-dir` per-cell file names: derived from the cell's axis values in
 * [MatrixCell.label] order, filesystem-safe, and `default.png` for the all-default cell.
 */
class RenderMatrixCellNamesTest {

  @Test
  fun `axis values join in label order`() {
    assertEquals(
      "en--light--1.0x.png",
      RenderMatrixCommand.cellFileName(
        MatrixCell(locale = "en", uiMode = "light", fontScale = 1.0f)
      ),
    )
    assertEquals(
      "ar--dark--1.5x.png",
      RenderMatrixCommand.cellFileName(MatrixCell(locale = "ar", uiMode = "dark", fontScale = 1.5f)),
    )
  }

  @Test
  fun `device specs are made filesystem-safe`() {
    assertEquals(
      "id_pixel_5--dark.png",
      RenderMatrixCommand.cellFileName(MatrixCell(device = "id:pixel_5", uiMode = "dark")),
    )
    assertEquals(
      "spec_width_411dp_height_891dp.png",
      RenderMatrixCommand.cellFileName(MatrixCell(device = "spec:width=411dp,height=891dp")),
    )
  }

  @Test
  fun `the all-default cell is named default`() {
    assertEquals("default.png", RenderMatrixCommand.cellFileName(MatrixCell()))
  }
}
