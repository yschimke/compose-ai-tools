package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
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
   * `…__light` / `…__dark` id segment) and every other axis at its discovery-time default, so a
   * bare `uiMode` that matches the variant is a no-op and stays baked (keeping browsing instant);
   * anything else — a font scale, device, locale, orientation, a named knob, a feature override
   * (gestures / focus / keyboard / …) — needs a re-render. Uses data-class equality against a
   * defaults instance so a newly added override field is covered without touching this predicate.
   */
  fun overridesAffectRender(previewId: String, o: PreviewOverrides): Boolean {
    // A uiMode matching the baked variant is a no-op; drop it, then any remaining set field
    // (including a *differing* uiMode) means a re-render is required.
    return if (uiModeIsNoOp(previewId, o)) o.copy(uiMode = null) != PreviewOverrides() else true
  }

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
    if (!overridesAffectRender(previewId, o)) return emptyList()
    val names = mutableListOf<String>()
    fun add(name: String, value: Any?) {
      if (value != null) names += name
    }
    add("widthPx", o.widthPx)
    add("heightPx", o.heightPx)
    add("minWidthPx", o.minWidthPx)
    add("minHeightPx", o.minHeightPx)
    add("maxWidthPx", o.maxWidthPx)
    add("maxHeightPx", o.maxHeightPx)
    add("density", o.density)
    add("localeTag", o.localeTag)
    add("fontScale", o.fontScale)
    // Only a uiMode differing from the baked variant's own theme was dropped; a matching one is
    // already what these pixels show.
    if (!uiModeIsNoOp(previewId, o)) names += "uiMode"
    add("orientation", o.orientation)
    add("device", o.device)
    add("inspectionMode", o.inspectionMode)
    add("slotMode", o.slotMode)
    add("placeholderActive", o.placeholderActive)
    add("talkBack", o.talkBack)
    add("touchOverlay", o.touchOverlay)
    add("themeProvider", o.themeProvider)
    add("focus", o.focus)
    add("gestures", o.gestures)
    add("clearBackground", o.clearBackground)
    o.namedOverrides?.keys?.sorted()?.forEach { names += "${ServeOverrides.KNOB_PREFIX}$it" }
    o.remoteCompose?.let { rc ->
      add("rcProfile", rc.profile)
      add("rcPlayer", rc.player)
      rc.namedValues.keys.sorted().forEach { names += "${ServeOverrides.RC_NAMED_PREFIX}$it" }
    }
    return names.ifEmpty { listOf("overrides") }
  }

  /**
   * Whether [o]'s `uiMode` (if any) already matches the baked variant's own theme, making it a
   * no-op the baked PNG satisfies.
   *
   * The theme is the LAST `light`/`dark` id segment (past the component slug) — matching
   * `ServeUrls.wasmAppSrc` / `ServeWeb.cardTheme`. Scanning for `dark` first would misread a
   * non-theme segment named `dark` in an otherwise-light variant, wrongly treating `uiMode=dark` as
   * a no-op and dropping the override.
   */
  private fun uiModeIsNoOp(previewId: String, o: PreviewOverrides): Boolean {
    if (o.uiMode == null) return true
    val bakedTheme =
      when (previewId.split("__").drop(1).lastOrNull { it == "light" || it == "dark" }) {
        "dark" -> UiMode.DARK
        "light" -> UiMode.LIGHT
        else -> null
      }
    return o.uiMode == bakedTheme
  }
}
