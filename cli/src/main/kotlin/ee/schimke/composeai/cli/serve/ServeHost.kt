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
