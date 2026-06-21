package ee.schimke.composeai.fakeemulator.app

import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode as ProtocolUiMode
import ee.schimke.composeai.fakeemulator.DeviceSettings
import ee.schimke.composeai.fakeemulator.UiMode as DeviceUiMode

/**
 * Maps the fake emulator's [DeviceSettings] (driven by Android Studio's `adb shell` toggles + the
 * emulator gRPC) onto a daemon [PreviewOverrides], so a launched preview re-renders under whatever
 * the user flipped in Studio. Pure function — unit-tested directly.
 *
 * Mapped today: dark/light theme, font scale, display density + size, locale, and rotation (→
 * orientation). [DeviceSettings.talkBack] is handled separately (it drives the a11y data product,
 * not a render override); [DeviceSettings.colorMode], [DeviceSettings.posture], and
 * [DeviceSettings.showLayoutBounds] have no `PreviewOverrides` equivalent yet and are carried for a
 * follow-up.
 */
fun DeviceSettings.toPreviewOverrides(): PreviewOverrides =
  PreviewOverrides(
    widthPx = widthPx,
    heightPx = heightPx,
    density = densityDpi?.let { it / 160f },
    localeTag = localeTag,
    fontScale = fontScale,
    uiMode =
      when (uiMode) {
        DeviceUiMode.LIGHT -> ProtocolUiMode.LIGHT
        DeviceUiMode.DARK -> ProtocolUiMode.DARK
        DeviceUiMode.UNSET -> null
      },
    orientation = if (rotation.isLandscape) Orientation.LANDSCAPE else Orientation.PORTRAIT,
  )

/** Whether the override-relevant fields are all defaults (nothing to apply). */
fun PreviewOverrides.isEmpty(): Boolean =
  widthPx == null &&
    heightPx == null &&
    density == null &&
    localeTag == null &&
    fontScale == null &&
    uiMode == null &&
    orientation == Orientation.PORTRAIT
