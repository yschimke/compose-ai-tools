package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;

import java.awt.Dimension;
import org.junit.Test;

public class WindowZoomTest {

  @Test
  public void aDisplayThatFitsStartsAtActualSize() {
    assertEquals(1, WindowZoom.fit(1080, 2400, 3000, 3000, 1), 0);
  }

  @Test
  public void aDisplayTallerThanTheScreenShrinksToItsHeight() {
    // A 1080x2400 phone on a 1600-point-tall screen.
    assertEquals(0.5, WindowZoom.fit(1080, 2400, 2000, 1200, 1), 1e-9);
  }

  @Test
  public void aDraggedWindowFitsTheNarrowerSide() {
    assertEquals(0.25, WindowZoom.fit(1000, 2000, 250, 2000, WindowZoom.MAX), 1e-9);
    assertEquals(2, WindowZoom.fit(400, 400, 800, 1000, WindowZoom.MAX), 1e-9);
  }

  @Test
  public void fittingIsClamped() {
    assertEquals(WindowZoom.MAX, WindowZoom.fit(10, 10, 5000, 5000, 100), 0);
    assertEquals(WindowZoom.MIN, WindowZoom.fit(10000, 10000, 1, 1, 1), 0);
    assertEquals(1, WindowZoom.fit(0, 0, 100, 100, 1), 0);
  }

  @Test
  public void stepsMultiplyAndStopAtActualSizeOnTheWay() {
    assertEquals(1.25, WindowZoom.stepped(1, true), 1e-9);
    assertEquals(0.8, WindowZoom.stepped(1, false), 1e-9);
    // 0.9 * 1.25 passes 100%: it lands there first.
    assertEquals(1, WindowZoom.stepped(0.9, true), 0);
    assertEquals(1, WindowZoom.stepped(1.1, false), 0);
  }

  @Test
  public void stepsAreClamped() {
    assertEquals(WindowZoom.MAX, WindowZoom.stepped(WindowZoom.MAX, true), 0);
    assertEquals(WindowZoom.MIN, WindowZoom.stepped(WindowZoom.MIN, false), 0);
  }

  @Test
  public void theWindowSizeRoundsTheScaledDisplay() {
    assertEquals(new Dimension(540, 1200), WindowZoom.scaledSize(1080, 2400, 0.5));
    assertEquals(new Dimension(1, 1), WindowZoom.scaledSize(2, 2, 0.1));
  }

  @Test
  public void aClickMapsBackToTheDevicePixelUnderIt() {
    assertEquals(200, WindowZoom.toDevice(100, 0.5, 1080));
    assertEquals(40, WindowZoom.toDevice(81, 2, 1080));
    assertEquals(100, WindowZoom.toDevice(100, 1, 1080));
  }

  @Test
  public void aDragPastTheEdgeStaysOnTheDisplay() {
    assertEquals(0, WindowZoom.toDevice(-5, 0.5, 1080));
    assertEquals(1079, WindowZoom.toDevice(4000, 0.5, 1080));
  }
}
