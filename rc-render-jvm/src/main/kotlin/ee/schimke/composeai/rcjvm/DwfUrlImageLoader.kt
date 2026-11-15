package ee.schimke.composeai.rcjvm

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import ee.schimke.composeai.rccreation.dwf.DwfVariables
import ee.schimke.composeai.rccreation.dwf.normalizeDwfImageUrl
import ee.schimke.composeai.rcplayer.compose.RcImageLoader
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import org.jetbrains.skia.Image

private const val MAX_URL_IMAGE_BYTES = 32 * 1024 * 1024
private const val HTTP_TIMEOUT_MS = 10_000
private const val MAX_REDIRECTS = 5

/**
 * Configurable [RcImageLoader] that resolves `ENCODING_URL` (`https://`, `http://`, `file:`,
 * `data:image/...`, and registered synthetic URLs) and `ENCODING_FILE` bitmaps synchronously with
 * in-memory caching so headless `ImageComposeScene` renders (`scene.render(0L)`) and live players
 * both draw URL-backed images on the first frame.
 */
public class ConfiguredUrlRcImageLoader(
  private val urlProviders: Map<String, () -> ByteArray> = emptyMap(),
  private val allowNetwork: Boolean = true,
) : RcImageLoader {

  private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()
  private val failedKeys = ConcurrentHashMap.newKeySet<String>()

  public fun withUrls(extra: Map<String, () -> ByteArray>): ConfiguredUrlRcImageLoader =
    ConfiguredUrlRcImageLoader(urlProviders = urlProviders + extra, allowNetwork = allowNetwork)

  public fun withPngBytes(extra: Map<String, ByteArray>): ConfiguredUrlRcImageLoader =
    withUrls(extra.mapValues { (_, bytes) -> { bytes } })

  override fun cached(bitmap: RcBitmapData): ImageBitmap? = resolveBitmap(bitmap)

  override suspend fun load(bitmap: RcBitmapData): ImageBitmap? = resolveBitmap(bitmap)

  private fun resolveBitmap(data: RcBitmapData): ImageBitmap? {
    val rawRef =
      when (data.encoding) {
        RcBitmapData.ENCODING_URL -> String(data.data, StandardCharsets.UTF_8).trim()
        RcBitmapData.ENCODING_FILE -> {
          val path = String(data.data, StandardCharsets.UTF_8).trim()
          if (path.isEmpty()) return null
          File(path).toURI().toString()
        }
        else -> return null
      }
    if (rawRef.isEmpty()) return null
    val key = normalizeDwfImageUrl(rawRef) ?: rawRef
    bitmapCache[key]?.let {
      return it
    }
    if (key in failedKeys) return null
    val bytes = runCatching { loadBytes(key) }.getOrNull()?.takeIf { it.isNotEmpty() }
    if (bytes == null) {
      failedKeys += key
      return null
    }
    val decoded = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    if (decoded == null) {
      failedKeys += key
      return null
    }
    bitmapCache[key] = decoded
    return decoded
  }

  private fun loadBytes(url: String): ByteArray? {
    urlProviders[url]?.let {
      return it()
    }
    val lower = url.lowercase()
    if (lower.startsWith("data:image/")) {
      val commaIdx = url.indexOf(',')
      if (commaIdx < 0) return null
      val meta = url.substring(0, commaIdx).lowercase()
      val payload = url.substring(commaIdx + 1)
      return if (";base64" in meta) {
        Base64.getDecoder().decode(payload.trim())
      } else {
        java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8)
          .toByteArray(StandardCharsets.ISO_8859_1)
      }
    }
    if (lower.startsWith("file:")) {
      val file = File(URI.create(url))
      return if (file.isFile && file.length() <= MAX_URL_IMAGE_BYTES) file.readBytes() else null
    }
    if (allowNetwork && (lower.startsWith("http://") || lower.startsWith("https://"))) {
      return fetchHttpBytes(url)
    }
    return null
  }

  private fun fetchHttpBytes(initialUrl: String): ByteArray? {
    var current = initialUrl
    repeat(MAX_REDIRECTS + 1) {
      val uri = URI.create(current)
      val host = uri.host?.lowercase().orEmpty()
      val isLoopback =
        host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
      val conn =
        (if (isLoopback) uri.toURL().openConnection(java.net.Proxy.NO_PROXY)
        else uri.toURL().openConnection())
          as HttpURLConnection
      conn.connectTimeout = HTTP_TIMEOUT_MS
      conn.readTimeout = HTTP_TIMEOUT_MS
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("User-Agent", "compose-preview-dwf-loader/1.0")
      try {
        val status = conn.responseCode
        if (status in intArrayOf(301, 302, 303, 307, 308)) {
          val location = conn.getHeaderField("Location")?.takeIf { it.isNotBlank() } ?: return null
          current = URI.create(current).resolve(location).toString()
          return@repeat
        }
        if (status !in 200..299) return null
        conn.inputStream.use { input ->
          val out = ByteArrayOutputStream()
          val buf = ByteArray(8192)
          var total = 0
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_URL_IMAGE_BYTES) {
              error("Image URL exceeded $MAX_URL_IMAGE_BYTES bytes: $initialUrl")
            }
            out.write(buf, 0, n)
          }
          return out.toByteArray()
        }
      } finally {
        conn.disconnect()
      }
    }
    return null
  }
}

