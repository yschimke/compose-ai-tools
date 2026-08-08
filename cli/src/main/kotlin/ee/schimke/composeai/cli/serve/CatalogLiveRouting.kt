package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.UiMode

/**
 * Shared override-routing logic for the two trusted-catalog live hosts:
 * - [ServeCatalogLiveHost] — one monolithic `liveBundle` daemon serving every preview by id;
 * - [ServePerPreviewLiveHost] — a daemon per per-preview bundle
 *   (`bundle/previews/<daemon-id>.png`).
 *
 * Both front a baked catalog and re-render only the requests the baked PNG can't satisfy, mapping
 * the catalog id to its daemon-preview id via the same alias. Extracted so the "does this override
 * need a fresh render vs. the baked sticker?" predicate is defined once and can't drift between the
 * two hosts.
 */
internal object CatalogLiveRouting {

  /**
   * The daemon-preview id to route an override [render][ServeHost.render] to, or null to stay
   * baked. Non-null only when [previewId] is a mapped (daemon-renderable) id in [alias] AND the
   * request carries an override the baked PNG can't satisfy ([overridesAffectRender]).
   */
  fun daemonIdForOverrideRender(
    previewId: String,
    overrides: PreviewOverrides,
    alias: Map<String, String>,
  ): String? {
    // No daemon twin (an Android-only variant) ⇒ always baked; it has no live lane.
    val daemonId = alias[previewId] ?: return null
    return if (overridesAffectRender(previewId, overrides)) daemonId else null
  }

  /**
   * [daemonIdForOverrideRender], extended with the session's **live-only** ids
   * ([ServeHost.liveOnlyPreviewIds] — the catalog's deferred previews, published with no baked
   * PNG). Those have nothing to replay, so for them even an override-free browse must go to the
   * daemon; every other id keeps the baked-unless-the-override-demands-otherwise routing that makes
   * browsing instant.
   */
  fun daemonIdForRender(
    previewId: String,
    overrides: PreviewOverrides,
    alias: Map<String, String>,
    liveOnly: Set<String>,
  ): String? =
    if (previewId in liveOnly) alias[previewId]
    else daemonIdForOverrideRender(previewId, overrides, alias)

  /**
   * Whether [o] would change pixels vs the preview's baked sticker, so the render must go to the
   * daemon rather than replay the baked PNG. The baked variant already encodes its **theme** (the
   * `…__light` / `…__dark` id segment) and every other axis at its discovery-time default, so the
   * overrides that merely restate what it already shows ([withoutBakedNoOps]) stay baked (keeping
   * browsing instant); anything else — a font scale, device, locale, orientation, a named knob, a
   * feature override (gestures / focus / keyboard / …) — needs a re-render. Uses data-class
   * equality against a defaults instance so a newly added override field is covered without
   * touching this predicate.
   */
  fun overridesAffectRender(previewId: String, o: PreviewOverrides): Boolean =
    withoutBakedNoOps(previewId, o) != PreviewOverrides()

