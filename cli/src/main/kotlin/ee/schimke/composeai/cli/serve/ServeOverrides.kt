package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
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
      // Live-only overlay toggles (held-session / recording features). The daemon composites these
      // onto the streamed frames; a baked snapshot never carries them, so the viewer offers them
      // only while a Live Compose session is active. Booleans, like inspectionMode/slotMode.
      "talkBack",
      "touchOverlay",
      // FQN of an app-declared @ThemeCatalog `PreviewWrapperProvider` to render this preview under
      // (the discrete-theme axis). Daemon-only — a baked bundle has no provider to load.
      "themeProvider",
      // Detected-feature: keyboard focus. `focus=<tabIndex>` lands focus on the n-th focusable and
      // draws the focus overlay (`FocusOverride(tabIndex, overlay=true)`). Offered only for a
      // `@FocusedPreview`-detected preview; daemon-only (the desktop daemon honours it).
      "focus",
      // "crisp outline" toggle. Friendly `background=clear` (aliases below) or the raw
      // `clearBackground=true`; both map to `PreviewOverrides.clearBackground`.
      "background",
      "clearBackground",
    )

  /**
   * Prefix for author-declared named-override knobs: `knob.<wireKey>=<value>`, e.g. `knob.label=Tap
   * me` or `knob.count=3`. `wireKey` is the declaration key (indexed knobs use `key[index]`). The
   * value's **type is inferred from the preview's declaration** (the `knobKinds` map passed to
   * [parse]) — a viewer never has to spell it. An explicit `<kind>:<value>` prefix
   * (`knob.count=int:3`, `kind` one of string/int/float/bool/color) is still honoured for older
   * shared links and keys the server has no declaration for; absent a declaration and a recognised
   * prefix, a bare value parses as a string. The daemon's named-override planner seeds the
   * preview's declared knobs from these, so editing one re-renders the composable (only on a
   * daemon-backed session — a static bundle / the Wasm tier ignore them). Dynamic keys, so not
   * listed in [SUPPORTED_KEYS].
   */
  const val KNOB_PREFIX = "knob."

  /**
   * The `<kind>` tags an explicit `knob.<key>=<kind>:<value>` may carry (legacy /
   * declaration-less).
   */
  private val KNOWN_KINDS: Set<String> = setOf("string", "int", "float", "bool", "color")

  /**
   * Map a `compose/overrides` declaration `type` to the [PreviewOverrideValue] wire kind. Shared
   * with the viewer's control rendering so the inferred type always matches the widget shown.
   */
  fun knobKind(type: String): String =
    when (type.lowercase()) {
      "int" -> "int"
      "float",
      "dp" -> "float"
      "bool",
      "boolean" -> "bool"
      "color" -> "color"
      else -> "string"
    }

  /**
   * The `wireKey → kind` map [parse] uses to type a bare `knob.<key>=<value>`, built from a
   * preview's declared knobs (keyed by
   * [ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration.seedKey]). Empty when the
   * preview is unknown or declares no knobs — a bare value then falls back to string.
   */
  fun declaredKnobKinds(preview: ServePreview?): Map<String, String> =
    preview?.overrides?.associate { it.seedKey to knobKind(it.type) } ?: emptyMap()

  /**
   * Parse [params] (one value per key — the ktor layer collapses multi-values to the first) into a
   * [PreviewOverrides]. Returns [OverrideParse.Invalid] with a human reason on malformed values
   * (bad number, unknown enum) rather than rendering with a silent default. Absent / blank keys
   * leave the corresponding field null (the preview's discovery-time value).
   *
   * [knobKinds] maps a knob's `wireKey` to its declared kind so a bare `knob.<key>=<value>` is
   * typed without the caller spelling it (see [declaredKnobKinds]); an explicit `<kind>:<value>`
   * prefix still wins, and an undeclared key with a bare value falls back to a string.
   */
  fun parse(
    params: Map<String, String>,
    knobKinds: Map<String, String> = emptyMap(),
  ): OverrideParse {
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

    // Live-only overlay flags (daemon composites onto the held session's frames). Parsed like the
    // other booleans; a malformed value is a hard Invalid rather than a silently-dropped toggle.
    val talkBack =
      params["talkBack"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          when (it.lowercase()) {
            "true",
            "1" -> true
            "false",
            "0" -> false
            else -> return OverrideParse.Invalid("talkBack must be a boolean, got '$it'")
          }
        }

    val touchOverlay =
      params["touchOverlay"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          when (it.lowercase()) {
            "true",
            "1" -> true
            "false",
            "0" -> false
            else -> return OverrideParse.Invalid("touchOverlay must be a boolean, got '$it'")
          }
        }

    // Detected-feature: keyboard focus. `focus=<tabIndex>` lands focus on the n-th focusable in tab
    // order and draws the post-capture focus overlay (stroke + label). A non-negative integer; a
    // malformed value is a hard Invalid. Absent → no focus override (discovery-time behaviour).
    val focus: FocusOverride? =
      params["focus"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          val tabIndex =
            it.toIntOrNull()?.takeIf { n -> n >= 0 }
              ?: return OverrideParse.Invalid(
                "focus must be a non-negative integer tab index, got '$it'"
              )
          FocusOverride(tabIndex = tabIndex, overlay = true)
        }

    // Cleared background ("crisp outline"). Two spellings: the friendly `background=clear`
    // (aliases `transparent` / `none` / `off`; `default` / `show` mean "keep the preview's
    // background") and the raw boolean `clearBackground=true`. A present `background` key wins over
    // `clearBackground`. Absent → null (discovery-time background).
    val clearBackground: Boolean? =
      when {
        !blank("background") ->
          when (params.getValue("background").lowercase()) {
            "clear",
            "transparent",
            "none",
            "off" -> true
            "default",
            "show",
            "on" -> false
            else ->
              return OverrideParse.Invalid(
                "background must be 'clear' or 'default', got '${params["background"]}'"
              )
          }
        !blank("clearBackground") ->
          when (params.getValue("clearBackground").lowercase()) {
            "true",
            "1" -> true
            "false",
            "0" -> false
            else ->
              return OverrideParse.Invalid(
                "clearBackground must be a boolean, got '${params["clearBackground"]}'"
              )
          }
        else -> null
      }

    // Named-override knobs (`knob.<key>=<value>`, type inferred from the declaration; a legacy
    // `<kind>:<value>` prefix still wins). A malformed typed value is a hard Invalid (mirrors the
    // numeric fields) rather than a silently-dropped edit.
    val namedOverrides = mutableMapOf<String, PreviewOverrideValue>()
    for ((rawKey, raw) in params) {
      if (!rawKey.startsWith(KNOB_PREFIX)) continue
      val wireKey = rawKey.removePrefix(KNOB_PREFIX)
      if (wireKey.isBlank() || raw.isBlank()) continue
      // Type the value. A bare value takes its type from the preview's declaration (default
      // string). A legacy `<kind>:<value>` prefix is honoured ONLY when the knob is undeclared or
      // the prefix matches its declared kind — otherwise a declared *string* knob could never hold
      // a value that happens to start with `int:` / `color:` / … (the type-free viewer submits such
      // text verbatim), which would silently mistype the seed or strip a legitimate prefix.
      val declaredKind = knobKinds[wireKey]
      val sep = raw.indexOf(':')
      val prefix = if (sep > 0) raw.substring(0, sep).takeIf { it in KNOWN_KINDS } else null
      val explicitKind = prefix?.takeIf { declaredKind == null || it == declaredKind }
      val kind = explicitKind ?: declaredKind ?: "string"
      val value = if (explicitKind != null) raw.substring(sep + 1) else raw
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
        talkBack = talkBack,
        touchOverlay = touchOverlay,
        themeProvider = params["themeProvider"]?.takeIf { it.isNotBlank() },
        focus = focus,
        clearBackground = clearBackground,
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
      append("talk=").append(o.talkBack).append('|')
      append("touch=").append(o.touchOverlay).append('|')
      append("theme=").append(o.themeProvider).append('|')
      append("focus=").append(o.focus).append('|')
      append("clearbg=").append(o.clearBackground).append('|')
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
