package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.WatchFaceActiveComplicationDto
import ee.schimke.composeai.bundle.WatchFaceComplicationsPayload
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Renderer-neutral query, WebSocket, and MCP override translation for Declarative Watch Faces
 * (`<WatchFace>`).
 *
 * Callers express state using renderer-neutral keys (`ambient`, `clockEpochMillis`, `wf.slot.*`,
 * `wf.config.*`, `wf.batteryPercent`, …). [parseWfParams] populates both:
 * 1. [PreviewOverrides.namedOverrides] (`wf.*`) and [PreviewOverrides.ambient] /
 *    [PreviewOverrides.clockEpochMillis] for non-RemoteCompose watch-face backends (such as the
 *    Wear OS DWF renderer APK lane), and
 * 2. [PreviewOverrides.remoteCompose] `namedValues` (`dwf.*` / `metrics.*`) for the JVM
 *    RemoteCompose DWF renderer (`DwfDaemonMain` and `.rc` replay).
 */
public object WatchFaceOverrides {

  /** Dynamic query/WebSocket key prefix for watch-face configurations, sensors, and slots. */
  public const val WF_PREFIX: String = "wf."

  /** Query/WebSocket/MCP parameter selecting which [WatchFaceRendererBackend] executes a render. */
  public const val WF_RENDERER_PARAM: String = "wfRenderer"

  /** Canonical complication type names accepted on `wf.slot.<slotId>.type`. */
  public val KNOWN_COMPLICATION_TYPES: Set<String> =
    setOf(
      "EMPTY",
      "NO_DATA",
      "NO_PERMISSION",
      "NOT_CONFIGURED",
      "SHORT_TEXT",
      "LONG_TEXT",
      "RANGED_VALUE",
      "MONOCHROMATIC_IMAGE",
      "ICON",
      "SMALL_IMAGE",
      "PHOTO_IMAGE",
      "LARGE_IMAGE",
      "GOAL_PROGRESS",
      "WEIGHTED_ELEMENTS",
    )

  private const val MAX_COLOR_STOPS: Int = 4
  private val CANONICAL_PREVIEW_DATE: LocalDate = LocalDate.of(2026, 11, 18)
  private val RENDERER_ID_REGEX: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
  private val payloadJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  /** Outcome of parsing `wf.*` parameters. */
  public sealed interface WfParamParse {
    public data class Ok(
      val namedOverrides: Map<String, PreviewOverrideValue>,
      val rcNamedValues: Map<String, RemoteNamedValue>,
      val ambient: AmbientOverride?,
    ) : WfParamParse

    public data class Invalid(val message: String) : WfParamParse
  }

  /** Outcome of parsing top-level `ambient` / `ambientBurnIn` / `ambientLowBit` parameters. */
  public sealed interface AmbientParamParse {
    public data class Ok(val ambient: AmbientOverride?) : AmbientParamParse

    public data class Invalid(val message: String) : AmbientParamParse
  }

  /** True if [raw] is syntactically valid for [WF_RENDERER_PARAM]. */
  public fun isValidRendererId(raw: String): Boolean = RENDERER_ID_REGEX.matches(raw.trim())

  /**
   * Parse a `clockEpochMillis` parameter: accepts epoch milliseconds (`1763460630000`), `HH:mm`,
   * `HH:mm:ss` (anchored at `2026-11-18` UTC to match DWF preview renders), or an ISO-8601 instant
   * (`2026-11-18T10:10:30Z`). Returns null when unparseable.
   */
  public fun parseClockEpochMillis(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    trimmed.toLongOrNull()?.let {
      return it
    }
    if (':' in trimmed && 'T' !in trimmed && 't' !in trimmed) {
      val parts = trimmed.split(':')
      if (parts.size == 2 || parts.size == 3) {
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = if (parts.size == 3) parts[2].toIntOrNull() ?: return null else 0
        if (h in 0..23 && m in 0..59 && s in 0..59) {
          return CANONICAL_PREVIEW_DATE.atTime(LocalTime.of(h, m, s))
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        }
      }
      return null
    }
    return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()
  }

  /** Parse top-level `ambient`, `ambientBurnIn`, and `ambientLowBit` query parameters. */
  public fun parseAmbientParams(params: Map<String, String>): AmbientParamParse {
    val rawState = params["ambient"]?.trim()?.takeIf { it.isNotEmpty() }
    val rawBurnIn = params["ambientBurnIn"]?.trim()?.takeIf { it.isNotEmpty() }
    val rawLowBit = params["ambientLowBit"]?.trim()?.takeIf { it.isNotEmpty() }
    if (rawState == null && rawBurnIn == null && rawLowBit == null) {
      return AmbientParamParse.Ok(null)
    }
    val state =
      if (rawState == null) {
        AmbientStateOverride.AMBIENT
      } else {
        when (rawState.lowercase(Locale.ROOT)) {
          "true",
          "1",
          "ambient",
          "on" -> AmbientStateOverride.AMBIENT
          "false",
          "0",
          "interactive",
          "off" -> AmbientStateOverride.INTERACTIVE
          else ->
            return AmbientParamParse.Invalid(
              "ambient must be true/false/ambient/interactive, got '$rawState'",
            )
        }
      }
    val burnIn = rawBurnIn?.let {
      parseStrictBool(it)
        ?: return AmbientParamParse.Invalid("ambientBurnIn must be a boolean, got '$it'")
    }
    val lowBit = rawLowBit?.let {
      parseStrictBool(it)
        ?: return AmbientParamParse.Invalid("ambientLowBit must be a boolean, got '$it'")
    }
    return AmbientParamParse.Ok(
      AmbientOverride(
        state = state,
        burnInProtectionRequired = burnIn,
        deviceHasLowBitAmbient = lowBit,
      ),
    )
  }

