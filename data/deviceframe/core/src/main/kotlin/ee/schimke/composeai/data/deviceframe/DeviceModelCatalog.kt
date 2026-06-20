package ee.schimke.composeai.data.deviceframe

/**
 * Catalog of 3D device models (glTF-binary) for the spatial / 3D device-shape viewer — the 3D
 * analogue of [DeviceArtCatalog]'s 2D bezel frames.
 *
 * As with the device-art frames, the model **bytes are not committed**: each entry is a [url] that
 * is fetched on demand and cached on disk (the same prefetch-off-the-render-subprocess pattern as
 * `DeviceArtPrefetch` / `CachedDeviceArtSource`). Referencing a remote asset by URL is not
 * redistribution, so the upstream model's own licence governs use — and any produced image must
 * carry that model's [DeviceModelSpec.attribution], exactly like [DeviceArtCatalog.ATTRIBUTION].
 *
 * Offline tests and CI use the committed CC0 fixture under `renderers/xr-composite/test/models`
 * instead of a network fetch; `render_glb_preview.py` renders either a committed `.glb` or one of
 * these URLs.
 */
object DeviceModelCatalog {

  /**
   * One runtime-fetched device model.
   *
   * @param id stable lookup key (also the cache filename stem).
   * @param url remote glTF-binary, fetched at runtime and cached; never committed to the repo.
   * @param attribution credit line that must be surfaced next to any rendered output, as the
   *   upstream licence requires.
   * @param license SPDX-style identifier of the upstream model's licence.
   */
  data class DeviceModelSpec(
    val id: String,
    val url: String,
    val attribution: String,
    val license: String,
  )

  /**
   * Apple iPhone 11 Pro. Licensed **CC BY-NC-SA 4.0** by the original author, so it is *referenced,
   * never redistributed* — there is no committed copy, and every rendered frame carries
   * [attribution]. The NonCommercial + ShareAlike terms attach to the upstream model; this
   * runtime-fetch reference is cleared for the project's use. "Apple" and "iPhone" are trademarks
   * of Apple Inc.; this model is a third-party likeness, not an Apple asset.
   */
  val IPHONE =
    DeviceModelSpec(
      id = "iphone",
      url = "https://raw.githubusercontent.com/pizza3/asset/master/iphone.glb",
      attribution =
        "\"Apple iPhone 11 Pro\" by OneSteven (https://sketchfab.com/Steven007), licensed " +
          "CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/), via Sketchfab " +
          "(https://sketchfab.com/3d-models/apple-iphone-11-pro-e88c8489a48b494bb4db178c2907f737). " +
          "Apple and iPhone are trademarks of Apple Inc.",
      license = "CC-BY-NC-SA-4.0",
    )

  /** Every runtime-fetchable device model, addressable by [DeviceModelSpec.id]. */
  val ALL: List<DeviceModelSpec> = listOf(IPHONE)

  private val BY_ID: Map<String, DeviceModelSpec> = ALL.associateBy { it.id }

  /** Look a model up by its [DeviceModelSpec.id]; null when unknown. */
  fun byId(id: String): DeviceModelSpec? = BY_ID[id]
}
