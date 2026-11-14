package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

public class WatchFaceDynamicColorsTest {

  @Test
  public void paletteHexAndEqualityBehaveDeterministically() {
    WatchFaceDynamicColors.Palette p1 =
        new WatchFaceDynamicColors.Palette(0xFF4F378B, 0xFF625B71, 0xFF7D5260, "config");
    WatchFaceDynamicColors.Palette p2 =
        new WatchFaceDynamicColors.Palette(0xFF4F378B, 0xFF625B71, 0xFF7D5260, "config");
    WatchFaceDynamicColors.Palette p3 =
        new WatchFaceDynamicColors.Palette(0xFF006A6A, 0xFF4A6363, 0xFF4B607C, "config");

    assertEquals(p1, p2);
    assertNotEquals(p1, p3);
    assertEquals("#4F378B", WatchFaceDynamicColors.Palette.hex(p1.primaryArgb));
    assertEquals("#625B71", WatchFaceDynamicColors.Palette.hex(p1.secondaryArgb));
    assertEquals("#7D5260", WatchFaceDynamicColors.Palette.hex(p1.tertiaryArgb));
  }

  @Test
  public void buildsMaterial3SystemTonalAndRoleColorMapsWithDistinctSeeds() {
    WatchFaceDynamicColors.Palette purple =
        new WatchFaceDynamicColors.Palette(0xFF7B61FF, 0xFF68587A, 0xFFFF6B9E, "config");
    WatchFaceDynamicColors.Palette emerald =
        new WatchFaceDynamicColors.Palette(0xFF00C853, 0xFF4C8C64, 0xFF00B0FF, "config");

    Map<String, Integer> purpleMap = WatchFaceDynamicColors.buildSystemColorNameMap(purple);
    Map<String, Integer> emeraldMap = WatchFaceDynamicColors.buildSystemColorNameMap(emerald);

    // Core Material 3 tonal shade resources (API 31+).
    assertTrue(purpleMap.containsKey("system_accent1_200"));
    assertTrue(purpleMap.containsKey("system_accent1_600"));
    assertTrue(purpleMap.containsKey("system_accent2_300"));
    assertTrue(purpleMap.containsKey("system_accent3_200"));
    assertTrue(purpleMap.containsKey("system_neutral1_900"));
    assertTrue(purpleMap.containsKey("system_neutral2_800"));

    // Android 14+ dynamic role resources used by Wear Compose Material 3 dynamicColorScheme.
    assertNotNull(purpleMap.get("system_primary_dark"));
    assertNotNull(purpleMap.get("system_on_primary_dark"));
    assertNotNull(purpleMap.get("system_primary_container_dark"));
    assertNotNull(purpleMap.get("system_surface_container_dark"));

    //Changing the watch face palette shifts the generated system_primary_dark accent!
    assertNotEquals(purpleMap.get("system_primary_dark"), emeraldMap.get("system_primary_dark"));
    assertNotEquals(purpleMap.get("system_accent1_200"), emeraldMap.get("system_accent1_200"));

    // Tone ordering invariant: shade 0 is pure white, shade 1000 is pure black, 200 is lighter than 800.
    assertEquals(0xFFFFFFFF, purpleMap.get("system_accent1_0").intValue());
    assertEquals(0xFF000000, purpleMap.get("system_accent1_1000").intValue());
    int light200 = luminanceApprox(purpleMap.get("system_accent1_200").intValue());
    int dark800 = luminanceApprox(purpleMap.get("system_accent1_800").intValue());
    assertTrue("expected tone 200 brighter than tone 800", light200 > dark800);
    assertFalse(purpleMap.isEmpty());
  }

  private static int luminanceApprox(int argb) {
    int r = (argb >>> 16) & 0xFF;
    int g = (argb >>> 8) & 0xFF;
    int b = argb & 0xFF;
    return r * 299 + g * 587 + b * 114;
  }
}