private val defaultDwfImageLoaderInstance: ConfiguredUrlRcImageLoader by lazy {
  ConfiguredUrlRcImageLoader(urlProviders = DwfSampleUrlAssets.providers, allowNetwork = true)
}

/** Default configured [RcImageLoader] for DWF and JVM RemoteCompose renders. */
public fun defaultDwfImageLoader(): ConfiguredUrlRcImageLoader = defaultDwfImageLoaderInstance

internal object DwfSampleUrlAssets {
  private const val BASE = DwfVariables.DEFAULT_IMAGE_URL_BASE

  const val PHOTO_AQUARIUM_URL: String = "${BASE}photo_aquarium.png"
  const val WATCH_BOARD_URL: String = "${BASE}watch_board.png"
  const val BATTERY_ICON_URL: String = "${BASE}battery.png"
  const val STEPS_ICON_URL: String = "${BASE}steps.png"
  const val HEART_ICON_URL: String = "${BASE}heart.png"
  const val SUN_ICON_URL: String = "${BASE}sun.png"

  val providers: Map<String, () -> ByteArray> =
    mapOf(
      DwfVariables.DEFAULT_MONOCHROMATIC_IMAGE_URL to ::renderDefaultMonochromaticPng,
      DwfVariables.DEFAULT_SMALL_IMAGE_URL to ::renderDefaultSmallPng,
      DwfVariables.DEFAULT_PHOTO_IMAGE_URL to ::renderDefaultPhotoPng,
      PHOTO_AQUARIUM_URL to ::renderAquariumPhotoPng,
      WATCH_BOARD_URL to ::renderWatchBoardPng,
      BATTERY_ICON_URL to ::renderBatteryIconPng,
      STEPS_ICON_URL to ::renderStepsIconPng,
      HEART_ICON_URL to ::renderHeartIconPng,
      SUN_ICON_URL to ::renderSunIconPng,
    )

