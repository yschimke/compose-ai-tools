package ee.schimke.composeai.rccreation.dwf

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Multiplatform Android `<vector>` drawable representation that draws directly through Remote
 * Compose `RemotePath` commands without requiring an Android `Context` or `VectorDrawable`.
 */
@Immutable
public data class DwfVectorDrawable(
  public val viewportWidth: Float,
  public val viewportHeight: Float,
  public val widthDp: Float = viewportWidth,
  public val heightDp: Float = viewportHeight,
  public val tintColor: Int? = null,
  public val alpha: Float = 1f,
  public val nodes: List<Node> = emptyList(),
) {
  @Immutable
  public sealed interface Node {
    @Immutable
    public data class GroupNode(
      public val rotation: Float = 0f,
      public val pivotX: Float = 0f,
      public val pivotY: Float = 0f,
      public val scaleX: Float = 1f,
      public val scaleY: Float = 1f,
      public val translateX: Float = 0f,
      public val translateY: Float = 0f,
      public val children: List<Node> = emptyList(),
    ) : Node

    @Immutable
    public data class PathNode(
      public val pathData: String,
      public val fillColor: Int? = null,
      public val fillAlpha: Float = 1f,
      public val strokeColor: Int? = null,
      public val strokeWidth: Float = 0f,
      public val strokeAlpha: Float = 1f,
      public val strokeLineCap: DwfStrokeCap = DwfStrokeCap.BUTT,
      public val evenOddFill: Boolean = false,
    ) : Node
  }

  public companion object {
    /** Parses an Android `<vector>` XML document, returning `null` if it is not a `<vector>`. */
    public fun parseOrNull(
      xml: String,
      colorResolver: (String) -> Int? = { parseDwfColorArgb(it) },
    ): DwfVectorDrawable? {
      val root = XmlMiniDom.parseDocumentOrNull(xml) ?: return null
      if (root.localName != "vector") return null
      val vw = root.attr("viewportWidth")?.toFloatOrNull() ?: return null
      val vh = root.attr("viewportHeight")?.toFloatOrNull() ?: return null
      if (vw <= 0f || vh <= 0f) return null
      val widthDp = parseDimenNumber(root.attr("width")) ?: vw
      val heightDp = parseDimenNumber(root.attr("height")) ?: vh
      val tint = root.attr("tint")?.let(colorResolver)
      val alpha = root.attr("alpha")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
      return DwfVectorDrawable(
        viewportWidth = vw,
        viewportHeight = vh,
        widthDp = widthDp,
        heightDp = heightDp,
        tintColor = tint,
        alpha = alpha,
        nodes = root.childElements().mapNotNull { parseVectorNode(it, colorResolver) },
      )
    }

    private fun parseVectorNode(
      el: XmlElement,
      colorResolver: (String) -> Int?,
    ): Node? =
      when (el.localName) {
        "group" ->
          Node.GroupNode(
            rotation = el.attr("rotation")?.toFloatOrNull() ?: 0f,
            pivotX = el.attr("pivotX")?.toFloatOrNull() ?: 0f,
            pivotY = el.attr("pivotY")?.toFloatOrNull() ?: 0f,
            scaleX = el.attr("scaleX")?.toFloatOrNull() ?: 1f,
            scaleY = el.attr("scaleY")?.toFloatOrNull() ?: 1f,
            translateX = el.attr("translateX")?.toFloatOrNull() ?: 0f,
            translateY = el.attr("translateY")?.toFloatOrNull() ?: 0f,
            children = el.childElements().mapNotNull { parseVectorNode(it, colorResolver) },
          )
        "path" -> {
          val pathData = el.attr("pathData")?.trim().orEmpty()
          if (pathData.isEmpty()) null
          else {
            val cap =
              when (el.attr("strokeLineCap")?.uppercase()) {
                "ROUND" -> DwfStrokeCap.ROUND
                "SQUARE" -> DwfStrokeCap.SQUARE
                else -> DwfStrokeCap.BUTT
              }
            Node.PathNode(
              pathData = pathData,
              fillColor = el.attr("fillColor")?.let(colorResolver),
              fillAlpha = el.attr("fillAlpha")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
              strokeColor = el.attr("strokeColor")?.let(colorResolver),
              strokeWidth = el.attr("strokeWidth")?.toFloatOrNull() ?: 0f,
              strokeAlpha = el.attr("strokeAlpha")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
              strokeLineCap = cap,
              evenOddFill = el.attr("fillType")?.equals("evenOdd", ignoreCase = true) == true,
            )
          }
        }
        else -> null
      }

    private fun parseDimenNumber(raw: String?): Float? =
      raw
        ?.trim()
        ?.removeSuffix("dp")
        ?.removeSuffix("dip")
        ?.removeSuffix("px")
        ?.removeSuffix("sp")
        ?.trim()
        ?.toFloatOrNull()
  }
}