  /**
   * The overrides in [o] that the **baked** PNG for [previewId] does not reflect, named as the
   * caller spelled them in the query string (`fontScale`, `knob.label`, `rc.stopColor`, …). Empty
   * exactly when [overridesAffectRender] is false — i.e. when the baked pixels are a truthful
   * answer to the request.
   *
   * This is what makes a baked fallback *legible* (#3449). Serving the snapshot for a request that
   * asked for `?fontScale=2.0` produces pixels that are byte-identical to the un-overridden render,
   * so nothing in the response body distinguishes "the override had no visual effect" from "the
   * override was never applied" — a caller comparing renders across override values reads the first
   * and concludes wrongly. The HTTP layer turns a non-empty list into a refusal (or, when the
   * caller opted into the snapshot, into response headers naming exactly these params).
   *
   * [overridesAffectRender] stays the authority on *whether* anything was dropped — it compares
   * against a defaults instance, so a newly added override field is covered without touching this
   * function. The per-field names below are the human detail on top; a field that affects the
   * render but isn't named here (one only the WebSocket lanes can set, or one added later) still
   * reports, as the catch-all `overrides`.
   */
  fun droppedOverrideNames(previewId: String, o: PreviewOverrides): List<String> {
    // Name from the no-op-free copy, not the raw request: an override that merely restates the
    // baked pixels was honoured, so naming it would refuse a request the snapshot answers truly.
    val dropped = withoutBakedNoOps(previewId, o)
    if (dropped == PreviewOverrides()) return emptyList()
    val names = mutableListOf<String>()
    fun add(name: String, value: Any?) {
      if (value != null) names += name
    }
    add("widthPx", dropped.widthPx)
    add("heightPx", dropped.heightPx)
    add("minWidthPx", dropped.minWidthPx)
    add("minHeightPx", dropped.minHeightPx)
    add("maxWidthPx", dropped.maxWidthPx)
    add("maxHeightPx", dropped.maxHeightPx)
    add("density", dropped.density)
    add("localeTag", dropped.localeTag)
    add("fontScale", dropped.fontScale)
    add("uiMode", dropped.uiMode)
    add("orientation", dropped.orientation)
    add("device", dropped.device)
    add("inspectionMode", dropped.inspectionMode)
    add("slotMode", dropped.slotMode)
    add("placeholderActive", dropped.placeholderActive)
    add("talkBack", dropped.talkBack)
    add("touchOverlay", dropped.touchOverlay)
    add("themeProvider", dropped.themeProvider)
    add("focus", dropped.focus)
    add("gestures", dropped.gestures)
    add("clearBackground", dropped.clearBackground)
    dropped.namedOverrides?.keys?.sorted()?.forEach { names += "${ServeOverrides.KNOB_PREFIX}$it" }
    dropped.remoteCompose?.let { rc ->
      add("rcProfile", rc.profile)
      add("rcPlayer", rc.player)
      rc.namedValues.keys.sorted().forEach { names += "${ServeOverrides.RC_NAMED_PREFIX}$it" }
    }
    return names.ifEmpty { listOf("overrides") }
  }

