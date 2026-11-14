package ee.schimke.composeai.simulator;

import android.app.Application;
import android.graphics.Bitmap;
import android.provider.Settings;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.json.JSONObject;

/**
 * Extracts a 3-color seed palette ({@code primary}, {@code secondary}, {@code tertiary}) from an
 * active watch face and optionally projects it onto Android's {@code android.R.color.system_*}
 * dynamic color resources (with {@code Settings.Global "dynamic_color_theme_enabled" = 1}) so Wear
 * OS Tiles ({@code protolayout-material3}), Glance Wear widgets, and Wear Compose Material 3 apps
 * re-theme dynamically from the watch face.
 */
final class WatchFaceDynamicColors {

  static final String PROPERTY = "composeai.simulator.themeFromWatchface";
  private static final String DYNAMIC_COLOR_SETTING = "dynamic_color_theme_enabled";
  private static final int ERROR_SEED_ARGB = 0xFFB3261E;
  private static final int[] SHADES = {
    0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000
  };

  static final class Palette {
    final int primaryArgb;
    final int secondaryArgb;
    final int tertiaryArgb;
    final String source;

    Palette(int primaryArgb, int secondaryArgb, int tertiaryArgb, String source) {
      this.primaryArgb = 0xFF000000 | primaryArgb;
      this.secondaryArgb = 0xFF000000 | secondaryArgb;
      this.tertiaryArgb = 0xFF000000 | tertiaryArgb;
      this.source = source;
    }

    static Palette fromColorList(List<Integer> colors, String source) {
      if (colors == null || colors.isEmpty()) return null;
      int p = colors.get(0).intValue();
      int s = colors.size() > 1 ? colors.get(1).intValue() : shiftHue(p, 25.0);
      int t = colors.size() > 2 ? colors.get(2).intValue() : shiftHue(p, 60.0);
      return new Palette(p, s, t, source);
    }

    JSONObject toJson() {
      return new Protocol.Json()
          .put("primary", hex(primaryArgb))
          .put("secondary", hex(secondaryArgb))
          .put("tertiary", hex(tertiaryArgb))
          .put("source", source)
          .object();
    }

