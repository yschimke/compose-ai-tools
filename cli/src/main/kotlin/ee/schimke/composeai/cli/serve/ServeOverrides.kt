package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import java.security.MessageDigest

/**
 * How a preview is delivered to the browser. The `compose-preview serve` surface (URLs, token,
 * override controls) is identical across modes; only the transport behind the viewer changes.
 *
 * Modelled now even though only [SNAPSHOT] is implemented, so the URL scheme and the
 * `/api/previews` payload can advertise per-preview mode support from day one and the future
 * in-browser [LIVE] transport (CMP→JS) slots into the same `/p/{id}` surface instead of a parallel
 * one. See the plan's "design both now, build PNG first" decision.
 */
enum class PreviewMode(val wire: String) {
  /** Daemon renders server-side; the browser shows PNG bytes. Universal (Android + Desktop). */
  SNAPSHOT("snapshot"),
  /** Composable compiled to Kotlin/Wasm and run live in the browser. CMP-only; not yet built. */
  LIVE("live");

  companion object {
    fun parse(raw: String?): PreviewMode? =
      raw?.lowercase()?.let { v -> entries.firstOrNull { it.wire == v } }
  }
}

/** Outcome of parsing query-string overrides — either a typed [PreviewOverrides] or a reason. */
sealed interface OverrideParse {
  data class Ok(val overrides: PreviewOverrides) : OverrideParse

  data class Invalid(val message: String) : OverrideParse
}

/**
 * Pure mapping from `/render` query parameters to a typed [PreviewOverrides], plus a stable cache
 * key over the pixel-affecting fields. No IO — unit-tested directly. The accepted keys mirror the
 * `render-matrix` axes ([ee.schimke.composeai.mcp.MatrixCell]) plus the extra display knobs the
 * daemon already honours, so behaviour matches the rest of the CLI.
 */
object ServeOverrides {

  /** Query keys `/render` understands. Unknown keys are ignored (forward-compatible). */
  val SUPPORTED_KEYS: Set<String> =
    setOf(
      "uiMode",
      "device",
      "localeTag",
      "fontScale",
      "density",
      "widthPx",
      "heightPx",
      "orientation",
      "inspectionMode",
      "slotMode",
    )

  /**
   * Prefix for author-declared named-override knobs: `knob.<wireKey>=<kind>:<value>`, e.g.
   * `knob.label=string:Tap me` or `knob.count=int:3`. `wireKey` is the declaration key (indexed
   * knobs use `key[index]`); `kind` is one of string/int/float/bool/color, matching
   * [PreviewOverrideValue]. The daemon's named-override planner seeds the preview's declared knobs
   * from these, so editing one re-renders the composable (only on a daemon-backed session — a
   * static bundle / the Wasm tier ignore them). Dynamic keys, so not listed in [SUPPORTED_KEYS].
   */
  const val KNOB_PREFIX = "knob."

