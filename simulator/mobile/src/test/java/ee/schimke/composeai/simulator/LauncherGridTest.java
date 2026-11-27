package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LauncherGridTest {

  @Test
  public void spansMatchThePreviewRuntimesCells() {
    // `runtimes/appwidget`: 72dp cells, 8dp apart; a 4×2 widget is 312×152dp.
    assertEquals(312, LauncherGrid.sizeDp(4));
    assertEquals(152, LauncherGrid.sizeDp(2));
    assertEquals(72, LauncherGrid.sizeDp(1));
  }

  @Test
  public void aMinimumSizeTakesEnoughCellsToHoldIt() {
    // Cadence's podcast widget: minWidth 250dp, minHeight 80dp. Three cells are 232dp and one is
    // 72dp, so it takes four by two, never less than its minimum.
    assertEquals(4, LauncherGrid.cellsAtLeast(375, 1.5f));
    assertEquals(2, LauncherGrid.cellsAtLeast(120, 1.5f));
    // An exact span stays exact: 152dp is two cells.
    assertEquals(2, LauncherGrid.cellsAtLeast(228, 1.5f));
    assertEquals(1, LauncherGrid.cellsAtLeast(0, 2f));
  }

  @Test
  public void aMaximumSizeTakesTheCellsThatFitInIt() {
    // weather_widget_info: maxResizeWidth 360dp holds four cells (312dp), not five (392dp).
    assertEquals(4, LauncherGrid.cellsAtMost(540, 1.5f));
    assertEquals(2, LauncherGrid.cellsAtMost(228, 1.5f));
    assertEquals(1, LauncherGrid.cellsAtMost(30, 1.5f));
  }

  @Test
  public void aPhoneHasFourColumnsAndALargerPhoneFive() {
    LauncherGrid phone = new LauncherGrid(360, 640, 1.5f);
    assertEquals(4, phone.columns);
    assertEquals(6, phone.rows);
    LauncherGrid large = new LauncherGrid(411, 891, 2.625f);
    assertEquals(5, large.columns);
    assertEquals(9, large.rows);
  }

  @Test
  public void landscapeAndTabletGrids() {
    assertEquals("7×3", new LauncherGrid(640, 360, 1.5f).toString());
    assertEquals("15×8", new LauncherGrid(1280, 800, 1.5f).toString());
  }

  @Test
  public void theGridIsCentred() {
    LauncherGrid phone = new LauncherGrid(360, 640, 1.5f);
    // 4 cells span 312dp: 24dp each side. 6 rows span 472dp of the 536dp between the status area
    // and the drawer handle: 32dp above them.
    assertEquals(36, phone.left());
    assertEquals(96, phone.top());
    assertArrayEquals(new int[] {36, 96, 504, 324}, phone.rect(0, 0, 4, 2));
    assertArrayEquals(new int[] {36, 696, 144, 804}, phone.rect(0, 5, 1, 1));
  }
}
