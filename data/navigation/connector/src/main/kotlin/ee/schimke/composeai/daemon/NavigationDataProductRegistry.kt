package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.navigation.NavigationDataProduct
import java.io.File

/**
 * Registry for `data/navigation`. Path-transport with the base class's inline-upgrade for clients
 * that request `inline = true`; missing-file → `NotAvailable` since the producer side is
 * Android-only and CMP-desktop sessions don't write this artefact yet.
 *
 * **Module layout** mirrors the fonts split: `:data-navigation-core` holds the payload types and
 * shared constants, this connector holds the registry. The Intent → wire-payload producer lives in
 * `:daemon:android` since it imports `android.content.Intent`.
 */
class NavigationDataProductRegistry(private val rootDir: File) :
  FileBackedDataProductRegistry(
    capabilities =
      listOf(
        DataProductCapability(
          kind = NavigationDataProduct.KIND,
          schemaVersion = NavigationDataProduct.SCHEMA_VERSION,
          transport = DataProductTransport.PATH,
          attachable = true,
          fetchable = true,
          requiresRerender = false,
        )
      )
  ) {
  override fun fileFor(previewId: String, kind: String): File? =
    if (kind == NavigationDataProduct.KIND)
      rootDir.resolve(previewId).resolve(NavigationDataProduct.FILE)
    else null
}