  /**
   * The overrides an **IR replay** of [previewId] cannot honour, named as the caller spelled them —
   * the daemon-lane counterpart of [droppedOverrideNames].
   *
   * A schema-v5 IR-backed preview is redrawn by replaying a captured document, never by re-running
   * the composable that authored it (`BundleIrReplayStore` / `RemoteComposeIrReplay`). So every
   * axis whose *only* route to the pixels is a fresh composition is inert: the daemon renders,
   * answers `200`, and hands back bytes byte-identical to the baked snapshot. That is the #3449
   * failure mode wearing a successful render's clothes — worse than the baked case it was written
   * for, because `generation=daemon` reads as proof the override was applied.
   *
   * Deliberately a **narrow allow-list of the inert axes** rather than the inverse of what replay
   * honours. Getting this set too wide turns working renders into refusals, so an axis earns its
   * place here only by having **no representation in the document at all** — not merely by looking
   * inert against one catalog:
   * - `themeProvider` and the `knob.` named overrides — both are seeded *into* a composition
   *   (`PreviewWrapperProvider` substitution, the named-override planner). There is no composition,
   *   and neither has a document-side counterpart to fall back on.
   * - `localeTag` — `stringResource()` resolved to a literal during capture and the text op holds
   *   that literal. Unlike the font/theme pair below, `RemoteContext` exposes no locale among its
   *   system variables (`ID_*` covers time, window, touch, sensors, density, API level, font size,
   *   dates), so a document has no way to defer the choice to the host.
   * - **string** `rc.` named values. The rest of the Remote Compose facet does reach the replayed
   *   document through the player's `StateUpdater` — `rc.shaderColor` and `rc.progress` both move
   *   pixels on `remote-m3` — but a string seed does not land in the alpha player
   *   (`RemoteContext.setNamedStringOverride` → `overrideText` → `RemoteComposeState.overrideData`
   *   is structurally identical to the float path that works, so the divergence is downstream of
   *   anything this repo controls). Reported as un-applied until the player honours it; the day it
   *   does, this entry comes out and `IrReplayDroppedOverridesTest` is what notices.
   *
   * **`fontScale` and `uiMode` are deliberately absent, and that is the subtle one.** They look
   * inert against `remote-m3` — every render there comes back byte-identical to the baked snapshot
   * — but that is a property of *those documents*, not of replay. A document can defer both to the
   * host and resolve them at paint time, with no recomposition:
   * `RemoteComposeView.getDefaultTextSize()` is `14f * density * Configuration.fontScale`, and
   * `onDraw` derives the paint theme from `Configuration.isNightModeActive()` whenever the player's
   * own theme is `THEME_UNSPECIFIED`. Both read the live Android `Configuration`, which
   * `RenderEngine` already sets per render spec — so the wiring is end-to-end today and a document
   * that reads the host values genuinely responds. The `remote-m3` catalog simply baked absolute
   * text sizes and concrete colours at capture. Naming them here would 409 an override the replay
   * can honour, which is exactly the false-refusal failure this list's narrowness exists to
   * prevent. Their silence on a constant-folded document is authored behaviour, not a server lie.
   *
   * The size / density / device family is **not** listed either: those reach the player through the
   * capture's `displayMetrics`, so a replay can answer them.
   *
   * Still runs [withoutBakedNoOps] first, for the same reason [droppedOverrideNames] does. It is a
   * no-op for the axes that remain — none of them is ever satisfied by baked pixels — but it keeps
   * the two predicates honest about the same baseline if this list ever grows.
   */
  fun irReplayDroppedOverrideNames(previewId: String, o: PreviewOverrides): List<String> {
    val dropped = withoutBakedNoOps(previewId, o)
    val names = mutableListOf<String>()
    if (dropped.localeTag != null) names += "localeTag"
    if (dropped.themeProvider != null) names += "themeProvider"
    dropped.namedOverrides?.keys?.sorted()?.forEach { names += "${ServeOverrides.KNOB_PREFIX}$it" }
    dropped.remoteCompose
      ?.namedValues
      ?.filterValues { it is RemoteNamedValue.StringValue }
      ?.keys
      ?.sorted()
      ?.forEach { names += "${ServeOverrides.RC_NAMED_PREFIX}$it" }
    return names
  }

  /**
   * [o] with the fields the baked PNG **already satisfies** cleared, so what remains is exactly
   * what a baked answer would fail to honour. Two of them:
   * - a `uiMode` matching the variant's own theme. That theme is the LAST `light`/`dark` id segment
   *   (past the component slug) — matching `ServeUrls.wasmAppSrc` / `ServeWeb.cardTheme`. Scanning
   *   for `dark` first would misread a non-theme segment named `dark` in an otherwise-light
   *   variant, wrongly treating `uiMode=dark` as a no-op and dropping the override.
   * - `clearBackground = false` (the `background=default` / `show` / `on` spellings). That asks to
   *   *preserve* the preview's authored background, which is what the baked render drew — so it is
   *   satisfied, not dropped. Only `true` ("crisp outline", strip the background) needs a
   *   re-render.
   */
  private fun withoutBakedNoOps(previewId: String, o: PreviewOverrides): PreviewOverrides {
    val bakedTheme =
      when (previewId.split("__").drop(1).lastOrNull { it == "light" || it == "dark" }) {
        "dark" -> UiMode.DARK
        "light" -> UiMode.LIGHT
        else -> null
      }
    return o.copy(
      uiMode = o.uiMode?.takeIf { it != bakedTheme },
      clearBackground = o.clearBackground?.takeIf { it },
    )
  }
}
