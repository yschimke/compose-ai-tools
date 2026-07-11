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
import ee.schimke.composeai.overrides.LocalClock
import ee.schimke.composeai.overrides.PreviewClock
import ee.schimke.composeai.overrides.fixedPreviewClock

/**
 * `AroundComposable` extension that pins [LocalClock] to a fixed instant so time-dependent preview
 * UI (relative timestamps, countdowns) renders deterministically (issue #1968). The portable,
 * both-backend counterpart of the frame-clock advance: it acts purely through a composition local,
 * so there is no renderer branch — a preview reads `LocalClock.current.nowEpochMillis()` and sees
 * [epochMillis] instead of the drifting real time.
 *
 * Runs in [DataExtensionPhase.OuterEnvironment] so the clock local is in scope before user preview
 * content reads it — matching `PseudolocaleOverrideExtension` /
 * `PreviewOverridesOverrideExtension`.
 */
class FakeClockOverrideExtension(private val epochMillis: Long) :
  AroundComposableExtension(
    id = ID,
    constraints = DataExtensionConstraints(phase = DataExtensionPhase.OuterEnvironment),
  ) {
  private val clock: PreviewClock = fixedPreviewClock(epochMillis)

  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalClock provides clock) { content() }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId("data-fake-clock")
  }
}

/**
 * Planner mapping `renderNow.overrides.clockEpochMillis` to a [FakeClockOverrideExtension]. Returns
 * null when the field is unset or negative, so a plain render keeps [LocalClock] at real system
 * time and stays byte-identical — the fake clock only engages when a caller explicitly pins an
 * instant.
 */
class FakeClockPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = FakeClockOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    request.clockEpochMillis?.takeIf { it >= 0 }?.let(::FakeClockOverrideExtension)
}