  private fun encodePng(width: Int, height: Int, draw: (java.awt.Graphics2D) -> Unit): ByteArray {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      draw(g)
    } finally {
      g.dispose()
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "PNG", out)
    return out.toByteArray()
  }

  private fun renderDefaultMonochromaticPng(): ByteArray =
    encodePng(48, 48) { g ->
      g.color = Color.WHITE
      val star = GeneralPath()
      star.moveTo(24f, 4f)
      star.lineTo(29.6f, 18.4f)
      star.lineTo(44f, 24f)
      star.lineTo(29.6f, 29.6f)
      star.lineTo(24f, 44f)
      star.lineTo(18.4f, 29.6f)
      star.lineTo(4f, 24f)
      star.lineTo(18.4f, 18.4f)
      star.closePath()
      g.fill(star)
    }

  private fun renderDefaultSmallPng(): ByteArray =
    encodePng(96, 96) { g ->
      g.paint = GradientPaint(0f, 0f, Color(0x3D, 0x5A, 0xFE), 96f, 96f, Color(0x00, 0xBF, 0xA5))
      g.fill(Ellipse2D.Float(4f, 4f, 88f, 88f))
      g.color = Color.WHITE
      val bolt = GeneralPath()
      bolt.moveTo(52f, 16f)
      bolt.lineTo(28f, 52f)
      bolt.lineTo(46f, 52f)
      bolt.lineTo(42f, 80f)
      bolt.lineTo(68f, 44f)
      bolt.lineTo(50f, 44f)
      bolt.closePath()
      g.fill(bolt)
    }

  private fun renderDefaultPhotoPng(): ByteArray =
    encodePng(450, 450) { g ->
      g.paint = GradientPaint(0f, 0f, Color(0x18, 0x2B, 0x49), 0f, 450f, Color(0x09, 0x10, 0x1B))
      g.fillRect(0, 0, 450, 450)
      g.color = Color(0x3D, 0x5A, 0xFE, 120)
      g.fill(Ellipse2D.Float(-90f, -150f, 630f, 500f))
      g.color = Color(0xFF, 0xD1, 0x80, 195)
      g.fill(Ellipse2D.Float(243f, 72f, 126f, 126f))
      g.color = Color(0x1E, 0x3A, 0x5F, 210)
      g.fill(Ellipse2D.Float(-135f, 252f, 550f, 355f))
      g.color = Color(0x09, 0x10, 0x1B, 230)
      g.fill(Ellipse2D.Float(54f, 292f, 555f, 340f))
    }

  private fun renderAquariumPhotoPng(): ByteArray =
    encodePng(450, 450) { g ->
      g.color = Color(0x0B, 0x6A, 0xD4)
      g.fillRect(0, 0, 450, 450)
      g.color = Color(0x1E, 0x88, 0xE5, 217)
      val upper = GeneralPath()
      upper.moveTo(0f, 0f)
      upper.lineTo(450f, 0f)
      upper.lineTo(450f, 250f)
      upper.quadTo(225f, 300f, 0f, 220f)
      upper.closePath()
      g.fill(upper)
      g.color = Color(0xD9, 0xA4, 0x5B, 184)
      val bells = GeneralPath()
      bells.moveTo(110f, 95f)
      bells.curveTo(110f, 55f, 175f, 55f, 175f, 100f)
      bells.curveTo(175f, 125f, 110f, 125f, 110f, 95f)
      bells.closePath()
      bells.moveTo(290f, 55f)
      bells.curveTo(290f, 25f, 355f, 25f, 355f, 60f)
      bells.curveTo(355f, 80f, 290f, 80f, 290f, 55f)
      bells.closePath()
      bells.moveTo(385f, 175f)
      bells.curveTo(385f, 140f, 445f, 140f, 445f, 180f)
      bells.curveTo(445f, 205f, 385f, 205f, 385f, 175f)
      bells.closePath()
      g.fill(bells)
      g.color = Color(0x0A, 0x2E, 0x5C, 224)
      val ledge = GeneralPath()
      ledge.moveTo(0f, 210f)
      ledge.lineTo(450f, 255f)
      ledge.lineTo(450f, 450f)
      ledge.lineTo(0f, 450f)
      ledge.closePath()
      g.fill(ledge)
      g.color = Color(0x04, 0x14, 0x28, 235)
      val fg = GeneralPath()
      fg.moveTo(0f, 105f)
      fg.curveTo(75f, 95f, 125f, 145f, 120f, 220f)
      fg.lineTo(250f, 245f)
      fg.lineTo(300f, 155f)
      fg.lineTo(315f, 255f)
      fg.curveTo(280f, 350f, 220f, 420f, 155f, 450f)
      fg.lineTo(0f, 450f)
      fg.closePath()
      g.fill(fg)
    }

  private fun renderWatchBoardPng(): ByteArray =
    encodePng(64, 64) { g ->
      g.color = Color(0xCF, 0xD8, 0xDC)
      g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
      g.drawLine(24, 46, 18, 56)
      g.drawLine(32, 46, 32, 56)
      g.drawLine(40, 46, 46, 56)
      g.color = Color(0x19, 0x95, 0xD1)
      g.fillRect(10, 14, 44, 31)
      g.color = Color(0xEC, 0xEF, 0xF1)
      g.fillRect(8, 11, 48, 4)
      g.fillRect(9, 44, 46, 3)
      g.color = Color.WHITE
      g.fillRect(28, 17, 8, 4)
      g.fillRect(28, 38, 8, 4)
      g.stroke = BasicStroke(2.4f)
      g.draw(RoundRectangle2D.Float(22f, 21f, 20f, 17f, 6f, 6f))
    }

  private fun renderBatteryIconPng(): ByteArray =
    encodePng(48, 48) { g ->
      g.color = Color.WHITE
      g.fillRect(20, 4, 8, 4)
      g.fillRect(14, 8, 20, 36)
    }

  private fun renderStepsIconPng(): ByteArray =
    encodePng(48, 48) { g ->
      g.color = Color.WHITE
      g.fill(Ellipse2D.Float(9f, 10f, 13f, 18f))
      g.fill(Ellipse2D.Float(27f, 20f, 13f, 18f))
    }

  private fun renderHeartIconPng(): ByteArray =
    encodePng(48, 48) { g ->
      g.color = Color.WHITE
      val heart = GeneralPath()
      heart.moveTo(24f, 42f)
      heart.lineTo(21.1f, 39.4f)
      heart.curveTo(10.8f, 30f, 4f, 23.8f, 4f, 16.2f)
      heart.curveTo(4f, 10f, 8.8f, 5.2f, 15f, 5.2f)
      heart.curveTo(18.4f, 5.2f, 21.8f, 6.8f, 24f, 9.4f)
      heart.curveTo(26.2f, 6.8f, 29.6f, 5.2f, 33f, 5.2f)
      heart.curveTo(39.2f, 5.2f, 44f, 10f, 44f, 16.2f)
      heart.curveTo(44f, 23.8f, 37.2f, 30f, 26.9f, 39.4f)
      heart.closePath()
      g.fill(heart)
    }

  private fun renderSunIconPng(): ByteArray =
    encodePng(48, 48) { g ->
      g.color = Color(0xFF, 0xD5, 0x4F)
      g.fill(Ellipse2D.Float(12f, 12f, 24f, 24f))
    }
}