  /** Parse `wf.*` parameters into renderer-neutral and RemoteCompose watch-face overrides. */
  public fun parseWfParams(params: Map<String, String>): WfParamParse {
    val named = LinkedHashMap<String, PreviewOverrideValue>()
    val rc = LinkedHashMap<String, RemoteNamedValue>()
    var wfAmbient: AmbientOverride? = null
    var explicitBatteryLow: Boolean? = null
    val slotFields = LinkedHashMap<Int, MutableMap<String, String>>()

    for ((rawKey, rawVal) in params) {
      if (!rawKey.startsWith(WF_PREFIX)) continue
      val rest = rawKey.removePrefix(WF_PREFIX)
      if (rest.isBlank()) continue
      val trimmed = rawVal.trim()

      when {
        rest == "ambient" || rest == "isAmbient" -> {
          if (trimmed.isEmpty()) continue
          val amb =
            when (trimmed.lowercase(Locale.ROOT)) {
              "true",
              "1",
              "ambient",
              "on" -> true
              "false",
              "0",
              "interactive",
              "off" -> false
              else ->
                return WfParamParse.Invalid(
                  "$rawKey must be true/false/ambient/interactive, got '$rawVal'",
                )
            }
          named["wf.ambient"] = PreviewOverrideValue.BooleanValue(amb)
          rc["dwf.is_ambient"] = RemoteNamedValue.BooleanValue(amb)
          wfAmbient =
            AmbientOverride(
              state = if (amb) AmbientStateOverride.AMBIENT else AmbientStateOverride.INTERACTIVE,
            )
        }
        rest == "is24Hour" -> {
          if (trimmed.isEmpty()) continue
          val b =
            parseStrictBool(trimmed)
              ?: return WfParamParse.Invalid("$rawKey must be a boolean, got '$rawVal'")
          named[rawKey] = PreviewOverrideValue.BooleanValue(b)
          rc["dwf.is_24_hour"] = RemoteNamedValue.BooleanValue(b)
        }
        rest == "isDst" -> {
          if (trimmed.isEmpty()) continue
          val b =
            parseStrictBool(trimmed)
              ?: return WfParamParse.Invalid("$rawKey must be a boolean, got '$rawVal'")
          named[rawKey] = PreviewOverrideValue.BooleanValue(b)
          rc["dwf.is_dst"] = RemoteNamedValue.BooleanValue(b)
        }
        rest == "batteryPercent" -> {
          if (trimmed.isEmpty()) continue
          val f =
            trimmed.toFloatOrNull()?.takeIf { it.isFinite() }
              ?: return WfParamParse.Invalid("$rawKey must be a number, got '$rawVal'")
          named[rawKey] = PreviewOverrideValue.FloatValue(f)
          rc["dwf.battery_percent"] = RemoteNamedValue.FloatValue(f)
          if (explicitBatteryLow == null) {
            rc["dwf.battery_is_low"] = RemoteNamedValue.BooleanValue(f <= 15f)
          }
        }
        rest == "batteryCharging" -> {
          if (trimmed.isEmpty()) continue
          val b =
            parseStrictBool(trimmed)
              ?: return WfParamParse.Invalid("$rawKey must be a boolean, got '$rawVal'")
          named[rawKey] = PreviewOverrideValue.BooleanValue(b)
          rc["dwf.battery_charging"] = RemoteNamedValue.BooleanValue(b)
        }
        rest == "batteryIsLow" -> {
          if (trimmed.isEmpty()) continue
          val b =
            parseStrictBool(trimmed)
              ?: return WfParamParse.Invalid("$rawKey must be a boolean, got '$rawVal'")
          explicitBatteryLow = b
          named[rawKey] = PreviewOverrideValue.BooleanValue(b)
          rc["dwf.battery_is_low"] = RemoteNamedValue.BooleanValue(b)
        }
        rest == "unreadNotifications" -> {
          if (trimmed.isEmpty()) continue
          val n =
            trimmed.toIntOrNull()
              ?: return WfParamParse.Invalid("$rawKey must be an integer, got '$rawVal'")
          named[rawKey] = PreviewOverrideValue.IntValue(n)
          rc["dwf.unread_notifications"] = RemoteNamedValue.IntValue(n)
        }
        rest == "stepCount" -> {
          if (trimmed.isEmpty()) continue
          val n =
            trimmed.toIntOrNull()
              ?: return WfParamParse.Invalid("$rawKey must be an integer, got '$rawVal'")
          named[rawKey] = PreviewOverrideValue.IntValue(n)
          rc["metrics.day.steps"] = RemoteNamedValue.IntValue(n)
        }
        rest == "stepGoal" -> {
          if (trimmed.isEmpty()) continue
          val f =
            trimmed.toFloatOrNull()?.takeIf { it.isFinite() }
              ?: return WfParamParse.Invalid("$rawKey must be a number, got '$rawVal'")
          named[rawKey] = PreviewOverrideValue.FloatValue(f)
          rc["dwf.step_goal"] = RemoteNamedValue.FloatValue(f)
        }
        rest == "heartRate" -> {
          if (trimmed.isEmpty()) continue
          val f =
            trimmed.toFloatOrNull()?.takeIf { it.isFinite() }
              ?: return WfParamParse.Invalid("$rawKey must be a number, got '$rawVal'")
          named[rawKey] = PreviewOverrideValue.FloatValue(f)
          rc["metrics.heart_rate"] = RemoteNamedValue.FloatValue(f)
        }
        rest == "calories" -> {
          if (trimmed.isEmpty()) continue
          val f =
            trimmed.toFloatOrNull()?.takeIf { it.isFinite() }
              ?: return WfParamParse.Invalid("$rawKey must be a number, got '$rawVal'")
          named[rawKey] = PreviewOverrideValue.FloatValue(f)
          rc["metrics.day.calories"] = RemoteNamedValue.FloatValue(f)
        }
        rest == "distance" -> {
          if (trimmed.isEmpty()) continue
          val f =
            trimmed.toFloatOrNull()?.takeIf { it.isFinite() }
              ?: return WfParamParse.Invalid("$rawKey must be a number, got '$rawVal'")
          named[rawKey] = PreviewOverrideValue.FloatValue(f)
          rc["metrics.day.distance"] = RemoteNamedValue.FloatValue(f)
        }
        rest == "floors" -> {
          if (trimmed.isEmpty()) continue
          val f =
            trimmed.toFloatOrNull()?.takeIf { it.isFinite() }
              ?: return WfParamParse.Invalid("$rawKey must be a number, got '$rawVal'")
          named[rawKey] = PreviewOverrideValue.FloatValue(f)
          rc["metrics.day.flights_of_stairs"] = RemoteNamedValue.FloatValue(f)
        }
        rest.startsWith("config.") -> {
          val cfgId = rest.removePrefix("config.")
          if (cfgId.isBlank()) {
            return WfParamParse.Invalid("watch-face config id must not be blank in '$rawKey'")
          }
          if (trimmed.isEmpty()) continue
          // Keep string form so DwfDaemonMain.normalizeDwfConfigValues can resolve option IDs
          // ("red_style") against the parsed <ListConfiguration>/<ColorConfiguration>, while pure
          // booleans/ints also carry typed values when unambiguous.
          val asBool =
            when (trimmed.lowercase(Locale.ROOT)) {
              "true" -> true
              "false" -> false
              else -> null
            }
          val asInt = trimmed.toIntOrNull()
          when {
            asBool != null -> {
              named[rawKey] = PreviewOverrideValue.BooleanValue(asBool)
              rc["dwf.config.$cfgId"] = RemoteNamedValue.BooleanValue(asBool)
            }
            asInt != null -> {
              named[rawKey] = PreviewOverrideValue.IntValue(asInt)
              rc["dwf.config.$cfgId"] = RemoteNamedValue.IntValue(asInt)
            }
            else -> {
              named[rawKey] = PreviewOverrideValue.StringValue(trimmed)
              rc["dwf.config.$cfgId"] = RemoteNamedValue.StringValue(trimmed)
            }
          }
        }
        rest.startsWith("themeColor.") -> {
          val colorKey = rest.removePrefix("themeColor.")
          if (colorKey.isBlank()) {
            return WfParamParse.Invalid("watch-face themeColor key must not be blank in '$rawKey'")
          }
          if (trimmed.isEmpty()) continue
          val normalized =
            normalizeHexColor(trimmed)
              ?: return WfParamParse.Invalid(
                "$rawKey must be #RRGGBB or #AARRGGBB, got '$rawVal'",
              )
          named[rawKey] = PreviewOverrideValue.ColorValue(normalized)
          val dot = colorKey.lastIndexOf('.')
          val rcKey =
            if (dot > 0 && colorKey.substring(dot + 1).toIntOrNull() != null) {
              "dwf.color.$colorKey"
            } else {
              "dwf.color.$colorKey.0"
            }
          rc[rcKey] = RemoteNamedValue.ColorValue(normalized)
        }
        rest.startsWith("slot.") -> {
          val tail = rest.removePrefix("slot.")
          val dot = tail.indexOf('.')
          if (dot <= 0 || dot == tail.lastIndex) {
            return WfParamParse.Invalid(
              "slot parameter '$rawKey' must be wf.slot.<slotId>.<field>",
            )
          }
          val slotId =
            tail.substring(0, dot).toIntOrNull()?.takeIf { it >= 0 }
              ?: return WfParamParse.Invalid(
                "slotId in '$rawKey' must be a non-negative integer",
              )
          val field = tail.substring(dot + 1)
          slotFields.getOrPut(slotId) { LinkedHashMap() }[field] = rawVal
        }
        else -> {
          return WfParamParse.Invalid("unknown watch-face parameter '$rawKey'")
        }
      }
    }

    for ((slotId, fields) in slotFields) {
      val rcPrefix = "dwf.complication.$slotId"
      val wfSlotPrefix = "wf.slot.$slotId"

      val rawType = fields["type"]?.trim()?.takeIf { it.isNotEmpty() }
      val normalizedType = rawType?.uppercase(Locale.ROOT)?.removePrefix("COMPLICATION_TYPE_")
      if (normalizedType != null && normalizedType !in KNOWN_COMPLICATION_TYPES) {
        return WfParamParse.Invalid(
          "$wfSlotPrefix.type must be one of ${KNOWN_COMPLICATION_TYPES.sorted().joinToString()}, got '$rawType'",
        )
      }
      val typeCode = normalizedType?.let(::complicationTypeCode)

      for (field in fields.keys) {
        if (field !in KNOWN_SLOT_FIELDS) {
          return WfParamParse.Invalid("unknown complication slot field '$wfSlotPrefix.$field'")
        }
      }

      if (typeCode == COMPLICATION_TYPE_EMPTY) {
        val canonicalEmpty = complicationTypeName(COMPLICATION_TYPE_EMPTY)
        named["$wfSlotPrefix.type"] = PreviewOverrideValue.StringValue(canonicalEmpty)
        rc["$rcPrefix.type"] = RemoteNamedValue.IntValue(COMPLICATION_TYPE_EMPTY)
        rc["$rcPrefix.text"] = RemoteNamedValue.StringValue("")
        rc["$rcPrefix.title"] = RemoteNamedValue.StringValue("")
        rc["$rcPrefix.has_monochromatic_image"] = RemoteNamedValue.BooleanValue(false)
        rc["$rcPrefix.has_small_image"] = RemoteNamedValue.BooleanValue(false)
        rc["$rcPrefix.has_photo_image"] = RemoteNamedValue.BooleanValue(false)
        continue
      }

      if (typeCode != null) {
        named["$wfSlotPrefix.type"] =
          PreviewOverrideValue.StringValue(complicationTypeName(typeCode))
        rc["$rcPrefix.type"] = RemoteNamedValue.IntValue(typeCode)
      }

      if ("text" in fields) {
        val text = fields.getValue("text")
        named["$wfSlotPrefix.text"] = PreviewOverrideValue.StringValue(text)
        rc["$rcPrefix.text"] = RemoteNamedValue.StringValue(text)
      } else if (typeCode != null) {
        rc["$rcPrefix.text"] = RemoteNamedValue.StringValue("")
      }

      if ("title" in fields) {
        val title = fields.getValue("title")
        named["$wfSlotPrefix.title"] = PreviewOverrideValue.StringValue(title)
        rc["$rcPrefix.title"] = RemoteNamedValue.StringValue(title)
      } else if (typeCode != null) {
        rc["$rcPrefix.title"] = RemoteNamedValue.StringValue("")
      }

      fields["value"]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
          val v =
            raw.toFloatOrNull()?.takeIf { it.isFinite() }
              ?: return WfParamParse.Invalid("$wfSlotPrefix.value must be a number, got '$raw'")
          named["$wfSlotPrefix.value"] = PreviewOverrideValue.FloatValue(v)
          rc["$rcPrefix.value"] = RemoteNamedValue.FloatValue(v)
        }

      fields["min"]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
          val v =
            raw.toFloatOrNull()?.takeIf { it.isFinite() }
              ?: return WfParamParse.Invalid("$wfSlotPrefix.min must be a number, got '$raw'")
          named["$wfSlotPrefix.min"] = PreviewOverrideValue.FloatValue(v)
          rc["$rcPrefix.min"] = RemoteNamedValue.FloatValue(v)
        }

