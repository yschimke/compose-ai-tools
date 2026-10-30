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
  public void providerSizesRoundToCells() {
    // weather_widget_info: minWidth 180dp is two cells, minResizeWidth 90dp one, maxResize 360dp
    // five, at any density.
    assertEquals(2, LauncherGrid.cells(270, 1.5f));
    assertEquals(1, LauncherGrid.cells(135, 1.5f));
    assertEquals(5, LauncherGrid.cells(540, 1.5f));
    assertEquals(2, LauncherGrid.cells(180, 1f));
    assertEquals(1, LauncherGrid.cells(0, 2f));
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
