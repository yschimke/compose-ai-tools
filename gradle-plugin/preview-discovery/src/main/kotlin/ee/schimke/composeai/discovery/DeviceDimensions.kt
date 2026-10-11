package ee.schimke.composeai.discovery

object DeviceDimensions {
  /**
   * Per-device geometry resolved from a `@Preview(device = ...)` string. `density` is densityDpi /
   * 160, mapped to a Robolectric `<n>dpi` qualifier (or Compose `Density` on desktop) so PNGs match
   * Studio.
   */
  data class DeviceSpec(
    val widthDp: Int,
    val heightDp: Int,
    val density: Float = DEFAULT_DENSITY,
    val isRound: Boolean = false,
  )

  /**
   * Studio's density when no device is specified (420dpi → 2.625x); only affects previews without a
   * `device` or `spec:…,dpi=`.
   */
  const val DEFAULT_DENSITY: Float = 2.625f

  /**
   * Per-axis sizing, mirroring Studio: an axis is *fixed* when a dp value, device or `showSystemUi`
   * was given, otherwise it *wraps*. Wrapped axes still need a finite sandbox to render into; the
   * renderer crops to measured bounds afterwards.
   */
  data class SizeSpec(
    val widthDp: Int,
    val heightDp: Int,
    val wrapWidth: Boolean,
    val wrapHeight: Boolean,
    val density: Float = DEFAULT_DENSITY,
  )

  /**
   * Sandbox dp for wrapped axes (400×800), so `fillMax*` composables measure into a phone-shaped
   * viewport.
   */
  const val SANDBOX_WIDTH_DP = 400
  const val SANDBOX_HEIGHT_DP = 800

  /** Back-compat alias for callers/tests that want a single constant. */
  const val SANDBOX_DP = SANDBOX_WIDTH_DP

