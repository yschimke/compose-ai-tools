package ee.schimke.composeai.simulator;

import android.graphics.Bitmap;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Measures per-frame rendering effort, animation churn, and Always-On Display (ambient) pixel
 * efficiency for a watch face running in the Robolectric simulator.
 *
 * <p>Host CPU timings in Robolectric do not map 1:1 to a Wear OS watch SoC, so this profiler pairs
 * relative per-frame wall-clock draw time and thread allocations with hardware-independent metrics:
 *
 * <ul>
 *   <li>Frame-to-frame dirty pixel ratio and dirty bounding-box area across a stepped timeline
 *   <li>Effective redraw cadence (FPS) compared against Wear OS's 15 fps battery guideline
 *   <li>{@link SoftwareRenderNodes} {@code drawRenderNode} and software-fallback counts per frame
 *   <li>Ambient On-Pixel Ratio (OPR) against Wear OS {@code WO-P7} (<= 15%), OLED luminance proxy,
 *       and multi-timestamp burn-in ratio
 * </ul>
 */
final class WatchFaceFrameCostProfiler {
  /** Wear OS app quality guideline WO-P7: ambient mode illuminates at most 15% of pixels. */
  static final double MAX_WO_P7_ON_PIXEL_RATIO = 0.15;

  /** Wear OS / Watch Face Studio battery guideline for continuous interactive animation FPS. */
  static final double RECOMMENDED_MAX_FPS = 15.0;

  /** Provides one rendered frame for a given timestamp and ambient mode. */
  @FunctionalInterface
  interface FrameProvider {
    FrameSample render(long timeMillis, boolean ambient) throws Exception;
  }

  /** Raw ARGB pixels (`0xAARRGGBB`) of one rendered frame. */
  static final class FrameSample {
    final int width;
    final int height;
    final int[] pixels;
    /** Pure draw duration in nanoseconds, or {@code -1} when not measured separately. */
    final long drawDurationNs;

    FrameSample(int width, int height, int[] pixels) {
      this(width, height, pixels, -1L);
    }

    FrameSample(int width, int height, int[] pixels, long drawDurationNs) {
      if (width <= 0 || height <= 0) {
        throw new IllegalArgumentException("width and height must be positive");
      }
      if (pixels == null || pixels.length != width * height) {
        throw new IllegalArgumentException("pixels length must equal width * height");
      }
      this.width = width;
      this.height = height;
      this.pixels = pixels;
      this.drawDurationNs = drawDurationNs;
    }

    static FrameSample fromBitmap(Bitmap bitmap) {
      return fromBitmap(bitmap, -1L);
    }

    static FrameSample fromBitmap(Bitmap bitmap, long drawDurationNs) {
      if (bitmap == null) return null;
      int w = bitmap.getWidth();
      int h = bitmap.getHeight();
      int[] px = new int[w * h];
      bitmap.getPixels(px, 0, w, 0, 0, w, h);
      return new FrameSample(w, h, px, drawDurationNs);
    }
  }

  /** Configuration for a profiling sweep. */
  static final class Options {
    final long startTimeMillis;
    final int interactiveFrames;
    final long interactiveStepMs;
    final int ambientFrames;
    final long ambientStepMs;
    final boolean circular;
    final boolean warmup;

    Options(
        long startTimeMillis,
        int interactiveFrames,
        long interactiveStepMs,
        int ambientFrames,
        long ambientStepMs,
        boolean circular,
        boolean warmup) {
      this.startTimeMillis = startTimeMillis;
      this.interactiveFrames = Math.max(0, interactiveFrames);
      this.interactiveStepMs = Math.max(1L, interactiveStepMs);
      this.ambientFrames = Math.max(0, ambientFrames);
      this.ambientStepMs = Math.max(1L, ambientStepMs);
      this.circular = circular;
      this.warmup = warmup;
    }

    static Options fromJson(JSONObject c, long defaultStartTimeMillis) {
      long start = c.optLong("startTimeMillis", defaultStartTimeMillis);
      int frames = Math.min(120, Math.max(0, c.optInt("frames", 10)));
      long stepMs = Math.max(16L, c.optLong("stepMs", 100L));
      int ambientFrames = Math.min(48, Math.max(0, c.optInt("ambientFrames", 4)));
      long ambientStepMs = Math.max(1000L, c.optLong("ambientStepMs", 3_600_000L));
      boolean circular = c.optBoolean("circular", true);
      boolean warmup = c.optBoolean("warmup", true);
      return new Options(start, frames, stepMs, ambientFrames, ambientStepMs, circular, warmup);
    }
  }

