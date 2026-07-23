package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
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
      // Wrapped-axis content-size bounds (the Max / Min / Within size modes). Fixed size uses
      // widthPx/heightPx above; these constrain a wrapping preview's intrinsic measure instead.
      "minWidthPx",
      "minHeightPx",
      "maxWidthPx",
      "maxHeightPx",
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
      // Detected-feature: one-handed (wear) gesture hints. `gestures=true` force-shows the gesture
      // hint affordance (`GestureOverride(showHints=true)`). Offered only for a
      // `@GestureHintPreview`-detected preview on an Android-backed session (the desktop daemon
      // ignores it).
      "gestures",
      // "crisp outline" toggle. Friendly `background=clear` (aliases below) or the raw
      // `clearBackground=true`; both map to `PreviewOverrides.clearBackground`.
      "background",
      "clearBackground",
      // Remote Compose platform profile (`RcPlatformProfiles` variant) the daemon compiles the
      // remote document against. Wire names match `RemoteComposeProfile` (androidx, androidx7…,
      // widgetsV6/V7, wearWidgets). Daemon-only + Android-only — a desktop/static session ignores
      // it. The per-name seeds ride the dynamic `rc.<name>=…` prefix ([RC_NAMED_PREFIX]), like the
      // `knob.` knobs.
      "rcProfile",
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
   * Prefix for Remote Compose named-value seeds: `rc.<name>=<value>`, e.g. `rc.label=Tap me` or
   * `rc.stopColor=color:%23FF8800`. These feed `PreviewOverrides.remoteCompose.namedValues` (the
   * `RemoteComposeOverride` facet the `:data-remotecompose-connector` bridges into the running
   * `RemoteDocumentPlayer`'s `StateUpdater`), which is a **separate channel** from the generic
   * `knob.` overrides — a Remote Compose sticker's `rememberNamedRemote*` binding is reachable only
   * through this facet, never the `compose/overrides` knob map. Unlike `knob.`, there is no
   * per-preview declaration to infer the type from, so the value carries its own `<kind>:<value>`
   * tag ([RC_KNOWN_KINDS], default `string`). Daemon-only + Android-only — a desktop/static session
   * has no Remote Compose runtime and ignores it. Dynamic keys, so not listed in [SUPPORTED_KEYS].
   */
  const val RC_NAMED_PREFIX = "rc."

  /**
   * True when [key] is a param [parse] consumes — a fixed [SUPPORTED_KEYS] axis, an author-declared
   * `knob.` knob, or an `rc.` Remote Compose seed. The HTTP `GET /render` handlers filter the query
   * string through this so a dynamic knob/rc edit reaches [parse] instead of being dropped, while
   * an unrelated param (a cache-buster, an analytics tag) never does. The `message.overrides` map
   * the WebSocket live/stream sessions send is already scoped, so it is passed to [parse]
   * wholesale.
   */
  fun isOverrideParam(key: String): Boolean =
    key in SUPPORTED_KEYS || key.startsWith(KNOB_PREFIX) || key.startsWith(RC_NAMED_PREFIX)

  /**
   * The `<kind>` tags an explicit `knob.<key>=<kind>:<value>` may carry (legacy /
   * declaration-less).
   */
  private val KNOWN_KINDS: Set<String> = setOf("string", "int", "float", "bool", "color")

  /**
   * The `<kind>` tags an `rc.<name>=<kind>:<value>` seed may carry. Superset of [KNOWN_KINDS] with
   * `dp` — Remote Compose distinguishes a density-independent measure ([RemoteNamedValue.DpValue])
   * from a raw float, matching the connector's `setUserLocalFloat` (dp) vs `setUserLocalFloat`
   * (float) bind. A bare value with no recognised prefix is a `string`.
   */
  private val RC_KNOWN_KINDS: Set<String> = setOf("string", "int", "float", "dp", "bool", "color")

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

    // Wrapped-axis content-size bounds (Max / Min / Within). Same positive-integer grammar as the
    // fixed widthPx/heightPx; a malformed value is a hard Invalid rather than a silently-dropped
    // bound. A `min > max` on the same axis is rejected below — it can't be satisfied.
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

    // Detected-feature: one-handed gesture hints. `gestures=true` (or `1`) force-shows the gesture
    // hint affordance; `false`/`0` clears it. A malformed value is a hard Invalid.
    val gestures: GestureOverride? =
      params["gestures"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          when (it.lowercase()) {
            "true",
            "1" -> GestureOverride(showHints = true)
            "false",
            "0" -> GestureOverride(showHints = false)
            else -> return OverrideParse.Invalid("gestures must be a boolean, got '$it'")
          }
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

    // Remote Compose profile (`rcProfile=<wire name>`). Absent → null (the connector's default
    // ANDROIDX). An unknown value is a hard Invalid rather than a silently-ignored profile.
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

    // Remote Compose named-value seeds (`rc.<name>=<value>`, own `<kind>:<value>` tag, default
    // string). A malformed typed value is a hard Invalid, mirroring the `knob.` block.
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
          // Color carries the raw `#AARRGGBB` string; the connector strips `#` and skips a value it
          // can't parse (a panel typo must not crash the render), so accept any string here.
          "color" -> RemoteNamedValue.ColorValue(value)
          else -> return OverrideParse.Invalid("rc '$name' has unknown kind '$kind'")
        }
    }

    // Fold the two Remote Compose facets into one override, or leave null when neither is present
    // so
    // an rc-free render carries no `remoteCompose` payload (identical wire shape to before).
    val remoteCompose: RemoteComposeOverride? =
      if (rcProfile == null && rcNamedValues.isEmpty()) null
      else RemoteComposeOverride(profile = rcProfile, namedValues = rcNamedValues)

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
        talkBack = talkBack,
        touchOverlay = touchOverlay,
        themeProvider = params["themeProvider"]?.takeIf { it.isNotBlank() },
        focus = focus,
        gestures = gestures,
        clearBackground = clearBackground,
        namedOverrides = namedOverrides.ifEmpty { null },
        remoteCompose = remoteCompose,
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
      append("talk=").append(o.talkBack).append('|')
      append("touch=").append(o.touchOverlay).append('|')
      append("theme=").append(o.themeProvider).append('|')
      append("focus=").append(o.focus).append('|')
      append("gestures=").append(o.gestures).append('|')
      append("clearbg=").append(o.clearBackground).append('|')
      // Named overrides participate so a knob edit isn't coalesced onto the prior render. Sorted by
      // key for order-independence; the value data classes have stable toString.
      append("named=")
      o.namedOverrides?.toSortedMap()?.forEach { (k, v) ->
        append(k).append('=').append(v).append(';')
      }
      // Remote Compose facet participates for the same reason — an `rc.` seed / profile edit must
      // re-render. Named values sorted for order-independence; the value/profile toStrings are
      // stable. acceptedHostActions is never set from the serve query path, so it is omitted.
      append("|rcProfile=").append(o.remoteCompose?.profile)
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