  // Source of the dp values and densities: sergio-sastre/ComposablePreviewScanner's device tables
  // (dp = px / (densityDpi / 160)), the same data roborazzi's scanner support uses.
  private val KNOWN_DEVICES =
    mapOf(
      // --- Pixel phones ---
      "id:pixel" to DeviceSpec(411, 731, 2.625f),
      "id:pixel_xl" to DeviceSpec(411, 731, 3.5f),
      "id:pixel_2" to DeviceSpec(411, 731, 2.625f),
      "id:pixel_2_xl" to DeviceSpec(411, 823, 3.5f),
      "id:pixel_3" to DeviceSpec(393, 786, 2.75f),
      "id:pixel_3_xl" to DeviceSpec(411, 846, 3.5f),
      "id:pixel_3a" to DeviceSpec(393, 808, 2.75f),
      "id:pixel_3a_xl" to DeviceSpec(411, 823, 2.625f),
      "id:pixel_4" to DeviceSpec(393, 829, 2.75f),
      "id:pixel_4_xl" to DeviceSpec(411, 869, 3.5f),
      "id:pixel_4a" to DeviceSpec(393, 851, 2.75f),
      "id:pixel_5" to DeviceSpec(393, 851, 2.75f),
      // Pixel 6 / 6a / 7 / 7a / 8 / 8a share 1080×2400 px @ 420dpi → 411×914 dp.
      "id:pixel_6" to DeviceSpec(411, 914, 2.625f),
      "id:pixel_6a" to DeviceSpec(411, 914, 2.625f),
      "id:pixel_6_pro" to DeviceSpec(411, 891, 3.5f),
      "id:pixel_7" to DeviceSpec(411, 914, 2.625f),
      "id:pixel_7a" to DeviceSpec(411, 914, 2.625f),
      "id:pixel_7_pro" to DeviceSpec(411, 891, 3.5f),
      "id:pixel_8" to DeviceSpec(411, 914, 2.625f),
      "id:pixel_8a" to DeviceSpec(411, 914, 2.625f),
      "id:pixel_8_pro" to DeviceSpec(448, 997, 3.0f),
      "id:pixel_9" to DeviceSpec(411, 923, 2.625f),
      "id:pixel_9a" to DeviceSpec(411, 923, 2.625f),
      "id:pixel_9_pro" to DeviceSpec(426, 952, 3.0f),
      "id:pixel_9_pro_xl" to DeviceSpec(438, 997, 3.0f),
      // Foldables — natural orientation per upstream
      "id:pixel_fold" to DeviceSpec(841, 701, 2.625f),
      "id:pixel_9_pro_fold" to DeviceSpec(791, 819, 2.625f),

      // --- Pixel tablets ---
      "id:pixel_c" to DeviceSpec(1280, 900, 2.0f),
      "id:pixel_tablet" to DeviceSpec(1280, 800, 2.0f),

      // --- Generic Android Studio device IDs ("Small Phone", "Medium Phone", …) ---
      "id:small_phone" to DeviceSpec(360, 640, 2.0f),
      "id:medium_phone" to DeviceSpec(411, 914, 2.625f),
      "id:medium_tablet" to DeviceSpec(1280, 800, 2.0f),
      "id:resizable" to DeviceSpec(411, 914, 2.625f),

      // --- Wear OS --- WearDevices constants, all 320dpi (2.0x). xl_round comes from AOSP's sdklib
      // `wear.xml` (480x480 px).
      "id:wearos_small_round" to DeviceSpec(192, 192, 2.0f),
      "id:wearos_large_round" to DeviceSpec(227, 227, 2.0f),
      "id:wearos_xl_round" to DeviceSpec(240, 240, 2.0f),
      "id:wearos_square" to DeviceSpec(180, 180, 2.0f),
      "id:wearos_rect" to DeviceSpec(201, 238, 2.0f),
      // Older Studio id for `wearos_rect`.
      "id:wearos_rectangular" to DeviceSpec(201, 238, 2.0f),

      // --- Desktop --- Studio's desktop entries, for desktop previews routed through the Android
      // renderer.
      "id:desktop_small" to DeviceSpec(1366, 768, 1.0f),
      "id:desktop_medium" to DeviceSpec(1920, 1080, 2.0f),
      "id:desktop_large" to DeviceSpec(1920, 1080, 1.0f),

      // --- Television --- 4K and 1080p share a dp surface; 4K renders at 4× density.
      "id:tv_720p" to DeviceSpec(931, 524, 1.375f),
      "id:tv_1080p" to DeviceSpec(960, 540, 2.0f),
      "id:tv_4k" to DeviceSpec(960, 540, 4.0f),

      // --- Automotive (Android Auto / AAOS) ---
      "id:automotive_1024p_landscape" to DeviceSpec(1024, 768, 1.0f),
      "id:automotive_1080p_landscape" to DeviceSpec(1440, 800, 0.75f),
      "id:automotive_1408p_landscape_with_google_apis" to DeviceSpec(1408, 792, 1.0f),
      "id:automotive_1408p_landscape_with_play" to DeviceSpec(1408, 792, 1.0f),
      "id:automotive_distant_display" to DeviceSpec(1440, 800, 0.75f),
      "id:automotive_distant_display_with_play" to DeviceSpec(1440, 800, 0.75f),
      "id:automotive_portrait" to DeviceSpec(1067, 1707, 0.75f),
      "id:automotive_large_portrait" to DeviceSpec(1280, 1606, 1.0f),
      "id:automotive_ultrawide" to DeviceSpec(2603, 880, 1.5f),

      // --- XR --- `xr_device` is the deprecated name for `xr_headset_device`.
      "id:xr_headset_device" to DeviceSpec(1280, 1279, 2.0f),
      "id:xr_device" to DeviceSpec(1280, 1279, 2.0f),
    )

  val DEFAULT = DeviceSpec(400, 800, DEFAULT_DENSITY)
  val DEFAULT_WEAR = DeviceSpec(227, 227, 2.0f, isRound = true)

  /**
   * The AI-glasses display `androidx.xr.glimmer` targets: 960x720 at **density 1.0**.
   *
   * The density is load-bearing: Glimmer sizes UI in visual angle, and at ~30 pixels per degree its
   * calibrated sizes hold only at density 1.0 (18dp text → 18px → 0.6°); higher densities make
   * contrast and legibility read optimistically. 960x720 at 30 PPD is a 32 × 24° field of view.
   * Re-pin if Google publishes the AI Glasses AVD's exact values; keep the 30-PPD identity.
   *
   * Used as a wrap sandbox, not a pinned canvas; see [PreviewDiscovery.retargetGlimmerStickers].
   */
  val DEFAULT_GLASSES = DeviceSpec(960, 720, 1.0f)

