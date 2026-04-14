package ee.schimke.composeai.plugin

object DeviceDimensions {
    data class DeviceSpec(val widthDp: Int, val heightDp: Int, val density: Float = 2.0f)

    private val KNOWN_DEVICES = mapOf(
        "id:pixel_6" to DeviceSpec(411, 891),
        "id:pixel_5" to DeviceSpec(393, 851),
        "id:pixel_4" to DeviceSpec(393, 829),
        "id:pixel_7" to DeviceSpec(411, 891),
        "id:pixel_7_pro" to DeviceSpec(411, 891),
        "id:pixel_8" to DeviceSpec(411, 891),
        "id:pixel_9" to DeviceSpec(411, 891),
        "id:pixel_tablet" to DeviceSpec(1280, 800),
        "id:wearos_small_round" to DeviceSpec(192, 192),
        "id:wearos_large_round" to DeviceSpec(227, 227),
    )

    val DEFAULT = DeviceSpec(400, 800)
    val DEFAULT_WEAR = DeviceSpec(227, 227)

    fun resolve(device: String?, widthDp: Int? = null, heightDp: Int? = null): DeviceSpec {
        if (widthDp != null && widthDp > 0 && heightDp != null && heightDp > 0) {
            return DeviceSpec(widthDp, heightDp)
        }

        if (device != null) {
            KNOWN_DEVICES[device]?.let { return it }

            if (device.startsWith("spec:")) {
                val params = device.removePrefix("spec:").split(",").associate {
                    val (k, v) = it.split("=")
                    k.trim() to v.trim().removeSuffix("dp")
                }
                val w = params["width"]?.toIntOrNull() ?: DEFAULT.widthDp
                val h = params["height"]?.toIntOrNull() ?: DEFAULT.heightDp
                return DeviceSpec(w, h)
            }

            if (device.contains("wear", ignoreCase = true)) return DEFAULT_WEAR
        }

        return DEFAULT
    }
}
