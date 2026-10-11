package ee.schimke.composeai.cli.serve

/**
 * Layout convention for a published catalog's baked images, and how an image path becomes the
 * route-safe preview id. Lives here (not in the server's `ServeCatalogStore`, which mirrors it) so
 * [PreviewHistoryManifest] can use it. Pure, no I/O.
 */
public object CatalogImagePaths {
  /** Directory, relative to the catalog root, holding the baked preview PNGs. */
  public const val IMAGES_DIR: String = "images"

  /**
   * The single-segment preview id for a catalog image path: the routes capture one segment, so drop
   * `images/` and `.png` and replace `/` with `__` (e.g.
   * `images/button-filled/ideal__default__dark.png` → `button-filled__ideal__default__dark`). The
   * design-parity exporter derives `livePreview` links the same way.
   */
  public fun previewIdFor(imagePath: String): String =
    imagePath.removePrefix("$IMAGES_DIR/").removeSuffix(".png").replace("/", "__")
}
