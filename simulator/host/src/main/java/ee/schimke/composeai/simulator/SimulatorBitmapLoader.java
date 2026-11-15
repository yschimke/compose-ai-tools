package ee.schimke.composeai.simulator;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configurable URL image loader for {@code RemoteComposePlayer} in the Wear/Android simulator.
 *
 * <p>Resolves {@code http://}, {@code https://}, {@code file:}, {@code data:image/...}, custom
 * registered URLs, and the canonical {@code https://compose-preview.local/dwf/*.png} sample URLs
 * with in-memory byte caching. Adapts reflectively to {@code
 * androidx.compose.remote.player.core.platform.BitmapLoader} and also decodes {@link Bitmap}
 * instances for runtime {@code setUserLocalBitmap} overrides.
 */
final class SimulatorBitmapLoader {

  static final String DEFAULT_URL_BASE = "https://compose-preview.local/dwf/";
  static final String DEFAULT_MONOCHROMATIC_URL = DEFAULT_URL_BASE + "monochromatic_default.png";
  static final String DEFAULT_SMALL_URL = DEFAULT_URL_BASE + "small_default.png";
  static final String DEFAULT_PHOTO_URL = DEFAULT_URL_BASE + "photo_default.png";

  private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
  private static final int HTTP_TIMEOUT_MS = 10_000;
  private static final int MAX_REDIRECTS = 5;

  private static final SimulatorBitmapLoader SHARED = new SimulatorBitmapLoader(true);

  private final Map<String, Callable<byte[]>> providers = new ConcurrentHashMap<>();
  private final Map<String, byte[]> byteCache = new ConcurrentHashMap<>();
  private final boolean allowNetwork;

  SimulatorBitmapLoader(boolean allowNetwork) {
    this.allowNetwork = allowNetwork;
    registerDefaultSampleUrls();
  }

  static SimulatorBitmapLoader shared() {
    return SHARED;
  }

  static boolean isImageUrl(String raw) {
    if (raw == null) return false;
    String s = raw.trim();
    if (s.isEmpty() || s.startsWith("@") || s.startsWith("[")) return false;
    String lower = s.toLowerCase(Locale.ROOT);
    return lower.startsWith("http://")
        || lower.startsWith("https://")
        || lower.startsWith("file:")
        || lower.startsWith("data:image/")
        || lower.contains("://");
  }

  void registerUrlBytes(String url, byte[] encodedBytes) {
    if (url == null || encodedBytes == null) return;
    String key = url.trim();
    providers.put(key, () -> encodedBytes);
    byteCache.put(key, encodedBytes);
  }

  void registerUrlProvider(String url, Callable<byte[]> provider) {
    if (url == null || provider == null) return;
    String key = url.trim();
    providers.put(key, provider);
    byteCache.remove(key);
  }

  void clearCache() {
    byteCache.clear();
  }

  InputStream openStream(String url) throws IOException {
    byte[] bytes = loadBytes(url);
    return new ByteArrayInputStream(bytes);
  }

  byte[] loadBytes(String rawUrl) throws IOException {
    if (rawUrl == null || rawUrl.trim().isEmpty()) {
      throw new IOException("Empty bitmap URL");
    }
    String url = rawUrl.trim();
    byte[] cached = byteCache.get(url);
    if (cached != null) return cached;

    Callable<byte[]> provider = providers.get(url);
    if (provider != null) {
      try {
        byte[] generated = provider.call();
        if (generated != null && generated.length > 0) {
          byteCache.put(url, generated);
          return generated;
        }
      } catch (Exception e) {
        throw new IOException("Configured URL provider failed for " + url, e);
      }
    }

    byte[] resolved = fetchExternalBytes(url);
    if (resolved == null || resolved.length == 0) {
      throw new IOException("Could not load bitmap from URL: " + url);
    }
    byteCache.put(url, resolved);
    return resolved;
  }

  Object decodeBitmap(String url) {
    try {
      byte[] bytes = loadBytes(url);
      return AndroidGraphics.decodeBitmap(bytes);
    } catch (IOException | RuntimeException | LinkageError e) {
      return null;
    }
  }

  Object createRemoteBitmapLoaderProxy(ClassLoader classLoader)
      throws ClassNotFoundException {
    Class<?> loaderType =
        Class.forName(
            "androidx.compose.remote.player.core.platform.BitmapLoader", true, classLoader);
    return Proxy.newProxyInstance(
        loaderType.getClassLoader(),
        new Class<?>[] {loaderType},
        (proxy, method, args) -> {
          String name = method.getName();
          if ("loadBitmap".equals(name) && args != null && args.length == 1) {
            return openStream((String) args[0]);
          }
          if ("equals".equals(name)) {
            return Boolean.valueOf(proxy == (args != null && args.length > 0 ? args[0] : null));
          }
          if ("hashCode".equals(name)) {
            return Integer.valueOf(System.identityHashCode(proxy));
          }
          if ("toString".equals(name)) {
            return "SimulatorBitmapLoader";
          }
          return null;
        });
  }

  static void installOnPlayer(Class<?> playerType, Object playerView, SimulatorBitmapLoader loader) {
    if (playerType == null || playerView == null || loader == null) return;
    try {
      ClassLoader cl = playerType.getClassLoader();
      Class<?> loaderIface =
          Class.forName("androidx.compose.remote.player.core.platform.BitmapLoader", true, cl);
      Object proxy = loader.createRemoteBitmapLoaderProxy(cl);
      playerType.getMethod("setBitmapLoader", loaderIface).invoke(playerView, proxy);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
    }
  }

  /**
   * Normalizes {@code 1x1} wire placeholder dimensions emitted by {@code rc-creation-writer} on
   * {@code ENCODING_URL} ({@code 1}) and {@code ENCODING_FILE} ({@code 2}) {@code BitmapData}
   * operations to {@code 0x0} before {@code RemoteComposePlayer.setDocument} runs, so AndroidX
   * {@code RemoteBitmapDecoder} and {@code AndroidRemoteContext.loadBitmap} accept arbitrary-sized
   * URL bitmaps and update runtime {@code ImageAttribute} dimensions dynamically.
   */
  static int normalizeUrlBitmapDimensions(Object remoteDoc) {
    if (remoteDoc == null) return 0;
    try {
      Object coreDoc = remoteDoc.getClass().getMethod("getDocument").invoke(remoteDoc);
      if (coreDoc == null) return 0;
      Object[] bitmaps =
          (Object[]) coreDoc.getClass().getMethod("getBitmapDataSet").invoke(coreDoc);
      if (bitmaps == null || bitmaps.length == 0) return 0;
      int normalized = 0;
      for (Object bmp : bitmaps) {
        if (bmp == null) continue;
        Class<?> bt = bmp.getClass();
        short encoding = ((Number) bt.getMethod("getEncoding").invoke(bmp)).shortValue();
        int w = ((Number) bt.getMethod("getWidth").invoke(bmp)).intValue();
        int h = ((Number) bt.getMethod("getHeight").invoke(bmp)).intValue();
        if ((encoding == 1 || encoding == 2) && w == 1 && h == 1) {
          bt.getMethod("setWidth", int.class).invoke(bmp, Integer.valueOf(0));
          bt.getMethod("setHeight", int.class).invoke(bmp, Integer.valueOf(0));
          normalized++;
        }
      }
      return normalized;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
      return 0;
    }
  }

  static void setDocumentNormalized(Class<?> playerType, Object playerView, byte[] document)
      throws ReflectiveOperationException {
    if (playerType == null || playerView == null || document == null) return;
    try {
      ClassLoader cl = playerType.getClassLoader();
      Class<?> remoteDocType =
          Class.forName("androidx.compose.remote.player.core.RemoteDocument", true, cl);
      Object remoteDoc =
          remoteDocType.getConstructor(byte[].class).newInstance((Object) document);
      normalizeUrlBitmapDimensions(remoteDoc);
      playerType.getMethod("setDocument", remoteDocType).invoke(playerView, remoteDoc);
      return;
    } catch (ClassNotFoundException | NoSuchMethodException ignored) {
    }
    playerType.getMethod("setDocument", byte[].class).invoke(playerView, (Object) document);
  }

  private byte[] fetchExternalBytes(String url) throws IOException {
    String lower = url.toLowerCase(Locale.ROOT);
    if (lower.startsWith("data:image/")) {
      int comma = url.indexOf(',');
      if (comma < 0) throw new IOException("Malformed data: URL");
      String meta = url.substring(0, comma).toLowerCase(Locale.ROOT);
      String payload = url.substring(comma + 1);
      if (meta.contains(";base64")) {
        return Base64.getDecoder().decode(payload.trim());
      }
      return URLDecoder.decode(payload, StandardCharsets.UTF_8.name())
          .getBytes(StandardCharsets.ISO_8859_1);
    }
    if (lower.startsWith("file:")) {
      File file;
      try {
        file = new File(URI.create(url));
      } catch (IllegalArgumentException e) {
        file = new File(url.substring("file:".length()));
      }
      if (!file.isFile()) {
        throw new IOException("Image file does not exist: " + file);
      }
      long len = file.length();
      if (len > MAX_IMAGE_BYTES) {
        throw new IOException("Image file exceeds max size (" + len + " bytes): " + file);
      }
      return Files.readAllBytes(file.toPath());
    }
    if (allowNetwork && (lower.startsWith("http://") || lower.startsWith("https://"))) {
      return fetchHttpBytes(url);
    }
    File maybeLocal = new File(url);
    if (maybeLocal.isFile() && maybeLocal.length() <= MAX_IMAGE_BYTES) {
      return Files.readAllBytes(maybeLocal.toPath());
    }
    return null;
  }

  private static byte[] fetchHttpBytes(String initialUrl) throws IOException {
    String current = initialUrl;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      URI uri = URI.create(current);
      String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
      boolean loopback =
          "127.0.0.1".equals(host)
              || "localhost".equals(host)
              || "::1".equals(host)
              || "[::1]".equals(host);
      HttpURLConnection conn =
          (HttpURLConnection)
              (loopback
                  ? uri.toURL().openConnection(java.net.Proxy.NO_PROXY)
                  : uri.toURL().openConnection());
      conn.setConnectTimeout(HTTP_TIMEOUT_MS);
      conn.setReadTimeout(HTTP_TIMEOUT_MS);
      conn.setInstanceFollowRedirects(false);
      conn.setRequestProperty("User-Agent", "compose-preview-simulator-loader/1.0");
      try {
        int status = conn.getResponseCode();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
          String location = conn.getHeaderField("Location");
          if (location == null || location.trim().isEmpty()) {
            throw new IOException("Redirect missing Location header from " + current);
          }
          current = uri.resolve(location.trim()).toString();
          continue;
        }
        if (status < 200 || status >= 300) {
          throw new IOException("HTTP " + status + " loading image URL " + current);
        }
        try (InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
          byte[] buf = new byte[8192];
          int total = 0;
          int n;
          while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > MAX_IMAGE_BYTES) {
              throw new IOException("Image URL exceeded max size: " + initialUrl);
            }
            out.write(buf, 0, n);
          }
          return out.toByteArray();
        }
      } finally {
        conn.disconnect();
      }
    }
    throw new IOException("Too many redirects for image URL: " + initialUrl);
  }

  private void registerDefaultSampleUrls() {
    providers.put(DEFAULT_MONOCHROMATIC_URL, () -> encodeSampleOrFallback("mono", 48, 48, 0xFFFFFFFF));
    providers.put(DEFAULT_SMALL_URL, () -> encodeSampleOrFallback("small", 96, 96, 0xFF3D5AFE));
    providers.put(DEFAULT_PHOTO_URL, () -> encodeSampleOrFallback("photo", 450, 450, 0xFF182B49));
    providers.put(
        DEFAULT_URL_BASE + "photo_aquarium.png",
        () -> encodeSampleOrFallback("aquarium", 450, 450, 0xFF0B6AD4));
    providers.put(
        DEFAULT_URL_BASE + "watch_board.png",
        () -> encodeSampleOrFallback("watch_board", 64, 64, 0xFF263238));
    providers.put(
        DEFAULT_URL_BASE + "battery.png", () -> encodeSampleOrFallback("battery", 48, 48, 0xFFFFFFFF));
    providers.put(
        DEFAULT_URL_BASE + "steps.png", () -> encodeSampleOrFallback("steps", 48, 48, 0xFFFFFFFF));
    providers.put(
        DEFAULT_URL_BASE + "heart.png", () -> encodeSampleOrFallback("heart", 48, 48, 0xFFFFFFFF));
    providers.put(
        DEFAULT_URL_BASE + "sun.png", () -> encodeSampleOrFallback("sun", 48, 48, 0xFFFFFFFF));
  }

  private static byte[] encodeSampleOrFallback(
      String kind, int width, int height, int fallbackArgb) {
    try {
      byte[] encoded = AndroidGraphics.encodeNamedSample(kind);
      if (encoded != null && encoded.length > 0) return encoded;
    } catch (RuntimeException | LinkageError ignored) {
      // Pure-JVM unit tests run without android.jar on the runtime classpath.
    }
    return encodeSolidJava2dPng(width, height, fallbackArgb);
  }

  private static byte[] encodeSolidJava2dPng(int width, int height, int argb) {
    try {
      Class<?> bufImgType = Class.forName("java.awt.image.BufferedImage");
      Object img =
          bufImgType
              .getConstructor(int.class, int.class, int.class)
              .newInstance(Integer.valueOf(width), Integer.valueOf(height), Integer.valueOf(2));
      java.lang.reflect.Method setRgb =
          bufImgType.getMethod("setRGB", int.class, int.class, int.class);
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          setRgb.invoke(img, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(argb));
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Class<?> imageIo = Class.forName("javax.imageio.ImageIO");
      Class<?> renderedImage = Class.forName("java.awt.image.RenderedImage");
      imageIo
          .getMethod("write", renderedImage, String.class, java.io.OutputStream.class)
          .invoke(null, img, "PNG", out);
      return out.toByteArray();
    } catch (ReflectiveOperationException | RuntimeException e) {
      return Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }
  }

  private static final class AndroidGraphics {
    private interface CanvasDrawer {
      void draw(Canvas canvas);
    }

    static Object decodeBitmap(byte[] bytes) {
      if (bytes == null || bytes.length == 0) return null;
      return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    static byte[] encodeNamedSample(String kind) {
      switch (kind) {
        case "mono":
          return encodeDefaultMonochromaticPng();
        case "small":
          return encodeDefaultSmallPng();
        case "photo":
          return encodeDefaultPhotoPng();
        case "aquarium":
          return encodeAquariumPng();
        case "watch_board":
          return encodeWatchBoardPng();
        case "battery":
          return encodeBatteryPng();
        case "steps":
          return encodeStepsPng();
        case "heart":
          return encodeHeartPng();
        case "sun":
          return encodeSunPng();
        default:
          return null;
      }
    }

    private static byte[] encodeSamplePng(int width, int height, CanvasDrawer drawer) {
      Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      if (bmp == null) return null;
      Canvas canvas = new Canvas(bmp);
      drawer.draw(canvas);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
        byte[] bytes = out.toByteArray();
        if (bytes.length > 0) return bytes;
      }
      return null;
    }

    private static byte[] encodeDefaultMonochromaticPng() {
      return encodeSamplePng(
          48,
          48,
          canvas -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            Path star = new Path();
            star.moveTo(24f, 4f);
            star.lineTo(29.6f, 18.4f);
            star.lineTo(44f, 24f);
            star.lineTo(29.6f, 29.6f);
            star.lineTo(24f, 44f);
            star.lineTo(18.4f, 29.6f);
            star.lineTo(4f, 24f);
            star.lineTo(18.4f, 18.4f);
            star.close();
            canvas.drawPath(star, p);
          });
    }

    private static byte[] encodeDefaultSmallPng() {
      return encodeSamplePng(
          96,
          96,
          canvas -> {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setShader(
                new LinearGradient(
                    0f, 0f, 96f, 96f, 0xFF3D5AFE, 0xFF00BFA5, Shader.TileMode.CLAMP));
            canvas.drawOval(new RectF(4f, 4f, 92f, 92f), bg);
            Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
            fg.setColor(0xFFFFFFFF);
            Path bolt = new Path();
            bolt.moveTo(52f, 16f);
            bolt.lineTo(28f, 52f);
            bolt.lineTo(46f, 52f);
            bolt.lineTo(42f, 80f);
            bolt.lineTo(68f, 44f);
            bolt.lineTo(50f, 44f);
            bolt.close();
            canvas.drawPath(bolt, fg);
          });
    }

    private static byte[] encodeDefaultPhotoPng() {
      return encodeSamplePng(
          450,
          450,
          canvas -> {
            Paint sky = new Paint(Paint.ANTI_ALIAS_FLAG);
            sky.setShader(
                new LinearGradient(
                    0f, 0f, 0f, 450f, 0xFF182B49, 0xFF09101B, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, 450f, 450f, sky);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x783D5AFE);
            canvas.drawOval(new RectF(-90f, -150f, 540f, 350f), p);
            p.setColor(0xC3FFD180);
            canvas.drawOval(new RectF(243f, 72f, 369f, 198f), p);
            p.setColor(0xD21E3A5F);
            canvas.drawOval(new RectF(-135f, 252f, 415f, 607f), p);
            p.setColor(0xE609101B);
            canvas.drawOval(new RectF(54f, 292f, 609f, 632f), p);
          });
    }

    private static byte[] encodeAquariumPng() {
      return encodeSamplePng(
          450,
          450,
          canvas -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF0B6AD4);
            canvas.drawRect(0f, 0f, 450f, 450f, p);
            p.setColor(0xD91E88E5);
            canvas.drawRect(0f, 0f, 450f, 235f, p);
            p.setColor(0xB8D9A45B);
            canvas.drawOval(new RectF(110f, 60f, 175f, 120f), p);
            canvas.drawOval(new RectF(290f, 30f, 355f, 80f), p);
            p.setColor(0xE00A2E5C);
            canvas.drawRect(0f, 235f, 450f, 450f, p);
          });
    }

    private static byte[] encodeWatchBoardPng() {
      return encodeSamplePng(
          64,
          64,
          canvas -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF263238);
            canvas.drawRoundRect(new RectF(8f, 14f, 56f, 46f), 6f, 6f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f);
            p.setColor(0xFFCFD8DC);
            canvas.drawRoundRect(new RectF(8f, 14f, 56f, 46f), 6f, 6f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF80DEEA);
            canvas.drawOval(new RectF(17f, 25f, 25f, 33f), p);
            canvas.drawOval(new RectF(39f, 25f, 47f, 33f), p);
          });
    }

    private static byte[] encodeBatteryPng() {
      return encodeSamplePng(
          48,
          48,
          canvas -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            canvas.drawRect(19f, 4f, 29f, 9f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.5f);
            canvas.drawRoundRect(new RectF(12f, 9f, 36f, 43f), 4f, 4f, p);
            p.setStyle(Paint.Style.FILL);
            canvas.drawRect(16f, 23f, 32f, 39f, p);
          });
    }

    private static byte[] encodeStepsPng() {
      return encodeSamplePng(
          48,
          48,
          canvas -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            canvas.drawOval(new RectF(10f, 8f, 22f, 26f), p);
            canvas.drawOval(new RectF(26f, 18f, 38f, 36f), p);
          });
    }

    private static byte[] encodeHeartPng() {
      return encodeSamplePng(
          48,
          48,
          canvas -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            Path heart = new Path();
            heart.moveTo(24f, 42f);
            heart.cubicTo(8f, 30f, 4f, 18f, 13f, 11f);
            heart.cubicTo(19f, 7f, 24f, 12f, 24f, 16f);
            heart.cubicTo(24f, 12f, 29f, 7f, 35f, 11f);
            heart.cubicTo(44f, 18f, 40f, 30f, 24f, 42f);
            heart.close();
            canvas.drawPath(heart, p);
          });
    }

    private static byte[] encodeSunPng() {
      return encodeSamplePng(
          48,
          48,
          canvas -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            canvas.drawOval(new RectF(14f, 14f, 34f, 34f), p);
          });
    }
  }
}
