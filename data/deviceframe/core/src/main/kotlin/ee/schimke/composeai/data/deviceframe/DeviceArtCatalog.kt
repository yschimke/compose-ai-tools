package ee.schimke.composeai.data.deviceframe

/**
 * Geometry for compositing a rendered preview into a Google "device art" bezel — the same frames
 * Android Studio / the
 * [Device Art Generator](https://developer.android.com/distribute/marketing-tools/device-art-generator)
 * draw around a screenshot, including the hardware buttons (the Wear crown / side button, the phone
 * power + volume keys) which are painted into the `back.png` artwork itself.
 *
 * The numbers below are transcribed from Google's `device-art-generator.js` `DEVICES` table (the
 * `portOffset` / `portSize` / `portCornerRadius` fields). The screenshot is scaled into the
 * `[screenX, screenY] .. [+screenWidth, +screenHeight]` rectangle and clipped to [cornerRadius] (a
 * full circle when `cornerRadius * 2 == screenWidth`), then the bezel is composited around it.
 *
 * The frame PNGs themselves are **not** committed here — the connector fetches them on demand from
 * Google's CDN and caches them on disk (see `DeviceArtFetcher`). They are licensed CC-BY 3.0, so
 * any produced image must carry the [ATTRIBUTION] string.
 */
object DeviceArtCatalog {

  /** Layer resource names, in composite (back-to-front) order. */
  const val SHADOW = "shadow"
  const val BACK = "back"
  const val FORE = "fore"

  /**
   * CDN the bezel layers are fetched from: `<base>/<artId>/port_<resource>.png`. The Gradle plugin
   * prefetches from here (via Ktor/OkHttp, off the render subprocess); the renderer reads the disk
   * cache the prefetch fills.
   */
  const val DEFAULT_BASE_URL =
    "https://developer.android.com/distribute/marketing-tools/device-art-resources"

  /**
   * Attribution required by the CC-BY 3.0 licence on the Device Art Generator frames. Producers
   * write this next to every framed PNG and docs surface it; do not drop it.
   */
  const val ATTRIBUTION =
    "Device frame artwork from the Android Device Art Generator " +
      "(https://developer.android.com/distribute/marketing-tools/device-art-generator), " +
      "© Google, licensed under CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/)."

  /**
   * One device-art frame.
   *
   * @param artId the Device Art Generator device id; also the CDN path segment and output filename
   *   suffix (`deviceframe_<artId>.png`).
   * @param screenX/screenY top-left of the screen rectangle within the (un-scaled) frame, in frame
   *   pixels.
   * @param screenWidth/screenHeight the screen rectangle the screenshot is scaled into.
   * @param cornerRadius rounded-rect corner radius for the screen clip; `0` = square corners,
   *   `screenWidth / 2` = full circle (Wear round).
   * @param resources frame layers to fetch + composite, in back-to-front order. `back` is always
   *   present; `shadow` (drop shadow, drawn first) and `fore` (glare, drawn last) are optional.
   * @param notch when true the `back` layer is drawn a second time *over* the screen so a display
   *   cutout occludes the screenshot (matches the generator's notch handling).
   */
  data class DeviceArtSpec(
    val artId: String,
    val screenX: Int,
    val screenY: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val cornerRadius: Int,
    val resources: List<String>,
    val notch: Boolean = false,
  )

  /** Round Wear OS watch — crown + side button are part of the bezel art. */
  val WEAR_ROUND =
    DeviceArtSpec(
      artId = "wear_round",
      screenX = 128,
      screenY = 134,
      screenWidth = 320,
      screenHeight = 320,
      cornerRadius = 160,
      resources = listOf(BACK),
    )

  /** Square Wear OS watch. */
  val WEAR_SQUARE =
    DeviceArtSpec(
      artId = "wear_square",
      screenX = 200,
      screenY = 214,
      screenWidth = 320,
      screenHeight = 320,
      cornerRadius = 0,
      resources = listOf(BACK),
    )

  /** Generic modern phone frame (Pixel 5 art) — power + volume keys are part of the bezel. */
  val PHONE =
    DeviceArtSpec(
      artId = "pixel_5",
      screenX = 140,
      screenY = 84,
      screenWidth = 1080,
      screenHeight = 2340,
      cornerRadius = 0,
      resources = listOf(SHADOW, BACK, FORE),
      notch = true,
    )

  /** Every frame addressable by an explicit `composeai.deviceframe.device=<artId>` override. */
  val ALL: List<DeviceArtSpec> = listOf(WEAR_ROUND, WEAR_SQUARE, PHONE)

  private val BY_ART_ID: Map<String, DeviceArtSpec> = ALL.associateBy { it.artId }

  /** Look a frame up by its explicit [DeviceArtSpec.artId]; null when unknown. */
  fun byArtId(artId: String): DeviceArtSpec? = BY_ART_ID[artId]

  /**
   * Resolve the frame to use for a `@Preview(device = ...)` string in "auto" mode, mirroring the
   * device-class buckets the renderer already understands. Returns null for device classes that
   * have no shipped frame (tablet, TV, automotive, XR, desktop) so those previews stay un-framed.
   */
  fun forDeviceString(device: String?): DeviceArtSpec? {
    if (device == null) return null
    val d = device.lowercase()
    return when {
      // Any round screen is a watch (a `spec:...,isround=true` need not name "wear").
      isRound(d) -> WEAR_ROUND
      d.contains("wear") || d.contains("watch") ->
        // Rectangular Wear has no generator frame; fall back to the round bezel rather than
        // leaving the watch un-framed.
        if (d.contains("square")) WEAR_SQUARE else WEAR_ROUND
      d.contains("tablet") ||
        d.contains("tv_") ||
        d.contains("automotive") ||
        d.contains("xr_") ||
        d.contains("desktop") -> null
      // Pixel / generic phone ids, spec: strings, and bare width/height previews all get the
      // generic phone bezel.
      else -> PHONE
    }
  }

  private fun isRound(lowerDevice: String): Boolean =
    lowerDevice.contains("_round") ||
      lowerDevice.contains("isround=true") ||
      lowerDevice.contains("shape=round")
}
