package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension

/**
 * SPI for `renderNow.overrides`-driven Compose extensions.
 *
 * Extensions that wrap or otherwise observe the preview based on per-call [PreviewOverrides]
 * implement this seam. Each `plan(...)` call inspects the merged [PreviewOverrides] for the single
 * render and returns a [PlannedDataExtension] (typically an
 * [ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook]) when its override
 * applies, or `null` to abstain.
 *
 * Adding a new override-driven feature only requires:
 * 1. Declaring the override field on [PreviewOverrides] (the protocol surface).
 * 2. Shipping a connector that contains both the runtime hook and a [PreviewOverrideExtension]
 *    planner that maps the override to the hook.
 * 3. Registering the planner in `DaemonMain` via [PreviewOverrideExtensions].
 *
 * The render engine itself does **not** need to know about specific override fields — it just hands
 * the merged [PreviewOverrides] to every registered planner and threads the resulting list through
 * the Compose data-extension pipeline.
 */
typealias PreviewOverrideExtension = DataExtension<PreviewOverrides>

/**
 * Aggregator of registered [PreviewOverrideExtension]s, injected into the renderer.
 *
 * Used to keep render-engine call sites generic: instead of hardcoding `spec.wallpaper?.let(...)`
 * and friends, the engine calls [plan] and receives the list of [PlannedDataExtension] entries to
 * thread through `ComposeDataExtensionPipeline.Apply`.
 *
 * `isActive` is consulted on every `plan(...)` call so a runtime `extensions/enable` /
 * `extensions/disable` from the [ExtensionRegistry] takes effect on the next render without
 * rebuilding the renderer. The default predicate considers every extension active — used by tests
 * and by callers that want the legacy "always on" behaviour.
 */
class PreviewOverrideExtensions(
  val extensions: List<PreviewOverrideExtension>,
  private val isActive: (PreviewOverrideExtension) -> Boolean = { true },
) {
  fun plan(overrides: PreviewOverrides?): List<PlannedDataExtension> {
    if (overrides == null || extensions.isEmpty()) return emptyList()
    return extensions.mapNotNull { if (isActive(it)) it.plan(overrides) else null }
  }

  companion object {
    val Empty: PreviewOverrideExtensions = PreviewOverrideExtensions(emptyList())
  }
}
