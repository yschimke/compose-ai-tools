package ee.schimke.composeai.rccreation.dwf

/**
 * Canonical `USER`-domain Remote Compose named variable keys emitted by the Declarative Watch Face
 * transformer.
 *
 * Health metric names intentionally match Wear Glance / simulator-host conventions
 * (`metrics.heart_rate`, `metrics.day.steps`, `metrics.day.calories`, `metrics.day.distance`,
 * `metrics.day.flights_of_stairs`) so the Wear simulator and Remote Compose players drive both
 * Glance widgets and DWF watch faces from one host data model.
 */
public object DwfVariables {
  /** `1` (or boolean true) when the watch is in ambient / always-on display mode, `0` otherwise. */
  public const val IS_AMBIENT: String = "dwf.is_ambient"

  /** `1` (or boolean true) when 24-hour clock display is active, `0` for 12-hour mode. */
  public const val IS_24_HOUR: String = "dwf.is_24_hour"

  /** `1` (or boolean true) when Daylight Saving Time is active, `0` otherwise. */
  public const val IS_DST: String = "dwf.is_dst"

  /** Battery charge percentage in `[0, 100]` (`RemoteFloat`). */
  public const val BATTERY_PERCENT: String = "dwf.battery_percent"

  /** `1` when battery is charging, `0` otherwise (`RemoteBoolean` / `RemoteInt`). */
  public const val BATTERY_CHARGING: String = "dwf.battery_charging"

  /** `1` when battery is low (`<= 15%`), `0` otherwise (`RemoteBoolean` / `RemoteInt`). */
  public const val BATTERY_IS_LOW: String = "dwf.battery_is_low"

  /** Unread notification count (`RemoteInt`). */
  public const val UNREAD_NOTIFICATIONS: String = "dwf.unread_notifications"

  /** Daily step goal (`RemoteFloat`, default `10000`). */
  public const val STEP_GOAL: String = "dwf.step_goal"

  /** Heart rate in BPM (`RemoteFloat`, shared with Wear health metrics). */
  public const val HEART_RATE: String = "metrics.heart_rate"

  /** Daily step count (`RemoteInt`, shared with Wear health metrics). */
  public const val STEP_COUNT: String = "metrics.day.steps"

  /** Daily active calories (`RemoteFloat`, shared with Wear health metrics). */
  public const val CALORIES: String = "metrics.day.calories"

  /** Daily distance in meters (`RemoteFloat`, shared with Wear health metrics). */
  public const val DISTANCE: String = "metrics.day.distance"

  /** Daily flights of stairs (`RemoteFloat`, shared with Wear health metrics). */
  public const val FLOORS: String = "metrics.day.flights_of_stairs"

  /** Device tilt X angle in degrees (`RemoteFloat`). */
  public const val ACCEL_ANGLE_X: String = "dwf.accel_angle_x"

  /** Device tilt Y angle in degrees (`RemoteFloat`). */
  public const val ACCEL_ANGLE_Y: String = "dwf.accel_angle_y"

  /** `1` when weather data is available, `0` otherwise (`RemoteBoolean`). */
  public const val WEATHER_IS_AVAILABLE: String = "dwf.weather.is_available"

  /** `1` during daytime at the weather location, `0` at night (`RemoteBoolean`). */
  public const val WEATHER_IS_DAY: String = "dwf.weather.is_day"

  /** Weather condition enum code (`0..15`, `RemoteInt`). */
  public const val WEATHER_CONDITION: String = "dwf.weather.condition"

  /** Localized weather condition display string (`RemoteString`). */
  public const val WEATHER_CONDITION_NAME: String = "dwf.weather.condition_name"

  /** Current temperature (`RemoteFloat`). */
  public const val WEATHER_TEMPERATURE: String = "dwf.weather.temperature"

  /** Daily high temperature (`RemoteFloat`). */
  public const val WEATHER_TEMPERATURE_HIGH: String = "dwf.weather.temperature_high"

