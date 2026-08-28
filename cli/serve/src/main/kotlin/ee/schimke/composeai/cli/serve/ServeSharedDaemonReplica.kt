package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files

/** Open one daemon replica with private render and data output directories. */
public fun openIsolatedSharedDaemonReplica(
  descriptorPath: File,
  open: (systemPropertyOverrides: Map<String, String>) -> ServeHost,
): ServeHost {
  val replicaRoot =
    Files.createTempDirectory(descriptorPath.parentFile.toPath(), "serve-shared-replica-").toFile()
  val replica =
    try {
      open(
        mapOf(
          // Both engines derive their data root as outputDir.parent/data, so this isolates PNGs
          // and every file-backed data product in one override.
          "composeai.render.outputDir" to File(replicaRoot, "renders").absolutePath
        )
      )
    } catch (t: Throwable) {
      replicaRoot.deleteRecursively()
      throw t
    }
  return object : ServeHost by replica {
    override fun close() {
      try {
        replica.close()
      } finally {
        replicaRoot.deleteRecursively()
      }
    }
  }
}
