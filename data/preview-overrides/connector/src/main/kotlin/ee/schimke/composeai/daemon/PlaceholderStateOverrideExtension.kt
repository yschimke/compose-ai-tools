package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension
import ee.schimke.composeai.overrides.LocalPlaceholderActive

/**
 * `AroundComposable` extension that pins [LocalPlaceholderActive] so a placeholdered preview
 * renders in a chosen content-loading state (issue #2646) — `active = true` is the loading state
 * (the placeholder blocks paint), `false` the loaded/ideal one.
 *
 * Portable across both backends like [FakeClockOverrideExtension]: it acts purely through a
 * composition local, so there is no renderer branch. A `PlaceholderState` is app-owned, so this is
 * an **opt-in** seam — preview content must read `placeholderActive(...)` into its own state for
 * the pin to take effect (see `:data-preview-overrides-runtime`).
 *
 * Runs in [DataExtensionPhase.OuterEnvironment] so the local is in scope before preview content
 * remembers its `PlaceholderState`.
 */
class PlaceholderStateOverrideExtension(private val active: Boolean) :
  AroundComposableExtension(
    id = ID,
    constraints = DataExtensionConstraints(phase = DataExtensionPhase.OuterEnvironment),
  ) {

  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlaceholderActive provides active) { content() }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId("data-placeholder-state")
  }
}

/**
 * Planner mapping `renderNow.overrides.placeholderActive` to a [PlaceholderStateOverrideExtension].
 * Returns null when the field is unset, so a plain render leaves [LocalPlaceholderActive] null and
 * the preview keeps whatever state it computes for itself — the placeholder pin only engages when a
 * caller explicitly asks for a state.
 *
 * [AlwaysOnPreviewOverrideExtension] so it is consulted on **every** render rather than only when
 * the `data/overrides` extension it is registered under has been enabled. The enable gate governs
 * that extension's client-visible `data/fetch` product (the editable-knob payload), which this
 * planner has nothing to do with — and the consumer that would break is exactly the one the feature
 * targets: `serve` (`/render/<id>.png?placeholderActive=true`) never calls `extensions/enable`, so
 * gating would parse, cache, and advertise the override while silently rendering the wrong state.
 * Being consulted always costs nothing: [plan] abstains unless the field is set.
 */
class PlaceholderStatePreviewOverrideExtension :
  DataExtension<PreviewOverrides>, AlwaysOnPreviewOverrideExtension {
  override val id: DataExtensionId = PlaceholderStateOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    request.placeholderActive?.let(::PlaceholderStateOverrideExtension)
}
