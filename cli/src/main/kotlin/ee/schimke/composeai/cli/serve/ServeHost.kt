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
   * Whether [renderSvg] can actually produce a `compose/figma-svg` export for this session's
   * previews — a daemon-backed host always can, a static bundle only when it carried baked
   * `figma/<slug>.svg` vectors (a design catalog). Drives whether the viewer offers a copyable SVG
   * download URL alongside the PNG one. Defaults to false (a plain bundle 404s the `.svg` lane).
   */
  val hasSvgExport: Boolean
    get() = false

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
   */
  fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle?

  /** Count of live upstream streams (0 for hosts without a live lane). */
  fun activeStreamCount(): Int
}