  static final class FrameMetrics {
    final int index;
    final long timeMillis;
    final long drawDurationNs;
    final long captureDurationNs;
    final long allocatedBytes;
    final long renderNodeDraws;
    final long softwareRenderNodeFallbacks;
    final int dirtyPixels;
    final double dirtyRatio;
    final double dirtyBoundsRatio;

    FrameMetrics(
        int index,
        long timeMillis,
        long drawDurationNs,
        long captureDurationNs,
        long allocatedBytes,
        long renderNodeDraws,
        long softwareRenderNodeFallbacks,
        int dirtyPixels,
        double dirtyRatio,
        double dirtyBoundsRatio) {
      this.index = index;
      this.timeMillis = timeMillis;
      this.drawDurationNs = drawDurationNs;
      this.captureDurationNs = captureDurationNs;
      this.allocatedBytes = allocatedBytes;
      this.renderNodeDraws = renderNodeDraws;
      this.softwareRenderNodeFallbacks = softwareRenderNodeFallbacks;
      this.dirtyPixels = dirtyPixels;
      this.dirtyRatio = dirtyRatio;
      this.dirtyBoundsRatio = dirtyBoundsRatio;
    }

    JSONObject toJson() throws JSONException {
      return new JSONObject()
          .put("index", index)
          .put("timeMillis", timeMillis)
          .put("drawMs", drawDurationNs / 1_000_000.0)
          .put("captureMs", captureDurationNs / 1_000_000.0)
          .put("allocatedBytes", allocatedBytes)
          .put("renderNodeDraws", renderNodeDraws)
          .put("softwareRenderNodeFallbacks", softwareRenderNodeFallbacks)
          .put("dirtyPixels", dirtyPixels)
          .put("dirtyRatio", dirtyRatio)
          .put("dirtyBoundsRatio", dirtyBoundsRatio);
    }
  }

  static final class InteractiveSummary {
    final int frameCount;
    final long stepMs;
    final int changedFrameCount;
    final double effectiveFps;
    final boolean exceeds15FpsGuideline;
    final double avgDrawMs;
    final double maxDrawMs;
    final double avgCaptureMs;
    final double maxCaptureMs;
    final long avgAllocatedBytes;
    final long totalRenderNodeDraws;
    final long totalSoftwareRenderNodeFallbacks;
    final double avgDirtyRatio;
    final double maxDirtyRatio;
    final List<FrameMetrics> frames;

    InteractiveSummary(
        int frameCount,
        long stepMs,
        int changedFrameCount,
        double effectiveFps,
        double avgDrawMs,
        double maxDrawMs,
        double avgCaptureMs,
        double maxCaptureMs,
        long avgAllocatedBytes,
        long totalRenderNodeDraws,
        long totalSoftwareRenderNodeFallbacks,
        double avgDirtyRatio,
        double maxDirtyRatio,
        List<FrameMetrics> frames) {
      this.frameCount = frameCount;
      this.stepMs = stepMs;
      this.changedFrameCount = changedFrameCount;
      this.effectiveFps = effectiveFps;
      this.exceeds15FpsGuideline = effectiveFps > RECOMMENDED_MAX_FPS;
      this.avgDrawMs = avgDrawMs;
      this.maxDrawMs = maxDrawMs;
      this.avgCaptureMs = avgCaptureMs;
      this.maxCaptureMs = maxCaptureMs;
      this.avgAllocatedBytes = avgAllocatedBytes;
      this.totalRenderNodeDraws = totalRenderNodeDraws;
      this.totalSoftwareRenderNodeFallbacks = totalSoftwareRenderNodeFallbacks;
      this.avgDirtyRatio = avgDirtyRatio;
      this.maxDirtyRatio = maxDirtyRatio;
      this.frames = frames;
    }

    JSONObject toJson() throws JSONException {
      JSONArray arr = new JSONArray();
      for (FrameMetrics m : frames) {
        arr.put(m.toJson());
      }
      return new JSONObject()
          .put("frameCount", frameCount)
          .put("stepMs", stepMs)
          .put("changedFrameCount", changedFrameCount)
          .put("effectiveFps", effectiveFps)
          .put("exceeds15FpsGuideline", exceeds15FpsGuideline)
          .put("avgDrawMs", avgDrawMs)
          .put("maxDrawMs", maxDrawMs)
          .put("avgCaptureMs", avgCaptureMs)
          .put("maxCaptureMs", maxCaptureMs)
          .put("avgAllocatedBytes", avgAllocatedBytes)
          .put("totalRenderNodeDraws", totalRenderNodeDraws)
          .put("totalSoftwareRenderNodeFallbacks", totalSoftwareRenderNodeFallbacks)
          .put("avgDirtyRatio", avgDirtyRatio)
          .put("maxDirtyRatio", maxDirtyRatio)
          .put("frames", arr);
    }
  }

