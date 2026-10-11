package ee.schimke.composeai.discovery

import ee.schimke.composeai.daemon.devices.DeviceDimensions

/**
 * The plugin's own sizing rules layered on top of the shared device catalog.
 *
 * Device geometry — the `id:` table and the `spec:` grammar — comes from the contracts'
 * [DeviceDimensions] (`ee.schimke.composeai:daemon-devices`), the single copy the CLI, the daemons
 * and this plugin all resolve through. What stays here is what only the plugin decides: the
 * per-axis fixed-vs-wrap choice ([resolveForRender]), which consumes the annotation's
 * `showSystemUi` and the discovery-time wrap sandbox, and the AI-glasses wrap sandbox
 * ([DEFAULT_GLASSES]) that `retargetGlimmerStickers` measures against.
 */
object PreviewSizing {
  /**
   * Per-axis sizing decision for a single preview, mirroring Android Studio's Compose preview pane:
   * an axis is *fixed* when the user named a dp value or chose a device/`showSystemUi`, otherwise
   * it *wraps* to the composable's intrinsic size. Wrapped axes still need a sandbox dimension so
   * Robolectric Configuration qualifiers and the desktop `ImageComposeScene` have a finite canvas
   * to render into — the renderer crops the PNG back down to the measured content bounds
   * afterwards.
   */
  data class SizeSpec(
    val widthDp: Int,
    val heightDp: Int,
    val wrapWidth: Boolean,
    val wrapHeight: Boolean,
    val density: Float = DeviceDimensions.DEFAULT_DENSITY,
  )

  /**
   * Sandbox dp used for wrapped axes — matches the historical default (400×800 dp) that stood in
   * for "no device" before AS-parity sizing, so `fillMax*` composables measure into a phone-shaped
   * viewport rather than a giant square. Wrapped small composables then crop well below this.
   */
  const val SANDBOX_WIDTH_DP = 400
  const val SANDBOX_HEIGHT_DP = 800

  /**
   * The AI-glasses display `androidx.xr.glimmer` draws for: 960x720 at **density 1.0**.
   *
   * The density is the load-bearing half and is not a phone-style guess. Glimmer sizes UI in
   * **visual angle**, not dp: at ~30 pixels per degree the library's own type and touch targets
   * land on the angular sizes it is calibrated for (18dp text -> 18px -> 0.6 degrees) and that
   * identity holds **only at density 1.0**. At 1.5 the same text measures 27px -> 0.9 degrees and
   * contrast and legibility read optimistically; at the renderer's 2.625 phone default it is worse
   * again. `samples/xr-glimmer` pinned dpi=160 for exactly this reason and states the arithmetic.
   *
   * 960x720 at 30 PPD spans 32 x 24 degrees of field of view — a plausible 4:3 HUD. Re-pin all
   * three numbers here if Google publishes the AI Glasses AVD's exact resolution, FoV and
   * densityDpi; the 30-PPD identity is the anchor to preserve.
   *
   * Used as a **wrap sandbox** rather than a pinned canvas — see
   * [PreviewDiscovery.retargetGlimmerStickers], which is where the difference between "measure
   * against the glasses display" and "occupy the glasses display" is spelled out.
   */
  val DEFAULT_GLASSES: DeviceDimensions.DeviceSpec = DeviceDimensions.DeviceSpec(960, 720, 1.0f)

  /**
   * AS-parity sizing: axes are fixed iff the user specified them (or chose a device frame /
   * `showSystemUi`); otherwise the axis wraps to the composable's intrinsic size. The `widthDp` /
   * `heightDp` fields of the returned [SizeSpec] are the *sandbox* dimensions the renderer should
   * use — for wrapped axes that's [SANDBOX_WIDTH_DP] x [SANDBOX_HEIGHT_DP]; for fixed axes it's the
   * effective dp the frame was resolved to.
   *
   * [wrapSandboxWidthDp] / [wrapSandboxHeightDp] ([PreviewParams.wrapSandboxWidthDp]) replace
   * [SANDBOX_WIDTH_DP] / [SANDBOX_HEIGHT_DP] on a wrapped axis without fixing it — the axis still
   * reports `wrap = true`, so the capture crops to measured size. They're ignored on a fixed axis
   * (an explicit dp or a device frame already owns the geometry) and on the device / `showSystemUi`
   * branch below, where nothing wraps.
   */
  fun resolveForRender(
    device: String?,
    widthDp: Int?,
    heightDp: Int?,
    showSystemUi: Boolean,
    wrapSandboxWidthDp: Int? = null,
    wrapSandboxHeightDp: Int? = null,
  ): SizeSpec {
    val w = widthDp?.takeIf { it > 0 }
    val h = heightDp?.takeIf { it > 0 }
    // Device / showSystemUi → full device frame on both axes. Explicit
    // widthDp/heightDp still override the resolved device spec (matches
    // the existing `DeviceDimensions.resolve()` precedence and AS).
    if (device != null || showSystemUi) {
      val spec = DeviceDimensions.resolve(device, w, h)
      return SizeSpec(
        widthDp = spec.widthDp,
        heightDp = spec.heightDp,
        wrapWidth = false,
        wrapHeight = false,
        density = spec.density,
      )
    }
    return SizeSpec(
      widthDp = w ?: wrapSandboxWidthDp?.takeIf { it > 0 } ?: SANDBOX_WIDTH_DP,
      heightDp = h ?: wrapSandboxHeightDp?.takeIf { it > 0 } ?: SANDBOX_HEIGHT_DP,
      wrapWidth = w == null,
      wrapHeight = h == null,
    )
  }
}
