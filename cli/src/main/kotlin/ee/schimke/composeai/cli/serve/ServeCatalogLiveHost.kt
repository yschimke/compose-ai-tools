package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams

/**
 * A [ServeHost] that fronts a trusted design-system catalog with its baked-PNG render **and** an
 * opt-in live daemon stream, bridging the two id namespaces so the published catalog URLs keep
 * working.
 *
 * ## Why
 *
 * A daemon knows its previews by the function-based descriptor id it discovered
 * (`FilledButton_Dark`), but the published `design-artifacts/<system>` catalog links + image routes
 * use the componentId-slug id (`button-filled__ideal__default__dark`) — and the two don't match.
 * Registering a bare [ServeRenderHost] for a catalog therefore 404s every published `/p/<id>` deep
 * link and `/render/<id>.png` thumbnail, drops the catalog's title/trust badge (only a
 * [ServeBundleHost] carries those), and — because a daemon host reports `canApplyOverrides = true`
 * — flips the viewer into dynamic mode, so ordinary browsing renders every preview through the
 * daemon (cold-start "rendering…") instead of showing the instant baked PNG.
 *
 * ## What
 *
 * This composite keeps the [baked] [ServeBundleHost] as the whole **snapshot** surface —
 * [previews], the grid, deep links, thumbnails, title, and trust badge all resolve to the baked
 * catalog exactly as a static catalog would, and every snapshot is the baked PNG (so browsing never
 * wakes the daemon). The [live] daemon is offered only through the **"Live (stream)"** toggle:
 * [hasLiveStream] is true, so the viewer enables the checkbox, and [subscribeStream] maps the
 * catalog id to the daemon preview id via [alias] and streams it. An id with no alias (an
 * Android-only variant the desktop daemon can't render) simply has no stream and stays baked.
 *
 * The net effect: the published catalog behaves exactly as before (static, trusted, instant), plus
 * the CMP components the desktop daemon can run gain an interactive live stream on demand.
 */
