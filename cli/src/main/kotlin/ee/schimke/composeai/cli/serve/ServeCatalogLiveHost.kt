package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams

/**
 * A [ServeHost] that fronts a trusted design-system catalog with **both** its baked-PNG render and
 * a live daemon lane, bridging the two id namespaces so the published catalog URLs keep working.
 *
 * ## Why
 *
 * A daemon knows its previews by the function-based descriptor id it discovered
 * (`FilledButton_Dark`), but the published `design-artifacts/<system>` catalog links + image routes
 * use the componentId-slug id (`button-filled__ideal__default__dark`) — and the two don't match.
 * Registering a bare [ServeRenderHost] for a catalog therefore 404s every published `/p/<id>` deep
 * link and `/render/<id>.png` thumbnail. It also can't serve the ids the desktop daemon simply
 * doesn't have (the Android-only inset focus-ring variant, rendered by a separate supplement and
 * baked in, with no runnable desktop preview).
 *
 * ## What
 *
 * This composite keeps the [baked] [ServeBundleHost] as the browse surface — so [previews], the
 * grid, deep links, and thumbnails resolve to the baked catalog **exactly as before** — and layers
 * the [live] daemon underneath for the ids [alias] maps (catalog id → daemon preview id):
 * - **Plain snapshot** (no overrides): served from the baked PNG, so ordinary browsing never wakes
 *   the daemon (thumbnails, deep-link default view).
 * - **Overridden snapshot** on an aliased id: routed to the daemon for a fresh render of the edit.
 * - **Live stream** on an aliased id: routed to the daemon; an unmapped id has no stream (the
 *   caller transparently falls back to the snapshot lane, i.e. the baked PNG).
 *
 * The result: the whole published catalog resolves under its own ids, and the CMP components the
 * desktop daemon can run gain a live, interactive lane — while the Android-only variants degrade
 * gracefully to their baked pixels.
 */
class ServeCatalogLiveHost(
  /**
   * Catalog id (`button-filled__ideal__default__dark`) → daemon preview id (`FilledButton_Dark`).
   */
  private val alias: Map<String, String>,
  /** The daemon-backed host, keyed by daemon preview ids (the [alias] values). */
  private val live: ServeHost,
  /** The static baked-PNG host, keyed by catalog ids (the browse surface + fallback). */
  private val baked: ServeHost,
) : ServeHost {

  /** Browse surface is the baked catalog — its ids are the published catalog ids. */
  override val previews: List<ServePreview> = baked.previews

  override val label: String = baked.label

  /** Some ids (the aliased ones) re-render live, so the viewer should offer editable knobs. */
  override val canApplyOverrides: Boolean = true

  /**
   * Plain (no-override) snapshots come from the baked PNG so browsing never spins the daemon; an
   * override edit on an aliased id renders fresh through the daemon. An unmapped id always serves
   * baked (it has no runnable preview).
   */
  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    val daemonId = alias[previewId]
    return if (daemonId != null && overrides != EMPTY_OVERRIDES) {
      live.render(daemonId, overrides)
    } else {
      baked.render(previewId, overrides)
    }
  }

  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    val daemonId = alias[previewId]
    return if (daemonId != null && overrides != EMPTY_OVERRIDES) {
      live.renderSvg(daemonId, overrides)
    } else {
      baked.renderSvg(previewId, overrides)
    }
  }

  /**
   * Live streaming is available only for aliased ids; others have no stream (snapshot fallback).
   */
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

  private companion object {
    /** All-default overrides = "no override" (what the snapshot lane passes for a plain view). */
    private val EMPTY_OVERRIDES = PreviewOverrides()
  }
}
