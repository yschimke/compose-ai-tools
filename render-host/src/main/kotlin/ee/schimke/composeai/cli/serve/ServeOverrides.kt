package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.GestureKindOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.UiMode
import java.security.MessageDigest

/**
 * How a preview is delivered to the browser; the serve URLs and controls are identical across
 * modes. Only [SNAPSHOT] is implemented; [LIVE] is modelled so `/api/previews` can advertise
 * per-preview support and a future in-browser transport fits the same `/p/{id}` surface.
 */
public enum class PreviewMode(public val wire: String) {
  /** Daemon renders server-side; the browser shows PNG bytes. Universal (Android + Desktop). */
  SNAPSHOT("snapshot"),
  /** Composable compiled to Kotlin/Wasm and run live in the browser. CMP-only; not yet built. */
  LIVE("live");

  public companion object {
    public fun parse(raw: String?): PreviewMode? =
      raw?.lowercase()?.let { v -> entries.firstOrNull { it.wire == v } }
  }
}

/** Outcome of parsing query-string overrides — either a typed [PreviewOverrides] or a reason. */
public sealed interface OverrideParse {
  public data class Ok(val overrides: PreviewOverrides) : OverrideParse

  public data class Invalid(val message: String) : OverrideParse
}

/**
 * Pure mapping from `/render` query parameters to a typed [PreviewOverrides], plus a stable cache
 * key over the pixel-affecting fields. Keys mirror the `render-matrix` axes plus extra display
 * knobs the daemon honours.
 */
public object ServeOverrides {

  /** Query keys `/render` understands. Unknown keys are ignored (forward-compatible). */
  public val SUPPORTED_KEYS: Set<String> =
    setOf(
      "uiMode",
      "device",
      "localeTag",
      "fontScale",
      "density",
      "widthPx",
      "heightPx",
      // Wrapped-axis content-size bounds (Max / Min / Within modes); fixed size uses
      // widthPx/heightPx.
      "minWidthPx",
      "minHeightPx",
      "maxWidthPx",
      "maxHeightPx",
      "orientation",
      "inspectionMode",
      "slotMode",
      // Content-loading placeholder state; opt-in on the preview side (`placeholderActive(...)`).
      "placeholderActive",
      // Live-only overlay toggles, composited onto streamed frames during a Live Compose session.
      "talkBack",
      "touchOverlay",
      // FQN of an app-declared @ThemeCatalog `PreviewWrapperProvider`. Daemon-only.
      "themeProvider",
      // Keyboard focus: `focus=<tabIndex>` focuses the n-th focusable and draws the overlay. For
      // `@FocusedPreview` previews; daemon-only.
      "focus",
      // `gestures=true` force-shows one-handed gesture hints. For `@GestureHintPreview` previews on
      // an Android-backed session.
      "gestures",
      // `gestureInvoke=primary|dismiss|scroll|page` fires a one-handed gesture once composition
      // settles, since off-watch there is no sensor source. Android-backed sessions only.
      "gestureInvoke",
      // "Crisp outline": `background=clear` (or aliases) or raw `clearBackground=true`.
      "background",
      "clearBackground",
      // Remote Compose platform profile (`RemoteComposeProfile` wire names). Daemon + Android only.
      // Per-name seeds use the `rc.<name>=…` prefix ([RC_NAMED_PREFIX]).
      "rcProfile",
      // Which server-side player draws the replayed `ir/<id>.rc`: `androidx-view` (or
      // `java`/`view`) → VIEW, `androidx-embedded` (or `embedded`) → EMBEDDED, `cmp-android` and
      // other id-shaped values → `playerId`. Client-side lanes (`camaelon-js`, `cmp-wasm`) and
      // `cmp-jvm` are rejected. Daemon + Android only.
      "rcPlayer",
    )