  static final class AmbientSummary {
    final int frameCount;
    final int totalMaskPixels;
    final double maxOnPixelRatio;
    final double averageOnPixelRatio;
    final double averageLuminance;
    final int alwaysLitPixels;
    final double alwaysLitRatio;
    final boolean exceedsWoP7Limit;

    AmbientSummary(
        int frameCount,
        int totalMaskPixels,
        double maxOnPixelRatio,
        double averageOnPixelRatio,
        double averageLuminance,
        int alwaysLitPixels,
        double alwaysLitRatio) {
      this.frameCount = frameCount;
      this.totalMaskPixels = totalMaskPixels;
      this.maxOnPixelRatio = maxOnPixelRatio;
      this.averageOnPixelRatio = averageOnPixelRatio;
      this.averageLuminance = averageLuminance;
      this.alwaysLitPixels = alwaysLitPixels;
      this.alwaysLitRatio = alwaysLitRatio;
      this.exceedsWoP7Limit = maxOnPixelRatio > MAX_WO_P7_ON_PIXEL_RATIO;
    }

    JSONObject toJson() throws JSONException {
      return new JSONObject()
          .put("frameCount", frameCount)
          .put("totalMaskPixels", totalMaskPixels)
          .put("maxOnPixelRatio", maxOnPixelRatio)
          .put("averageOnPixelRatio", averageOnPixelRatio)
          .put("averageLuminance", averageLuminance)
          .put("alwaysLitPixels", alwaysLitPixels)
          .put("alwaysLitRatio", alwaysLitRatio)
          .put("exceedsWoP7Limit", exceedsWoP7Limit);
    }
  }

  static final class Report {
    final InteractiveSummary interactive;
    final AmbientSummary ambient;
    final boolean circular;

    Report(InteractiveSummary interactive, AmbientSummary ambient, boolean circular) {
      this.interactive = interactive;
      this.ambient = ambient;
      this.circular = circular;
    }

    JSONObject toJson() throws JSONException {
      JSONObject out = new JSONObject().put("circular", circular);
      if (interactive != null) out.put("interactive", interactive.toJson());
      if (ambient != null) out.put("ambient", ambient.toJson());
      return out;
    }
  }

  private WatchFaceFrameCostProfiler() {}

  static Report profile(FrameProvider provider, Options options) throws Exception {
    if (options.warmup && options.interactiveFrames > 0) {
      provider.render(options.startTimeMillis, false);
    }

    InteractiveSummary interactive =
        options.interactiveFrames > 0 ? profileInteractive(provider, options) : null;
    AmbientSummary ambient =
        options.ambientFrames > 0 ? profileAmbient(provider, options) : null;
    return new Report(interactive, ambient, options.circular);
  }

