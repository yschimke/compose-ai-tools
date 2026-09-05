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
      // Content-loading placeholder state (#2646): `placeholderActive=true` renders a placeholdered
      // preview in its loading state, `false` in the loaded one. Boolean, like slotMode; opt-in on
      // the preview's side (it must read `placeholderActive(...)` into its `PlaceholderState`).
      "placeholderActive",
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
      // Detected-feature: FIRE a one-handed gesture (`GestureOverride(invoke=…)`, issue #5102). The
      // double pinch and the wrist turn are sensor events — no pointer stands in for them, and off
      // a watch there is no gesture source at all — so a gesture-aware preview could play its hint
      // and then never be taken up. `gestureInvoke=primary|dismiss|scroll|page` names the gesture
      // to fire; the daemon runs the matching handler once composition has settled. Same audience
      // and same lane as `gestures`: an Android-backed daemon session.
      "gestureInvoke",
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
      // Remote Compose render backend (the viewer's per-preview backend selector). Selects which
      // *server-side* player draws the replayed `ir/<id>.rc` document: `java`/`view` →
      // `RemoteComposePlayerKind.VIEW`, `cmp-android`/`embedded` → `EMBEDDED`. The client-side `js`
      // canvas lane and the not-yet-renderable `cmp-jvm` lane never ride this param (js replays the
      // doc in-browser; cmp-jvm has no draw path), so those values are rejected. Daemon-only +
      // Android-only — a desktop/static session has no Remote Compose runtime and ignores it.
      "rcPlayer",
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
   *
   * **`knob.<key>=` with no value is a value, for a string knob.** Clearing a label is an edit a
   * viewer has to be able to express, and an `@OverrideVariant` can seed one deliberately (`strings
   * = ["label="]`, which discovery preserves). For any other kind there is nothing to parse out of
   * it, so it is skipped rather than rejected — the viewer builds these URLs itself and a
   * half-typed number field must not 400 the page it came from.
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
   * The Remote Compose `rc.<name>=<value>` seeds in [params], parsed **leniently** — a malformed
   * typed value is skipped rather than failing. Used by the cmp-jvm render lane, which renders the
   * captured document server-side and is best-effort (a bad seed drops to the authored default
   * rather than 400ing the whole render). The strict counterpart lives inline in [parse], which
   * returns [OverrideParse.Invalid] for the daemon lanes. Both read the same `<kind>:` grammar
   * ([RC_KNOWN_KINDS], default `string`).
   */
  fun rcNamedValueSeeds(params: Map<String, String>): Map<String, RemoteNamedValue> {
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

  /**
   * The `<kind>` tags an explicit `knob.<key>=<kind>:<value>` may carry (legacy /
   * declaration-less).
   */
  private val KNOWN_KINDS: Set<String> = setOf("string", "int", "float", "bool", "color")

  /**
   * The bare value a `knob.<key>=<raw>` param holds, with a legacy `<kind>:` prefix stripped when
   * [parse] would strip it — i.e. when the prefix is a known kind AND matches the knob's
   * [declaredKind].
   *
   * For the viewer's *controls*, which hold the bare value while the wire may carry the tag. A
   * `?knob.count=int:3` seeded verbatim puts `int:3` in a number input, which the browser sanitizes
   * to empty; `?knob.enabled=bool:true` reads as unchecked, since the checkbox tests the whole
   * string. Either way the control ends up disagreeing with the render the same URL produced, and
   * the next query built from that control drops or inverts the value.
   *
   * The prefix rule is deliberately NOT re-spelled at the call site: a declared *string* knob may
   * legitimately hold text beginning `int:` / `color:` (the type-free viewer submits it verbatim),
   * so what may be stripped is exactly what [parse] treats as a type tag, and the two must agree
   * for the control and the pixels to.
   */
  fun knobControlValue(raw: String, declaredKind: String?): String? {
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
    // `knob.count=` / `knob.count=int:` — an EMPTY value on a non-string knob. [parse] skips it (it
    // has nothing to parse), so the render keeps the declaration; blanking the number field beside
    // those pixels would show a value the picture isn't. An empty STRING is a real value there and
    // a real one here.
    return value.takeUnless { it.isEmpty() && kind != "string" }
  }

  /**
   * The bare value an `rc.<name>=<raw>` param puts on a **declared** Remote Compose control, or
   * null when the seed does not apply to it and the declaration should stand.
   *
   * Stricter than [knobControlValue], because [parse] types the two differently. A plain knob takes
   * its type from the DECLARATION, so a bare `knob.count=3` is an int on an int knob. An RC seed
   * carries its own tag and defaults to `string` with no declaration lookup at all — so a bare
   * `rc.count=3` parses as `StringValue("3")` and leaves a declared *int* knob on its authored
   * value. Showing `3` on that control would contradict the pixels it was rendered beside, and the
   * next query built from it serialises `rc.count=int:3`, quietly turning a request the renderer
   * ignored into one it obeys.
   *
   * So a seed is taken only when the kind it will actually parse as matches the declared one: an
   * explicit tag that agrees, or no tag at all on a `string` knob. Anything else — a bare value on
   * a typed knob, a `float:` on an `int` — leaves the control showing what the render used.
   */
  fun rcControlValue(raw: String, declaredKind: String): String? {
    // A blank `rc.<name>=` is skipped wholesale by [parse] — unlike a knob, not even a string RC
    // seed accepts one — so the declaration stands and the control has to keep showing it.
    if (raw.isBlank()) return null
    val sep = raw.indexOf(':')
    val wireKind = if (sep > 0) raw.substring(0, sep).takeIf { it in RC_KNOWN_KINDS } else null
    val value = if (wireKind != null) raw.substring(sep + 1) else raw
    return value.takeIf { (wireKind ?: "string") == declaredKind }
  }

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
   *
   * [declaredThemeFqns] is the session's `@ThemeCatalog` provider FQNs
   * ([ServeHost.declaredThemes]). A `themeProvider` outside that set is rejected here rather than
   * passed down: the renderer's `loadWrapperByFqnOrNull` logs to stderr and returns null on a class
   * it can't load, so a misspelled or stale FQN used to come back HTTP 200 with a
   * **default-themed** PNG — visually indistinguishable from a theme that genuinely renders the
   * same, and therefore the kind of failure a caller can stare straight through. `null` (the
   * default) means "the caller doesn't know this session's themes" and skips the check; an **empty
   * set** means the session declares none, so any `themeProvider` is rejected — nothing could apply
   * it.
   */
  fun parse(
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

    // Detected-feature: fire a gesture (issue #5102). `gestureInvoke=<kind>` names which of the
    // wearer's gestures to run — `primary` is the double pinch, `dismiss` the wrist turn — and the
    // daemon invokes the matching registered handler once composition has settled. Spelled as the
    // wire kind rather than as a label, because the label is authored per handler and a viewer
    // offering "Double pinch" must not have to know what this preview called it.
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

    // One override carries both halves, so hints and an invocation compose: a viewer can show the
    // hint and fire the gesture in the same render, which is what "play the hint, then take it up"
    // looks like. Absent from both ⇒ null, i.e. no gesture override at all rather than an empty
    // one.
    val gestures: GestureOverride? =
      if (showGestureHints == null && invokeGesture == null) null
      else GestureOverride(showHints = showGestureHints, invoke = invokeGesture)

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
      if (wireKey.isBlank()) continue
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
      // `knob.label=` — an EMPTY value. For a string knob that is a real value, not a missing one:
      // clearing a label is an edit a viewer must be able to express, and an `@OverrideVariant` may
      // seed one deliberately (`strings = ["label="]`). Dropping it (as a blanket blank-skip did)
      // silently rendered the author default instead — the seeded variant's whole point inverted.
      // Whitespace is likewise a real string, so this tests isEmpty rather than isBlank.
      //
      // For every other kind there is nothing to parse out of "", and a viewer legitimately
      // produces it (an emptied number input). That stays a skipped no-op rather than a hard
      // Invalid, which would 400 a URL the viewer itself built.
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

    // Remote Compose render backend (`rcPlayer=<backend>`). Maps a server-side backend id onto the
    // daemon's `RemoteComposePlayerKind` (`java`/`view` → VIEW, `cmp-android`/`embedded` →
    // EMBEDDED).
    // The client-side `js` canvas and the not-yet-renderable `cmp-jvm` lane never ride the PNG
    // render param, so those (and any other value) are a hard Invalid rather than a silent default.
    val rcPlayer =
      params["rcPlayer"]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          RcPlayerBackend.serverSideFromParam(it)?.playerKind
            ?: return OverrideParse.Invalid(
              "rcPlayer must be one of java/view or cmp-android/embedded, got '$it'"
            )
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

    // Fold the two Remote Compose facets into one override, or leave null when neither is present
    // so
    // an rc-free render carries no `remoteCompose` payload (identical wire shape to before).
    val remoteCompose: RemoteComposeOverride? =
      if (rcProfile == null && rcNamedValues.isEmpty() && rcPlayer == null) null
      else
        RemoteComposeOverride(profile = rcProfile, namedValues = rcNamedValues, player = rcPlayer)

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
      append("ph=").append(o.placeholderActive).append('|')
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
      // The render backend participates so switching the RC player (java ⇄ cmp-android) re-renders
      // rather than serving the prior backend's cached pixels under a shared key.
      append("|rcPlayer=").append(o.remoteCompose?.player)
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
