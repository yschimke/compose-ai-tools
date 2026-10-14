package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.BundleClasspathHydration
import ee.schimke.composeai.bundle.BundleReader
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
 *
 * A bundle that keeps payloads outside the PNG (`bundle externalize`, `bundle split
 * --shared-classpath-out`) is hydrated first from `--res <pool>`, the same content-addressed pool
 * `bundle render` reads, with every blob size- and hash-verified; without the pool it fails rather
 * than preparing a daemon that cannot load its classes or renders without its fonts.
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
    val pool = args.flagValue("--res")?.let(::File)
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
    val descriptor =
      try {
        prepare(bundle, File(out), pool)
      } catch (e: IllegalStateException) {
        System.err.println("bundle prepare: ${e.message}")
        exitProcess(1)
      } catch (e: IllegalArgumentException) {
        System.err.println("bundle prepare: ${e.message}")
        exitProcess(1)
      }
    if (descriptor == null) {
      System.err.println(
        "bundle prepare: could not prepare a daemon for ${bundle.name} (see the log above)"
      )
      exitProcess(1)
    }
    println(descriptor.absolutePath)
  }

  /** Writes [bundle]'s daemon workspace under [workspace]; the descriptor, or null on failure. */
  internal fun prepare(bundle: File, workspace: File, pool: File? = null): File? {
    val destDir = File(workspace.absoluteFile, OUTPUT_DIR)
    // A fresh extraction: a repacked bundle must not keep the previous one's classes or previews,
    // and `classes/` goes straight onto the daemon's classpath, so a partial delete is a failure.
    check(!destDir.exists() || destDir.deleteRecursively()) {
      "could not remove the previous extraction at $destDir; stop the daemon using it and retry"
    }
    destDir.mkdirs()
    val runnable = hydrate(bundle, destDir, pool)
    return materialize(runnable, destDir, bundle.nameWithoutExtension.ifBlank { "bundle" })
  }

  /**
   * [bundle] with its externalized classpath and resources restored from [pool], or [bundle] itself
   * when it carries everything. An unreadable bundle is passed through for [materialize] to report.
   */
  private fun hydrate(bundle: File, destDir: File, pool: File?): File {
    val manifest = runCatching { BundleReader.readMetadata(bundle).manifest }.getOrNull()
    val external = manifest?.let { it.externalClasspath.size + it.externalResources.size } ?: 0
    if (external == 0) return bundle
    checkNotNull(pool) {
      "${bundle.name} keeps $external payload(s) outside the PNG (bundle externalize or " +
        "bundle split --shared-classpath-out); pass --res <pool-dir>, the content-addressed pool " +
        "they were published to"
    }
    check(pool.isDirectory) { "--res pool '${pool.path}' is not a directory" }
    fun blob(sha256: String): ByteArray? = File(pool, sha256).takeIf { it.isFile }?.readBytes()
    return BundleClasspathHydration.hydrate(
      source = bundle,
      output = File(destDir, HYDRATED_BUNDLE),
      resolveClasspath = { blob(it.sha256) },
      resolveResource = { blob(it.sha256) },
    )
  }

  companion object {
    /** Where a Gradle module's daemon descriptor lives, relative to the module. */
    const val OUTPUT_DIR = "build/compose-previews"

    const val USAGE =
      "Usage: compose-preview bundle prepare <bundle.png | URL> -o <dir> [--res <pool-dir>]"

    /** The self-contained copy of an externalized bundle, kept beside what it was prepared into. */
    const val HYDRATED_BUNDLE = "bundle-hydrated.png"

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