  private static InteractiveSummary profileInteractive(FrameProvider provider, Options options)
      throws Exception {
    List<FrameMetrics> metrics = new ArrayList<>(options.interactiveFrames);
    FrameSample previous = null;
    long totalDrawNs = 0L;
    long maxDrawNs = 0L;
    long totalCaptureNs = 0L;
    long maxCaptureNs = 0L;
    long totalAlloc = 0L;
    int allocSamples = 0;
    long totalRnDraws = 0L;
    long totalSwFallbacks = 0L;
    int changedFrames = 0;
    double sumDirtyRatio = 0.0;
    double maxDirtyRatio = 0.0;
    int diffSamples = 0;

    for (int i = 0; i < options.interactiveFrames; i++) {
      long ts = options.startTimeMillis + i * options.interactiveStepMs;
      long rnBefore = SoftwareRenderNodes.drawCount();
      long swBefore = SoftwareRenderNodes.softwareFallbackCount();
      long allocBefore = currentThreadAllocatedBytes();
      long t0 = System.nanoTime();
      FrameSample sample = provider.render(ts, false);
      long captureNs = System.nanoTime() - t0;
      long allocAfter = currentThreadAllocatedBytes();
      long rnDelta = SoftwareRenderNodes.drawCount() - rnBefore;
      long swDelta = SoftwareRenderNodes.softwareFallbackCount() - swBefore;

      if (sample == null) {
        throw new IllegalStateException("Watch face returned null frame at index " + i);
      }
      long drawNs = sample.drawDurationNs >= 0L ? sample.drawDurationNs : captureNs;

      long allocDelta =
          (allocBefore >= 0 && allocAfter >= allocBefore) ? (allocAfter - allocBefore) : -1L;
      if (allocDelta >= 0) {
        totalAlloc += allocDelta;
        allocSamples++;
      }
      totalDrawNs += drawNs;
      if (drawNs > maxDrawNs) maxDrawNs = drawNs;
      totalCaptureNs += captureNs;
      if (captureNs > maxCaptureNs) maxCaptureNs = captureNs;
      totalRnDraws += rnDelta;
      totalSwFallbacks += swDelta;

      int dirtyPixels = 0;
      double dirtyRatio = 0.0;
      double dirtyBoundsRatio = 0.0;
      if (previous != null
          && previous.width == sample.width
          && previous.height == sample.height) {
        Diff diff =
            computeDiff(
                previous.pixels, sample.pixels, sample.width, sample.height, options.circular);
        dirtyPixels = diff.dirtyPixels;
        dirtyRatio = diff.dirtyRatio;
        dirtyBoundsRatio = diff.dirtyBoundsRatio;
        diffSamples++;
        sumDirtyRatio += dirtyRatio;
        if (dirtyRatio > maxDirtyRatio) maxDirtyRatio = dirtyRatio;
        if (dirtyPixels > 0) changedFrames++;
      }

      metrics.add(
          new FrameMetrics(
              i,
              ts,
              drawNs,
              captureNs,
              allocDelta,
              rnDelta,
              swDelta,
              dirtyPixels,
              dirtyRatio,
              dirtyBoundsRatio));
      previous = sample;
    }

    int count = metrics.size();
    double avgDrawMs = count == 0 ? 0.0 : (totalDrawNs / (double) count) / 1_000_000.0;
    double maxDrawMs = maxDrawNs / 1_000_000.0;
    double avgCaptureMs = count == 0 ? 0.0 : (totalCaptureNs / (double) count) / 1_000_000.0;
    double maxCaptureMs = maxCaptureNs / 1_000_000.0;
    long avgAlloc = allocSamples == 0 ? -1L : totalAlloc / allocSamples;
    double avgDirty = diffSamples == 0 ? 0.0 : Math.min(maxDirtyRatio, sumDirtyRatio / diffSamples);
    double windowSeconds =
        diffSamples > 0 ? (diffSamples * options.interactiveStepMs) / 1000.0 : 0.0;
    double effectiveFps = windowSeconds > 0.0 ? changedFrames / windowSeconds : 0.0;

    return new InteractiveSummary(
        count,
        options.interactiveStepMs,
        changedFrames,
        effectiveFps,
        avgDrawMs,
        maxDrawMs,
        avgCaptureMs,
        maxCaptureMs,
        avgAlloc,
        totalRnDraws,
        totalSwFallbacks,
        avgDirty,
        maxDirtyRatio,
        metrics);
  }

