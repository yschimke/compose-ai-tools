package ee.schimke.composeai.simulator;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import java.util.Locale;
import org.robolectric.RuntimeEnvironment;

/**
 * The device the launched app is built for, as the CLI detected it from the APK ({@code
 * composeai.simulator.formFactor}): {@code phone}, {@code wear}, {@code glimmer} or {@code xr}.
 * The CLI picks the display's qualifiers from it; in here it decides what the simulator draws and
 * fakes for that device.
 */
enum FormFactor {
  PHONE,
  WEAR,
  GLIMMER,
  XR;

  static final String PROPERTY = "composeai.simulator.formFactor";

  static FormFactor current() {
    String value = System.getProperty(PROPERTY, "phone").trim().toUpperCase(Locale.ROOT);
    try {
      return valueOf(value);
    } catch (IllegalArgumentException e) {
      return PHONE;
    }
  }

  String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Whether the display is round: the {@code round} qualifier, which is what Wear Compose reads
   * ({@code Configuration.isScreenRound()}), so {@code --qualifiers} without it gives a square
   * watch, as an emulator's square skin does.
   */
  static boolean isRound() {
    try {
      Configuration configuration =
          RuntimeEnvironment.getApplication().getResources().getConfiguration();
      return configuration.isScreenRound();
    } catch (RuntimeException e) {
      String qualifiers = RuntimeEnvironment.getQualifiers();
      return qualifiers != null && qualifiers.contains("-round");
    }
  }

  /**
   * A round display's capture: the pixels outside the inscribed circle made transparent, as the
   * preview renderer's round crop (Roborazzi's {@code applyDeviceCrop}) does for a Wear {@code
   * @Preview}. The edge is anti-aliased by pixel coverage.
   */
  static Bitmap cropRound(Bitmap source) {
    int width = source.getWidth();
    int height = source.getHeight();
    int[] pixels = new int[width * height];
    source.getPixels(pixels, 0, width, 0, 0, width, height);
    float cx = width / 2f;
    float cy = height / 2f;
    float radius = Math.min(width, height) / 2f;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        float dx = x + 0.5f - cx;
        float dy = y + 0.5f - cy;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float coverage = Math.max(0f, Math.min(1f, radius - distance + 0.5f));
        if (coverage >= 1f) continue;
        int i = y * width + x;
        int alpha = Math.round((pixels[i] >>> 24) * coverage);
        pixels[i] = (alpha << 24) | (pixels[i] & 0x00FFFFFF);
      }
    }
    Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    out.setPixels(pixels, 0, width, 0, 0, width, height);
    return out;
  }
}
