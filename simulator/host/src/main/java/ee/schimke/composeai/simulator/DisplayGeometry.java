package ee.schimke.composeai.simulator;

/**
 * The arithmetic behind {@link DeviceDisplay} and {@link Foldables}, free of Android types so it
 * is unit-tested on a plain JVM.
 */
final class DisplayGeometry {

  /** The launch's device preset ({@code --device}), applied before the app starts. */
  static final String DEVICE_PROPERTY = "composeai.simulator.device";

  /** A device shape: Android Studio's reference devices, plus the fold they have. */
  static final class Preset {
    final String name;
    final int widthDp;
    final int heightDp;
    /** {@code none}, or the fold's posture when the preset is applied. */
    final String posture;
    /** A density qualifier, or null to keep the current one. */
    final String density;

    Preset(String name, int widthDp, int heightDp, String posture, String density) {
      this.name = name;
      this.widthDp = widthDp;
      this.heightDp = heightDp;
      this.posture = posture;
      this.density = density;
    }
  }

  static final Preset[] PRESETS = {
    new Preset("phone", 411, 891, "none", null),
    // A book-style foldable, open: its fold runs top to bottom.
    new Preset("foldable", 673, 841, "flat", null),
    // The same device closed, on its outer screen: no fold to report.
    new Preset("foldable-folded", 411, 891, "none", null),
    new Preset("tablet", 1280, 800, "none", null),
    // A desktop window is mdpi, as on ChromeOS and desktop Android.
    new Preset("desktop", 1920, 1080, "none", "mdpi"),
  };

  static Preset preset(String name) {
    String wanted = name.trim().toLowerCase(java.util.Locale.ROOT);
    StringBuilder names = new StringBuilder();
    for (Preset p : PRESETS) {
      if (p.name.equals(wanted)) return p;
      names.append(names.length() == 0 ? "" : ", ").append(p.name);
    }
    throw new IllegalArgumentException("unknown device " + name + " (" + names + ")");
  }

  /** The qualifiers a launch on [preset] starts with, at [density] unless the preset has one. */
  static String launchQualifiers(Preset preset, String density) {
    String d = preset.density != null ? preset.density : density;
    return sizeQualifiers(preset.widthDp, preset.heightDp) + (d == null ? "" : "-" + d);
  }

  /**
   * The size-derived resource qualifiers for a [widthDp] x [heightDp] display: smallest width,
   * width, height, the screen-size and aspect buckets, and the orientation, as the framework
   * derives them.
   */
  static String sizeQualifiers(int widthDp, int heightDp) {
    int shortSide = Math.min(widthDp, heightDp);
    int longSide = Math.max(widthDp, heightDp);
    String size;
    if (longSide >= 960 && shortSide >= 720) size = "xlarge";
    else if (longSide >= 640 && shortSide >= 480) size = "large";
    else if (longSide >= 470 && shortSide >= 320) size = "normal";
    else size = "small";
    // Configuration.reduceScreenLayout: long when markedly longer than wide.
    String aspect = longSide * 3 / 5 >= shortSide - 1 ? "long" : "notlong";
    String orientation = widthDp > heightDp ? "land" : "port";
    return "sw" + shortSide + "dp-w" + widthDp + "dp-h" + heightDp + "dp-" + size + "-" + aspect
        + "-" + orientation;
  }

  /**
   * A fold across the middle of the window {@code [left, top, right, bottom]}: a line for a fold,
   * a band [hingePx] wide for a hinge, spanning the window's whole height ([vertical]) or width,
   * which is what {@code androidx.window} accepts. Returns {@code [left, top, right, bottom]}.
   */
  static int[] foldBounds(int left, int top, int right, int bottom, int hingePx, boolean vertical) {
    if (vertical) {
      int x = left + (right - left - hingePx) / 2;
      return new int[] {x, top, x + hingePx, bottom};
    }
    int y = top + (bottom - top - hingePx) / 2;
    return new int[] {left, y, right, y + hingePx};
  }

  private DisplayGeometry() {}
}