  /**
   * Prefix for author-declared knobs: `knob.<wireKey>=<value>` (indexed knobs use `key[index]`).
   * The type is inferred from the preview's declaration ([parse]'s `knobKinds`); an explicit
   * `<kind>:<value>` prefix (string/int/float/bool/color) is still honoured for old links and
   * undeclared keys, otherwise bare values are strings. Daemon-backed sessions only. Dynamic, so
   * not in [SUPPORTED_KEYS].
   *
   * An empty `knob.<key>=` is a real value for a string knob (clearing a label); for other kinds it
   * is skipped rather than rejected, so a half-typed number doesn't 400 the viewer's own URL.
   */
  public const val KNOB_PREFIX: String = "knob."

  /**
   * Prefix for Remote Compose named-value seeds: `rc.<name>=<value>` (e.g.
   * `rc.stopColor=color:%23FF8800`), feeding `PreviewOverrides.remoteCompose.namedValues` — a
   * separate channel from `knob.`, and the only way to reach a `rememberNamedRemote*` binding. No
   * declaration to infer from, so values carry their own `<kind>:` tag ([RC_KNOWN_KINDS], default
   * `string`). Daemon + Android only; not in [SUPPORTED_KEYS].
   */
  public const val RC_NAMED_PREFIX: String = "rc."

  /**
   * True when [parse] consumes [key]: a [SUPPORTED_KEYS] axis, a `knob.` knob or an `rc.` seed.
   * HTTP `GET /render` filters queries through this so unrelated params (cache-busters) are
   * ignored. WebSocket override maps are already scoped and passed wholesale.
   */
  public fun isOverrideParam(key: String): Boolean =
    key in SUPPORTED_KEYS || key.startsWith(KNOB_PREFIX) || key.startsWith(RC_NAMED_PREFIX)

  /**
   * The `rc.<name>=<value>` seeds in [params], parsed leniently (malformed values skipped), for the
   * best-effort cmp-jvm lane. [parse] is the strict counterpart; both use the same grammar.
   */
  public fun rcNamedValueSeeds(params: Map<String, String>): Map<String, RemoteNamedValue> {
    val seeds = mutableMapOf<String, RemoteNamedValue>()
    for ((rawKey, raw) in params) {
      if (!rawKey.startsWith(RC_NAMED_PREFIX)) continue
      val name = rawKey.removePrefix(RC_NAMED_PREFIX)
      if (name.isBlank() || raw.isBlank()) continue
      val sep = raw.indexOf(':')
      val kind = if (sep > 0) raw.substring(0, sep).takeIf { it in RC_KNOWN_KINDS } else null
      val value = if (kind != null) raw.substring(sep + 1) else raw
      val seed =
        when (kind ?: "string") {
          "string" -> RemoteNamedValue.StringValue(value)
          "int" -> value.toIntOrNull()?.let { RemoteNamedValue.IntValue(it) }
          "float" -> value.toFloatOrNull()?.let { RemoteNamedValue.FloatValue(it) }
          "dp" -> value.toFloatOrNull()?.let { RemoteNamedValue.DpValue(it) }
          "bool" ->
            RemoteNamedValue.BooleanValue(value.equals("true", ignoreCase = true) || value == "1")
          "color" -> RemoteNamedValue.ColorValue(value)
          else -> null
        }
      if (seed != null) seeds[name] = seed
    }
    return seeds
  }

  /** `<kind>` tags an explicit `knob.<key>=<kind>:<value>` may carry. */
  private val KNOWN_KINDS: Set<String> = setOf("string", "int", "float", "bool", "color")

  /**
   * The bare value of `knob.<key>=<raw>` for the viewer's controls, with a `<kind>:` prefix
   * stripped exactly when [parse] would (known kind matching [declaredKind]). Otherwise `int:3` in
   * a number input sanitizes to empty and the control disagrees with the render. Must match
   * [parse], since a string knob may legitimately start with `int:`.
   */
  public fun knobControlValue(raw: String, declaredKind: String?): String? {
    val sep = raw.indexOf(':')
    val prefix =
      if (sep > 0) {
        raw
          .substring(0, sep)
          .takeIf { it in KNOWN_KINDS }
          ?.takeIf { declaredKind == null || it == declaredKind }
      } else null
    val value = if (prefix != null) raw.substring(sep + 1) else raw
    val kind = prefix ?: declaredKind ?: "string"
    // An empty value on a non-string knob is skipped by [parse], so keep showing the declaration.
    return value.takeUnless { it.isEmpty() && kind != "string" }
  }

