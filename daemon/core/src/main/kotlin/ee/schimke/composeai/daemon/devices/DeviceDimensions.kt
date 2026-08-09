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
 * it to the gradle-plugin copy too (and vice versa). The `spec:parent=…,width=…,height=…,dpi=…`
 * parser is also duplicated; the rules are intentionally identical so a single payload string
 * drives both code paths, and `DeviceDimensionsCatalogDriftTest` compares both the catalog and the
 * set of `spec:` terms each copy reads so a term learned on one side can't go missing on the other.
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
   * - `spec:parent=…,width=…,height=…,dpi=…,isRound=…,orientation=…` is parsed inline; the `dp`
   *   suffix on values is tolerated. `parent=` names a catalog device that supplies every term the
   *   string doesn't restate, and `orientation=` rotates the resolved frame through
   *   [FrameOrientation.orientedPx] — the same idempotent swap the override lane applies.
   *   `cutout=…` is accepted by Studio's grammar but ignored until a renderer consumes it.
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
              if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim().removeSuffix("dp")
              else null
            }
            .toMap()
        // `parent=<id>` — what Studio's device picker writes as soon as you pick a catalog device
        // and change anything about it (`spec:parent=pixel_tablet,orientation=portrait`). It
        // supplies every term the string doesn't restate — geometry, density, shape — so without
        // the lookup the whole spec collapsed to the 400×800 default and the picked device
        // vanished. Resolved through [resolve] itself so a parent id follows exactly the same rules
        // as a bare `device = "id:…"`, wear fallback included.
        val parent = params["parent"]?.let { resolve(it.asDeviceId()) }
        val base = parent ?: DEFAULT
        val parsedWidth = params["width"]?.toIntOrNull() ?: base.widthDp
        val parsedHeight = params["height"]?.toIntOrNull() ?: base.heightDp
        // The device string's own `orientation=` term is the same request the override lane sends
        // (issue #3547), one layer earlier — so it goes through the same idempotent swap. Landscape
        // alone used to be handled here, so `orientation=portrait` on a landscape spec — what
        // `@PreviewScreenSizes`' "Tablet" entry asks for — silently stayed landscape.
        val (w, h) = FrameOrientation.orientedPx(parsedWidth, parsedHeight, params["orientation"])
        // `isRound=` / `shape=` state the shape outright; only when neither is present does the
        // parent's shape carry through (a round watch parent stays round).
        val isRound =
          if (params.containsKey("isround") || params.containsKey("shape")) {
            params["isround"]?.equals("true", ignoreCase = true) == true ||
              params["shape"]?.equals("round", ignoreCase = true) == true
          } else {
            base.isRound
          }
        val density = params["dpi"]?.toIntOrNull()?.let { it / 160f } ?: base.density
        return DeviceSpec(w, h, density, isRound = isRound)
      }

      if (device.contains("wear", ignoreCase = true)) return DEFAULT_WEAR
    }

    return DEFAULT
  }

  /** `pixel_tablet` / `id:pixel_tablet` → the catalog key [resolve] looks up. */
  private fun String.asDeviceId(): String =
    trim().lowercase().let { if (it.startsWith("id:")) it else "id:$it" }

  private fun isRoundDeviceString(device: String): Boolean {
    val lower = device.lowercase()
    return lower.contains("_round") ||
      lower.contains("isround=true") ||
      lower.contains("shape=round")
  }
}

/**
 * The `(widthDp, heightDp)` a device frame renders at, given the annotation's own [widthDp] /
 * [heightDp] alongside the `device`.
 *
 * Annotation dp override the catalog only when **both** axes are set — the same precedence
 * [DeviceDimensions.resolve] applies to its `widthDp`/`heightDp` arguments, and therefore the one
 * the gradle plugin's `DeviceDimensions.resolveForRender` (and so the standalone renderer's PNG)
 * uses. A single-axis hint is ignored, exactly as Studio ignores it on a device frame.
 *
 * Kept here rather than in either resolver so the four places that make this decision — both
 * daemons' `PreviewManifestEntry.resolved()` and `renderSpecFromInfo()` — cannot drift apart on it.
 */
fun DeviceDimensions.DeviceSpec.frameDpOverriddenBy(widthDp: Int?, heightDp: Int?): Pair<Int, Int> {
  val w = widthDp?.takeIf { it > 0 }
  val h = heightDp?.takeIf { it > 0 }
  return if (w != null && h != null) w to h else this.widthDp to this.heightDp
}