  /** Daily low temperature (`RemoteFloat`). */
  public const val WEATHER_TEMPERATURE_LOW: String = "dwf.weather.temperature_low"

  /** Temperature unit (`1` = Celsius, `0` = Fahrenheit, `RemoteInt`). */
  public const val WEATHER_TEMPERATURE_UNIT: String = "dwf.weather.temperature_unit"

  /** Current precipitation probability in `[0, 100]` (`RemoteInt`). */
  public const val WEATHER_CHANCE_OF_PRECIPITATION: String = "dwf.weather.chance_of_precipitation"

  /** Current UV index (`RemoteInt`). */
  public const val WEATHER_UV_INDEX: String = "dwf.weather.uv_index"

  /** Formatted last-updated time string for `icuText(..., [WEATHER.LAST_UPDATED])`. */
  public const val WEATHER_LAST_UPDATED_TEXT: String = "dwf.weather.last_updated_text"

  /** Hourly weather forecast availability (`RemoteBoolean`) for 1-based [hourOffset]. */
  public fun weatherHourAvailableKey(hourOffset: Int): String =
    "dwf.weather.hours.$hourOffset.is_available"

  /** Hourly weather forecast day/night flag (`RemoteBoolean`) for 1-based [hourOffset]. */
  public fun weatherHourIsDayKey(hourOffset: Int): String = "dwf.weather.hours.$hourOffset.is_day"

  /** Hourly weather forecast condition code (`RemoteInt`) for 1-based [hourOffset]. */
  public fun weatherHourConditionKey(hourOffset: Int): String =
    "dwf.weather.hours.$hourOffset.condition"

  /** Hourly weather forecast temperature (`RemoteFloat`) for 1-based [hourOffset]. */
  public fun weatherHourTemperatureKey(hourOffset: Int): String =
    "dwf.weather.hours.$hourOffset.temperature"

  /** Hourly weather forecast UV index (`RemoteInt`) for 1-based [hourOffset]. */
  public fun weatherHourUvIndexKey(hourOffset: Int): String =
    "dwf.weather.hours.$hourOffset.uv_index"

  /** Daily weather forecast availability (`RemoteBoolean`) for 1-based [dayOffset]. */
  public fun weatherDayAvailableKey(dayOffset: Int): String =
    "dwf.weather.days.$dayOffset.is_available"

  /** Daily weather forecast daytime condition code (`RemoteInt`) for 1-based [dayOffset]. */
  public fun weatherDayConditionKey(dayOffset: Int): String =
    "dwf.weather.days.$dayOffset.condition_day"

  /** Daily weather forecast nighttime condition code (`RemoteInt`) for 1-based [dayOffset]. */
  public fun weatherDayNightConditionKey(dayOffset: Int): String =
    "dwf.weather.days.$dayOffset.condition_night"

  /** Daily weather forecast high temperature (`RemoteFloat`) for 1-based [dayOffset]. */
  public fun weatherDayTempHighKey(dayOffset: Int): String =
    "dwf.weather.days.$dayOffset.temperature_high"

  /** Daily weather forecast low temperature (`RemoteFloat`) for 1-based [dayOffset]. */
  public fun weatherDayTempLowKey(dayOffset: Int): String =
    "dwf.weather.days.$dayOffset.temperature_low"

  /** Daily weather forecast daytime precipitation chance (`RemoteInt`) for 1-based [dayOffset]. */
  public fun weatherDayPrecipKey(dayOffset: Int): String =
    "dwf.weather.days.$dayOffset.chance_of_precipitation"

  /**
   * Daily weather forecast nighttime precipitation chance (`RemoteInt`) for 1-based [dayOffset].
   */
  public fun weatherDayNightPrecipKey(dayOffset: Int): String =
    "dwf.weather.days.$dayOffset.chance_of_precipitation_night"

  /** Daily weather forecast UV index (`RemoteInt`) for 1-based [dayOffset]. */
  public fun weatherDayUvIndexKey(dayOffset: Int): String = "dwf.weather.days.$dayOffset.uv_index"

