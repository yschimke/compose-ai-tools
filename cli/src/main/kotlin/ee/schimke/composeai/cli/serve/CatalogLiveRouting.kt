package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.rcplayer.runtime.RcDocumentCapabilities

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
   * A replayed preview is redrawn from its captured document, never by re-running the composable
   * that authored it. So every axis whose only route to the pixels is a fresh composition is inert:
   * the daemon renders, answers `200`, and hands back bytes byte-identical to the baked snapshot —
   * the #3449 failure mode wearing a successful render's clothes, and worse than the baked case,
   * because `generation=daemon` reads as proof the override was applied.
   *
   * **[caps] is what makes this per document rather than per axis.** Support is a property of the
   * bytes: of `remote-m3`'s 27 published documents 16 declare colour state a palette can move and
   * 11 declare none, so no single answer for `themeProvider` is right even inside one catalog. Pass
   * the document's [RcDocumentCapabilities] and the answer is read off its declarations; pass null
   * (no document, or bytes that don't decode) and it falls back to the conservative answer, which
   * is what this returned for every preview before it could check.
   *
   * Two axes stay named regardless, because they have **no representation in the document at all**:
   * - `themeProvider`'s and the `knob.` overrides' only route is a composition — the
   *   `PreviewWrapperProvider` substitution and the named-override planner both seed *into* one.
   *   (`themeProvider` additionally lands as named colour seeds, which is the part [caps] answers.)
   * - `localeTag` — `stringResource()` resolved to a literal during capture and the text op holds
   *   that literal. Unlike the font/theme pair below, `RemoteContext` exposes no locale among its
   *   system variables, so a document has no way to defer the choice to the host.
   *
   * A **string** `rc.` seed also stays named: the rest of the facet reaches the replayed document
   * through the player's `StateUpdater`, but a string seed does not land in the alpha player
   * (structurally identical to the float path that works, so the divergence is downstream of
   * anything this repo controls). Reported as un-applied until the player honours it.
   *
   * **`fontScale` and `uiMode` are deliberately absent, and that is the subtle one.** They look
   * inert against `remote-m3` — every render there comes back byte-identical to the baked snapshot
   * — but that is a property of *those documents*, not of replay. A document can defer both to the
   * host and resolve them at paint time, with no recomposition. Naming them here would 409 an
   * override the replay can honour, which is exactly the false-refusal this list's narrowness
   * exists to prevent. Deciding them properly needs the player's execution semantics rather than
   * its declarations, which is why [RcDocumentCapabilities] deliberately does not answer `uiMode`.
   *
   * The size / density / device family is **not** listed either: those reach the player through the
   * capture's `displayMetrics`, so a replay can answer them.
   *
   * Still runs [withoutBakedNoOps] first, for the same reason [droppedOverrideNames] does.
   */
  fun irReplayDroppedOverrideNames(
    previewId: String,
    o: PreviewOverrides,
    caps: RcDocumentCapabilities? = null,
  ): List<String> {
    val dropped = withoutBakedNoOps(previewId, o)
    val names = mutableListOf<String>()
    // Order is part of the contract — callers render these as a list, so it stays localeTag,
    // themeProvider, knob.*, rc.* regardless of which of them the document can now answer.
    if (dropped.localeTag != null) names += "localeTag"
    // A palette reaches a replay only as named colour seeds, so it lands exactly when the document
    // declares colour slots. Without a document to read, keep the conservative answer this returned
    // before it could check: dropped.
    if (dropped.themeProvider != null && caps?.supportsThemeProvider != true) {
      names += "themeProvider"
    }
    // Composition-only: the named-override planner seeds into a composition, and there is no
    // document-side counterpart for [caps] to consult.
    dropped.namedOverrides?.keys?.sorted()?.forEach { names += "${ServeOverrides.KNOB_PREFIX}$it" }
    dropped.remoteCompose?.namedValues?.toSortedMap()?.forEach { (name, value) ->
      // A string seed does not land in the alpha player whatever the document says (the float path
      // with the identical shape does), so it stays named until that is fixed upstream.
      val undrivableType = value is RemoteNamedValue.StringValue
      // Beyond that, a seed lands only if the document declares the name at all — which the
      // allow-list could never check, so an `rc.` seed aimed at a name no document carries was
      // silently reported as applied.
      val undeclared = caps != null && !caps.declaresNamedValue(name)
      if (undrivableType || undeclared) names += "${ServeOverrides.RC_NAMED_PREFIX}$name"
    }
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
