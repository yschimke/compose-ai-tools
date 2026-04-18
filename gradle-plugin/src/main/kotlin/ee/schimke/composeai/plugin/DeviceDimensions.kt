package ee.schimke.composeai.plugin

object DeviceDimensions {
    /**
     * Per-device geometry resolved from a `@Preview(device = ...)` string.
     *
     * `density` is the Compose density factor (= densityDpi / 160). Renderers map
     * this back to a Robolectric `<n>dpi` qualifier (and the desktop renderer
     * passes it straight through to Compose's `Density(...)` constructor) so output
     * PNGs match what Android Studio's preview renders for the same `@Preview`.
     */
    data class DeviceSpec(val widthDp: Int, val heightDp: Int, val density: Float = DEFAULT_DENSITY)

    /**
     * The density Android Studio uses when no device is specified — xxhdpi-ish
     * (420dpi → 2.625x), matching its default phone-class preview. Picked over
     * the previous 2.0x default to align with Studio; only affects previews
     * that don't pin a `device` (or a `spec:...,dpi=...`).
     */
    const val DEFAULT_DENSITY: Float = 2.625f

    // Source-of-truth for the dp values and densities below: sergio-sastre/ComposablePreviewScanner
    // (Phone.kt / Tablet.kt / Wear.kt / GenericDevices.kt / Desktop.kt / Television.kt /
    // Automotive.kt / XR.kt under android/.../device/types/), with dp = px / (densityDpi / 160)
    // and density = densityDpi / 160. This is the same data the takahirom/roborazzi
    // compose-preview-scanner-support pipeline uses, surfaced via our Roborazzi adoption
    // exploration — see docs/explorations/roborazzi-compose-preview-scanner-support-findings.md.
    private val KNOWN_DEVICES = mapOf(
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
        // Pixel 6 / 6a / 7 / 7a / 8 / 8a all share the same screen geometry
        // (1080×2400 px @ 420dpi → 411×914 dp). Earlier revisions of this file
        // accidentally used the Pixel 6 Pro value (891) for these — fixed.
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
        // These appear as "Small Phone", "Medium Phone", "Medium Tablet", "Resizable
        // (Experimental)" in the @Preview device picker.
        "id:small_phone" to DeviceSpec(360, 640, 2.0f),
        "id:medium_phone" to DeviceSpec(411, 914, 2.625f),
        "id:medium_tablet" to DeviceSpec(1280, 800, 2.0f),
        "id:resizable" to DeviceSpec(411, 914, 2.625f),

        // --- Wear OS ---
        // WearDevices constants from androidx.wear.tooling.preview.devices —
        // used by @androidx.wear.tiles.tooling.preview.Preview. All wear devices
        // run at 320dpi (xhdpi → 2.0x) per upstream.
        "id:wearos_small_round" to DeviceSpec(192, 192, 2.0f),
        "id:wearos_large_round" to DeviceSpec(227, 227, 2.0f),
        "id:wearos_square" to DeviceSpec(180, 180, 2.0f),
        "id:wearos_rect" to DeviceSpec(201, 238, 2.0f),
        // wearos_rectangular is the older Studio identifier for the same device as
        // wearos_rect (kept around for projects that haven't migrated yet).
        "id:wearos_rectangular" to DeviceSpec(201, 238, 2.0f),

        // --- Desktop ---
        // Studio's "Small/Medium/Large Desktop" entries; useful for Compose for Desktop
        // previews routed through the Android renderer.
        "id:desktop_small" to DeviceSpec(1366, 768, 1.0f),
        "id:desktop_medium" to DeviceSpec(1920, 1080, 2.0f),
        "id:desktop_large" to DeviceSpec(1920, 1080, 1.0f),

        // --- Television (Android TV) ---
        // 4K and 1080p resolve to the same dp surface; 4K just renders at 4× density.
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
        // xr_device is the deprecated identifier replaced by xr_headset_device in newer
        // Android Studio versions; both refer to the same device.
        "id:xr_headset_device" to DeviceSpec(1280, 1279, 2.0f),
        "id:xr_device" to DeviceSpec(1280, 1279, 2.0f),
    )

    val DEFAULT = DeviceSpec(400, 800, DEFAULT_DENSITY)
    val DEFAULT_WEAR = DeviceSpec(227, 227, 2.0f)

    fun resolve(device: String?, widthDp: Int? = null, heightDp: Int? = null): DeviceSpec {
        // Explicit widthDp/heightDp on the @Preview annotation — no device info,
        // so fall back to the AS default density (xxhdpi-ish, matching Studio's
        // default phone preview).
        if (widthDp != null && widthDp > 0 && heightDp != null && heightDp > 0) {
            return DeviceSpec(widthDp, heightDp, DEFAULT_DENSITY)
        }

        if (device != null) {
            KNOWN_DEVICES[device]?.let { return it }

            if (device.startsWith("spec:")) {
                val params = device.removePrefix("spec:").split(",").mapNotNull {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) parts[0].trim() to parts[1].trim().removeSuffix("dp") else null
                }.toMap()
                val w = params["width"]?.toIntOrNull() ?: DEFAULT.widthDp
                val h = params["height"]?.toIntOrNull() ?: DEFAULT.heightDp
                // `dpi=` is part of Studio's spec: grammar (e.g. spec:width=411dp,height=914dp,dpi=420)
                // — honour it if present, otherwise fall back to the AS default.
                val density = params["dpi"]?.toIntOrNull()?.let { it / 160f } ?: DEFAULT_DENSITY
                return DeviceSpec(w, h, density)
            }

            if (device.contains("wear", ignoreCase = true)) return DEFAULT_WEAR
        }

        return DEFAULT
    }
}