  private static AmbientSummary profileAmbient(FrameProvider provider, Options options)
      throws Exception {
    boolean[] alwaysLit = null;
    int width = 0;
    int height = 0;
    int maskTotal = 0;
    double maxOpr = 0.0;
    double sumOpr = 0.0;
    double sumLum = 0.0;

    for (int i = 0; i < options.ambientFrames; i++) {
      long ts = options.startTimeMillis + i * options.ambientStepMs;
      FrameSample sample = provider.render(ts, true);
      if (sample == null) {
        throw new IllegalStateException("Watch face returned null ambient frame at index " + i);
      }
      if (alwaysLit == null) {
        width = sample.width;
        height = sample.height;
        alwaysLit = new boolean[width * height];
        java.util.Arrays.fill(alwaysLit, true);
      }
      double cx = sample.width / 2.0;
      double cy = sample.height / 2.0;
      double radius = Math.min(sample.width, sample.height) / 2.0;
      double radiusSq = radius * radius;

      int lit = 0;
      int frameMask = 0;
      double lumSum = 0.0;
      for (int y = 0; y < sample.height; y++) {
        double dy = y + 0.5 - cy;
        double dySq = dy * dy;
        int row = y * sample.width;
        for (int x = 0; x < sample.width; x++) {
          int idx = row + x;
          if (options.circular) {
            double dx = x + 0.5 - cx;
            if (dx * dx + dySq > radiusSq) {
              if (idx < alwaysLit.length) alwaysLit[idx] = false;
              continue;
            }
          }
          frameMask++;
          int argb = sample.pixels[idx];
          int a = (argb >>> 24) & 0xFF;
          boolean isLit = a != 0 && (argb & 0x00FFFFFF) != 0;
          if (isLit) {
            lit++;
            int r = (argb >>> 16) & 0xFF;
            int g = (argb >>> 8) & 0xFF;
            int b = argb & 0xFF;
            lumSum += ((r + g + b) / (3.0 * 255.0)) * (a / 255.0);
          } else if (idx < alwaysLit.length) {
            alwaysLit[idx] = false;
          }
        }
      }
      maskTotal = frameMask;
      double opr = frameMask == 0 ? 0.0 : lit / (double) frameMask;
      double lum = frameMask == 0 ? 0.0 : lumSum / (double) frameMask;
      if (opr > maxOpr) maxOpr = opr;
      sumOpr += opr;
      sumLum += lum;
    }

    int alwaysLitCount = 0;
    if (alwaysLit != null) {
      for (boolean b : alwaysLit) {
        if (b) alwaysLitCount++;
      }
    }
    double alwaysLitRatio = maskTotal == 0 ? 0.0 : alwaysLitCount / (double) maskTotal;
    return new AmbientSummary(
        options.ambientFrames,
        maskTotal,
        maxOpr,
        sumOpr / options.ambientFrames,
        sumLum / options.ambientFrames,
        alwaysLitCount,
        alwaysLitRatio);
  }

  private static final class Diff {
    final int dirtyPixels;
    final double dirtyRatio;
    final double dirtyBoundsRatio;

    Diff(int dirtyPixels, double dirtyRatio, double dirtyBoundsRatio) {
      this.dirtyPixels = dirtyPixels;
      this.dirtyRatio = dirtyRatio;
      this.dirtyBoundsRatio = dirtyBoundsRatio;
    }
  }

  private static Diff computeDiff(
      int[] prev, int[] curr, int width, int height, boolean circular) {
    int dirty = 0;
    int maskTotal = 0;
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int maxX = -1;
    int maxY = -1;
    double cx = width / 2.0;
    double cy = height / 2.0;
    double radius = Math.min(width, height) / 2.0;
    double radiusSq = radius * radius;

    for (int y = 0; y < height; y++) {
      double dy = y + 0.5 - cy;
      double dySq = dy * dy;
      int row = y * width;
      for (int x = 0; x < width; x++) {
        if (circular) {
          double dx = x + 0.5 - cx;
          if (dx * dx + dySq > radiusSq) continue;
        }
        maskTotal++;
        int idx = row + x;
        if (prev[idx] != curr[idx]) {
          dirty++;
          if (x < minX) minX = x;
          if (y < minY) minY = y;
          if (x > maxX) maxX = x;
          if (y > maxY) maxY = y;
        }
      }
    }
    if (dirty == 0 || maskTotal == 0) {
      return new Diff(0, 0.0, 0.0);
    }
    double dirtyRatio = dirty / (double) maskTotal;
    double boundsArea = (maxX - minX + 1.0) * (maxY - minY + 1.0);
    double boundsRatio = Math.min(1.0, boundsArea / (double) maskTotal);
    return new Diff(dirty, dirtyRatio, boundsRatio);
  }

  private static final Method ALLOC_METHOD = resolveAllocatedBytesMethod();

  private static Method resolveAllocatedBytesMethod() {
    try {
      // Look up on the exported com.sun.management.ThreadMXBean interface rather than the
      // non-exported HotSpotThreadImpl class so JDK 17-25 succeed without --add-opens.
      Class<?> iface = Class.forName("com.sun.management.ThreadMXBean");
      return iface.getMethod("getCurrentThreadAllocatedBytes");
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static long currentThreadAllocatedBytes() {
    Method m = ALLOC_METHOD;
    if (m == null) return -1L;
    try {
      Object bean = ManagementFactory.getThreadMXBean();
      if (!m.getDeclaringClass().isInstance(bean)) return -1L;
      Object result = m.invoke(bean);
      return result instanceof Long ? ((Long) result).longValue() : -1L;
    } catch (Throwable ignored) {
      return -1L;
    }
  }
}
