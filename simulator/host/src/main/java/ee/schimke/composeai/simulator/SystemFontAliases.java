package ee.schimke.composeai.simulator;

import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seeds Pixel / Wear OS system font aliases ({@code google-sans-flex}, {@code roboto-flex}) into
 * Android's {@code Typeface.sSystemFontMap} inside the Robolectric simulator sandbox.
 *
 * <p>On a real Pixel Watch / Wear OS 6+ device, {@code /system/etc/fonts.xml} registers {@code
 * google-sans-flex} (used by the Wear OS 8 global status bar and Pixel system UI) and {@code
 * roboto-flex} (used by Wear Compose Material 3's {@code TypefaceTokens.Brand}). Robolectric ships
 * only stock AOSP Roboto, so without seeding those aliases both fall back to plain Roboto.
 *
 * <p>TTFs for regular (400) and semibold (600) weights are downloaded from Google Fonts CSS2 on
 * demand and cached on disk under {@code composeai.fonts.cacheDir} (defaulting to {@code
 * ~/.cache/compose-ai-tools/fonts}, matching {@code compose-preview-daemon}'s {@code
 * <slug>-<weight>.ttf} naming). Offline environments or {@code -Dcomposeai.fonts.offline=true} skip
 * downloads silently and fall back to stock system fonts without failing the session.
 */
final class SystemFontAliases {

  static final String CACHE_DIR_PROPERTY = "composeai.fonts.cacheDir";
  static final String OFFLINE_PROPERTY = "composeai.fonts.offline";

  private static final int CONNECT_TIMEOUT_MS = 4_000;
  private static final int READ_TIMEOUT_MS = 8_000;
  private static final int[] SEEDED_WEIGHTS = {400, 600};
  private static final Pattern TTF_URL = Pattern.compile("url\\((https://[^)]+\\.ttf)\\)");

  private static final Map<String, String> ALIASES = new LinkedHashMap<>();

  static {
    ALIASES.put("google-sans-flex", "Google Sans Flex");
    ALIASES.put("roboto-flex", "Roboto Flex");
  }

  private SystemFontAliases() {}

  /**
   * Seeds {@code Typeface.sSystemFontMap} for every alias in {@link #ALIASES}. Idempotent; returns
   * the slugs now present in the map.
   */
  static List<String> seed() {
    Map<String, Typeface> map = systemFontMap();
    if (map == null) return List.of();
    return seed(map, defaultCacheDir(), isOffline());
  }

  static List<String> seed(Map<String, Typeface> map, File cacheDir, boolean offline) {
    List<String> seeded = new ArrayList<>();
    for (Map.Entry<String, String> entry : ALIASES.entrySet()) {
      String slug = entry.getKey();
      String family = entry.getValue();
      if (map.containsKey(slug)) {
        seeded.add(slug);
        continue;
      }
      Typeface tf = buildFamilyTypeface(cacheDir, slug, family, offline);
      if (tf != null) {
        map.put(slug, tf);
        seeded.add(slug);
      }
    }
    return seeded;
  }

  private static Typeface buildFamilyTypeface(
      File cacheDir, String slug, String family, boolean offline) {
    FontFamily.Builder familyBuilder = null;
    File firstFile = null;
    for (int weight : SEEDED_WEIGHTS) {
      File ttf = resolveCachedOrDownload(cacheDir, slug, family, weight, offline);
      if (ttf == null || !ttf.isFile()) continue;
      if (firstFile == null) firstFile = ttf;
      try {
        Font font =
            new Font.Builder(ttf)
                .setWeight(weight)
                .setSlant(FontStyle.FONT_SLANT_UPRIGHT)
                .build();
        if (familyBuilder == null) {
          familyBuilder = new FontFamily.Builder(font);
        } else {
          familyBuilder.addFont(font);
        }
      } catch (IOException | RuntimeException ignored) {
        // Fall back to single-file Typeface below.
      }
    }
    if (familyBuilder != null) {
      try {
        return new Typeface.CustomFallbackBuilder(familyBuilder.build())
            .setStyle(new FontStyle(FontStyle.FONT_WEIGHT_NORMAL, FontStyle.FONT_SLANT_UPRIGHT))
            .build();
      } catch (RuntimeException ignored) {
        // Fall back to single-file Typeface below.
      }
    }
    if (firstFile == null) return null;
    try {
      return Typeface.createFromFile(firstFile);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  static File defaultCacheDir() {
    String configured = System.getProperty(CACHE_DIR_PROPERTY);
    if (configured != null && !configured.isBlank()) {
      return new File(configured.trim());
    }
    String xdg = System.getenv("XDG_CACHE_HOME");
    File base =
        (xdg != null && !xdg.isBlank())
            ? new File(xdg)
            : new File(System.getProperty("user.home", "."), ".cache");
    return new File(new File(base, "compose-ai-tools"), "fonts");
  }

  private static boolean isOffline() {
    return "true".equalsIgnoreCase(System.getProperty(OFFLINE_PROPERTY, "").trim());
  }

  static File resolveCachedOrDownload(
      File cacheDir, String slug, String family, int weight, boolean offline) {
    File cached = new File(cacheDir, slug + "-" + weight + ".ttf");
    if (cached.isFile() && cached.length() > 1024) return cached;
    if (offline) return null;

    try {
      String ttfUrl = fetchTruetypeUrl(family, weight);
      if (ttfUrl == null) return null;
      if (!cacheDir.exists() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) return null;
      File tmp = new File(cacheDir, slug + "-" + weight + ".ttf.part");
      download(ttfUrl, tmp);
      if (tmp.length() <= 1024) {
        tmp.delete();
        return null;
      }
      if (!tmp.renameTo(cached)) {
        if (cached.isFile() && cached.length() > 1024) {
          tmp.delete();
          return cached;
        }
        return tmp;
      }
      return cached;
    } catch (IOException | RuntimeException ignored) {
      return null;
    }
  }

  private static String fetchTruetypeUrl(String family, int weight) throws IOException {
    String encoded = URLEncoder.encode(family, StandardCharsets.UTF_8);
    // Request the exact weight with a non-woff2 User-Agent so Google Fonts CSS2 serves a direct
    // .ttf URL for that weight.
    String query =
        "https://fonts.googleapis.com/css2?family=" + encoded + ":wght@" + weight + "&display=swap";
    String css = httpGetString(query);
    if (css == null) return null;
    Matcher m = TTF_URL.matcher(css);
    return m.find() ? m.group(1) : null;
  }

  private static String httpGetString(String urlString) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; compose-ai-tools)");
    try {
      if (conn.getResponseCode() != 200) return null;
      try (InputStream in = conn.getInputStream()) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    } finally {
      conn.disconnect();
    }
  }

  private static void download(String urlString, File dest) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; compose-ai-tools)");
    try {
      if (conn.getResponseCode() != 200) return;
      try (InputStream in = conn.getInputStream();
          FileOutputStream out = new FileOutputStream(dest)) {
        in.transferTo(out);
      }
    } finally {
      conn.disconnect();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Typeface> systemFontMap() {
    try {
      Field field = Typeface.class.getDeclaredField("sSystemFontMap");
      field.setAccessible(true);
      Object value = field.get(null);
      return value instanceof Map ? (Map<String, Typeface>) value : null;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }
}