/**
 * Resolves external Android/Wear resources referenced from a Declarative Watch Face (`@drawable/x`,
 * `ic_hand_hour`, `@color/accent`, `@string/label`, `@font/outfit`).
 */
public interface DwfResourceResolver {
  /** Resolves a bitmap resource by name (such as `"hand_hour"` or `"@drawable/hand_hour"`). */
  public fun resolveImage(name: String): ImageBitmap? = null

  /** Resolves an Android `<vector>` drawable resource by name when not rasterized as a bitmap. */
  public fun resolveVectorDrawable(name: String): DwfVectorDrawable? = null

  /** Resolves a `@color/...` reference to a packed ARGB integer. */
  public fun resolveColor(name: String): Int? = null

  /** Resolves a `@string/...` reference. */
  public fun resolveString(name: String): String? = null

  /** Resolves a `@font/...` or custom font family reference to a Remote Compose typeface name. */
  public fun resolveFontFamily(name: String): String? = null

  public companion object {
    public val Empty: DwfResourceResolver = object : DwfResourceResolver {}
  }
}

/**
 * In-memory [DwfResourceResolver] backed by maps of decoded bitmaps, vectors, colors and strings.
 */
public class DwfMapResourceResolver(
  private val images: Map<String, ImageBitmap> = emptyMap(),
  private val encodedImages: Map<String, ByteArray> = emptyMap(),
  private val vectors: Map<String, DwfVectorDrawable> = emptyMap(),
  private val colors: Map<String, Int> = emptyMap(),
  private val strings: Map<String, String> = emptyMap(),
  private val fonts: Map<String, String> = emptyMap(),
) : DwfResourceResolver {
  private val normalizedImages = images.mapKeys { normalizeDwfResourceKey(it.key) }
  private val normalizedEncoded = encodedImages.mapKeys { normalizeDwfResourceKey(it.key) }
  private val normalizedVectors = vectors.mapKeys { normalizeDwfResourceKey(it.key) }
  private val normalizedColors = colors.mapKeys { normalizeDwfResourceKey(it.key) }
  private val normalizedStrings = strings.mapKeys { normalizeDwfResourceKey(it.key) }
  private val normalizedFonts = fonts.mapKeys { normalizeDwfResourceKey(it.key) }
  private val decodedCache = HashMap<String, ImageBitmap?>()

  override fun resolveImage(name: String): ImageBitmap? {
    val key = normalizeDwfResourceKey(name)
    normalizedImages[key]?.let {
      return it
    }
    if (decodedCache.containsKey(key)) return decodedCache[key]
    val decoded = normalizedEncoded[key]?.let(::decodeDwfImageBitmap)
    decodedCache[key] = decoded
    return decoded
  }

  override fun resolveVectorDrawable(name: String): DwfVectorDrawable? =
    normalizedVectors[normalizeDwfResourceKey(name)]

  override fun resolveColor(name: String): Int? = normalizedColors[normalizeDwfResourceKey(name)]

  override fun resolveString(name: String): String? =
    normalizedStrings[normalizeDwfResourceKey(name)]

  override fun resolveFontFamily(name: String): String? =
    normalizedFonts[normalizeDwfResourceKey(name)]
}

/** Default sample values for a complication slot before a host overrides its `USER:*` variables. */
@Immutable
public data class DwfComplicationSeed(
  public val type: String = "SHORT_TEXT",
  public val text: String = "72",
  public val title: String = "BPM",
  public val value: Float = 72f,
  public val min: Float = 0f,
  public val max: Float = 100f,
  /** Optional resource or `builtin:*` icon name for `[COMPLICATION.MONOCHROMATIC_IMAGE]`. */
  public val monochromaticImage: String? = null,
  /** Optional resource or `builtin:*` image name for `[COMPLICATION.SMALL_IMAGE]`. */
  public val smallImage: String? = null,
  /** Optional resource or `builtin:*` background photo name for `[COMPLICATION.PHOTO_IMAGE]`. */
  public val photoImage: String? = null,
  /** Optional ARGB color ramp for `[COMPLICATION.RANGED_VALUE_COLORS]`. */
  public val rangedValueColors: List<Int> = emptyList(),
  /** Whether `[COMPLICATION.RANGED_VALUE_COLORS_INTERPOLATE]` is `true`. */
  public val rangedValueColorsInterpolate: Boolean = false,
  /** Optional ARGB color ramp for `[COMPLICATION.GOAL_PROGRESS_COLORS]`. */
  public val goalProgressColors: List<Int> = emptyList(),
  /** Whether `[COMPLICATION.GOAL_PROGRESS_COLORS_INTERPOLATE]` is `true`. */
  public val goalProgressColorsInterpolate: Boolean = false,
  /** Segment ARGB colors for `[COMPLICATION.WEIGHTED_ELEMENTS_COLORS]`. */
  public val weightedElementsColors: List<Int> = emptyList(),
  /** Segment relative weights for `[COMPLICATION.WEIGHTED_ELEMENTS_WEIGHTS]`. */
  public val weightedElementsWeights: List<Float> = emptyList(),
)

