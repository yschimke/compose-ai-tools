package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
 *
 * ## Per-preview live lane (default, with monolithic fallback)
 *
 * When [perPreviewResolve] is supplied, an override-bearing render/stream first tries to resolve a
 * daemon that re-renders **only that one preview** from its own per-preview bundle
 * (`bundle/previews/<daemon-id>.png`, materialised + pooled by the caller). This is the default
 * render path — small, addressable, per-preview daemons the pool reaps when idle — so the
 * per-preview bundles the delivery branch ships are exercised routinely. It falls back to the
 * monolithic [live] `liveBundle` daemon when a per-preview daemon can't be resolved (fetch /
 * materialise failed, or the preview ships no per-preview bundle), and both fall back to [baked]
 * when the id has no daemon twin at all. So the worst case is exactly the pre-per-preview
 * behaviour; the composite never regresses. With [perPreviewResolve] absent it is the plain
 * monolithic-only host described above.
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
  /**
   * Resolve a daemon-backed host that re-renders the given **daemon-preview id** from its own
   * per-preview bundle, or null when none is available. Tried FIRST for an alias-mapped id carrying
   * a pixel-changing override; a null result falls back to the monolithic [live] daemon. The
   * returned host is owned + pooled by the caller (this host never closes it), so repeated calls
   * for the same id should return the pooled instance. `null` (the default) disables the
   * per-preview lane, leaving the plain monolithic-only host.
   */
  private val perPreviewResolve: ((daemonId: String) -> ServeHost?)? = null,
  /** Live upstream stream count across the pooled per-preview daemons (supplied by the pool). */
  private val perPreviewStreamCount: () -> Int = { 0 },
  /**
   * Serve the baked vector immediately and warm the daemon in the background rather than blocking a
   * browse on a cold (possibly minutes-long, esp. Android/Robolectric) first render — see the
   * cold-start note below. Off by default so the synchronous #2448 per-variant guarantee (and its
   * tests) are unchanged; a deploy fronting a slow-cold-starting catalog sets it on via
   * `-Dcomposeai.serve.warmInBackground=true`.
   */
  private val warmInBackground: Boolean =
    System.getProperty("composeai.serve.warmInBackground")?.toBooleanStrictOrNull() ?: false,
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

  // ── Non-blocking cold start ────────────────────────────────────────────────────────────────────
  // The no-override SVG lane prefers the daemon's per-variant vector over the baked per-slug one
  // (the #2448 fix). But a daemon's FIRST render can be slow — a desktop/Skiko daemon warms in
  // seconds, an Android/Robolectric daemon's cold render can take minutes. When [warmInBackground]
  // is on, a not-yet-"warm" daemon serves the BAKED vector immediately and warms in the background;
  // once a daemon id has produced one successful render it's warm and the per-variant lane kicks in
  // for it. [prewarm] closes the window off the request path so the first real browse is already
  // per-variant.
  private val warmDaemonIds = ConcurrentHashMap.newKeySet<String>()
  private val warmingInFlight = ConcurrentHashMap.newKeySet<String>()
  private val warmExecutor by lazy {
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "serve-catalog-warm").apply { isDaemon = true }
    }
  }

  /**
   * True when [daemonId] is warm (a live render is safe to await now). When it isn't and
   * [warmInBackground] is on, kick a one-shot background warm (a throwaway render that flips it
   * warm on success) and return false so the caller falls back to baked — the request never blocks
   * on a cold daemon. With [warmInBackground] off this always returns true (old always-block
   * behaviour).
   */
  private fun daemonWarmOrScheduling(daemonId: String): Boolean {
    if (!warmInBackground || warmDaemonIds.contains(daemonId)) return true
    if (warmingInFlight.add(daemonId)) {
      warmExecutor.execute {
        try {
          if (liveHostFor(daemonId).render(daemonId, PreviewOverrides()) is RenderOutcome.Ok) {
            warmDaemonIds.add(daemonId)
          }
        } catch (_: Throwable) {
          // Best-effort: a failed warm just leaves the id cold; the next request retries.
        } finally {
          warmingInFlight.remove(daemonId)
        }
      }
    }
    return false
  }

  /**
   * Warm the live daemon(s) off the request path so the first real browse already gets the
   * per-variant SVG lane rather than the baked fallback. Best-effort + async: warms up to
   * [PREWARM_MAX] distinct daemon ids (a monolithic daemon shares one; a per-preview pool has many,
   * and the rest warm lazily on first request). No-op when [warmInBackground] is off.
   */
  fun prewarm() {
    if (!warmInBackground) return
    alias.values.distinct().take(PREWARM_MAX).forEach { daemonWarmOrScheduling(it) }
  }

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

  /**
   * Per-preview SVG availability (issue #2352): narrows [hasSvgExport] to a specific preview so the
   * viewer doesn't offer the SVG control where the `.svg` lane would 404. A daemon-twinned id can
   * export its variant vector when the daemon lane can ([live.hasSvgExport]); an unmapped
   * (Android-only) id only when the baked catalog carried its slug's `figma/<slug>.svg`. Mirrors
   * [renderSvg]'s routing and never advertises more broadly than [hasSvgExport].
   */
  override fun hasSvgExportFor(previewId: String): Boolean =
    (previewId in alias && live.hasSvgExport) || baked.hasSvgExportFor(previewId)

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
    val daemonId =
      daemonIdForOverrideRender(previewId, overrides) ?: return baked.render(previewId, overrides)
    // Only await the daemon when it's warm and free. A cold Android render can take minutes, and
    // blocking the browse — and the HTTP render slot it holds — on it is what saturates the whole
    // server. A not-yet-warm daemon serves baked now and warms in the background; a warm daemon
    // that reports [RenderOutcome.Busy] (the bounded-lock back-off — another render in flight)
    // likewise falls back to baked instead of pinning a slot. This mirrors [renderSvg]'s warm gate.
    if (daemonWarmOrScheduling(daemonId)) {
      val live = liveHostFor(daemonId).render(daemonId, overrides)
      if (live !is RenderOutcome.Busy) return live
    }
    return baked.render(previewId, overrides)
  }

  /**
   * The daemon-backed host to route a mapped [daemonId] to: the per-preview daemon if
   * [perPreviewResolve] resolves one (the default lane, exercised routinely), else the monolithic
   * [live] daemon. Both re-render the same daemon id — the per-preview bundle simply carries only
   * that one preview's closure — so callers pass the daemon id either way.
   */
  private fun liveHostFor(daemonId: String): ServeHost = perPreviewResolve?.invoke(daemonId) ?: live

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
      return liveHostFor(it).renderSvg(it, overrides)
    }
    // No override — but the baked `figma/<slug>.svg` is keyed by component slug and light-preferred
    // (the catalog emits one SVG per component, the light variant), so a `…__dark` id would serve
    // the
    // LIGHT vector even though its PNG and live render are dark. Prefer the daemon's per-variant
    // SVG,
    // which carries the actual variant's theme (uiMode), for any daemon-twinned id; the baked slug
    // SVG stays the fallback for unmapped (Android-only) ids and if the daemon can't export.
    alias[previewId]?.let { daemonId ->
      // Only await the daemon when it's warm — otherwise a cold (possibly minutes-long) render
      // would
      // hang the browse. A cold daemon serves the baked vector now and warms in the background; a
      // warm daemon that still fails/NotFounds also falls through to baked (never surface an error
      // where a baked vector exists).
      if (daemonWarmOrScheduling(daemonId)) {
        val live = liveHostFor(daemonId).renderSvg(daemonId, overrides)
        if (live is SvgOutcome.Ok) return live
      }
    }
    return baked.renderSvg(previewId, overrides)
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
    onUnavailable: ((String) -> Unit)?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? {
    val daemonId =
      alias[previewId]
        ?: run {
          // An unmapped (Android-only) variant has no daemon twin, so no live lane — report it so
          // the viewer explains the snapshot fallback rather than a bare "input requires a stream".
          onUnavailable?.invoke("no live daemon twin for '$previewId' (baked snapshot only)")
          return null
        }
    return liveHostFor(daemonId)
      .subscribeStream(
        daemonId,
        overrides,
        codec,
        maxFps,
        onUnavailable = onUnavailable,
        onFrame = onFrame,
      )
  }

  override fun activeStreamCount(): Int = live.activeStreamCount() + perPreviewStreamCount()

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
    if (warmInBackground) {
      try {
        warmExecutor.shutdownNow()
      } catch (_: Throwable) {
        // ignore — best-effort shutdown of the daemon-thread warm pool
      }
    }
    try {
      live.close()
    } finally {
      baked.close()
    }
  }

  private companion object {
    /**
     * Cap on how many distinct daemon ids [prewarm] warms eagerly, so a per-preview pool doesn't
     * spawn a render per preview at once (a thundering herd on startup). The rest warm lazily on
     * first request. A monolithic daemon shares one id, so the cap is irrelevant there.
     */
    const val PREWARM_MAX = 8
  }
}
