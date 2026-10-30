package ee.schimke.composeai.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A secondary display the simulator adds: its name, kind, and size as resource qualifiers. Parsed
 * from {@code launch --display} and built from the control channel's {@code displays add}; free of
 * Android types so it is unit-tested on a plain JVM.
 */
final class DisplaySpec {

  /** The launch's extra displays ({@code --display}, repeatable), separated by {@code ;}. */
  static final String PROPERTY = "composeai.simulator.displays";

  /** What the display stands for, which decides its type and flags. */
  enum Kind {
    /** A monitor or TV on HDMI or USB-C: {@code TYPE_EXTERNAL}, a presentation display. */
    EXTERNAL,
    /** A display an app or the system made: {@code TYPE_VIRTUAL}, a presentation display. */
    VIRTUAL,
    /** A foldable's outer screen: {@code TYPE_INTERNAL} with {@code FLAG_REAR}. */
    REAR;

    static Kind of(String value) {
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "unknown display type " + value + " (external, virtual, rear)");
      }
    }

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  static final int DEFAULT_WIDTH_DP = 960;
  static final int DEFAULT_HEIGHT_DP = 540;
  static final String DEFAULT_DENSITY = "mdpi";

  final String name;
  final Kind kind;
  /** Robolectric qualifiers: given whole, or derived from the size and density. */
  final String qualifiers;

  /** The size and density the qualifiers came from; null when they were given whole. */
  final Integer widthDp;

  final Integer heightDp;
  final String density;

  DisplaySpec(String name, Kind kind, String qualifiers) {
    this(name, kind, qualifiers, null, null, null);
  }

  DisplaySpec(
      String name, Kind kind, String qualifiers, Integer widthDp, Integer heightDp, String density) {
    this.name = name;
    this.kind = kind;
    this.qualifiers = qualifiers;
    this.widthDp = widthDp;
    this.heightDp = heightDp;
    this.density = density;
  }

  /**
   * [base] with what a {@code displays change} gives replacing what it had: anything left out —
   * the name, the type, the width, the height, the density — stays as it was.
   */
  DisplaySpec changed(
      String name,
      String kind,
      Integer widthDp,
      Integer heightDp,
      String density,
      String qualifiers) {
    String n = name != null ? name : this.name;
    String k = kind != null ? kind : this.kind.label();
    if (qualifiers == null && widthDp == null && heightDp == null && density == null) {
      return new DisplaySpec(
          n, Kind.of(k), this.qualifiers, this.widthDp, this.heightDp, this.density);
    }
    return of(
        n,
        k,
        widthDp != null ? widthDp : this.widthDp,
        heightDp != null ? heightDp : this.heightDp,
        density != null ? density : this.density,
        qualifiers);
  }

  /**
   * A display of [widthDp] x [heightDp] at [density] (a qualifier such as {@code mdpi} or {@code
   * 320dpi}; null for {@value #DEFAULT_DENSITY}), unless [qualifiers] names the configuration
   * whole. [name] defaults by kind.
   */
  static DisplaySpec of(
      String name,
      String kind,
      Integer widthDp,
      Integer heightDp,
      String density,
      String qualifiers) {
    Kind k = kind == null || kind.isBlank() ? Kind.EXTERNAL : Kind.of(kind);
    String n = name == null || name.isBlank() ? defaultName(k) : name.trim();
    if (qualifiers != null && !qualifiers.isBlank()) {
      return new DisplaySpec(n, k, qualifiers.trim());
    }
    int w = widthDp == null ? DEFAULT_WIDTH_DP : widthDp;
    int h = heightDp == null ? DEFAULT_HEIGHT_DP : heightDp;
    if (w <= 0 || h <= 0) {
      throw new IllegalArgumentException("a display is at least 1dp each way");
    }
    String d = density == null || density.isBlank() ? DEFAULT_DENSITY : density.trim();
    return new DisplaySpec(n, k, DisplayGeometry.sizeQualifiers(w, h) + "-" + d, w, h, d);
  }

  static String defaultName(Kind kind) {
    switch (kind) {
      case VIRTUAL:
        return "Virtual display";
      case REAR:
        return "Rear display";
      default:
        return "External display";
    }
  }

  /**
   * One {@code --display} value: a size {@code <w>x<h>} in dp with an optional {@code @<density>},
   * then comma-separated {@code name=}, {@code type=}, {@code density=} or {@code qualifiers=}, in
   * any order — {@code 1280x720@xhdpi,name=TV}, {@code rear,640x480} (a bare word is the type).
   * Empty is a default external display.
   */
  static DisplaySpec parse(String value) {
    Integer w = null;
    Integer h = null;
    String density = null;
    String name = null;
    String kind = null;
    String qualifiers = null;
    for (String raw : value.split(",")) {
      String part = raw.trim();
      if (part.isEmpty()) continue;
      int eq = part.indexOf('=');
      if (eq < 0 && part.matches("[A-Za-z]+")) {
        kind = part;
        continue;
      }
      if (eq < 0) {
        String size = part;
        int at = size.indexOf('@');
        if (at >= 0) {
          density = size.substring(at + 1);
          size = size.substring(0, at);
        }
        String[] wh = size.toLowerCase(Locale.ROOT).replace("dp", "").split("x");
        if (wh.length != 2) {
          throw new IllegalArgumentException(
              "a display size is <width>x<height> in dp, not " + part);
        }
        try {
          w = Integer.parseInt(wh[0].trim());
          h = Integer.parseInt(wh[1].trim());
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(
              "a display size is <width>x<height> in dp, not " + part);
        }
        continue;
      }
      String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      String v = part.substring(eq + 1).trim();
      switch (key) {
        case "name":
          name = v;
          break;
        case "type":
          kind = v;
          break;
        case "density":
          density = v;
          break;
        case "qualifiers":
          qualifiers = v;
          break;
        default:
          throw new IllegalArgumentException(
              "unknown display option " + key + " (name, type, density, qualifiers)");
      }
    }
    return of(name, kind, w, h, density, qualifiers);
  }

  /** The specs in [property] ({@link #PROPERTY}'s value), or none. */
  static List<DisplaySpec> parseAll(String property) {
    List<DisplaySpec> specs = new ArrayList<>();
    if (property == null || property.isBlank()) return specs;
    for (String one : property.split(";")) specs.add(parse(one));
    return specs;
  }
}
