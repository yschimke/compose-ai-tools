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

  /** Browse + snapshot surface is the baked catalog — its ids are the published catalog ids. */
  override val previews: List<ServePreview> = baked.previews

  override val label: String = baked.label

  /**
   * Snapshots stay static (baked PNGs) so browsing is instant and the viewer shows the published
   * pixels + trust badge — the live daemon is opt-in via [hasLiveStream], not the snapshot lane.
   */
  override val canApplyOverrides: Boolean = false

  /** The "Live (stream)" toggle is offered (unlike a plain static catalog). */
  override val hasLiveStream: Boolean = true

  /**
   * The underlying baked catalog host, so the HTTP layer can read its title / subtitle / trust
   * verdict (which only a [ServeBundleHost] carries) even though the session is fronted by this
   * composite. See `ServeHttpServer.catalogBundleHost`.
   */
  internal val bakedHost: ServeHost = baked

  /** Snapshots are the baked catalog PNGs — the daemon is reserved for the live stream lane. */
  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
    baked.render(previewId, overrides)

  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    baked.renderSvg(previewId, overrides)

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

  override fun close() {
    try {
      live.close()
    } finally {
      baked.close()
    }
  }
}