  /**
   * Parse [params] (one value per key — the ktor layer collapses multi-values to the first) into a
   * [PreviewOverrides]. Returns [OverrideParse.Invalid] with a human reason on malformed values
   * (bad number, unknown enum) rather than rendering with a silent default. Absent / blank keys
   * leave the corresponding field null (the preview's discovery-time value).
   */
  fun parse(params: Map<String, String>): OverrideParse {
    fun blank(key: String): Boolean = params[key]?.isBlank() ?: true

    val uiMode =
      params["uiMode"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          when (it.lowercase()) {
            "light" -> UiMode.LIGHT
            "dark" -> UiMode.DARK
            else -> return OverrideParse.Invalid("uiMode must be 'light' or 'dark', got '$it'")
          }
        }

    val orientation =
      params["orientation"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          when (it.lowercase()) {
            "portrait" -> Orientation.PORTRAIT
            "landscape" -> Orientation.LANDSCAPE
            else ->
              return OverrideParse.Invalid(
                "orientation must be 'portrait' or 'landscape', got '$it'"
              )
          }
        }

    val fontScale =
      if (blank("fontScale")) null
      else
        params.getValue("fontScale").toFloatOrNull()?.takeIf { it > 0f }
          ?: return OverrideParse.Invalid(
            "fontScale must be a positive number, got '${params["fontScale"]}'"
          )

    val density =
      if (blank("density")) null
      else
        params.getValue("density").toFloatOrNull()?.takeIf { it > 0f }
          ?: return OverrideParse.Invalid(
            "density must be a positive number, got '${params["density"]}'"
          )

    val widthPx =
      if (blank("widthPx")) null
      else
        params.getValue("widthPx").toIntOrNull()?.takeIf { it > 0 }
          ?: return OverrideParse.Invalid(
            "widthPx must be a positive integer, got '${params["widthPx"]}'"
          )

    val heightPx =
      if (blank("heightPx")) null
      else
        params.getValue("heightPx").toIntOrNull()?.takeIf { it > 0 }
          ?: return OverrideParse.Invalid(
            "heightPx must be a positive integer, got '${params["heightPx"]}'"
          )

    val inspectionMode =
      params["inspectionMode"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          when (it.lowercase()) {
            "true",
            "1" -> true
            "false",
            "0" -> false
            else -> return OverrideParse.Invalid("inspectionMode must be a boolean, got '$it'")
          }
        }

    val slotMode =
      params["slotMode"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          when (it.lowercase()) {
            "true",
            "1" -> true
            "false",
            "0" -> false
            else -> return OverrideParse.Invalid("slotMode must be a boolean, got '$it'")
          }
        }

    // Named-override knobs (`knob.<key>=<kind>:<value>`). A malformed typed value is a hard
    // Invalid (mirrors the numeric fields) rather than a silently-dropped edit.
    val namedOverrides = mutableMapOf<String, PreviewOverrideValue>()
    for ((rawKey, raw) in params) {
      if (!rawKey.startsWith(KNOB_PREFIX)) continue
      val wireKey = rawKey.removePrefix(KNOB_PREFIX)
      if (wireKey.isBlank() || raw.isBlank()) continue
      val sep = raw.indexOf(':')
      if (sep <= 0) {
        return OverrideParse.Invalid("knob '$wireKey' must be '<kind>:<value>', got '$raw'")
      }
      val kind = raw.substring(0, sep)
      val value = raw.substring(sep + 1)
      namedOverrides[wireKey] =
        when (kind) {
          "string" -> PreviewOverrideValue.StringValue(value)
          "int" ->
            value.toIntOrNull()?.let { PreviewOverrideValue.IntValue(it) }
              ?: return OverrideParse.Invalid(
                "knob '$wireKey' int must be an integer, got '$value'"
              )
          "float" ->
            value.toFloatOrNull()?.let { PreviewOverrideValue.FloatValue(it) }
              ?: return OverrideParse.Invalid(
                "knob '$wireKey' float must be a number, got '$value'"
              )
          "bool" ->
            PreviewOverrideValue.BooleanValue(
              value.equals("true", ignoreCase = true) || value == "1"
            )
          "color" -> PreviewOverrideValue.ColorValue(value)
          else -> return OverrideParse.Invalid("knob '$wireKey' has unknown kind '$kind'")
        }
    }

    return OverrideParse.Ok(
      PreviewOverrides(
        widthPx = widthPx,
        heightPx = heightPx,
        density = density,
        localeTag = params["localeTag"]?.takeIf { it.isNotBlank() },
        fontScale = fontScale,
        uiMode = uiMode,
        orientation = orientation,
        device = params["device"]?.takeIf { it.isNotBlank() },
        inspectionMode = inspectionMode,
        slotMode = slotMode,
        namedOverrides = namedOverrides.ifEmpty { null },
      )
    )
  }

  /**
   * Stable cache key for one rendered preview + its overrides. Built from the pixel-affecting
   * fields in a fixed order (independent of query-param order) and hashed, so identical overrides
   * coalesce to one render and the key is safe as a map key. Only the fields tier 1 supports
   * participate; adding a field here is the one place to keep in lockstep with [parse].
   */
  fun cacheKey(previewId: String, o: PreviewOverrides): String {
    val canonical = buildString {
      append(previewId).append(' ')
      append("w=").append(o.widthPx).append('|')
      append("h=").append(o.heightPx).append('|')
      append("d=").append(o.density).append('|')
      append("loc=").append(o.localeTag).append('|')
      append("fs=").append(o.fontScale).append('|')
      append("ui=").append(o.uiMode).append('|')
      append("or=").append(o.orientation).append('|')
      append("dev=").append(o.device).append('|')
      append("insp=").append(o.inspectionMode).append('|')
      append("slot=").append(o.slotMode).append('|')
      // Named overrides participate so a knob edit isn't coalesced onto the prior render. Sorted by
      // key for order-independence; the value data classes have stable toString.
      append("named=")
      o.namedOverrides?.toSortedMap()?.forEach { (k, v) ->
        append(k).append('=').append(v).append(';')
      }
    }
    return MessageDigest.getInstance("SHA-256")
      .digest(canonical.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
  }
}
