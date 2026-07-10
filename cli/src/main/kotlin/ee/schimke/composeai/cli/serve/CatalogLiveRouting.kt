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
   * Whether [o] would change pixels vs the preview's baked sticker, so the render must go to the
   * daemon rather than replay the baked PNG. The baked variant already encodes its **theme** (the
   * `…__light` / `…__dark` id segment) and every other axis at its discovery-time default, so a
   * bare `uiMode` that matches the variant is a no-op and stays baked (keeping browsing instant);
   * anything else — a font scale, device, locale, orientation, a named knob, a feature override
   * (gestures / focus / keyboard / …) — needs a re-render. Uses data-class equality against a
   * defaults instance so a newly added override field is covered without touching this predicate.
   */
  fun overridesAffectRender(previewId: String, o: PreviewOverrides): Boolean {
    // The theme is the LAST `light`/`dark` id segment (past the component slug) — matching
    // `ServeUrls.wasmAppSrc` / `ServeWeb.cardTheme`. Scanning for `dark` first would misread a
    // non-theme segment named `dark` in an otherwise-light variant, wrongly treating `uiMode=dark`
    // as a no-op and dropping the override.
    val bakedTheme =
      when (previewId.split("__").drop(1).lastOrNull { it == "light" || it == "dark" }) {
        "dark" -> UiMode.DARK
        "light" -> UiMode.LIGHT
        else -> null
      }
    // A uiMode matching the baked variant is a no-op; drop it, then any remaining set field
    // (including a *differing* uiMode) means a re-render is required.
    val uiModeIsNoOp = o.uiMode == null || o.uiMode == bakedTheme
    return if (uiModeIsNoOp) o.copy(uiMode = null) != PreviewOverrides() else true
  }
}