      val maxRaw = (fields["max"] ?: fields["targetValue"])?.trim()?.takeIf { it.isNotEmpty() }
      if (maxRaw != null) {
        val v =
          maxRaw.toFloatOrNull()?.takeIf { it.isFinite() }
            ?: return WfParamParse.Invalid("$wfSlotPrefix.max must be a number, got '$maxRaw'")
        named["$wfSlotPrefix.max"] = PreviewOverrideValue.FloatValue(v)
        rc["$rcPrefix.max"] = RemoteNamedValue.FloatValue(v)
      }

      val monoRaw =
        (fields["monochromaticImage"] ?: fields["icon"])?.trim()?.takeIf { it.isNotEmpty() }
      if (monoRaw != null) {
        val b =
          parseStrictBool(monoRaw)
            ?: return WfParamParse.Invalid(
              "$wfSlotPrefix.monochromaticImage must be a boolean, got '$monoRaw'",
            )
        named["$wfSlotPrefix.monochromaticImage"] = PreviewOverrideValue.BooleanValue(b)
        rc["$rcPrefix.has_monochromatic_image"] = RemoteNamedValue.BooleanValue(b)
      } else if (typeCode != null) {
        rc["$rcPrefix.has_monochromatic_image"] =
          RemoteNamedValue.BooleanValue(typeCode == COMPLICATION_TYPE_MONOCHROMATIC_IMAGE)
      }

