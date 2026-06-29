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
 * Opt-in marker for a [PreviewOverrideExtension] that must be planned on **every** render,
 * including renders that carry no `renderNow.overrides` bag at all (`plan(null)`).
 *
 * Most override-driven planners abstain when their field is absent, so [PreviewOverrideExtensions]
 * short-circuits and plans nothing when the whole bag is null — a cheap, behaviour-preserving fast
 * path. A few planners, though, install a composition local or stamp per-render bookkeeping that
 * has to be present whether or not the client sent an override. The plain-Compose named-override
 * planner is the canonical case: its around-composable both installs `LocalPreviewOverrideHost` and
 * stamps the active previewId into `PreviewOverrideController` so the Android sandbox bridge keys
 * the preview's declared knobs under the right scope. Without running on a no-override render the
 * very first `data/fetch?kind=compose/overrides` (before any knob has been edited) would find the
 * declarations stranded under the no-preview scope and report nothing.
 *
 * Marking a planner here makes [PreviewOverrideExtensions.plan] hand it an empty [PreviewOverrides]
 * on the null-bag path. Implementors MUST therefore treat an empty bag as "no seed" (the named
 * override planner already does — `request.namedOverrides` is null, so it seeds nothing and only
 * installs the host + stamps the scope). Do NOT mark a planner whose around-composable mutates
 * shared interactive state (keyboard visibility, permission grants) on an empty bag — running those
 * on every no-override render would reset state a held session still depends on.
 */
interface AlwaysOnPreviewOverrideExtension

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
    if (extensions.isEmpty()) return emptyList()
    // No override bag on this render: most planners would abstain, so skip them — but the
    // always-on planners (e.g. the named-override host + scope stamper) still have to run. Hand
    // them an empty bag so they seed nothing yet install their composition local / per-render
    // bookkeeping. See [AlwaysOnPreviewOverrideExtension].
    if (overrides == null) {
      val emptyBag = PreviewOverrides()
      return extensions.mapNotNull {
        if (it is AlwaysOnPreviewOverrideExtension && isActive(it)) it.plan(emptyBag) else null
      }
    }
    return extensions.mapNotNull { if (isActive(it)) it.plan(overrides) else null }
  }

  companion object {
    val Empty: PreviewOverrideExtensions = PreviewOverrideExtensions(emptyList())
  }
}