  fun resolve(device: String?, widthDp: Int? = null, heightDp: Int? = null): DeviceSpec {
    // Explicit widthDp/heightDp with no device: use Studio's default density.
    if (widthDp != null && widthDp > 0 && heightDp != null && heightDp > 0) {
      return DeviceSpec(widthDp, heightDp, DEFAULT_DENSITY)
    }

    if (device != null) {
      KNOWN_DEVICES[device]?.let {
        return it.copy(isRound = isRoundDeviceString(device))
      }

      if (device.startsWith("spec:")) {
        val params =
          device
            .removePrefix("spec:")
            .split(",")
            .mapNotNull {
              val parts = it.split("=", limit = 2)
              if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim().removeSuffix("dp")
              else null
            }
            .toMap()
        // `parent=<id>` (what Studio writes after customizing a catalog device) supplies every term
        // the spec doesn't restate. Resolved through [resolve] so it follows the same rules as
        // `id:…`.
        val parent = params["parent"]?.let { resolve(it.asDeviceId()) }
        val base = parent ?: DEFAULT
        val parsedWidth = params["width"]?.toIntOrNull() ?: base.widthDp
        val parsedHeight = params["height"]?.toIntOrNull() ?: base.heightDp
        val (w, h) = orientedDp(parsedWidth, parsedHeight, params["orientation"])
        // `isRound=` / `shape=` override; otherwise the parent's shape carries through.
        val isRound =
          if (params.containsKey("isround") || params.containsKey("shape")) {
            params["isround"]?.equals("true", ignoreCase = true) == true ||
              params["shape"]?.equals("round", ignoreCase = true) == true
          } else {
            base.isRound
          }
        // `dpi=` is honoured, else the parent's (or Studio default) density. `cutout=` is accepted
        // but ignored.
        val density = params["dpi"]?.toIntOrNull()?.let { it / 160f } ?: base.density
        return DeviceSpec(w, h, density, isRound = isRound)
      }

      if (device.contains("wear", ignoreCase = true)) return DEFAULT_WEAR
    }

    return DEFAULT
  }

  /** `pixel_tablet` / `id:pixel_tablet` → the catalog key `resolve` looks up. */
  private fun String.asDeviceId(): String =
    trim().lowercase().let { if (it.startsWith("id:")) it else "id:$it" }

  /**
   * [widthDp] / [heightDp] rotated only when they contradict `orientation=`; a no-op otherwise, for
   * squares, and for absent or unknown tokens. Handling only landscape left `orientation=portrait`
   * (e.g. `@PreviewScreenSizes`' Tablet) rendering landscape.
   *
   * KEEP IN SYNC with the daemon's `FrameOrientation.orientedPx` (separate builds).
   */
  private fun orientedDp(widthDp: Int, heightDp: Int, orientation: String?): Pair<Int, Int> =
    when (orientation?.trim()?.lowercase()) {
      "portrait" -> if (widthDp > heightDp) heightDp to widthDp else widthDp to heightDp
      "landscape" -> if (heightDp > widthDp) heightDp to widthDp else widthDp to heightDp
      else -> widthDp to heightDp
    }

  private fun isRoundDeviceString(device: String): Boolean {
    val lower = device.lowercase()
    if (lower.startsWith("spec:")) {
      val params =
        lower
          .removePrefix("spec:")
          .split(',')
          .mapNotNull {
            val pair = it.split('=', limit = 2)
            pair.takeIf { it.size == 2 }?.let { values -> values[0].trim() to values[1].trim() }
          }
          .toMap()
      if ("isround" in params || "shape" in params) {
        return params["isround"] == "true" || params["shape"] == "round"
      }
    }
    return lower.contains("_round") ||
      lower.contains("isround=true") ||
      lower.contains("shape=round")
  }

  /**
   * Studio-parity sizing: an axis is fixed iff specified (or a device / `showSystemUi` frame
   * applies), else it wraps. The returned [SizeSpec] dimensions are sandbox dims: [SANDBOX_DP] for
   * wrapped axes, the effective dp for fixed ones.
   *
   * [wrapSandboxWidthDp] / [wrapSandboxHeightDp] ([PreviewParams.wrapSandboxWidthDp]) replace the
   * sandbox on a wrapped axis without fixing it; ignored on fixed axes and the device branch.
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
    // Device / showSystemUi: full frame on both axes; explicit widthDp/heightDp still override, as
    // in [resolve] and Studio.
    if (device != null || showSystemUi) {
      val spec = resolve(device, w, h)
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