      val smallRaw = fields["smallImage"]?.trim()?.takeIf { it.isNotEmpty() }
      if (smallRaw != null) {
        val b =
          parseStrictBool(smallRaw)
            ?: return WfParamParse.Invalid(
              "$wfSlotPrefix.smallImage must be a boolean, got '$smallRaw'",
            )
        named["$wfSlotPrefix.smallImage"] = PreviewOverrideValue.BooleanValue(b)
        rc["$rcPrefix.has_small_image"] = RemoteNamedValue.BooleanValue(b)
      } else if (typeCode != null) {
        rc["$rcPrefix.has_small_image"] =
          RemoteNamedValue.BooleanValue(typeCode == COMPLICATION_TYPE_SMALL_IMAGE)
      }

      val photoRaw = fields["photoImage"]?.trim()?.takeIf { it.isNotEmpty() }
      if (photoRaw != null) {
        val b =
          parseStrictBool(photoRaw)
            ?: return WfParamParse.Invalid(
              "$wfSlotPrefix.photoImage must be a boolean, got '$photoRaw'",
            )
        named["$wfSlotPrefix.photoImage"] = PreviewOverrideValue.BooleanValue(b)
        rc["$rcPrefix.has_photo_image"] = RemoteNamedValue.BooleanValue(b)
      } else if (typeCode != null) {
        rc["$rcPrefix.has_photo_image"] =
          RemoteNamedValue.BooleanValue(typeCode == COMPLICATION_TYPE_PHOTO_IMAGE)
      }

