package ee.schimke.composeai.dwf

import ee.schimke.composeai.rccreation.dwf.DwfVariables
import ee.schimke.composeai.rcplayer.protocol.RcColorConstant
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcIntegerConstant
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcTextData

/** A typed value a named variable holds (its default, or what a host seeds). */
public sealed interface WffValue {
  public data class Text(public val value: String) : WffValue

  public data class Float(public val value: kotlin.Float) : WffValue

  public data class Int(public val value: kotlin.Int) : WffValue

  /** Carried as `0`/`1` on the wire; the player's named-value type is an integer. */
  public data class Bool(public val value: Boolean) : WffValue

  /** Packed `0xAARRGGBB`. */
  public data class Color(public val argb: kotlin.Int) : WffValue
}

/** What a named value is for, so a host can group its preview controls. */
public enum class NamedValueCategory {
  /** `dwf.is_ambient`: the always-on display switch. */
  AMBIENT,
  /** `dwf.is_24_hour`, `dwf.is_dst`: clock presentation. The time itself is not a named value. */
  CLOCK,
  /** `dwf.battery_*`. */
  BATTERY,
  /** `dwf.unread_notifications`. */
  NOTIFICATIONS,
  /** `metrics.*` and `dwf.step_goal`: health and activity data sources. */
  METRIC,
  /** `dwf.accel_angle_*`: motion sensors. */
  SENSOR,
  /** `dwf.weather.*`. */
  WEATHER,
  /** `dwf.config.<id>`: a `<UserConfigurations>` choice (list, boolean, color or photo). */
  CONFIGURATION,
  /** `dwf.color.<id>.<n>`: the resolved theme colors of a `<ColorConfiguration>`. */
  COLOR_THEME,
  /** `dwf.complication.<slot>.*`: the data a `<ComplicationSlot>` renders. */
  COMPLICATION,
  /** Declared by the document but not one of the above. */
  OTHER,
}

/** The wire type of a [NamedValueSpec]. */
public enum class NamedValueType {
  TEXT,
  FLOAT,
  INT,
  BOOLEAN,
  COLOR,
}

/**
 * One named variable the compiled `.rc` reads, with the default the document was compiled against.
 *
 * A host drives the preview by seeding these into the Remote Compose player's `namedValues` map
 * (keyed by [name]) — no recompilation. [name] is the document's `USER:`-domain variable without
 * the `USER:` prefix, which is the key the players take.
 *
 * Only variables the document **actually reads** are listed: a watch face with no complication
 * slots has no `dwf.complication.<slot>.…` entries. Time is deliberately absent — the clock comes
 * from the player's time source, not a named value.
 */
public class NamedValueSpec(
  public val name: String,
  public val type: NamedValueType,
  public val default: WffValue,
  public val category: NamedValueCategory,
  public val description: String,
) {
  override fun toString(): String = "NamedValueSpec($name: $type = $default, $category)"

  override fun equals(other: Any?): Boolean =
    other is NamedValueSpec &&
      name == other.name &&
      type == other.type &&
      default == other.default &&
      category == other.category &&
      description == other.description

  override fun hashCode(): kotlin.Int = name.hashCode()
}

/**
 * Reads the `USER:` named variables of a compiled `.rc` and their defaults. [booleanConfigIds] are
 * the ids of the watch face's `<BooleanConfiguration>`s: the player carries booleans as integers,
 * so those (and the well-known `dwf.*` flags) are what let a default read back as [WffValue.Bool].
 * [WffRemoteCompose.compile] does this for you; this is for a host holding only `.rc` bytes.
 */
public fun extractNamedValueSpecs(
  rcBytes: ByteArray,
  booleanConfigIds: Set<String> = emptySet(),
): List<NamedValueSpec> = extractNamedValueSpecs(RcDocumentCodec.decode(rcBytes), booleanConfigIds)