class ServeCatalogLiveHost(
  /**
   * Catalog id (`button-filled__ideal__default__dark`) → daemon preview id (`FilledButton_Dark`).
   */
  private val alias: Map<String, String>,
  /** The daemon-backed host, keyed by daemon preview ids (the [alias] values). */
  private val live: ServeHost,
  /** The static baked-PNG host, keyed by catalog ids (the browse + snapshot surface). */
  private val baked: ServeHost,
) : ServeHost {

  /**
   * Browse + snapshot surface is the baked catalog — its ids are the published catalog ids. The
   * author-declared knobs ([ServePreview.overrides]), however, are carried by the *daemon* previews
   * (read from the live bundle's `previews/<daemon-id>.overrides.json` sidecars, keyed by the
   * daemon descriptor id), not by the baked catalog images. So graft each mapped catalog preview's
   * knob declarations across from its daemon twin via [alias]; an unmapped (Android-only) preview
   * keeps the baked entry as-is (no live lane, no editable knobs).
   */
  override val previews: List<ServePreview> = mergeDeclaredKnobs(baked.previews, live.previews)

  override val label: String = baked.label

  /**
   * The app-declared `@ThemeCatalog` themes come from the daemon lane (read from the live bundle's
   * `previews.json`) — the baked browse surface carries none. Forwarded so the viewer's App theme
   * selector renders and, since [canRenderOverrides] is true, actually re-renders under a chosen
   * theme via the carried daemon.
   */
  override val declaredThemes: List<ServeTheme> = live.declaredThemes

  /**
   * Snapshots stay static (baked PNGs) so browsing is instant and the viewer shows the published
   * pixels + trust badge — the live daemon is opt-in via [hasLiveStream], not the snapshot lane.
   */
  override val canApplyOverrides: Boolean = false

  /**
   * The carried daemon CAN re-render a snapshot on demand, so an override-bearing `/render` (a
   * `?knob.<key>=…` edit, or a display-axis change on a mapped id) returns fresh pixels — see
   * [render] / [renderSvg]. This leaves [canApplyOverrides] false (ordinary browsing never wakes
   * the daemon) while enabling the viewer's knob controls as live rather than baked-and-disabled.
   */
  override val canRenderOverrides: Boolean = true

  /**
   * Only a preview with a daemon twin ([alias]) can actually re-render an override; an unaliased
   * (Android-only) variant always replays the baked PNG, which ignores overrides. So the viewer
   * must treat those as non-renderable — otherwise the App theme selector (advertised host-wide via
   * [declaredThemes]) would render enabled on a variant where picking a theme changes nothing.
   */
  override fun canRenderOverridesFor(previewId: String): Boolean = previewId in alias

  /** The gesture override is honoured by the daemon lane, if that daemon is Android-backed. */
  override val gesturesRenderable: Boolean = live.gesturesRenderable

  /**
   * SVG is exportable when either lane can produce it — the baked catalog carries
   * `figma/<slug>.svg` vectors, and the daemon exports a `compose/figma-svg` for a knob-bearing
   * render.
   */
  override val hasSvgExport: Boolean = baked.hasSvgExport || live.hasSvgExport

  /** The "Live (stream)" toggle is offered (unlike a plain static catalog). */
  override val hasLiveStream: Boolean = true

  /**
   * The underlying baked catalog host, so the HTTP layer can read its title / subtitle / trust
   * verdict (which only a [ServeBundleHost] carries) even though the session is fronted by this
   * composite. See `ServeHttpServer.catalogBundleHost`.
   */
  internal val bakedHost: ServeHost = baked

  /**
   * Ordinary browsing serves the baked catalog PNG — instant, and never wakes the daemon: an
   * override-free render (or one carrying only a `uiMode` that matches the variant's baked theme,
   * as the viewer replays from its sticky theme) lands on baked pixels. Any override that would
   * change those pixels — a named knob, a font scale, device, locale, orientation, a feature
   * override, … — is routed to the [live] daemon to re-render, since the baked PNG can't represent
   * it ([overridesAffectRender]). So a `/render?fontScale=…` or `?knob.label=…` URL returns fresh
   * pixels, while the default browse stays baked-instant. Only the mapped (daemon-twinned) ids can
   * re-render; an unmapped Android-only variant always replays baked.
   */
  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    val daemonId = daemonIdForOverrideRender(previewId, overrides)
    return if (daemonId != null) live.render(daemonId, overrides)
    else baked.render(previewId, overrides)
  }

  /**
   * SVG export mirrors [render]'s knob routing, plus a fallback: the SVG row is advertised whenever
   * *either* lane can export ([hasSvgExport]), but a specific mapped preview may have no baked
   * `figma/<slug>.svg` (or the whole catalog carried none and only the daemon exports). So when the
   * baked lane can't produce the vector, fall back to the daemon for a mapped id rather than 404
   * the advertised link. Unlike PNG browsing, an SVG export is an explicit user action (the
   * Download / Copy link), so waking the daemon here is fine.
   */
  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    daemonIdForOverrideRender(previewId, overrides)?.let {
      return live.renderSvg(it, overrides)
    }
    val bakedOutcome = baked.renderSvg(previewId, overrides)
    if (bakedOutcome !is SvgOutcome.NotFound) return bakedOutcome
    val daemonId = alias[previewId] ?: return bakedOutcome
    return live.renderSvg(daemonId, overrides)
  }

  /**
   * The daemon preview id to route a [render] / [renderSvg] to, or null to stay baked. Delegates to
   * [CatalogLiveRouting] — the same predicate [ServePerPreviewLiveHost] uses — so the "baked vs
   * re-render" decision is identical across the two trusted-catalog live hosts.
   */
  private fun daemonIdForOverrideRender(previewId: String, overrides: PreviewOverrides): String? =
    CatalogLiveRouting.daemonIdForOverrideRender(previewId, overrides, alias)

  /** Live streaming is available only for aliased ids; others have no stream (snapshot only). */
  override fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? {
    val daemonId = alias[previewId] ?: return null
    return live.subscribeStream(daemonId, overrides, codec, maxFps, onFrame)
  }

  override fun activeStreamCount(): Int = live.activeStreamCount()

  /**
   * Graft the daemon previews' per-preview metadata onto the baked browse surface. The daemon knows
   * its previews by descriptor id (`FilledButton_Dark`) and carries their author-declared knobs
   * ([ServePreview.overrides], from the bundle sidecars) plus the detected-feature flags
   * ([ServePreview.supportsFocus] / [supportsGestures], from `@FocusedPreview` /
   * `@GestureHintPreview` discovery); the baked catalog keys by catalog id
   * (`button-filled__ideal__default__dark`) and carries neither. For each mapped baked preview,
   * copy its daemon twin's knobs + feature flags across so `/api/previews` + the viewer advertise
   * the editable knobs AND the detected-feature controls (a mapped `@FocusedPreview` component's
   * Keyboard focus toggle re-renders on the daemon). Unmapped previews (Android-only variants with
   * no daemon lane) are returned unchanged.
   */
  private fun mergeDeclaredKnobs(
    bakedPreviews: List<ServePreview>,
    livePreviews: List<ServePreview>,
  ): List<ServePreview> {
    val twinByDaemonId = livePreviews.associateBy { it.id }
    return bakedPreviews.map { p ->
      val twin = alias[p.id]?.let { twinByDaemonId[it] } ?: return@map p
      p.copy(
        overrides = twin.overrides,
        supportsFocus = twin.supportsFocus,
        supportsGestures = twin.supportsGestures,
      )
    }
  }

  override fun close() {
    try {
      live.close()
    } finally {
      baked.close()
    }
  }
}