/** Seed values for `[WEATHER.*]`, `[WEATHER.HOURS.*]`, and `[WEATHER.DAYS.*]` data sources. */
@Immutable
public data class DwfWeatherSeed(
  public val isAvailable: Boolean = true,
  public val isDay: Boolean = true,
  public val condition: Int = 14,
  public val conditionName: String = "Partly Cloudy",
  public val temperature: Float = 22f,
  public val temperatureLow: Float? = null,
  public val temperatureHigh: Float? = null,
  /** `1` for Celsius, `0` for Fahrenheit. */
  public val temperatureUnit: Int = 1,
  public val chanceOfPrecipitation: Int = 15,
  public val uvIndex: Int = 5,
  /** Optional explicit formatted string returned by `icuText(..., [WEATHER.LAST_UPDATED])`. */
  public val lastUpdatedText: String = "",
  /** Hourly forecast overrides keyed by 1-based hour offset (`1..24`). */
  public val hours: Map<Int, Hour> = emptyMap(),
  /** Daily forecast overrides keyed by 1-based day offset (`1..7`). */
  public val days: Map<Int, Day> = emptyMap(),
) {
  @Immutable
  public data class Hour(
    public val isAvailable: Boolean = true,
    public val isDay: Boolean = true,
    public val condition: Int = 14,
    public val temperature: Float = 22f,
    public val uvIndex: Int = 4,
    public val chanceOfPrecipitation: Int = 10,
  )

  @Immutable
  public data class Day(
    public val isAvailable: Boolean = true,
    public val conditionDay: Int = 8,
    public val conditionNight: Int = 1,
    public val temperatureHigh: Float = 25f,
    public val temperatureLow: Float = 16f,
    public val chanceOfPrecipitation: Int = 15,
    public val chanceOfPrecipitationNight: Int = 10,
  )
}

/**
 * Authoring/capture options controlling initial default values of named host variables in the
 * emitted Remote Compose document.
 */
@Immutable
public data class DwfRenderOptions(
  public val isAmbientDefault: Boolean = false,
  public val is24HourDefault: Boolean = true,
  public val isDstDefault: Boolean = false,
  public val batteryPercentDefault: Float = 85f,
  public val batteryChargingDefault: Boolean = false,
  public val unreadNotificationsDefault: Int = 2,
  public val stepCountDefault: Int = 4200,
  public val stepGoalDefault: Float = 10_000f,
  /**
   * Default `[HEART_RATE]` in BPM. Pass a negative value (for example `-1f`) to represent an
   * unavailable sensor reading so `[HEART_RATE] != null` evaluates to `false`.
   */
  public val heartRateDefault: Float = 72f,
  public val caloriesDefault: Float = 180f,
  public val distanceDefault: Float = 3150f,
  public val floorsDefault: Float = 4f,
  /** Optional `<Flavor id="...">` preset to activate before [configurationDefaults]. */
  public val flavorDefault: String? = null,
  /** Overrides for `<UserConfigurations>` keyed by configuration `id`. */
  public val configurationDefaults: Map<String, String> = emptyMap(),
  /** Initial complication slot values keyed by `slotId`. */
  public val complicationDefaults: Map<Int, DwfComplicationSeed> = emptyMap(),
  /** Initial weather data and hourly/daily forecast values. */
  public val weatherDefault: DwfWeatherSeed = DwfWeatherSeed(),
  /**
   * Whether to clip the watch face scene to its `<WatchFace clipShape="...">` (`CIRCLE` / rounded
   * `RECTANGLE`). Defaults to `true`.
   */
  public val applyWatchShapeClip: Boolean = true,
)

