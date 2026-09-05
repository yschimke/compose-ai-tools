package ee.schimke.composeai.cli.serve

/**
 * The layout convention for a published catalog's baked images: where they live under the catalog
 * root, and how an image path becomes the route-safe preview id that names it everywhere else.
 *
 * Split out of `ServeCatalogStore`'s companion, which is where it was written and where it still
 * reads from (that class is in `:server`, so it is named here rather than linked). It is here
 * because [PreviewHistoryManifest] needs it and lives in `:render-host` — the `history manifest`
 * command has to turn the image paths it diffs between two commits into preview ids, and that is
 * the only thing it wanted from a 3,900-line catalog store bound to the server.
 *
 * Shape and a pure function, deliberately. Nothing here reads a file, so both sides of the module
 * boundary can agree on the convention without either owning the other's I/O.
 */
object CatalogImagePaths {
  /** Directory, relative to the catalog root, holding the baked preview PNGs. */
  const val IMAGES_DIR = "images"

  /**
   * The single-path-segment preview id for a catalog image path. The serve routes (`/p/{name}`,
   * `/render/{name}.png`, `/ws/{name}`) capture one segment, so a catalog image's subdirectory `/`
   * (e.g. `images/button-filled/ideal__default__dark.png`) must be flattened or the preview is
   * listed but can't be opened/rendered. We drop the `images/` prefix + `.png` suffix and replace
   * `/` with `__` (the same separator the variant keys already use), giving a stable, route-safe id
   * like `button-filled__ideal__default__dark`. The design-parity catalog exporter derives the
   * `livePreview` deep link the same way so the link resolves to this id.
   */
  fun previewIdFor(imagePath: String): String =
    imagePath.removePrefix("$IMAGES_DIR/").removeSuffix(".png").replace("/", "__")
}
