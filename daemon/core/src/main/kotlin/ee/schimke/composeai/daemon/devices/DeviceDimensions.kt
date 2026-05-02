package ee.schimke.composeai.daemon.devices

/**
 * Per-`@Preview(device = ...)` geometry catalog and parser.
 *
 * **Duplicated from `:gradle-plugin`'s
 * [DeviceDimensions][ee.schimke.composeai.plugin.DeviceDimensions].** Per
 * [DESIGN.md § 7](../../../../../../docs/daemon/DESIGN.md#7-sharing-strategy--what-crosses-the-boundary)
 * the catalog lives in two places — the standalone Gradle plugin (which uses it during discovery to
 * populate `PreviewParams.widthDp/heightDp/density`) and the daemon (which uses it at the override
 * layer to resolve `renderNow.overrides.device` to dimensions). The two builds are separate
 * (`includeBuild("gradle-plugin")`) so there's no clean way to share without a publish-and-consume
 * cycle on every catalog change.
 *
 * **Drift policy.** The KNOWN_DEVICES map is the load-bearing data. When you add a device here, add
 * it to the gradle-plugin copy too (and vice versa). The `spec:width=…,height=…,dpi=…` parser is
 * also duplicated; the rules are intentionally identical so a single payload string drives both
 * code paths.
 *
 * **Daemon-only constants.** Unlike the gradle-plugin counterpart, this object only ships the
 * `resolve(device, widthDp?, heightDp?)` entrypoint — `resolveForRender` (which makes the
 * fixed-vs-wrap decision per axis) is plugin-only because it consumes `showSystemUi`, an annotation
 * field that doesn't appear in the daemon's override surface. Callers who already have
 * widthPx/heightPx/density (e.g. the `PreviewManifestRouter` after merging an inbound override) can
 * skip [resolve] entirely.
 *
 * @see ee.schimke.composeai.plugin.DeviceDimensions
 */
object DeviceDimensions {
  /**
   * Per-device geometry resolved from a `@Preview(device = ...)` string.
   *
   * `density` is the Compose density factor (= densityDpi / 160). The daemon multiplies dp by
   * density to populate `RenderSpec.widthPx`/`heightPx`; the Android backend then re-derives the
   * Robolectric `<n>dpi` qualifier from it.
   */
  data class DeviceSpec(
    val widthDp: Int,
    val heightDp: Int,
    val density: Float = DEFAULT_DENSITY,
    val isRound: Boolean = false,
  )

  /**
   * The density Android Studio uses when no device is specified — xxhdpi-ish (420dpi → 2.625x),
   * matching its default phone-class preview.
   */
  const val DEFAULT_DENSITY: Float = 2.625f

  // Source-of-truth for the dp values and densities below: sergio-sastre/ComposablePreviewScanner
  // (Phone.kt / Tablet.kt / Wear.kt / GenericDevices.kt / Desktop.kt / Television.kt /
  // Automotive.kt / XR.kt under android/.../device/types/), with dp = px / (densityDpi / 160)
  // and density = densityDpi / 160. KEEP IN SYNC with `:gradle-plugin`'s `DeviceDimensions`.
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

      // --- Generic Android Studio device IDs ---
      "id:small_phone" to DeviceSpec(360, 640, 2.0f),
      "id:medium_phone" to DeviceSpec(411, 914, 2.625f),
      "id:medium_tablet" to DeviceSpec(1280, 800, 2.0f),
      "id:resizable" to DeviceSpec(411, 914, 2.625f),

      // --- Wear OS ---
      "id:wearos_small_round" to DeviceSpec(192, 192, 2.0f),
      "id:wearos_large_round" to DeviceSpec(227, 227, 2.0f),
      "id:wearos_xl_round" to DeviceSpec(240, 240, 2.0f),
      "id:wearos_square" to DeviceSpec(180, 180, 2.0f),
      "id:wearos_rect" to DeviceSpec(201, 238, 2.0f),
      "id:wearos_rectangular" to DeviceSpec(201, 238, 2.0f),

      // --- Desktop ---
      "id:desktop_small" to DeviceSpec(1366, 768, 1.0f),
      "id:desktop_medium" to DeviceSpec(1920, 1080, 2.0f),
      "id:desktop_large" to DeviceSpec(1920, 1080, 1.0f),

      // --- Television (Android TV) ---
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

      // --- XR ---
      "id:xr_headset_device" to DeviceSpec(1280, 1279, 2.0f),
      "id:xr_device" to DeviceSpec(1280, 1279, 2.0f),
    )

  val DEFAULT = DeviceSpec(400, 800, DEFAULT_DENSITY)
  val DEFAULT_WEAR = DeviceSpec(227, 227, 2.0f, isRound = true)

  /**
   * The set of device-id strings the catalog recognises (every key in [KNOWN_DEVICES]). Useful for
   * building a `list_devices` MCP tool surface or validating user input before issuing a
   * `renderNow`. The `spec:...` and `name:...` grammars are not enumerable; they're parsed at
   * resolve-time.
   */
  val KNOWN_DEVICE_IDS: Set<String> = KNOWN_DEVICES.keys

  /**
   * Resolves a `@Preview(device = ...)` string to a [DeviceSpec]. Mirrors the gradle-plugin's
   * resolution rules byte-for-byte:
   *
   * - Explicit `widthDp`+`heightDp` short-circuit to a [DeviceSpec] at [DEFAULT_DENSITY] (no device
   *   info, so we fall back to the Studio default).
   * - `id:pixel_5`-style ids hit [KNOWN_DEVICES]; unknown ids fall through to the default.
   * - `spec:width=…,height=…,dpi=…,isRound=…` is parsed inline; the `dp` suffix on values is
   *   tolerated. `cutout=…` is accepted by Studio's grammar but ignored here until a renderer
   *   consumes it.
   * - Any device string containing `wear` (case-insensitive) returns [DEFAULT_WEAR].
   * - Otherwise [DEFAULT] (400×800 dp at xxhdpi).
   */
  fun resolve(device: String?, widthDp: Int? = null, heightDp: Int? = null): DeviceSpec {
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
              if (parts.size == 2) parts[0].trim() to parts[1].trim().removeSuffix("dp") else null
            }
            .toMap()
        val parsedWidth = params["width"]?.toIntOrNull() ?: DEFAULT.widthDp
        val parsedHeight = params["height"]?.toIntOrNull() ?: DEFAULT.heightDp
        val landscape = params["orientation"]?.equals("landscape", ignoreCase = true) == true
        val w = if (landscape) maxOf(parsedWidth, parsedHeight) else parsedWidth
        val h = if (landscape) minOf(parsedWidth, parsedHeight) else parsedHeight
        val isRound = params["isRound"]?.toBooleanStrictOrNull() == true
        val density = params["dpi"]?.toIntOrNull()?.let { it / 160f } ?: DEFAULT_DENSITY
        return DeviceSpec(w, h, density, isRound = isRound)
      }

      if (device.contains("wear", ignoreCase = true)) return DEFAULT_WEAR
    }

    return DEFAULT
  }

  private fun isRoundDeviceString(device: String): Boolean {
    val lower = device.lowercase()
    return lower.contains("_round") ||
      lower.contains("isround=true") ||
      lower.contains("shape=round")
  }
}