internal fun resolveBuiltinDwfComplicationVector(rawName: String): DwfVectorDrawable? {
  val key = rawName.trim().lowercase().removePrefix("builtin:")
  return when (key) {
    "battery",
    "ic_battery" ->
      DwfVectorDrawable(
        viewportWidth = 24f,
        viewportHeight = 24f,
        nodes =
          listOf(
            DwfVectorDrawable.Node.PathNode(
              pathData = "M10,2 L14,2 L14,4 L17,4 L17,22 L7,22 L7,4 L10,4 Z",
              fillColor = 0xFFFFFFFF.toInt(),
            ),
          ),
      )
    "watch_board",
    "ic_watch_board" ->
      DwfVectorDrawable(
        viewportWidth = 64f,
        viewportHeight = 64f,
        nodes =
          listOf(
            // Easel legs
            DwfVectorDrawable.Node.PathNode(
              pathData = "M24,46 L18,56 M32,46 L32,56 M40,46 L46,56",
              strokeColor = 0xFFCFD8DC.toInt(),
              strokeWidth = 2.5f,
              strokeLineCap = DwfStrokeCap.ROUND,
            ),
            // Blue presentation board body
            DwfVectorDrawable.Node.PathNode(
              pathData = "M10,14 L54,14 L54,45 L10,45 Z",
              fillColor = 0xFF1995D1.toInt(),
            ),
            // Top & bottom board bars
            DwfVectorDrawable.Node.PathNode(
              pathData = "M8,11 L56,11 L56,15 L8,15 Z M9,44 L55,44 L55,46.5 L9,46.5 Z",
              fillColor = 0xFFECEFF1.toInt(),
            ),
            // Watch strap top & bottom
            DwfVectorDrawable.Node.PathNode(
              pathData = "M28,17 L36,17 L36,21 L28,21 Z M28,38 L36,38 L36,42 L28,42 Z",
              fillColor = 0xFFFFFFFF.toInt(),
            ),
            // Watch case outline
            DwfVectorDrawable.Node.PathNode(
              pathData =
                "M25,21 L39,21 Q42,21 42,24 L42,35 Q42,38 39,38 L25,38 Q22,38 22,35 L22,24 Q22,21 25,21 Z",
              strokeColor = 0xFFFFFFFF.toInt(),
              strokeWidth = 2.4f,
            ),
          ),
      )
    "steps",
    "ic_steps" ->
      DwfVectorDrawable(
        viewportWidth = 24f,
        viewportHeight = 24f,
        nodes =
          listOf(
            DwfVectorDrawable.Node.PathNode(
              pathData =
                "M7,14 C5.5,14 4.5,12.5 4.5,10.5 C4.5,8 6,5 8,5 C10,5 11,7.5 11,10 C11,12.5 9,14 7,14 Z " +
                  "M17,19 C15,19 13.5,17.5 13.5,15 C13.5,12.5 14.5,10 16.5,10 C18.5,10 20,13 20,15.5 C20,17.5 18.5,19 17,19 Z",
              fillColor = 0xFFFFFFFF.toInt(),
            ),
          ),
      )
    "heart",
    "ic_heart" ->
      DwfVectorDrawable(
        viewportWidth = 24f,
        viewportHeight = 24f,
        nodes =
          listOf(
            DwfVectorDrawable.Node.PathNode(
              pathData =
                "M12,21 L10.55,19.7 C5.4,15.0 2,11.9 2,8.1 C2,5.0 4.4,2.6 7.5,2.6 C9.2,2.6 10.9,3.4 12,4.7 C13.1,3.4 14.8,2.6 16.5,2.6 C19.6,2.6 22,5.0 22,8.1 C22,11.9 18.6,15.0 13.45,19.7 Z",
              fillColor = 0xFFFFFFFF.toInt(),
            ),
          ),
      )
    "sun",
    "ic_sun" ->
      DwfVectorDrawable(
        viewportWidth = 24f,
        viewportHeight = 24f,
        nodes =
          listOf(
            DwfVectorDrawable.Node.PathNode(
              pathData = "M12,6 A6,6 0 1,1 11.99,6 Z",
              fillColor = 0xFFFFD54F.toInt(),
            ),
          ),
      )
    "photo_aquarium" ->
      DwfVectorDrawable(
        viewportWidth = 450f,
        viewportHeight = 450f,
        nodes =
          listOf(
            // Deep aquarium blue backdrop
            DwfVectorDrawable.Node.PathNode(
              pathData = "M0,0 L450,0 L450,450 L0,450 Z",
              fillColor = 0xFF0B6AD4.toInt(),
            ),
            // Lighter upper water glow
            DwfVectorDrawable.Node.PathNode(
              pathData = "M0,0 L450,0 L450,250 Q225,300 0,220 Z",
              fillColor = 0xFF1E88E5.toInt(),
              fillAlpha = 0.85f,
            ),
            // Warm golden jellyfish bells
            DwfVectorDrawable.Node.PathNode(
              pathData =
                "M110,95 C110,55 175,55 175,100 C175,125 110,125 110,95 Z " +
                  "M290,55 C290,25 355,25 355,60 C355,80 290,80 290,55 Z " +
                  "M385,175 C385,140 445,140 445,180 C445,205 385,205 385,175 Z",
              fillColor = 0xFFD9A45B.toInt(),
              fillAlpha = 0.72f,
            ),
            // Lower aquarium ledge silhouette
            DwfVectorDrawable.Node.PathNode(
              pathData = "M0,210 L450,255 L450,450 L0,450 Z",
              fillColor = 0xFF0A2E5C.toInt(),
              fillAlpha = 0.88f,
            ),
            // Foreground dark silhouette on left/bottom
            DwfVectorDrawable.Node.PathNode(
              pathData =
                "M0,105 C75,95 125,145 120,220 L250,245 L300,155 L315,255 C280,350 220,420 155,450 L0,450 Z",
              fillColor = 0xFF041428.toInt(),
              fillAlpha = 0.92f,
            ),
          ),
      )
    else -> null
  }
}