  /** Selected option/boolean/string value for `<ListConfiguration>` or `<BooleanConfiguration>`. */
  public fun configKey(configId: String): String = "dwf.config.$configId"

  /** Resolved ARGB theme color for `<ColorConfiguration id="...">` at [colorIndex]. */
  public fun colorThemeKey(configId: String, colorIndex: Int = 0): String =
    "dwf.color.$configId.$colorIndex"

  /** Primary text string bound to `<ComplicationSlot slotId="...">`. */
  public fun complicationTextKey(slotId: Int): String = "dwf.complication.$slotId.text"

  /** Secondary title string bound to `<ComplicationSlot slotId="...">`. */
  public fun complicationTitleKey(slotId: Int): String = "dwf.complication.$slotId.title"

  /** Current ranged value (`RemoteFloat`) bound to `<ComplicationSlot slotId="...">`. */
  public fun complicationValueKey(slotId: Int): String = "dwf.complication.$slotId.value"

  /** Minimum ranged value (`RemoteFloat`) bound to `<ComplicationSlot slotId="...">`. */
  public fun complicationMinKey(slotId: Int): String = "dwf.complication.$slotId.min"

  /** Maximum ranged value (`RemoteFloat`) bound to `<ComplicationSlot slotId="...">`. */
  public fun complicationMaxKey(slotId: Int): String = "dwf.complication.$slotId.max"

  /** Complication type code (`RemoteInt`) for [slotId]; see `COMPLICATION_TYPE_*` constants. */
  public fun complicationTypeKey(slotId: Int): String = "dwf.complication.$slotId.type"

  /** `1` when [slotId] has a `MONOCHROMATIC_IMAGE` bound (`RemoteBoolean`). */
  public fun complicationHasMonoImageKey(slotId: Int): String =
    "dwf.complication.$slotId.has_monochromatic_image"

  /** `1` when [slotId] has a `SMALL_IMAGE` bound (`RemoteBoolean`). */
  public fun complicationHasSmallImageKey(slotId: Int): String =
    "dwf.complication.$slotId.has_small_image"

  /** `1` when [slotId] has a `PHOTO_IMAGE` bound (`RemoteBoolean`). */
  public fun complicationHasPhotoImageKey(slotId: Int): String =
    "dwf.complication.$slotId.has_photo_image"

  /** Foreground/tint color (`RemoteColor`) for procedural complication icons in [slotId]. */
  public fun complicationImageColorKey(slotId: Int): String = "dwf.complication.$slotId.image_color"

  /** Badge/backdrop accent color (`RemoteColor`) for `SMALL_IMAGE` / `PHOTO_IMAGE` in [slotId]. */
  public fun complicationAccentColorKey(slotId: Int): String =
    "dwf.complication.$slotId.accent_color"

  /** `1` when [slotId] supplies `RANGED_VALUE_COLORS` (`RemoteBoolean`). */
  public fun complicationHasRangedColorsKey(slotId: Int): String =
    "dwf.complication.$slotId.has_ranged_value_colors"

  /** `1` when [slotId]'s `RANGED_VALUE_COLORS` ramp interpolates (`RemoteBoolean`). */
  public fun complicationRangedInterpolateKey(slotId: Int): String =
    "dwf.complication.$slotId.ranged_value_colors_interpolate"

  /** `1` when [slotId] supplies `GOAL_PROGRESS_COLORS` (`RemoteBoolean`). */
  public fun complicationHasGoalColorsKey(slotId: Int): String =
    "dwf.complication.$slotId.has_goal_progress_colors"

  /** `1` when [slotId]'s `GOAL_PROGRESS_COLORS` ramp interpolates (`RemoteBoolean`). */
  public fun complicationGoalInterpolateKey(slotId: Int): String =
    "dwf.complication.$slotId.goal_progress_colors_interpolate"

  /** `1` when [slotId] supplies `WEIGHTED_ELEMENTS` data (`RemoteBoolean`). */
  public fun complicationHasWeightedElementsKey(slotId: Int): String =
    "dwf.complication.$slotId.has_weighted_elements"

