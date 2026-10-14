package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.ServeBundleDaemon
import java.io.File
import kotlin.system.exitProcess

/**
 * `compose-preview bundle prepare <bundle.png | URL> -o <dir>` — unpack a packed bundle into a
 * workspace a daemon client can start from, with no build: its classes, resolved classpath,
 * `previews.json` and a `daemon-launch.json` land in `<dir>/build/compose-previews/`, the layout a
 * Gradle module's `composePreviewDaemonStart` writes. Tools that discover descriptors by that path
 * (compose-preview-mcp's `project=<bundle>`) then treat the bundle like any module, `:catalog`.
 *
 * The descriptor comes from [ServeBundleDaemon.materialize], the engine behind `serve`'s live
 * bundles and `bundle render --knob`, so all three launch a bundle's daemon the same way. Prints
 * the descriptor path on success.
 */
internal class BundlePrepareCommand(
  private val args: List<String>,
  private val materialize: (bundle: File, destDir: File, system: String) -> File? =
    { bundle, destDir, system ->
      val provisioned =
        try {
          provisionSidecars(bundle)
          true
        } catch (e: IllegalStateException) {
          System.err.println("bundle prepare: ${e.message}")
          false
        }
      if (!provisioned) null
      else
        ServeBundleDaemon.materialize(
            bundle,
            destDir,
            system = system,
            onLog = { System.err.println("[bundle prepare] $it") },
          )
          ?.descriptor
    },
) {
  fun run() {
    val source = CliFlags.firstPositional(args)
    val out = args.flagValue("--output") ?: args.flagValue("-o")
    if (source == null || out == null) {
      System.err.println(USAGE)
      exitProcess(64)
    }
    val bundle =
      try {
        BundleSource.resolveToFile(source)
      } catch (e: IllegalArgumentException) {
        System.err.println("bundle prepare: ${e.message}")
        exitProcess(1)
      }
    val descriptor = prepare(bundle, File(out))
    if (descriptor == null) {
      System.err.println(
        "bundle prepare: could not prepare a daemon for ${bundle.name} (see the log above)"
      )
      exitProcess(1)
    }
    println(descriptor.absolutePath)
  }

  /** Writes [bundle]'s daemon workspace under [workspace]; the descriptor, or null on failure. */
  internal fun prepare(bundle: File, workspace: File): File? {
    val destDir = File(workspace.absoluteFile, OUTPUT_DIR)
    // A fresh extraction: a repacked bundle must not keep the previous one's classes or previews.
    destDir.deleteRecursively()
    destDir.mkdirs()
    return materialize(bundle, destDir, bundle.nameWithoutExtension.ifBlank { "bundle" })
  }

  companion object {
    /** Where a Gradle module's daemon descriptor lives, relative to the module. */
    const val OUTPUT_DIR = "build/compose-previews"

    const val USAGE = "Usage: compose-preview bundle prepare <bundle.png | URL> -o <dir>"

    /** Fetches the backend's daemon sidecar on first use, as `bundle render` does. */
    private fun provisionSidecars(bundle: File) {
      when (readBundleBackendForRender(bundle)) {
        "desktop" -> {
          DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.DESKTOP)
          SkikoNativeProvision.prepareInstalledDesktopSidecars()
        }
        "android" -> DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.ANDROID)
      }
    }
  }
}