/** Normalizes `@drawable/foo`, `res/drawable/foo.png`, or `foo` to `"foo"`. */
public fun normalizeDwfResourceKey(raw: String): String {
  var s = raw.trim()
  if (s.startsWith("@")) {
    s = s.substringAfter('/', s.removePrefix("@"))
  } else if ('/' in s) {
    s = s.substringAfterLast('/')
  }
  if ('.' in s) {
    s = s.substringBeforeLast('.')
  }
  return s
}

/**
 * Parses a DWF/Android hex color literal (`#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB`, `0xAARRGGBB`) or
 * basic named color into a packed ARGB `Int`, returning `null` if [raw] is an expression or
 * resource reference.
 */
public fun parseDwfColorArgb(raw: String, resources: DwfResourceResolver? = null): Int? {
  val trimmed = raw.trim()
  if (trimmed.isEmpty()) return null
  if (trimmed.startsWith("@color/") || trimmed.startsWith("@android:color/")) {
    resources?.resolveColor(trimmed)?.let {
      return it
    }
    val named = trimmed.substringAfter('/')
    return NAMED_COLORS[named.lowercase()]
  }
  if (trimmed.startsWith("#")) {
    val hex = trimmed.substring(1)
    return when (hex.length) {
      3 -> {
        val r = hex[0].digitToIntOrNull(16) ?: return null
        val g = hex[1].digitToIntOrNull(16) ?: return null
        val b = hex[2].digitToIntOrNull(16) ?: return null
        (0xFF shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
      }
      4 -> {
        val a = hex[0].digitToIntOrNull(16) ?: return null
        val r = hex[1].digitToIntOrNull(16) ?: return null
        val g = hex[2].digitToIntOrNull(16) ?: return null
        val b = hex[3].digitToIntOrNull(16) ?: return null
        ((a * 17) shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
      }
      6 -> {
        val rgb = hex.toLongOrNull(16)?.toInt() ?: return null
        (0xFF shl 24) or rgb
      }
      8 -> hex.toULongOrNull(16)?.toInt()
      else -> null
    }
  }
  if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
    val hex = trimmed.substring(2)
    return when (hex.length) {
      6 -> ((0xFFL shl 24) or (hex.toLongOrNull(16) ?: return null)).toInt()
      8 -> hex.toULongOrNull(16)?.toInt()
      else -> null
    }
  }
  return NAMED_COLORS[trimmed.lowercase()]
}

private val NAMED_COLORS: Map<String, Int> =
  mapOf(
    "black" to 0xFF000000.toInt(),
    "white" to 0xFFFFFFFF.toInt(),
    "transparent" to 0x00000000,
    "red" to 0xFFFF0000.toInt(),
    "green" to 0xFF00FF00.toInt(),
    "blue" to 0xFF0000FF.toInt(),
    "yellow" to 0xFFFFFF00.toInt(),
    "cyan" to 0xFF00FFFF.toInt(),
    "magenta" to 0xFFFF00FF.toInt(),
    "gray" to 0xFF888888.toInt(),
    "grey" to 0xFF888888.toInt(),
    "darkgray" to 0xFF444444.toInt(),
    "lightgray" to 0xFFCCCCCC.toInt(),
  )
