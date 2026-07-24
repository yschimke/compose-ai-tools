package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams

/**
 * A servable preview session behind the multi-tenant registry + HTTP layer. Two implementations:
 * - [ServeRenderHost] — live daemon-backed snapshot renders + a streaming lane;
 * - [ServeBundleHost] — a static, pre-rendered portable bundle (no daemon), for the shared/public
 *   "host bundles, don't build" mode.
 *
 * The HTTP routes and the registry only need this surface, so either kind can be served at
 * `?session=<id>` uniformly.
 */
interface ServeHost : AutoCloseable {
  /** The whole servable preview set for this session. */
  val previews: List<ServePreview>

  /**
   * The app's declared `@ThemeCatalog` themes — module-global, so the viewer's Theme selector can
   * offer "render this preview under Brand Dark". Non-empty only for a daemon-backed host
   * ([ServeRenderHost]) whose module declares them; a static bundle carries no theme-apply lane
   * (`themeProvider` needs the daemon to load the provider off the app classpath), so it stays
   * empty and the selector shows only the built-in light/dark axis.
   */
  val declaredThemes: List<ServeTheme>
    get() = emptyList()

  /**
   * Structured reasons this session is **degraded** — an interactive/live lane the viewer would
   * otherwise offer is unavailable, so the server falls back to baked PNG snapshots. Recorded at
   * catalog-load time by [ServeCatalogStore] (the point the fallback is decided, where it was
   * previously only logged to stderr) so the viewer + `/api/previews` can explain *why* a session
   * is snapshot-only rather than leaving the visitor to guess. Empty for a fully-live session (a
   * daemon-backed module, or a catalog served live from a carried bundle) — a non-empty list is the
   * signal the viewer shows its "why snapshot-only" banner. Defaults to empty; only
   * [ServeBundleHost] (the baked host [ServeCatalogStore] terminally registers) carries a populated
   * list.
   */
  val degradations: List<ServeDegradation>
    get() = emptyList()

  /** Human label for the tenant (module Gradle path, `module@rev`, or a bundle name). */
  val label: String

  /**
   * Whether editing an override actually re-renders. `true` for a daemon-backed host
   * ([ServeRenderHost]); `false` for a static pre-rendered bundle ([ServeBundleHost]) that can only
   * replay the baked PNGs — the viewer then shows the preview's declared knobs as disabled,
   * informational controls.
   */
  val canApplyOverrides: Boolean
    get() = false

  /**
   * Whether the host can produce a **freshly rendered** snapshot when an override is supplied —
   * even if the *default* (override-free) snapshot lane is baked. Governs whether the viewer offers
   * the author-declared knob controls as live (an edit re-renders via `/render`) rather than
   * disabled, informational ones. It defaults to [canApplyOverrides], so a plain daemon host (both
   * true) and a plain static bundle (both false) are unchanged. A trusted-catalog live session
   * ([ServeCatalogLiveHost]) is the exception: `canApplyOverrides = false` (browsing stays baked
   * and instant) but `canRenderOverrides = true` — an override-bearing `/render` re-renders through
   * the carried daemon on demand, so a `?knob.<key>=…` (or display-axis) URL returns fresh pixels.
   */
  val canRenderOverrides: Boolean
    get() = canApplyOverrides

  /**
   * Per-preview refinement of [canRenderOverrides]: whether *this* preview can be re-rendered with
   * an override. Defaults to the host-wide [canRenderOverrides] (true for every preview on a plain
   * daemon host, false on a static bundle). A trusted-catalog live session ([ServeCatalogLiveHost])
   * overrides it: only previews with a daemon twin can re-render, so an unaliased (e.g.
   * Android-only) variant returns false — the viewer then shows its override controls (knobs, App
   * theme) as disabled/informational rather than enabled-but-dead (an override on such a preview
   * falls back to the baked PNG, which ignores it).
   */
  fun canRenderOverridesFor(previewId: String): Boolean = canRenderOverrides

  /**
   * Aggregate render-performance counters for this host's live render lane, surfaced on `/status`
   * + `/status.json` (`runningServers[].renderStats`). Null when the host has no live render lane
   *   to measure — a static baked bundle never renders. Daemon-backed hosts ([ServeRenderHost])
   *   record every serve-side render round-trip; composites ([ServeCatalogLiveHost]) forward their
   *   carried daemon's stats.
   */
  fun renderPerfStats(): RenderPerfSnapshot? = null

  /**
   * Whether this session's daemon can actually apply the **one-handed gesture** override
   * (`overrides.gestures`) — i.e. the daemon advertises `"gestures"` in its capabilities. Only the
   * Android (Robolectric) backend does; the desktop backend behind a CMP `serve` / the published
   * catalogs silently ignores it. The viewer gates the "Show gesture hints" control on this so a
   * `@GestureHintPreview` component doesn't show a toggle that would do nothing on a desktop-backed
   * session. Defaults false (a static bundle has no daemon; a desktop daemon doesn't support it).
   */
  val gesturesRenderable: Boolean
    get() = false