    static String hex(int argb) {
      return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Palette)) return false;
      Palette that = (Palette) o;
      return primaryArgb == that.primaryArgb
          && secondaryArgb == that.secondaryArgb
          && tertiaryArgb == that.tertiaryArgb
          && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
      return Objects.hash(primaryArgb, secondaryArgb, tertiaryArgb, source);
    }
  }

  private static volatile Map<Integer, Integer> activeResourceColors = Collections.emptyMap();
  private static Map<String, Integer> systemColorIds;

  private WatchFaceDynamicColors() {}

  static Integer lookup(int resourceId) {
    return activeResourceColors.get(Integer.valueOf(resourceId));
  }

  static boolean isActive() {
    return !activeResourceColors.isEmpty();
  }

  static void apply(Application app, Palette palette) {
    if (palette == null) {
      clear(app);
      return;
    }
    activeResourceColors = buildSystemColorMap(palette);
    if (app != null) {
      Settings.Global.putInt(app.getContentResolver(), DYNAMIC_COLOR_SETTING, 1);
    }
  }

  static void clear(Application app) {
    activeResourceColors = Collections.emptyMap();
    if (app != null) {
      Settings.Global.putInt(app.getContentResolver(), DYNAMIC_COLOR_SETTING, 0);
    }
  }

  /** Extracts up to 3 vibrant/dominant non-black colors from a rendered watch face bitmap. */
  static Palette extractFromBitmap(Bitmap bitmap) {
    if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return null;
    int w = bitmap.getWidth();
    int h = bitmap.getHeight();
    int step = Math.max(1, Math.min(w, h) / 64);

    // 12 hue buckets + 1 achromatic light bucket (index 12)
    long[] sumR = new long[13];
    long[] sumG = new long[13];
    long[] sumB = new long[13];
    double[] weight = new double[13];
    int[] count = new int[13];

    for (int y = 0; y < h; y += step) {
      for (int x = 0; x < w; x += step) {
        int px = bitmap.getPixel(x, y);
        int a = (px >>> 24) & 0xFF;
        if (a < 128) continue;
        int r = (px >>> 16) & 0xFF;
        int g = (px >>> 8) & 0xFF;
        int b = px & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max < 28) continue; // ignore near-black watch face background
        float sat = max == 0 ? 0f : (max - min) / (float) max;
        int bucket;
        double wgt;
        if (sat < 0.15f) {
          bucket = 12;
          wgt = 0.25; // de-emphasize white/grey digits vs colored accents
        } else {
          double[] lch = argbToOklch(px);
          bucket = ((int) Math.floor(lch[2] / 30.0)) % 12;
          if (bucket < 0) bucket += 12;
          wgt = 1.0 + sat * 2.5;
        }
        sumR[bucket] += r;
        sumG[bucket] += g;
        sumB[bucket] += b;
        weight[bucket] += wgt;
        count[bucket]++;
      }
    }

    List<Integer> ranked = new ArrayList<>();
    for (int i = 0; i < 13; i++) {
      if (count[i] > 0) ranked.add(Integer.valueOf(i));
    }
    if (ranked.isEmpty()) return null;
    ranked.sort((a, b) -> Double.compare(weight[b.intValue()], weight[a.intValue()]));

    List<Integer> chosen = new ArrayList<>(3);
    for (Integer idxObj : ranked) {
      int idx = idxObj.intValue();
      int avgR = (int) Math.round(sumR[idx] / (double) count[idx]);
      int avgG = (int) Math.round(sumG[idx] / (double) count[idx]);
      int avgB = (int) Math.round(sumB[idx] / (double) count[idx]);
      chosen.add(Integer.valueOf(0xFF000000 | (avgR << 16) | (avgG << 8) | avgB));
      if (chosen.size() == 3) break;
    }
    return Palette.fromColorList(chosen, "bitmap");
  }

  // ---------------------------------------------------------------------------------------------
  // System color table generation (Oklab/Oklch perceptual tonal palettes)

  static Map<Integer, Integer> buildSystemColorMap(Palette palette) {
    Map<String, Integer> ids = systemColorResourceIds();
    if (ids.isEmpty()) return Collections.emptyMap();
    Map<String, Integer> byName = buildSystemColorNameMap(palette);
    Map<Integer, Integer> out = new HashMap<>(byName.size());
    for (Map.Entry<String, Integer> e : byName.entrySet()) {
      Integer id = ids.get(e.getKey());
      if (id != null) {
        out.put(id, e.getValue());
      }
    }
    return Collections.unmodifiableMap(out);
  }

  static Map<String, Integer> buildSystemColorNameMap(Palette palette) {
    double[] pLch = argbToOklch(palette.primaryArgb);
    double[] sLch = argbToOklch(palette.secondaryArgb);
    double[] tLch = argbToOklch(palette.tertiaryArgb);
    double[] eLch = argbToOklch(ERROR_SEED_ARGB);

    double a1Chroma = Math.max(0.08, pLch[1]);
    double a2Chroma = Math.max(0.04, Math.min(0.09, sLch[1] * 0.6));
    double a3Chroma = Math.max(0.06, tLch[1]);
    double n1Chroma = Math.min(0.018, pLch[1] * 0.12);
    double n2Chroma = Math.min(0.032, pLch[1] * 0.22);
    double errChroma = Math.max(0.14, eLch[1]);

    Map<String, Integer> out = new HashMap<>(160);
    putNamedShades(out, "system_accent1_", a1Chroma, pLch[2]);
    putNamedShades(out, "system_accent2_", a2Chroma, sLch[2]);
    putNamedShades(out, "system_accent3_", a3Chroma, tLch[2]);
    putNamedShades(out, "system_neutral1_", n1Chroma, pLch[2]);
    putNamedShades(out, "system_neutral2_", n2Chroma, pLch[2]);
    putNamedShades(out, "system_error_", errChroma, eLch[2]);

    // Android 14+ semantic role tokens (dark & light, read by protolayout-material3 & Wear M3)
    // Dark scheme roles
    putNamedTone(out, "system_primary_dark", 80, a1Chroma, pLch[2]);
    putNamedTone(out, "system_on_primary_dark", 20, a1Chroma, pLch[2]);
    putNamedTone(out, "system_primary_container_dark", 30, a1Chroma, pLch[2]);
    putNamedTone(out, "system_on_primary_container_dark", 90, a1Chroma, pLch[2]);
    putNamedTone(out, "system_secondary_dark", 80, a2Chroma, sLch[2]);
    putNamedTone(out, "system_on_secondary_dark", 20, a2Chroma, sLch[2]);
    putNamedTone(out, "system_secondary_container_dark", 30, a2Chroma, sLch[2]);
    putNamedTone(out, "system_on_secondary_container_dark", 90, a2Chroma, sLch[2]);
    putNamedTone(out, "system_tertiary_dark", 80, a3Chroma, tLch[2]);
    putNamedTone(out, "system_on_tertiary_dark", 20, a3Chroma, tLch[2]);
    putNamedTone(out, "system_tertiary_container_dark", 30, a3Chroma, tLch[2]);
    putNamedTone(out, "system_on_tertiary_container_dark", 90, a3Chroma, tLch[2]);
    putNamedTone(out, "system_error_dark", 80, errChroma, eLch[2]);
    putNamedTone(out, "system_on_error_dark", 20, errChroma, eLch[2]);
    putNamedTone(out, "system_error_container_dark", 30, errChroma, eLch[2]);
    putNamedTone(out, "system_on_error_container_dark", 90, errChroma, eLch[2]);
    putNamedTone(out, "system_background_dark", 6, n1Chroma, pLch[2]);
    putNamedTone(out, "system_on_background_dark", 90, n1Chroma, pLch[2]);
    putNamedTone(out, "system_surface_dark", 6, n1Chroma, pLch[2]);
    putNamedTone(out, "system_on_surface_dark", 90, n1Chroma, pLch[2]);
    putNamedTone(out, "system_surface_variant_dark", 30, n2Chroma, pLch[2]);
    putNamedTone(out, "system_on_surface_variant_dark", 80, n2Chroma, pLch[2]);
    putNamedTone(out, "system_surface_container_lowest_dark", 4, n1Chroma, pLch[2]);
    putNamedTone(out, "system_surface_container_low_dark", 10, n1Chroma, pLch[2]);
    putNamedTone(out, "system_surface_container_dark", 12, n1Chroma, pLch[2]);
    putNamedTone(out, "system_surface_container_high_dark", 17, n1Chroma, pLch[2]);
    putNamedTone(out, "system_surface_container_highest_dark", 22, n1Chroma, pLch[2]);
    putNamedTone(out, "system_surface_dim_dark", 6, n1Chroma, pLch[2]);
    putNamedTone(out, "system_surface_bright_dark", 24, n1Chroma, pLch[2]);
    putNamedTone(out, "system_outline_dark", 60, n2Chroma, pLch[2]);
    putNamedTone(out, "system_outline_variant_dark", 30, n2Chroma, pLch[2]);

    // Light scheme roles
    putNamedTone(out, "system_primary_light", 40, a1Chroma, pLch[2]);
    putNamedTone(out, "system_on_primary_light", 100, a1Chroma, pLch[2]);
    putNamedTone(out, "system_primary_container_light", 90, a1Chroma, pLch[2]);
    putNamedTone(out, "system_on_primary_container_light", 10, a1Chroma, pLch[2]);
    putNamedTone(out, "system_secondary_light", 40, a2Chroma, sLch[2]);
    putNamedTone(out, "system_on_secondary_light", 100, a2Chroma, sLch[2]);
    putNamedTone(out, "system_secondary_container_light", 90, a2Chroma, sLch[2]);
    putNamedTone(out, "system_on_secondary_container_light", 10, a2Chroma, sLch[2]);
    putNamedTone(out, "system_tertiary_light", 40, a3Chroma, tLch[2]);
    putNamedTone(out, "system_on_tertiary_light", 100, a3Chroma, tLch[2]);
    putNamedTone(out, "system_tertiary_container_light", 90, a3Chroma, tLch[2]);
    putNamedTone(out, "system_on_tertiary_container_light", 10, a3Chroma, tLch[2]);
    putNamedTone(out, "system_background_light", 98, n1Chroma, pLch[2]);
    putNamedTone(out, "system_on_background_light", 10, n1Chroma, pLch[2]);
    putNamedTone(out, "system_surface_light", 98, n1Chroma, pLch[2]);
    putNamedTone(out, "system_on_surface_light", 10, n1Chroma, pLch[2]);
    putNamedTone(out, "system_surface_variant_light", 90, n2Chroma, pLch[2]);
    putNamedTone(out, "system_on_surface_variant_light", 30, n2Chroma, pLch[2]);
    putNamedTone(out, "system_outline_light", 50, n2Chroma, pLch[2]);
    putNamedTone(out, "system_outline_variant_light", 80, n2Chroma, pLch[2]);

    // Fixed roles
    putNamedTone(out, "system_primary_fixed", 90, a1Chroma, pLch[2]);
    putNamedTone(out, "system_primary_fixed_dim", 80, a1Chroma, pLch[2]);
    putNamedTone(out, "system_on_primary_fixed", 10, a1Chroma, pLch[2]);
    putNamedTone(out, "system_on_primary_fixed_variant", 30, a1Chroma, pLch[2]);
    putNamedTone(out, "system_secondary_fixed", 90, a2Chroma, sLch[2]);
    putNamedTone(out, "system_secondary_fixed_dim", 80, a2Chroma, sLch[2]);
    putNamedTone(out, "system_on_secondary_fixed", 10, a2Chroma, sLch[2]);
    putNamedTone(out, "system_on_secondary_fixed_variant", 30, a2Chroma, sLch[2]);
    putNamedTone(out, "system_tertiary_fixed", 90, a3Chroma, tLch[2]);
    putNamedTone(out, "system_tertiary_fixed_dim", 80, a3Chroma, tLch[2]);
    putNamedTone(out, "system_on_tertiary_fixed", 10, a3Chroma, tLch[2]);
    putNamedTone(out, "system_on_tertiary_fixed_variant", 30, a3Chroma, tLch[2]);

    return Collections.unmodifiableMap(out);
  }

  private static void putNamedShades(
      Map<String, Integer> out, String prefix, double chroma, double hueDeg) {
    for (int shade : SHADES) {
      int tone = 100 - (shade / 10);
      putNamedTone(out, prefix + shade, tone, chroma, hueDeg);
    }
  }

  private static void putNamedTone(
      Map<String, Integer> out, String name, int tone, double chroma, double hueDeg) {
    out.put(name, Integer.valueOf(toneToArgb(tone, chroma, hueDeg)));
  }

  private static synchronized Map<String, Integer> systemColorResourceIds() {
    if (systemColorIds != null) return systemColorIds;
    Map<String, Integer> map = new HashMap<>();
    for (Field f : android.R.color.class.getFields()) {
      if (!f.getName().startsWith("system_") || f.getType() != int.class) continue;
      try {
        map.put(f.getName(), Integer.valueOf(f.getInt(null)));
      } catch (IllegalAccessException ignored) {
      }
    }
    systemColorIds = Collections.unmodifiableMap(map);
    return systemColorIds;
  }

  static int toneToArgb(int tone0To100, double maxChroma, double hueDeg) {
    if (tone0To100 <= 0) return 0xFF000000;
    if (tone0To100 >= 100) return 0xFFFFFFFF;
    double l = tone0To100 / 100.0;
    // Taper chroma cleanly near black and white so sRGB stays in gamut without hue shift
    double taper = Math.sin(Math.PI * l);
    double c = maxChroma * Math.pow(taper, 0.75);
    return oklchToArgb(l, c, hueDeg);
  }

  private static int shiftHue(int argb, double deltaDeg) {
    double[] lch = argbToOklch(argb);
    double h = (lch[2] + deltaDeg) % 360.0;
    if (h < 0) h += 360.0;
    return oklchToArgb(lch[0], lch[1], h);
  }

  static double[] argbToOklch(int argb) {
    double r = srgbToLinear(((argb >>> 16) & 0xFF) / 255.0);
    double g = srgbToLinear(((argb >>> 8) & 0xFF) / 255.0);
    double b = srgbToLinear((argb & 0xFF) / 255.0);

    double l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
    double m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
    double s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b;

    double l_ = Math.cbrt(l);
    double m_ = Math.cbrt(m);
    double s_ = Math.cbrt(s);

    double L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_;
    double A = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_;
    double B = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_;

    double C = Math.hypot(A, B);
    double H = Math.toDegrees(Math.atan2(B, A));
    if (H < 0) H += 360.0;
    return new double[] {L, C, H};
  }

  static int oklchToArgb(double L, double C, double hueDeg) {
    // Binary search chroma into sRGB gamut if necessary
    double lo = 0.0;
    double hi = C;
    int best = oklabToClippedArgb(L, 0.0, 0.0);
    double rad = Math.toRadians(hueDeg);
    double cos = Math.cos(rad);
    double sin = Math.sin(rad);
    if (inGamut(L, C * cos, C * sin)) {
      return oklabToClippedArgb(L, C * cos, C * sin);
    }
    for (int i = 0; i < 12; i++) {
      double mid = 0.5 * (lo + hi);
      if (inGamut(L, mid * cos, mid * sin)) {
        lo = mid;
        best = oklabToClippedArgb(L, mid * cos, mid * sin);
      } else {
        hi = mid;
      }
    }
    return best;
  }

  private static boolean inGamut(double L, double a, double b) {
    double[] rgb = oklabToLinearRgb(L, a, b);
    return rgb[0] >= -0.001
        && rgb[0] <= 1.001
        && rgb[1] >= -0.001
        && rgb[1] <= 1.001
        && rgb[2] >= -0.001
        && rgb[2] <= 1.001;
  }

  private static int oklabToClippedArgb(double L, double a, double b) {
    double[] rgb = oklabToLinearRgb(L, a, b);
    int r = (int) Math.round(linearToSrgb(rgb[0]) * 255.0);
    int g = (int) Math.round(linearToSrgb(rgb[1]) * 255.0);
    int bl = (int) Math.round(linearToSrgb(rgb[2]) * 255.0);
    r = Math.max(0, Math.min(255, r));
    g = Math.max(0, Math.min(255, g));
    bl = Math.max(0, Math.min(255, bl));
    return 0xFF000000 | (r << 16) | (g << 8) | bl;
  }

  private static double[] oklabToLinearRgb(double L, double a, double b) {
    double l_ = L + 0.3963377774 * a + 0.2158037573 * b;
    double m_ = L - 0.1055613458 * a - 0.0638541728 * b;
    double s_ = L - 0.0894841775 * a - 1.2914855480 * b;

    double l = l_ * l_ * l_;
    double m = m_ * m_ * m_;
    double s = s_ * s_ * s_;

    double r = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
    double g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
    double bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;
    return new double[] {r, g, bl};
  }

  private static double srgbToLinear(double c) {
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private static double linearToSrgb(double c) {
    double clamped = Math.max(0.0, Math.min(1.0, c));
    return clamped <= 0.0031308 ? 12.92 * clamped : 1.055 * Math.pow(clamped, 1.0 / 2.4) - 0.055;
  }
}
