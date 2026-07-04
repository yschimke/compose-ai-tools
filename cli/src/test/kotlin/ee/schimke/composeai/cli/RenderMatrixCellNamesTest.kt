package ee.schimke.composeai.cli

import ee.schimke.composeai.mcp.MatrixCell
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

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

  @Test
  fun `clearStaleCellPngs removes only top-level png files`() {
    val fs = FakeFileSystem()
    val dir = "/cells".toPath()
    fs.createDirectories(dir)
    fs.write(dir / "en--light--1.0x.png") { writeUtf8("a") }
    fs.write(dir / "ar--dark--1.5x.png") { writeUtf8("b") }
    fs.write(dir / "notes.txt") { writeUtf8("keep") } // non-png: kept
    fs.createDirectories(dir / "nested")
    fs.write(dir / "nested" / "inner.png") { writeUtf8("c") } // subdir png: kept

    RenderMatrixCommand.clearStaleCellPngs(fs, dir)

    val remaining = fs.list(dir).map { it.name }.toSet()
    assertEquals(setOf("notes.txt", "nested"), remaining)
    assertEquals(true, fs.exists(dir / "nested" / "inner.png"))
  }

  @Test
  fun `clearStaleCellPngs is a no-op on a missing directory`() {
    val fs = FakeFileSystem()
    // Must not throw when the directory does not exist yet.
    RenderMatrixCommand.clearStaleCellPngs(fs, "/does-not-exist".toPath())
  }
}
