package ee.schimke.composeai.daemon

/**
 * Sysprop-driven configuration for the device-frame post-capture step. The renderer reads
 * [fromSystemProperties] after each successful capture to decide whether — and into which frame —
 * to composite the just-rendered PNG. The gradle plugin forwards these from
 * `composePreview.deviceFrame.*` Gradle properties. Unset / blank `device` disables the feature, so
 * existing builds are unaffected.
 */
object DeviceFrameConfig {

  /**
   * `auto` (resolve the frame from each preview's `device` class), or an explicit Device Art
   * Generator id (e.g. `wear_round`, `pixel_5`) to force one frame for every preview. Blank / unset
   * disables device framing.
   */
  const val DEVICE_PROP: String = "composeai.deviceframe.device"

  /** Overrides the CDN base the bezel layers are fetched from (mainly for tests / mirrors). */
  const val BASE_URL_PROP: String = "composeai.deviceframe.baseUrl"

  /** Directory the fetched bezel layers are cached in. Defaults to a temp subdirectory. */
  const val CACHE_DIR_PROP: String = "composeai.deviceframe.cacheDir"

  /** `false` drops the drop-shadow layer (cleaner flat marketing background). */
  const val SHADOW_PROP: String = "composeai.deviceframe.shadow"

  /** `false` drops the screen-glare layer. */
  const val GLARE_PROP: String = "composeai.deviceframe.glare"

  const val DEFAULT_BASE_URL: String =
    ee.schimke.composeai.data.deviceframe.DeviceArtCatalog.DEFAULT_BASE_URL

  const val AUTO: String = "auto"

  /** Which frame to use per render. */
  sealed interface Selection {
    /** Resolve the frame from each preview's `device` string via `DeviceArtCatalog`. */
    data object Auto : Selection

    /** Force a specific Device Art Generator frame for every preview. */
    data class Forced(val artId: String) : Selection
  }

  data class Settings(
    val selection: Selection,
    val baseUrl: String = DEFAULT_BASE_URL,
    val cacheDir: String? = null,
    val includeShadow: Boolean = true,
    val includeGlare: Boolean = true,
  )

  fun parse(
    device: String?,
    baseUrl: String? = null,
    cacheDir: String? = null,
    shadow: String? = null,
    glare: String? = null,
  ): Settings? {
    val d = device?.trim().orEmpty()
    if (d.isEmpty()) return null
    val selection = if (d.equals(AUTO, ignoreCase = true)) Selection.Auto else Selection.Forced(d)
    return Settings(
      selection = selection,
      baseUrl = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE_URL,
      cacheDir = cacheDir?.trim()?.takeIf { it.isNotEmpty() },
      includeShadow = shadow?.equals("false", ignoreCase = true) != true,
      includeGlare = glare?.equals("false", ignoreCase = true) != true,
    )
  }

  fun fromSystemProperties(): Settings? =
    parse(
      device = System.getProperty(DEVICE_PROP),
      baseUrl = System.getProperty(BASE_URL_PROP),
      cacheDir = System.getProperty(CACHE_DIR_PROP),
      shadow = System.getProperty(SHADOW_PROP),
      glare = System.getProperty(GLARE_PROP),
    )
}