internal fun extractNamedValueSpecs(
  rcDoc: RcDocument,
  booleanConfigIds: Set<String>,
): List<NamedValueSpec> {
  val texts = HashMap<kotlin.Int, String>()
  val floats = HashMap<kotlin.Int, kotlin.Float>()
  val ints = HashMap<kotlin.Int, kotlin.Int>()
  val colors = HashMap<kotlin.Int, kotlin.Int>()

  val booleanConfigKeys = booleanConfigIds.map(DwfVariables::configKey).toSet()
  val knownBooleanKeys =
    setOf(
      DwfVariables.IS_AMBIENT,
      DwfVariables.IS_24_HOUR,
      DwfVariables.IS_DST,
      DwfVariables.BATTERY_CHARGING,
      DwfVariables.BATTERY_IS_LOW,
    ) + booleanConfigKeys

  for (op in rcDoc.operations) {
    when (op) {
      is RcTextData -> texts[op.id] = op.text
      is RcFloatConstant -> floats[op.id] = op.value.value
      is RcIntegerConstant -> ints[op.id] = op.value
      is RcColorConstant -> colors[op.id] = op.argb
      else -> {}
    }
  }

  val out = LinkedHashMap<String, NamedValueSpec>()
  for (op in rcDoc.operations) {
    if (op !is RcNamedVariable) continue
    val rawName = op.name
    // Other domains (`SYSTEM:`, `FLOAT:` ...) are the player's own, not host-seedable.
    if (rawName.contains(':') && !rawName.startsWith("USER:")) continue
    val name = rawName.removePrefix("USER:")
    if (name.isBlank() || name in out) continue
    val (type, default) =
      when (op.type) {
        RcNamedVariable.STRING_TYPE -> NamedValueType.TEXT to WffValue.Text(texts[op.id].orEmpty())
        RcNamedVariable.FLOAT_TYPE -> NamedValueType.FLOAT to WffValue.Float(floats[op.id] ?: 0f)
        RcNamedVariable.INT_TYPE -> {
          val v = ints[op.id] ?: 0
          val isBoolean =
            name in knownBooleanKeys ||
              name.endsWith(".is_available") ||
              name.endsWith(".is_day") ||
              (name.startsWith("dwf.complication.") &&
                (name.contains(".has_") || name.endsWith("_interpolate")))
          if (isBoolean) NamedValueType.BOOLEAN to WffValue.Bool(v != 0)
          else NamedValueType.INT to WffValue.Int(v)
        }
        RcNamedVariable.COLOR_TYPE ->
          NamedValueType.COLOR to WffValue.Color(colors[op.id] ?: 0xFFFFFFFF.toInt())
        else -> continue
      }
    val (category, description) = describeNamedValue(name)
    out[name] = NamedValueSpec(name, type, default, category, description)
  }
  return out.values.toList()
}

private val complicationField = Regex("""^dwf\.complication\.(\d+)\.(.+)$""")
private val weatherHourField = Regex("""^dwf\.weather\.hours\.(\d+)\.(.+)$""")
private val weatherDayField = Regex("""^dwf\.weather\.days\.(\d+)\.(.+)$""")
private val colorThemeField = Regex("""^dwf\.color\.(.+)\.(\d+)$""")

