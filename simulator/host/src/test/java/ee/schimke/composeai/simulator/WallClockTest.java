package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class WallClockTest {

  @Test
  public void aCycleThatRanLongerThanTheClockMovedIsMadeUp() {
    long[] wall = {1_000};
    List<Long> advanced = new ArrayList<>();
    SimulatorControl.WallClock clock =
        new SimulatorControl.WallClock(() -> wall[0], advanced::add);

    clock.keepUp(5_000);
    // 300 ns of real time, the simulated clock moved 100: 200 behind.
    wall[0] += 300;
    clock.keepUp(5_100);
    assertEquals(List.of(200L), advanced);

    // Next cycle measures from where the catch-up left it (5_300), not where the loop said.
    wall[0] += 50;
    clock.keepUp(5_350);
    assertEquals(List.of(200L), advanced);
  }

  @Test
  public void aClockAheadOfRealTimeIsLeftAlone() {
    long[] wall = {0};
    List<Long> advanced = new ArrayList<>();
    SimulatorControl.WallClock clock =
        new SimulatorControl.WallClock(() -> wall[0], advanced::add);

    clock.keepUp(0);
    // A main-thread SystemClock.sleep(…) jumped it 1 000 ahead in 10 of real time.
    wall[0] += 10;
    clock.keepUp(1_000);
    assertEquals(List.of(), advanced);
  }
}