  /** Active color-stop count (`RemoteInt`, `1..4`) for [slotId]'s ramp/weighted stroke. */
  public fun complicationColorCountKey(slotId: Int): String = "dwf.complication.$slotId.color_count"

  /** Dynamic color stop (`RemoteColor`) at 0-based [index] (`0..3`) for [slotId]. */
  public fun complicationColorStopKey(slotId: Int, index: Int): String =
    "dwf.complication.$slotId.color.$index"

  /** Dynamic element weight (`RemoteFloat`) at 0-based [index] (`0..3`) for [slotId]. */
  public fun complicationWeightKey(slotId: Int, index: Int): String =
    "dwf.complication.$slotId.weight.$index"

  public const val MAX_COMPLICATION_COLOR_STOPS: Int = 4

  public const val COMPLICATION_TYPE_EMPTY: Int = 0
  public const val COMPLICATION_TYPE_SHORT_TEXT: Int = 1
  public const val COMPLICATION_TYPE_LONG_TEXT: Int = 2
  public const val COMPLICATION_TYPE_RANGED_VALUE: Int = 3
  public const val COMPLICATION_TYPE_MONOCHROMATIC_IMAGE: Int = 4
  public const val COMPLICATION_TYPE_SMALL_IMAGE: Int = 5
  public const val COMPLICATION_TYPE_PHOTO_IMAGE: Int = 6
  public const val COMPLICATION_TYPE_GOAL_PROGRESS: Int = 7
  public const val COMPLICATION_TYPE_WEIGHTED_ELEMENTS: Int = 8

  /**
   * Maps a Watch Face Format complication type name (for example `"SHORT_TEXT"`) to its int code.
   */
  public fun complicationTypeCode(typeName: String): Int =
    when (typeName.trim().uppercase().removePrefix("COMPLICATION_TYPE_")) {
      "EMPTY",
      "NO_DATA",
      "NOT_CONFIGURED" -> COMPLICATION_TYPE_EMPTY
      "SHORT_TEXT" -> COMPLICATION_TYPE_SHORT_TEXT
      "LONG_TEXT" -> COMPLICATION_TYPE_LONG_TEXT
      "RANGED_VALUE" -> COMPLICATION_TYPE_RANGED_VALUE
      "MONOCHROMATIC_IMAGE",
      "ICON" -> COMPLICATION_TYPE_MONOCHROMATIC_IMAGE
      "SMALL_IMAGE" -> COMPLICATION_TYPE_SMALL_IMAGE
      "PHOTO_IMAGE",
      "LARGE_IMAGE" -> COMPLICATION_TYPE_PHOTO_IMAGE
      "GOAL_PROGRESS" -> COMPLICATION_TYPE_GOAL_PROGRESS
      "WEIGHTED_ELEMENTS" -> COMPLICATION_TYPE_WEIGHTED_ELEMENTS
      else -> COMPLICATION_TYPE_SHORT_TEXT
    }

  /** Maps an internal complication type int code back to its canonical WFF type name. */
  public fun complicationTypeName(code: Int): String =
    when (code) {
      COMPLICATION_TYPE_EMPTY -> "EMPTY"
      COMPLICATION_TYPE_SHORT_TEXT -> "SHORT_TEXT"
      COMPLICATION_TYPE_LONG_TEXT -> "LONG_TEXT"
      COMPLICATION_TYPE_RANGED_VALUE -> "RANGED_VALUE"
      COMPLICATION_TYPE_MONOCHROMATIC_IMAGE -> "MONOCHROMATIC_IMAGE"
      COMPLICATION_TYPE_SMALL_IMAGE -> "SMALL_IMAGE"
      COMPLICATION_TYPE_PHOTO_IMAGE -> "PHOTO_IMAGE"
      COMPLICATION_TYPE_GOAL_PROGRESS -> "GOAL_PROGRESS"
      COMPLICATION_TYPE_WEIGHTED_ELEMENTS -> "WEIGHTED_ELEMENTS"
      else -> "SHORT_TEXT"
    }
}