/** Category and one-line meaning for a named-value [name]; the catalogue is `DwfVariables`. */
internal fun describeNamedValue(name: String): Pair<NamedValueCategory, String> {
  when (name) {
    DwfVariables.IS_AMBIENT ->
      return NamedValueCategory.AMBIENT to
        "True in ambient (always-on) mode; WFF `<Variant mode=\"AMBIENT\">` switches on it."
    DwfVariables.IS_24_HOUR ->
      return NamedValueCategory.CLOCK to "True for 24-hour clock presentation, false for 12-hour."
    DwfVariables.IS_DST ->
      return NamedValueCategory.CLOCK to "True while Daylight Saving Time is in effect."
    DwfVariables.BATTERY_PERCENT ->
      return NamedValueCategory.BATTERY to "Battery charge, 0..100 (`[BATTERY_PERCENT]`)."
    DwfVariables.BATTERY_CHARGING ->
      return NamedValueCategory.BATTERY to
        "True while the battery is charging (`[BATTERY_IS_CHARGING]`)."
    DwfVariables.BATTERY_IS_LOW ->
      return NamedValueCategory.BATTERY to
        "True when the battery is low, <= 15% (`[BATTERY_IS_LOW]`)."
    DwfVariables.UNREAD_NOTIFICATIONS ->
      return NamedValueCategory.NOTIFICATIONS to
        "Unread notification count (`[UNREAD_NOTIFICATION_COUNT]`)."
    DwfVariables.STEP_GOAL -> return NamedValueCategory.METRIC to "Daily step goal (`[STEP_GOAL]`)."
    DwfVariables.HEART_RATE ->
      return NamedValueCategory.METRIC to
        "Heart rate in BPM (`[HEART_RATE]`); a negative value means no reading."
    DwfVariables.STEP_COUNT -> return NamedValueCategory.METRIC to "Steps today (`[STEP_COUNT]`)."
    DwfVariables.CALORIES ->
      return NamedValueCategory.METRIC to "Active calories today (`[CALORIES]`)."
    DwfVariables.DISTANCE ->
      return NamedValueCategory.METRIC to "Distance today in meters (`[DISTANCE]`)."
    DwfVariables.FLOORS ->
      return NamedValueCategory.METRIC to "Flights of stairs today (`[FLOORS]`)."
    DwfVariables.ACCEL_ANGLE_X ->
      return NamedValueCategory.SENSOR to
        "Device tilt about X in degrees (`[ACCELEROMETER_ANGLE_X]`)."
    DwfVariables.ACCEL_ANGLE_Y ->
      return NamedValueCategory.SENSOR to
        "Device tilt about Y in degrees (`[ACCELEROMETER_ANGLE_Y]`)."
    DwfVariables.WEATHER_IS_AVAILABLE ->
      return NamedValueCategory.WEATHER to
        "True when weather data is available (`[WEATHER.IS_AVAILABLE]`)."
    DwfVariables.WEATHER_IS_DAY ->
      return NamedValueCategory.WEATHER to
        "True during daytime at the weather location (`[WEATHER.IS_DAY]`)."
    DwfVariables.WEATHER_CONDITION ->
      return NamedValueCategory.WEATHER to "Condition enum, 0..15 (`[WEATHER.CONDITION]`)."
    DwfVariables.WEATHER_CONDITION_NAME ->
      return NamedValueCategory.WEATHER to "Localized condition text (`[WEATHER.CONDITION_NAME]`)."
    DwfVariables.WEATHER_TEMPERATURE ->
      return NamedValueCategory.WEATHER to "Current temperature (`[WEATHER.TEMPERATURE]`)."
    DwfVariables.WEATHER_TEMPERATURE_HIGH ->
      return NamedValueCategory.WEATHER to "Daily high temperature (`[WEATHER.TEMPERATURE_HIGH]`)."
    DwfVariables.WEATHER_TEMPERATURE_LOW ->
      return NamedValueCategory.WEATHER to "Daily low temperature (`[WEATHER.TEMPERATURE_LOW]`)."
    DwfVariables.WEATHER_TEMPERATURE_UNIT ->
      return NamedValueCategory.WEATHER to
        "1 = Celsius, 0 = Fahrenheit (`[WEATHER.TEMPERATURE_UNIT]`)."
    DwfVariables.WEATHER_CHANCE_OF_PRECIPITATION ->
      return NamedValueCategory.WEATHER to
        "Chance of precipitation, 0..100 (`[WEATHER.CHANCE_OF_PRECIPITATION]`)."
    DwfVariables.WEATHER_UV_INDEX ->
      return NamedValueCategory.WEATHER to "Current UV index (`[WEATHER.UV_INDEX]`)."
    DwfVariables.WEATHER_LAST_UPDATED_TEXT ->
      return NamedValueCategory.WEATHER to
        "Preformatted text for `icuText(..., [WEATHER.LAST_UPDATED])`."
  }
  weatherHourField.matchEntire(name)?.let { m ->
    return NamedValueCategory.WEATHER to
      "Hourly forecast, +${m.groupValues[1]}h: ${m.groupValues[2].replace('_', ' ')}."
  }
  weatherDayField.matchEntire(name)?.let { m ->
    return NamedValueCategory.WEATHER to
      "Daily forecast, +${m.groupValues[1]}d: ${m.groupValues[2].replace('_', ' ')}."
  }
  if (name.startsWith("dwf.weather.")) return NamedValueCategory.WEATHER to "Weather data source."
  complicationField.matchEntire(name)?.let { m ->
    val slot = m.groupValues[1]
    val field = m.groupValues[2]
    val what =
      when {
        field == "text" -> "primary text (`[COMPLICATION.TEXT]`)"
        field == "title" -> "secondary title (`[COMPLICATION.TITLE]`)"
        field == "value" -> "ranged / goal value (`[COMPLICATION.RANGED_VALUE]`)"
        field == "min" -> "ranged minimum (`[COMPLICATION.RANGED_VALUE_MIN]`)"
        field == "max" -> "ranged maximum (`[COMPLICATION.RANGED_VALUE_MAX]`)"
        field == "type" ->
          "type code: 0 EMPTY, 1 SHORT_TEXT, 2 LONG_TEXT, 3 RANGED_VALUE, 4 MONOCHROMATIC_IMAGE, " +
            "5 SMALL_IMAGE, 6 PHOTO_IMAGE, 7 GOAL_PROGRESS, 8 WEIGHTED_ELEMENTS"
        field.startsWith("has_") ->
          "whether the slot supplies ${field.removePrefix("has_").replace('_', ' ')}"
        field.endsWith("_interpolate") ->
          "whether the ${field.removeSuffix("_interpolate").replace('_', ' ')} ramp interpolates"
        field == "color_count" -> "active color-stop count, 1..4"
        field.startsWith("color.") ->
          "color stop ${field.removePrefix("color.")} of the ramp / weighted stroke"
        field.startsWith("weight.") -> "weight of element ${field.removePrefix("weight.")}"
        field == "image_color" -> "tint for the slot's icon"
        field == "accent_color" -> "badge / backdrop accent for image types"
        else -> field.replace('_', ' ')
      }
    return NamedValueCategory.COMPLICATION to "Complication slot $slot: $what."
  }
  colorThemeField.matchEntire(name)?.let { m ->
    return NamedValueCategory.COLOR_THEME to
      "Resolved ARGB of color ${m.groupValues[2]} of `<ColorConfiguration id=\"${m.groupValues[1]}\">`."
  }
  if (name.startsWith("dwf.config.")) {
    return NamedValueCategory.CONFIGURATION to
      "Selected value of `<UserConfigurations>` entry `${name.removePrefix("dwf.config.")}`: the " +
        "zero-based ordinal of the chosen option (list / color, an integer, not the option id), " +
        "0 / 1 (boolean), or the photo (photos). Colors read as [CONFIGURATION.id.n] come from " +
        "dwf.color.id.n, not from this value."
  }
  return NamedValueCategory.OTHER to "Named variable declared by the watch face."
}