      fields["imageColor"]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
          val c =
            normalizeHexColor(raw)
              ?: return WfParamParse.Invalid(
                "$wfSlotPrefix.imageColor must be #RRGGBB or #AARRGGBB, got '$raw'",
              )
          named["$wfSlotPrefix.imageColor"] = PreviewOverrideValue.ColorValue(c)
          rc["$rcPrefix.image_color"] = RemoteNamedValue.ColorValue(c)
        }

      fields["accentColor"]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
          val c =
            normalizeHexColor(raw)
              ?: return WfParamParse.Invalid(
                "$wfSlotPrefix.accentColor must be #RRGGBB or #AARRGGBB, got '$raw'",
              )
          named["$wfSlotPrefix.accentColor"] = PreviewOverrideValue.ColorValue(c)
          rc["$rcPrefix.accent_color"] = RemoteNamedValue.ColorValue(c)
        }

      val parsedColors =
        fields["colors"]
          ?.trim()
          ?.takeIf { it.isNotEmpty() }
          ?.let { raw ->
            val list = mutableListOf<String>()
            for (token in raw.split(',')) {
              val t = token.trim()
              if (t.isEmpty()) continue
              val c =
                normalizeHexColor(t)
                  ?: return WfParamParse.Invalid(
                    "$wfSlotPrefix.colors entry must be #RRGGBB or #AARRGGBB, got '$t'",
                  )
              list += c
            }
            list.take(MAX_COLOR_STOPS)
          }

      val parsedWeights =
        fields["weights"]
          ?.trim()
          ?.takeIf { it.isNotEmpty() }
          ?.let { raw ->
            val list = mutableListOf<Float>()
            for (token in raw.split(',')) {
              val t = token.trim()
              if (t.isEmpty()) continue
              val w =
                t.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
                  ?: return WfParamParse.Invalid(
                    "$wfSlotPrefix.weights entry must be a non-negative number, got '$t'",
                  )
              list += w
            }
            list.take(MAX_COLOR_STOPS)
          }

      if (!parsedColors.isNullOrEmpty()) {
        named["$wfSlotPrefix.colors"] =
          PreviewOverrideValue.StringValue(parsedColors.joinToString(","))
        rc["$rcPrefix.color_count"] = RemoteNamedValue.IntValue(parsedColors.size)
        val pad = parsedColors.last()
        for (idx in 0 until MAX_COLOR_STOPS) {
          rc["$rcPrefix.color.$idx"] =
            RemoteNamedValue.ColorValue(parsedColors.getOrElse(idx) { pad })
        }
        rc["$rcPrefix.has_ranged_value_colors"] = RemoteNamedValue.BooleanValue(true)
        rc["$rcPrefix.has_goal_progress_colors"] = RemoteNamedValue.BooleanValue(true)
        if (typeCode == COMPLICATION_TYPE_WEIGHTED_ELEMENTS || !parsedWeights.isNullOrEmpty()) {
          rc["$rcPrefix.has_weighted_elements"] = RemoteNamedValue.BooleanValue(true)
        }
      } else if (typeCode == COMPLICATION_TYPE_WEIGHTED_ELEMENTS) {
        rc["$rcPrefix.has_weighted_elements"] = RemoteNamedValue.BooleanValue(true)
      }

      if (!parsedWeights.isNullOrEmpty()) {
        named["$wfSlotPrefix.weights"] =
          PreviewOverrideValue.StringValue(parsedWeights.joinToString(","))
        rc["$rcPrefix.has_weighted_elements"] = RemoteNamedValue.BooleanValue(true)
        if (parsedColors.isNullOrEmpty()) {
          rc["$rcPrefix.color_count"] = RemoteNamedValue.IntValue(parsedWeights.size)
        }
        for (idx in 0 until MAX_COLOR_STOPS) {
          rc["$rcPrefix.weight.$idx"] =
            RemoteNamedValue.FloatValue(parsedWeights.getOrElse(idx) { 0f })
        }
      }

      fields["interpolate"]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
          val b =
            parseStrictBool(raw)
              ?: return WfParamParse.Invalid(
                "$wfSlotPrefix.interpolate must be a boolean, got '$raw'",
              )
          named["$wfSlotPrefix.interpolate"] = PreviewOverrideValue.BooleanValue(b)
          rc["$rcPrefix.ranged_value_colors_interpolate"] = RemoteNamedValue.BooleanValue(b)
          rc["$rcPrefix.goal_progress_colors_interpolate"] = RemoteNamedValue.BooleanValue(b)
        }
    }

    return WfParamParse.Ok(
      namedOverrides = named,
      rcNamedValues = rc,
      ambient = wfAmbient,
    )
  }

  /** True when [overrides] carries any watch-face, ambient, or preview-time override. */
  public fun hasWatchFaceOverrides(overrides: PreviewOverrides): Boolean =
    overrides.ambient != null ||
      overrides.clockEpochMillis != null ||
      overrides.namedOverrides?.keys?.any { it.startsWith(WF_PREFIX) } == true ||
      overrides.remoteCompose?.namedValues?.keys?.any {
        it.startsWith("dwf.") || it.startsWith("metrics.")
      } == true

  /**
   * Merge structured JSON `complications` and `watchFace` objects (as accepted by MCP tools) onto
   * [base]. Throws [IllegalArgumentException] when any field is malformed.
   */
  public fun mergeStructuredOverrides(
    base: PreviewOverrides,
    complications: JsonElement? = null,
    watchFace: JsonObject? = null,
    ambient: Boolean? = null,
    ambientBurnIn: Boolean? = null,
    ambientLowBit: Boolean? = null,
    clockEpochMillis: Long? = null,
    previewTime: String? = null,
  ): PreviewOverrides {
    val flatParams = LinkedHashMap<String, String>()
    if (ambient != null) flatParams["ambient"] = ambient.toString()
    if (ambientBurnIn != null) flatParams["ambientBurnIn"] = ambientBurnIn.toString()
    if (ambientLowBit != null) flatParams["ambientLowBit"] = ambientLowBit.toString()

    var effectiveClockMs: Long? = clockEpochMillis ?: base.clockEpochMillis
    if (previewTime != null && previewTime.isNotBlank()) {
      effectiveClockMs =
        parseClockEpochMillis(previewTime)
          ?: throw IllegalArgumentException(
            "Invalid previewTime '$previewTime': expected HH:mm, HH:mm:ss, ISO-8601 instant, or epoch millis",
          )
    }

    if (watchFace != null) {
      watchFace["isAmbient"]?.jsonPrimitive?.booleanOrNull?.let {
        if ("ambient" !in flatParams) flatParams["ambient"] = it.toString()
      }
      watchFace["ambient"]?.jsonPrimitive?.booleanOrNull?.let {
        if ("ambient" !in flatParams) flatParams["ambient"] = it.toString()
      }
      watchFace["previewTime"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { pt ->
          effectiveClockMs =
            parseClockEpochMillis(pt)
              ?: throw IllegalArgumentException(
                "Invalid watchFace.previewTime '$pt': expected HH:mm, HH:mm:ss, or epoch millis",
              )
        }
      watchFace["clockEpochMillis"]?.jsonPrimitive?.longOrNull?.let { effectiveClockMs = it }

      val scalarFields =
        listOf(
          "is24Hour",
          "isDst",
          "batteryPercent",
          "batteryCharging",
          "batteryIsLow",
          "unreadNotifications",
          "stepCount",
          "stepGoal",
          "heartRate",
          "calories",
          "distance",
          "floors",
        )
      for (key in scalarFields) {
        val prim = watchFace[key]?.takeUnless { it is JsonNull } as? JsonPrimitive ?: continue
        flatParams["wf.$key"] = prim.content
      }
      (watchFace["configurations"] as? JsonObject)?.forEach { (cfgId, el) ->
        if (el is JsonPrimitive) {
          flatParams["wf.config.$cfgId"] = el.content
        }
      }
      (watchFace["themeColors"] as? JsonObject)?.forEach { (colorKey, el) ->
        if (el is JsonPrimitive) {
          flatParams["wf.themeColor.$colorKey"] = el.content
        }
      }
    }

    when (complications) {
      null,
      is JsonNull -> {}
      is JsonObject -> {
        for ((slotKey, slotEl) in complications) {
          val slotId =
            slotKey.trim().removePrefix("slot_").removePrefix("slot").toIntOrNull()?.takeIf {
              it >= 0
            }
              ?: throw IllegalArgumentException(
                "Complication slot key '$slotKey' must be a non-negative integer",
              )
          val slotObj =
            slotEl as? JsonObject
              ?: throw IllegalArgumentException(
                "Complication slot '$slotKey' must be a JSON object",
              )
          flattenSlotObject(slotId, slotObj, flatParams)
        }
      }
      is JsonArray -> {
        for (item in complications) {
          val slotObj =
            item as? JsonObject
              ?: throw IllegalArgumentException("Each item in complications[] must be an object")
          val slotId =
            slotObj["slotId"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
              ?: throw IllegalArgumentException(
                "Each item in complications[] requires a non-negative integer 'slotId'",
              )
          flattenSlotObject(slotId, slotObj, flatParams)
        }
      }
      else ->
        throw IllegalArgumentException(
          "'complications' must be an object keyed by slotId or an array of slot objects",
        )
    }

    val ambParse =
      when (val r = parseAmbientParams(flatParams)) {
        is AmbientParamParse.Ok -> r.ambient
        is AmbientParamParse.Invalid -> throw IllegalArgumentException(r.message)
      }
    val wfParse =
      when (val r = parseWfParams(flatParams)) {
        is WfParamParse.Ok -> r
        is WfParamParse.Invalid -> throw IllegalArgumentException(r.message)
      }

    val mergedNamed =
      LinkedHashMap<String, PreviewOverrideValue>().apply {
        base.namedOverrides?.let(::putAll)
        putAll(wfParse.namedOverrides)
      }
    val mergedRcNamed =
      LinkedHashMap<String, RemoteNamedValue>().apply {
        base.remoteCompose?.namedValues?.let(::putAll)
        putAll(wfParse.rcNamedValues)
      }
    val mergedRc =
      if (
        mergedRcNamed.isEmpty() &&
          base.remoteCompose?.profile == null &&
          base.remoteCompose?.player == null &&
          base.remoteCompose?.playerId == null &&
          base.remoteCompose?.documentBase64 == null
      ) {
        null
      } else {
        (base.remoteCompose?.newBuilder() ?: RemoteComposeOverride.Builder())
          .also { it.namedValues = mergedRcNamed }
          .build()
      }

    return PreviewOverrides(
      widthPx = base.widthPx,
      heightPx = base.heightPx,
      minWidthPx = base.minWidthPx,
      minHeightPx = base.minHeightPx,
      maxWidthPx = base.maxWidthPx,
      maxHeightPx = base.maxHeightPx,
      density = base.density,
      localeTag = base.localeTag,
      fontScale = base.fontScale,
      uiMode = base.uiMode,
      orientation = base.orientation,
      device = base.device,
      inspectionMode = base.inspectionMode,
      slotMode = base.slotMode,
      placeholderActive = base.placeholderActive,
      talkBack = base.talkBack,
      touchOverlay = base.touchOverlay,
      themeProvider = base.themeProvider,
      focus = base.focus,
      gestures = base.gestures,
      clearBackground = base.clearBackground,
      ambient = ambParse ?: wfParse.ambient ?: base.ambient,
      clockEpochMillis = effectiveClockMs,
      namedOverrides = mergedNamed.ifEmpty { null },
      remoteCompose = mergedRc,
    )
  }

  /**
   * Project [overrides] onto a baked [WatchFaceComplicationsPayload] so static hosts (bundles or
   * catalogs without an active daemon) still report accurate active complication and configuration
   * state.
   */
  public fun projectComplicationsPayload(
    base: WatchFaceComplicationsPayload,
    overrides: PreviewOverrides,
  ): WatchFaceComplicationsPayload {
    if (
      !hasWatchFaceOverrides(overrides) && overrides.widthPx == null && overrides.heightPx == null
    ) {
      return base
    }
    val rc = LinkedHashMap<String, RemoteNamedValue>()
    overrides.remoteCompose?.namedValues?.forEach { (k, v) -> rc[k.removePrefix("USER:")] = v }
    val ambient =
      when {
        overrides.ambient != null -> overrides.ambient?.state == AmbientStateOverride.AMBIENT
        rc["dwf.is_ambient"] is RemoteNamedValue.BooleanValue ->
          (rc["dwf.is_ambient"] as RemoteNamedValue.BooleanValue).value
        else -> base.isAmbient
      }
    val previewTime =
      overrides.clockEpochMillis?.let { ms ->
        val zdt = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
        "%02d:%02d:%02d".format(Locale.ROOT, zdt.hour, zdt.minute, zdt.second)
      } ?: base.previewTime

    val updatedConfigs =
      base.userConfigurations.map { cfg ->
        val rawOverride = overrides.namedOverrides?.get("wf.config.${cfg.id}")
        val rcVal = rc["dwf.config.${cfg.id}"]
        val nextActive =
          when {
            rawOverride is PreviewOverrideValue.BooleanValue -> rawOverride.value.toString()
            rawOverride is PreviewOverrideValue.IntValue ->
              cfg.options.getOrNull(rawOverride.value) ?: rawOverride.value.toString()
            rawOverride is PreviewOverrideValue.StringValue -> rawOverride.value
            rcVal is RemoteNamedValue.BooleanValue -> rcVal.value.toString()
            rcVal is RemoteNamedValue.IntValue ->
              cfg.options.getOrNull(rcVal.value) ?: rcVal.value.toString()
            rcVal is RemoteNamedValue.StringValue -> rcVal.value
            else -> cfg.activeValue
          }
        cfg.copy(activeValue = nextActive)
      }

    val updatedSlots =
      base.slots.map { slot ->
        val sid = slot.slotId
        val cur = slot.activeState
        val p = "dwf.complication.$sid"
        val typeCode = (rc["$p.type"] as? RemoteNamedValue.IntValue)?.value ?: cur.typeCode
        val typeName = complicationTypeName(typeCode)
        val text = (rc["$p.text"] as? RemoteNamedValue.StringValue)?.value ?: cur.text
        val title = (rc["$p.title"] as? RemoteNamedValue.StringValue)?.value ?: cur.title
        val value = (rc["$p.value"] as? RemoteNamedValue.FloatValue)?.value ?: cur.value
        val min = (rc["$p.min"] as? RemoteNamedValue.FloatValue)?.value ?: cur.min
        val max = (rc["$p.max"] as? RemoteNamedValue.FloatValue)?.value ?: cur.max
        val mono =
          (rc["$p.has_monochromatic_image"] as? RemoteNamedValue.BooleanValue)?.value
            ?: cur.hasMonochromaticImage
        val small =
          (rc["$p.has_small_image"] as? RemoteNamedValue.BooleanValue)?.value ?: cur.hasSmallImage
        val photo =
          (rc["$p.has_photo_image"] as? RemoteNamedValue.BooleanValue)?.value ?: cur.hasPhotoImage
        val imageColor =
          (rc["$p.image_color"] as? RemoteNamedValue.ColorValue)?.argb ?: cur.imageColor
        val accentColor =
          (rc["$p.accent_color"] as? RemoteNamedValue.ColorValue)?.argb ?: cur.accentColor
        val hasRanged =
          (rc["$p.has_ranged_value_colors"] as? RemoteNamedValue.BooleanValue)?.value
            ?: cur.hasRangedValueColors
        val rangedInterp =
          (rc["$p.ranged_value_colors_interpolate"] as? RemoteNamedValue.BooleanValue)?.value
            ?: cur.rangedValueColorsInterpolate
        val hasGoal =
          (rc["$p.has_goal_progress_colors"] as? RemoteNamedValue.BooleanValue)?.value
            ?: cur.hasGoalProgressColors
        val goalInterp =
          (rc["$p.goal_progress_colors_interpolate"] as? RemoteNamedValue.BooleanValue)?.value
            ?: cur.goalProgressColorsInterpolate
        val hasWeighted =
          (rc["$p.has_weighted_elements"] as? RemoteNamedValue.BooleanValue)?.value
            ?: cur.hasWeightedElements
        val countOverride = (rc["$p.color_count"] as? RemoteNamedValue.IntValue)?.value
        val count = (countOverride ?: cur.colorCount).coerceIn(1, MAX_COLOR_STOPS)
        val colors =
          if (countOverride != null) {
            (0 until count).map { idx ->
              (rc["$p.color.$idx"] as? RemoteNamedValue.ColorValue)?.argb
                ?: cur.colors.getOrElse(idx) { "#FFFFFFFF" }
            }
          } else {
            cur.colors
          }
        val weights =
          if (hasWeighted) {
            (0 until count).map { idx ->
              (rc["$p.weight.$idx"] as? RemoteNamedValue.FloatValue)?.value
                ?: cur.weights.getOrElse(idx) { 0f }
            }
          } else {
            emptyList()
          }
        slot.copy(
          activeState =
            WatchFaceActiveComplicationDto(
              type = typeName,
              typeCode = typeCode,
              text = text,
              title = title,
              value = value,
              min = min,
              max = max,
              hasMonochromaticImage = mono,
              hasSmallImage = small,
              hasPhotoImage = photo,
              imageColor = imageColor,
              accentColor = accentColor,
              hasRangedValueColors = hasRanged,
              rangedValueColorsInterpolate = rangedInterp,
              hasGoalProgressColors = hasGoal,
              goalProgressColorsInterpolate = goalInterp,
              hasWeightedElements = hasWeighted,
              colorCount = count,
              colors = if (hasRanged || hasGoal || hasWeighted) colors else emptyList(),
              weights = weights,
            ),
        )
      }

    return base.copy(
      widthPx = overrides.widthPx ?: base.widthPx,
      heightPx = overrides.heightPx ?: base.heightPx,
      previewTime = previewTime,
      isAmbient = ambient,
      userConfigurations = updatedConfigs,
      slots = updatedSlots,
    )
  }

  /** Encode [projectComplicationsPayload] as a [WatchFaceComplicationsOutcome.Ok]. */
  public fun projectComplicationsOutcome(
    base: WatchFaceComplicationsPayload,
    overrides: PreviewOverrides,
    rendererId: String = WatchFaceRendererIds.DWF_RC,
  ): WatchFaceComplicationsOutcome.Ok {
    val projected = projectComplicationsPayload(base, overrides)
    val bytes =
      payloadJson
        .encodeToString(WatchFaceComplicationsPayload.serializer(), projected)
        .encodeToByteArray()
    return WatchFaceComplicationsOutcome.Ok(
      payload = projected,
      json = bytes,
      rendererId = rendererId,
    )
  }

  private fun flattenSlotObject(
    slotId: Int,
    slotObj: JsonObject,
    out: MutableMap<String, String>,
  ) {
    val prefix = "wf.slot.$slotId"
    fun primStr(key: String): String? =
      (slotObj[key]?.takeUnless { it is JsonNull } as? JsonPrimitive)?.content

    primStr("type")?.let { out["$prefix.type"] = it }
    if ("text" in slotObj && slotObj["text"] !is JsonNull) {
      out["$prefix.text"] = primStr("text").orEmpty()
    }
    if ("title" in slotObj && slotObj["title"] !is JsonNull) {
      out["$prefix.title"] = primStr("title").orEmpty()
    }
    primStr("value")?.let { out["$prefix.value"] = it }
    primStr("min")?.let { out["$prefix.min"] = it }
    (primStr("max") ?: primStr("targetValue"))?.let { out["$prefix.max"] = it }
    (primStr("monochromaticImage") ?: primStr("icon"))?.let { raw ->
      out["$prefix.monochromaticImage"] = coerceImagePresence(raw)
    }
    primStr("smallImage")?.let { raw -> out["$prefix.smallImage"] = coerceImagePresence(raw) }
    primStr("photoImage")?.let { raw -> out["$prefix.photoImage"] = coerceImagePresence(raw) }
    primStr("imageColor")?.let { out["$prefix.imageColor"] = it }
    primStr("accentColor")?.let { out["$prefix.accentColor"] = it }
    primStr("interpolate")?.let { out["$prefix.interpolate"] = it }

    val colorArray =
      (slotObj["colors"] as? JsonArray)
        ?: (slotObj["rangedValueColors"] as? JsonArray)
        ?: (slotObj["goalProgressColors"] as? JsonArray)
        ?: (slotObj["weightedElementsColors"] as? JsonArray)
    if (colorArray != null) {
      val joined = colorArray.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(",")
      if (joined.isNotEmpty()) out["$prefix.colors"] = joined
    } else {
      primStr("colors")?.let { out["$prefix.colors"] = it }
    }

    val weightArray =
      (slotObj["weights"] as? JsonArray) ?: (slotObj["weightedElementsWeights"] as? JsonArray)
    if (weightArray != null) {
      val joined = weightArray.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(",")
      if (joined.isNotEmpty()) out["$prefix.weights"] = joined
    } else {
      primStr("weights")?.let { out["$prefix.weights"] = it }
    }
  }

  private fun coerceImagePresence(raw: String): String =
    when (raw.trim().lowercase(Locale.ROOT)) {
      "false",
      "0",
      "none",
      "null",
      "" -> "false"
      else -> "true"
    }

  private fun parseStrictBool(raw: String): Boolean? =
    when (raw.trim().lowercase(Locale.ROOT)) {
      "true",
      "1" -> true
      "false",
      "0" -> false
      else -> null
    }

  private fun normalizeHexColor(raw: String): String? {
    val hex = raw.trim().removePrefix("%23").removePrefix("#")
    return when (hex.length) {
      6 -> hex.toLongOrNull(16)?.let { "#FF${hex.uppercase(Locale.ROOT)}" }
      8 -> hex.toLongOrNull(16)?.let { "#${hex.uppercase(Locale.ROOT)}" }
      else -> null
    }
  }

  private val KNOWN_SLOT_FIELDS: Set<String> =
    setOf(
      "type",
      "text",
      "title",
      "value",
      "min",
      "max",
      "targetValue",
      "monochromaticImage",
      "icon",
      "smallImage",
      "photoImage",
      "imageColor",
      "accentColor",
      "colors",
      "weights",
      "interpolate",
    )

  private const val COMPLICATION_TYPE_EMPTY: Int = 0
  private const val COMPLICATION_TYPE_SHORT_TEXT: Int = 1
  private const val COMPLICATION_TYPE_LONG_TEXT: Int = 2
  private const val COMPLICATION_TYPE_RANGED_VALUE: Int = 3
  private const val COMPLICATION_TYPE_MONOCHROMATIC_IMAGE: Int = 4
  private const val COMPLICATION_TYPE_SMALL_IMAGE: Int = 5
  private const val COMPLICATION_TYPE_PHOTO_IMAGE: Int = 6
  private const val COMPLICATION_TYPE_GOAL_PROGRESS: Int = 7
  private const val COMPLICATION_TYPE_WEIGHTED_ELEMENTS: Int = 8

  internal fun complicationTypeCode(typeName: String): Int =
    when (typeName.trim().uppercase(Locale.ROOT).removePrefix("COMPLICATION_TYPE_")) {
      "EMPTY",
      "NO_DATA",
      "NO_PERMISSION",
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

  internal fun complicationTypeName(code: Int): String =
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
