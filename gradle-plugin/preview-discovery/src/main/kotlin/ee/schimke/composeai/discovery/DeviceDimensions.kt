package ee.schimke.composeai.discovery

import ee.schimke.composeai.daemon.devices.DeviceDimensions as SharedDeviceDimensions

/**
 * Deprecated forwarding facade for the device catalog this module used to own. Geometry now comes
 * from the contracts' [SharedDeviceDimensions] and the plugin's sizing rules from [PreviewSizing];
 * this object keeps the old names and nested types working (source and binary) until a major
 * release, per `docs/VERSIONING.md` § 5.
 */
@Deprecated(
  "Use ee.schimke.composeai.daemon.devices.DeviceDimensions; resolveForRender, SizeSpec and the " +
    "sandbox constants are on PreviewSizing.",
  ReplaceWith("DeviceDimensions", "ee.schimke.composeai.daemon.devices.DeviceDimensions"),
)
object DeviceDimensions {
  @Deprecated(
    "Use ee.schimke.composeai.daemon.devices.DeviceDimensions.DeviceSpec.",
    ReplaceWith(
      "DeviceDimensions.DeviceSpec",
      "ee.schimke.composeai.daemon.devices.DeviceDimensions",
    ),
  )
  data class DeviceSpec(
    val widthDp: Int,
    val heightDp: Int,
    val density: Float = DEFAULT_DENSITY,
    val isRound: Boolean = false,
  )

  const val DEFAULT_DENSITY: Float = SharedDeviceDimensions.DEFAULT_DENSITY

  @Deprecated(
    "Use PreviewSizing.SizeSpec.",
    ReplaceWith("PreviewSizing.SizeSpec", "ee.schimke.composeai.discovery.PreviewSizing"),
  )
  data class SizeSpec(
    val widthDp: Int,
    val heightDp: Int,
    val wrapWidth: Boolean,
    val wrapHeight: Boolean,
    val density: Float = DEFAULT_DENSITY,
  )

  const val SANDBOX_WIDTH_DP = PreviewSizing.SANDBOX_WIDTH_DP
  const val SANDBOX_HEIGHT_DP = PreviewSizing.SANDBOX_HEIGHT_DP
  const val SANDBOX_DP = SANDBOX_WIDTH_DP

  val DEFAULT: DeviceSpec = SharedDeviceDimensions.DEFAULT.toLegacy()
  val DEFAULT_WEAR: DeviceSpec = SharedDeviceDimensions.DEFAULT_WEAR.toLegacy()
  val DEFAULT_GLASSES: DeviceSpec = PreviewSizing.DEFAULT_GLASSES.toLegacy()

  fun resolve(device: String?, widthDp: Int? = null, heightDp: Int? = null): DeviceSpec =
    SharedDeviceDimensions.resolve(device, widthDp, heightDp).toLegacy()

  fun resolveForRender(
    device: String?,
    widthDp: Int?,
    heightDp: Int?,
    showSystemUi: Boolean,
    wrapSandboxWidthDp: Int? = null,
    wrapSandboxHeightDp: Int? = null,
  ): SizeSpec =
    PreviewSizing.resolveForRender(
        device,
        widthDp,
        heightDp,
        showSystemUi,
        wrapSandboxWidthDp,
        wrapSandboxHeightDp,
      )
      .let { SizeSpec(it.widthDp, it.heightDp, it.wrapWidth, it.wrapHeight, it.density) }

  private fun SharedDeviceDimensions.DeviceSpec.toLegacy(): DeviceSpec =
    DeviceSpec(widthDp, heightDp, density, isRound)
}