  /**
   * The bare value an `rc.<name>=<raw>` seed puts on a declared Remote Compose control, or null
   * when the declaration stands. Stricter than [knobControlValue]: RC seeds default to `string`
   * without a declaration lookup, so a seed applies only when its parsed kind matches the declared
   * one (an agreeing tag, or no tag on a `string` knob).
   */
  public fun rcControlValue(raw: String, declaredKind: String): String? {
    // [parse] skips a blank `rc.` seed entirely, even for strings.
    if (raw.isBlank()) return null
    val sep = raw.indexOf(':')
    val wireKind = if (sep > 0) raw.substring(0, sep).takeIf { it in RC_KNOWN_KINDS } else null
    val value = if (wireKind != null) raw.substring(sep + 1) else raw
    return value.takeIf { (wireKind ?: "string") == declaredKind }
  }

  /**
   * `<kind>` tags for `rc.<name>=<kind>:<value>`: [KNOWN_KINDS] plus `dp`
   * ([RemoteNamedValue.DpValue]), which binds differently from a raw float. Untagged means
   * `string`.
   */
  private val RC_KNOWN_KINDS: Set<String> = setOf("string", "int", "float", "dp", "bool", "color")

  /**
   * Map a `compose/overrides` declaration `type` to its wire kind; shared with the viewer so the
   * widget always matches.
   */
  public fun knobKind(type: String): String =
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
   * `wireKey → kind` for typing bare `knob.` values, from the preview's declared knobs (keyed by
   * [ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration.seedKey]). Empty when unknown.
   */
  public fun declaredKnobKinds(preview: ServePreview?): Map<String, String> =
    preview?.overrides?.associate { it.seedKey to knobKind(it.type) } ?: emptyMap()

  /**
   * Parse [params] (first value per key) into [PreviewOverrides]. Malformed values return
   * [OverrideParse.Invalid] with a reason rather than a silent default; absent or blank keys leave
   * fields null (discovery-time values).
   *
   * [knobKinds] types bare `knob.` values ([declaredKnobKinds]); explicit prefixes still win.
   *
   * [declaredThemeFqns] are the session's `@ThemeCatalog` providers. A `themeProvider` outside it
   * is rejected, because the renderer silently falls back to the default theme for an unloadable
   * class. `null` skips the check; an empty set rejects every `themeProvider`.
   */
  public fun parse(
    params: Map<String, String>,
    knobKinds: Map<String, String> = emptyMap(),
    declaredThemeFqns: Set<String>? = null,
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

    // Wrapped-axis bounds: positive integers; `min > max` on one axis is rejected below.
    val minWidthPx =
      if (blank("minWidthPx")) null
      else
        params.getValue("minWidthPx").toIntOrNull()?.takeIf { it > 0 }
          ?: return OverrideParse.Invalid(
            "minWidthPx must be a positive integer, got '${params["minWidthPx"]}'"
          )

    val minHeightPx =
      if (blank("minHeightPx")) null
      else
        params.getValue("minHeightPx").toIntOrNull()?.takeIf { it > 0 }
          ?: return OverrideParse.Invalid(
            "minHeightPx must be a positive integer, got '${params["minHeightPx"]}'"
          )

    val maxWidthPx =
      if (blank("maxWidthPx")) null
      else
        params.getValue("maxWidthPx").toIntOrNull()?.takeIf { it > 0 }
          ?: return OverrideParse.Invalid(
            "maxWidthPx must be a positive integer, got '${params["maxWidthPx"]}'"
          )

    val maxHeightPx =
      if (blank("maxHeightPx")) null
      else
        params.getValue("maxHeightPx").toIntOrNull()?.takeIf { it > 0 }
          ?: return OverrideParse.Invalid(
            "maxHeightPx must be a positive integer, got '${params["maxHeightPx"]}'"
          )

    if (minWidthPx != null && maxWidthPx != null && minWidthPx > maxWidthPx) {
      return OverrideParse.Invalid(
        "minWidthPx ($minWidthPx) must not exceed maxWidthPx ($maxWidthPx)"
      )
    }
    if (minHeightPx != null && maxHeightPx != null && minHeightPx > maxHeightPx) {
      return OverrideParse.Invalid(
        "minHeightPx ($minHeightPx) must not exceed maxHeightPx ($maxHeightPx)"
      )
    }

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

    val placeholderActive =
      params["placeholderActive"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          when (it.lowercase()) {
            "true",
            "1" -> true
            "false",
            "0" -> false
            else -> return OverrideParse.Invalid("placeholderActive must be a boolean, got '$it'")
          }
        }

    // Live-only overlay flags; malformed values are Invalid.
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

    // `focus=<tabIndex>`: a non-negative integer; absent means no focus override.
    val focus: FocusOverride? =
      params["focus"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          val tabIndex =
            it.toIntOrNull()?.takeIf { n -> n >= 0 }
              ?: return OverrideParse.Invalid(
                "focus must be a non-negative integer tab index, got '$it'"
              )
          FocusOverride.Builder()
            .also {
              it.tabIndex = tabIndex
              it.overlay = true
            }
            .build()
        }

    // `gestures=true|1` shows hints, `false|0` clears them.
    val showGestureHints: Boolean? =
      params["gestures"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          when (it.lowercase()) {
            "true",
            "1" -> true
            "false",
            "0" -> false
            else -> return OverrideParse.Invalid("gestures must be a boolean, got '$it'")
          }
        }

    // `gestureInvoke=<kind>`: the wire kind (`primary` = double pinch, `dismiss` = wrist turn), not
    // the per-handler label.
    val invokeGesture: GestureKindOverride? =
      params["gestureInvoke"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          when (it.lowercase()) {
            "primary" -> GestureKindOverride.PRIMARY
            "dismiss" -> GestureKindOverride.DISMISS
            "scroll" -> GestureKindOverride.SCROLL
            "page" -> GestureKindOverride.PAGE
            else ->
              return OverrideParse.Invalid(
                "gestureInvoke must be primary, dismiss, scroll or page, got '$it'"
              )
          }
        }

    // One override carries both hints and invocation, so they compose; neither present ⇒ null.
    val gestures: GestureOverride? =
      if (showGestureHints == null && invokeGesture == null) null
      else
        GestureOverride.Builder()
          .also {
            it.showHints = showGestureHints
            it.invoke = invokeGesture
          }
          .build()

    // `background=clear` (aliases `transparent`/`none`/`off`; `default`/`show` keep it) or raw
    // `clearBackground=true`; `background` wins. Absent → null.
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

    // `knob.<key>=<value>` knobs; malformed typed values are Invalid.
    val namedOverrides = mutableMapOf<String, PreviewOverrideValue>()
    for ((rawKey, raw) in params) {
      if (!rawKey.startsWith(KNOB_PREFIX)) continue
      val wireKey = rawKey.removePrefix(KNOB_PREFIX)
      if (wireKey.isBlank()) continue
      // Bare values take the declared type (default string). A `<kind>:` prefix counts only when
      // the knob is undeclared or the prefix matches its declared kind, so string knobs can hold
      // text like `int:`.
      val declaredKind = knobKinds[wireKey]
      val sep = raw.indexOf(':')
      val prefix = if (sep > 0) raw.substring(0, sep).takeIf { it in KNOWN_KINDS } else null
      val explicitKind = prefix?.takeIf { declaredKind == null || it == declaredKind }
      val kind = explicitKind ?: declaredKind ?: "string"
      val value = if (explicitKind != null) raw.substring(sep + 1) else raw
      // Empty is a real value for a string knob (e.g. an `@OverrideVariant` seeding `label=`);
      // isEmpty, not isBlank. For other kinds it is skipped rather than Invalid (an emptied number
      // input).
      if (value.isEmpty() && kind != "string") continue
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

    // `rcProfile=<wire name>`; absent → the connector's default ANDROIDX, unknown → Invalid.
    val rcProfile: RemoteComposeProfile? =
      params["rcProfile"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          when (it.lowercase()) {
            "androidx" -> RemoteComposeProfile.ANDROIDX
            "androidx7" -> RemoteComposeProfile.ANDROIDX7
            "androidx8" -> RemoteComposeProfile.ANDROIDX8
            "androidx9" -> RemoteComposeProfile.ANDROIDX9
            "widgetsv6" -> RemoteComposeProfile.WIDGETS_V6
            "widgetsv7" -> RemoteComposeProfile.WIDGETS_V7
            "wearwidgets" -> RemoteComposeProfile.WEAR_WIDGETS
            else ->
              return OverrideParse.Invalid(
                "rcProfile must be one of androidx/androidx7/androidx8/androidx9/widgetsV6/" +
                  "widgetsV7/wearWidgets, got '$it'"
              )
          }
        }

    // `rc.<name>=<value>` seeds (own `<kind>:` tag, default string); malformed values are Invalid.
    val rcNamedValues = mutableMapOf<String, RemoteNamedValue>()
    for ((rawKey, raw) in params) {
      if (!rawKey.startsWith(RC_NAMED_PREFIX)) continue
      val name = rawKey.removePrefix(RC_NAMED_PREFIX)
      if (name.isBlank() || raw.isBlank()) continue
      val sep = raw.indexOf(':')
      val kind = if (sep > 0) raw.substring(0, sep).takeIf { it in RC_KNOWN_KINDS } else null
      val value = if (kind != null) raw.substring(sep + 1) else raw
      rcNamedValues[name] =
        when (kind ?: "string") {
          "string" -> RemoteNamedValue.StringValue(value)
          "int" ->
            value.toIntOrNull()?.let { RemoteNamedValue.IntValue(it) }
              ?: return OverrideParse.Invalid("rc '$name' int must be an integer, got '$value'")
          "float" ->
            value.toFloatOrNull()?.let { RemoteNamedValue.FloatValue(it) }
              ?: return OverrideParse.Invalid("rc '$name' float must be a number, got '$value'")
          "dp" ->
            value.toFloatOrNull()?.let { RemoteNamedValue.DpValue(it) }
              ?: return OverrideParse.Invalid("rc '$name' dp must be a number, got '$value'")
          "bool" ->
            RemoteNamedValue.BooleanValue(value.equals("true", ignoreCase = true) || value == "1")
          // Raw `#AARRGGBB`; the connector tolerates values it can't parse.
          "color" -> RemoteNamedValue.ColorValue(value)
          else -> return OverrideParse.Invalid("rc '$name' has unknown kind '$kind'")
        }
    }

    // `rcPlayer=<backend>` ([RcPlayerBackend.serverSideFromParam]): AndroidX players via the
    // `player` enum, `cmp-android` and other id-shaped values via `playerId` (the daemon resolves
    // or refuses). Lanes that never use the daemon (`camaelon-js`, `cmp-wasm`, `cmp-jvm`) and
    // non-ids are Invalid.
    var rcPlayer: ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind? = null
    var rcPlayerId: String? = null
    params["rcPlayer"]
      ?.takeIf { it.isNotBlank() }
      ?.let { raw ->
        val daemonBackend = RcPlayerBackend.serverSideFromParam(raw)
        when {
          daemonBackend != null -> {
            rcPlayer = daemonBackend.playerKind
            rcPlayerId = daemonBackend.daemonPlayerId
          }
          RcPlayerBackend.isNonDaemonLane(raw) ->
            return OverrideParse.Invalid(
              "rcPlayer '$raw' is not drawn by a server-side render; use androidx-view, " +
                "androidx-embedded, cmp-android, or the id of a player registered with the daemon"
            )
          RcPlayerBackend.isPlayerIdShaped(raw) -> rcPlayerId = raw.trim().lowercase()
          else ->
            return OverrideParse.Invalid(
              "rcPlayer must be a player id (letters, digits, '.', '_', '-'), got '$raw'"
            )
        }
      }

    val themeProvider = params["themeProvider"]?.takeIf { it.isNotBlank() }
    if (themeProvider != null && declaredThemeFqns != null && themeProvider !in declaredThemeFqns) {
      return OverrideParse.Invalid(
        if (declaredThemeFqns.isEmpty()) {
          "themeProvider '$themeProvider' cannot be applied: this catalog declares no " +
            "@ThemeCatalog providers"
        } else {
          "unknown themeProvider '$themeProvider'; this catalog declares " +
            declaredThemeFqns.sorted().joinToString(", ")
        }
      )
    }

    // Null when neither facet is present, keeping rc-free renders' wire shape unchanged.
    val remoteCompose: RemoteComposeOverride? =
      if (rcProfile == null && rcNamedValues.isEmpty() && rcPlayer == null && rcPlayerId == null)
        null
      else
        RemoteComposeOverride.Builder()
          .also {
            it.profile = rcProfile
            it.namedValues = rcNamedValues
            it.player = rcPlayer
            it.playerId = rcPlayerId
          }
          .build()

    return OverrideParse.Ok(
      PreviewOverrides(
        widthPx = widthPx,
        heightPx = heightPx,
        minWidthPx = minWidthPx,
        minHeightPx = minHeightPx,
        maxWidthPx = maxWidthPx,
        maxHeightPx = maxHeightPx,
        density = density,
        localeTag = params["localeTag"]?.takeIf { it.isNotBlank() },
        fontScale = fontScale,
        uiMode = uiMode,
        orientation = orientation,
        device = params["device"]?.takeIf { it.isNotBlank() },
        inspectionMode = inspectionMode,
        slotMode = slotMode,
        placeholderActive = placeholderActive,
        talkBack = talkBack,
        touchOverlay = touchOverlay,
        themeProvider = themeProvider,
        focus = focus,
        gestures = gestures,
        clearBackground = clearBackground,
        namedOverrides = namedOverrides.ifEmpty { null },
        remoteCompose = remoteCompose,
      )
    )
  }

  /**
   * Stable, hashed cache key for a preview + overrides, from the pixel-affecting fields in a fixed
   * order so identical overrides coalesce. Keep in lockstep with [parse].
   */
  public fun cacheKey(previewId: String, o: PreviewOverrides): String {
    val canonical = buildString {
      append(previewId).append(' ')
      append("w=").append(o.widthPx).append('|')
      append("h=").append(o.heightPx).append('|')
      append("minw=").append(o.minWidthPx).append('|')
      append("minh=").append(o.minHeightPx).append('|')
      append("maxw=").append(o.maxWidthPx).append('|')
      append("maxh=").append(o.maxHeightPx).append('|')
      append("d=").append(o.density).append('|')
      append("loc=").append(o.localeTag).append('|')
      append("fs=").append(o.fontScale).append('|')
      append("ui=").append(o.uiMode).append('|')
      append("or=").append(o.orientation).append('|')
      append("dev=").append(o.device).append('|')
      append("insp=").append(o.inspectionMode).append('|')
      append("slot=").append(o.slotMode).append('|')
      append("ph=").append(o.placeholderActive).append('|')
      append("talk=").append(o.talkBack).append('|')
      append("touch=").append(o.touchOverlay).append('|')
      append("theme=").append(o.themeProvider).append('|')
      append("focus=").append(o.focus).append('|')
      append("gestures=").append(o.gestures).append('|')
      append("clearbg=").append(o.clearBackground).append('|')
      // Named overrides sorted by key; value classes have stable toString.
      append("named=")
      o.namedOverrides?.toSortedMap()?.forEach { (k, v) ->
        append(k).append('=').append(v).append(';')
      }
      // Remote Compose seeds and profile must re-render too. acceptedHostActions is never set from
      // serve queries, so it is omitted.
      append("|rcProfile=").append(o.remoteCompose?.profile)
      // Switching RC player must not serve the previous backend's pixels.
      append("|rcPlayer=").append(o.remoteCompose?.player)
      append("|rcPlayerId=").append(o.remoteCompose?.playerId)
      // A replayed document replaces the preview's content, so it must be in the key (digested to
      // keep it fixed-size).
      append("|rcDoc=").append(o.remoteCompose?.documentBase64)
      append("|rc=")
      o.remoteCompose?.namedValues?.toSortedMap()?.forEach { (k, v) ->
        append(k).append('=').append(v).append(';')
      }
    }
    return MessageDigest.getInstance("SHA-256")
      .digest(canonical.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
  }
}
