package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Unit tests for {@link WatchFaceFrameCostProfiler}. */
public class WatchFaceFrameCostProfilerTest {

  @Test
  public void measuresInteractiveAnimationChurnAndEffectiveFps() throws Exception {
    int w = 20;
    int h = 20;
    // Every 50ms step moves a 2x2 dot inside the circle -> 20 fps (> 15 fps guideline).
    WatchFaceFrameCostProfiler.Options opts =
        new WatchFaceFrameCostProfiler.Options(
            1_000_000L, /* interactiveFrames= */ 6, /* interactiveStepMs= */ 50L,
            /* ambientFrames= */ 0, /* ambientStepMs= */ 60_000L,
            /* circular= */ true, /* warmup= */ true);

    WatchFaceFrameCostProfiler.Report report =
        WatchFaceFrameCostProfiler.profile(
            (ts, ambient) -> {
              int[] px = new int[w * h];
              java.util.Arrays.fill(px, 0xFF000000);
              int step = (int) ((ts - 1_000_000L) / 50L);
              int x = 6 + (step % 8);
              int y = 10;
              px[y * w + x] = 0xFFFFFFFF;
              px[y * w + x + 1] = 0xFFFFFFFF;
              return new WatchFaceFrameCostProfiler.FrameSample(w, h, px);
            },
            opts);

    assertNotNull(report.interactive);
    assertEquals(6, report.interactive.frameCount);
    assertEquals(5, report.interactive.changedFrameCount);
    assertEquals(20.0, report.interactive.effectiveFps, 0.01);
    assertTrue(report.interactive.exceeds15FpsGuideline);
    assertTrue(report.interactive.avgDirtyRatio > 0.0);
    assertTrue(report.interactive.maxDirtyRatio >= report.interactive.avgDirtyRatio);
    assertTrue(report.interactive.avgDrawMs >= 0.0);

    JSONObject json = report.toJson();
    assertTrue(json.getJSONObject("interactive").getBoolean("exceeds15FpsGuideline"));
    assertEquals(6, json.getJSONObject("interactive").getJSONArray("frames").length());
  }

  @Test
  public void staticFaceHasZeroEffectiveFpsAndZeroDirtyPixels() throws Exception {
    int w = 10;
    int h = 10;
    int[] fixed = new int[w * h];
    java.util.Arrays.fill(fixed, 0xFF000000);
    fixed[5 * w + 5] = 0xFFFFFFFF;

    WatchFaceFrameCostProfiler.Options opts =
        new WatchFaceFrameCostProfiler.Options(
            1_000_000L, /* interactiveFrames= */ 5, /* interactiveStepMs= */ 100L,
            /* ambientFrames= */ 0, /* ambientStepMs= */ 60_000L,
            /* circular= */ true, /* warmup= */ false);

    WatchFaceFrameCostProfiler.Report report =
        WatchFaceFrameCostProfiler.profile(
            (ts, ambient) -> new WatchFaceFrameCostProfiler.FrameSample(w, h, fixed), opts);

    assertEquals(0, report.interactive.changedFrameCount);
    assertEquals(0.0, report.interactive.effectiveFps, 0.001);
    assertFalse(report.interactive.exceeds15FpsGuideline);
    assertEquals(0.0, report.interactive.maxDirtyRatio, 0.001);
  }

  @Test
  public void measuresAmbientOprWoP7AndBurnIn() throws Exception {
    int w = 10;
    int h = 10;
    WatchFaceFrameCostProfiler.Options opts =
        new WatchFaceFrameCostProfiler.Options(
            0L, /* interactiveFrames= */ 0, /* interactiveStepMs= */ 100L,
            /* ambientFrames= */ 3, /* ambientStepMs= */ 3_600_000L,
            /* circular= */ false, /* warmup= */ false);

    // Frame 0..2: (5,5) is always lit (burn-in); 20 pixels lit total in each frame -> 20% OPR > 15%.
    WatchFaceFrameCostProfiler.Report report =
        WatchFaceFrameCostProfiler.profile(
            (ts, ambient) -> {
              int[] px = new int[w * h];
              java.util.Arrays.fill(px, 0xFF000000);
              px[5 * w + 5] = 0xFFFFFFFF;
              int frameIdx = (int) (ts / 3_600_000L);
              for (int i = 0; i < 19; i++) {
                px[(frameIdx * 2 + (i / 10)) * w + (i % 10)] = 0xFFFFFFFF;
              }
              return new WatchFaceFrameCostProfiler.FrameSample(w, h, px);
            },
            opts);

    assertNotNull(report.ambient);
    assertEquals(3, report.ambient.frameCount);
    assertEquals(100, report.ambient.totalMaskPixels);
    assertEquals(0.20, report.ambient.maxOnPixelRatio, 0.001);
    assertTrue(report.ambient.exceedsWoP7Limit);
    assertEquals(1, report.ambient.alwaysLitPixels);
    assertEquals(0.01, report.ambient.alwaysLitRatio, 0.001);
    assertTrue(report.ambient.averageLuminance > 0.15);

    JSONObject json = report.toJson();
    assertTrue(json.getJSONObject("ambient").getBoolean("exceedsWoP7Limit"));
  }
}