  /**
   * Whether [renderSvg] can actually produce a `compose/figma-svg` export for this session's
   * previews — a daemon-backed host always can, a static bundle only when it carried baked
   * `figma/<slug>.svg` vectors (a design catalog). Drives whether the viewer offers a copyable SVG
   * download URL alongside the PNG one. Defaults to false (a plain bundle 404s the `.svg` lane).
   */
  val hasSvgExport: Boolean
    get() = false

  /**
   * Whether [renderSvg] can produce a `compose/figma-svg` export for **this specific** [previewId]
   * — a per-preview refinement of [hasSvgExport]. A static catalog advertises SVG globally as soon
   * as it carries a `figma/` dir, but an individual preview whose component slug has no baked
   * `figma/<slug>.svg` still 404s the `.svg` lane; the viewer gates its SVG control on this so it
   * isn't offered on a preview that would then render "failed" (issue #2352). Defaults to the
   * session-wide [hasSvgExport] — a daemon-backed host exports any of its previews.
   */
  fun hasSvgExportFor(previewId: String): Boolean = hasSvgExport

  /**
   * Whether a **live daemon stream** ("Live (stream)") is available for this session — distinct
   * from [canApplyOverrides], which governs whether the *snapshot* lane re-renders on override
   * edits. The two usually coincide (a plain [ServeRenderHost] has both; a static [ServeBundleHost]
   * neither), so this defaults to [canApplyOverrides]. A trusted-catalog live session
   * ([ServeCatalogLiveHost]) is the exception: its snapshots stay baked (so browsing is instant and
   * stays on the published pixels) while the live stream is still offered on demand —
   * `canApplyOverrides = false` but `hasLiveStream = true`.
   */
  val hasLiveStream: Boolean
    get() = canApplyOverrides

  /** Render [previewId] at [overrides] (cached where possible). */
  fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome

  /**
   * Render [previewId] at [overrides] and return its figma-svg export, or [SvgOutcome.NotFound]
   * when this host can't produce SVG. Defaults to `NotFound`: only the daemon-backed
   * [ServeRenderHost] overrides this — a static [ServeBundleHost] has no daemon to export one.
   */
  fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome = SvgOutcome.NotFound

  /**
   * The figma-svg export tailored for **web/document** viewing (`?mode=web`): where possible the
   * hybrid raster crops are *linked* (an `<image href>` to the crop's public home — the catalog's
   * delivery branch) instead of base64-embedded, so the served SVG stays kilobytes. Defaults to the
   * self-contained [renderSvg]: a host with no public raster home (a live daemon render whose crops
   * exist only on its disk, a plain uploaded bundle) keeps embedding — the HTTP layer's
   * font-`@import` rewrite still applies on top either way. Only the catalog-backed
   * [ServeBundleHost] (which knows the `repo@branch` its crops were published to) overrides this.
   */
  fun renderSvgForWeb(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    renderSvg(previewId, overrides)

  /**
   * Render [previewId]'s **full-page** figma-svg export (`compose/figma-svg-long`) at [overrides] —
   * the whole scrollable screen as one editable SVG (a virtualised `LazyColumn` rendered at an
   * expanded viewport so every row composes), or [SvgOutcome.NotFound] when this host can't produce
   * it. Defaults to `NotFound`: only the daemon-backed [ServeRenderHost] overrides it (the tall
   * re-render needs a daemon; a static bundle has none). A non-scrolling preview yields its
   * ordinary viewport SVG. See [docs/design/SCROLLING_SVG.md].
   */
  fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome =
    SvgOutcome.NotFound

  /**
   * Render [previewId] at [overrides] and return its declared preview slots as JSON, or
   * [SlotsOutcome.NotFound] when this host can't extract them. Defaults to `NotFound`: only the
   * daemon-backed [ServeRenderHost] overrides this — a static [ServeBundleHost] has no daemon to
   * capture a semantics tree.
   */
  fun renderSlots(previewId: String, overrides: PreviewOverrides): SlotsOutcome =
    SlotsOutcome.NotFound

  /**
   * Join the shared live stream for [previewId], or `null` when this host has no live lane (the
   * snapshot fallback is used instead — always the case for [ServeBundleHost]).
   *
   * [onUnavailable] is invoked (once, before the `null` return) with a short human-readable reason
   * when the live lane can't be opened — the daemon's original failure (e.g. `interactive session
   * already held`, `previewSpecResolver returned null`, a `stream/start` timeout) or "no live
   * daemon twin for this variant". The caller surfaces it so the viewer shows *why* it fell back to
   * re-rendered snapshots instead of the opaque "input requires a live stream". Not called on
   * success (a non-null return).
   */
  fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onUnavailable: ((String) -> Unit)? = null,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle?

  /** Count of live upstream streams (0 for hosts without a live lane). */
  fun activeStreamCount(): Int
}
