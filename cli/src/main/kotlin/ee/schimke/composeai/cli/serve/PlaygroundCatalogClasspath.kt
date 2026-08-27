package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.coordinates.CoordinateResolver
import ee.schimke.composeai.bundle.extractBundleClassesAndManifest
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Resolves a catalog's packed **liveBundle** into the classpath a playground snippet compiles
 * against — the production backing for [PlaygroundCompileService]'s `catalogClasspath` seam
 * (`docs/design/PLAYGROUND.md` §8).
 *
 * This is the compile-time twin of [ServeBundleDaemon.materialize]'s classpath resolution, minus
 * the daemon launch: extract the bundle's `classes/app.jar` (the catalog's own composables) and
 * resolve its `manifest.classpath` Maven coordinates to jars via [CoordinateResolver] (Central +
 * Google Maven + any [extraMavenRepos]). A snippet compiled against the result can `import` both
 * the resolved library (e.g. `androidx.compose.material3.*`, complete because it comes from the
 * unminimized library jar) and whatever of the catalog's own composables survived bundle
 * minimization.
 *
 * **One flat classpath, no parent/child split.** [ServeBundleDaemon.bundleDaemonClasspaths]
 * partitions jars into a daemon-parent overlay and a user-child loader — but that split is a
 * *render-time* classloader-delegation concern. For *compiling* the snippet, every jar belongs on
 * one classpath, so this resolver keeps it flat (catalog classes first, then embedded libs, then
 * resolved deps).
 */
object PlaygroundCatalogClasspath {

  /**
   * Resolve [bundleFile] into a compile classpath, extracting into [destDir]. Returns null (logging
   * why) when the bundle can't be read or extracted — the caller then reports the mode as
   * unavailable rather than compiling against an incomplete classpath.
   */
  fun resolve(
    bundleFile: File,
    destDir: File,
    system: String,
    extraMavenRepos: List<String> = emptyList(),
    offline: Boolean = false,
    fileSystem: FileSystem = SystemFileSystem,
    onLog: (String) -> Unit = {},
  ): PlaygroundCompileService.Classpath? {
    val manifest =
      try {
        BundleReader.readMetadata(bundleFile).manifest
      } catch (e: Exception) {
        onLog("playground $system: could not read bundle metadata (${e.message})")
        return null
      }

    val zipBytes =
      try {
        BundleReader.extractZipBytes(bundleFile, fileSystem)
      } catch (e: Exception) {
        onLog("playground $system: could not read bundle zip (${e.message})")
        return null
      }

    destDir.mkdirs()
    val classesDir = File(destDir, "classes").apply { mkdirs() }
    val libsDir = File(destDir, "libs").apply { mkdirs() }
    val previewsJson = File(destDir, "previews.json")
    // A fully IR-backed bundle carries no classes/app.jar; a mixed/class-backed one must — mirrors
    // ServeBundleDaemon.materialize's gate.
    val irPreviewIds = manifest.intermediateRepresentations.mapTo(mutableSetOf()) { it.previewId }
    val requireAppJar = manifest.previewIds.any { it !in irPreviewIds }
    try {
      extractBundleClassesAndManifest(
        zipBytes,
        classesDir,
        previewsJson,
        bundleFile,
        requireAppJar,
        fileSystem,
      )
    } catch (e: Exception) {
      onLog("playground $system: bundle extraction failed (${e.message})")
      return null
    }

    val libJars = BundleReader.extractEmbeddedLibs(zipBytes, libsDir, fileSystem)
    val mavenCoords = manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
    val resolutions =
      CoordinateResolver(
          warn = { onLog("playground $system: $it") },
          networkEnabled = if (offline) false else CoordinateResolver.defaultNetworkEnabled(),
          remoteRepositories =
            CoordinateResolver.DEFAULT_REMOTE_REPOSITORIES +
              extraMavenRepos.filter { it.isNotBlank() },
        )
        .resolveAll(mavenCoords)
    val resolvedJars = requireAllResolved(system, resolutions, onLog) ?: return null

    return assemble(system, classesDir, libJars, resolvedJars)
  }

  /**
   * Every declared coordinate must resolve, or the compile classpath is **incomplete** and the mode
   * is reported unavailable (return null). Unlike the live-daemon path — which tolerates a partial
   * classpath and falls back to baked PNGs — a playground compile against a missing catalog library
   * would surface a misleading `unresolved reference` to the user instead of the honest
   * mode-unavailable response. So fail closed: log the misses and refuse the whole classpath rather
   * than assembling a partial one. Returns the resolved jars when every coordinate resolved.
   */
  internal fun requireAllResolved(
    system: String,
    resolutions: List<CoordinateResolver.Resolution>,
    onLog: (String) -> Unit,
  ): List<File>? {
    val unresolved = resolutions.filter { it.file == null }
    if (unresolved.isNotEmpty()) {
      onLog(
        "playground $system: ${unresolved.size} unresolved dependency coordinate(s), mode " +
          "unavailable: ${unresolved.joinToString { it.coordinate.toString() }}"
      )
      return null
    }
    return resolutions.mapNotNull { it.file }
  }

  /**
   * Pure classpath assembly: catalog classes first, then embedded libs, then every resolved Maven
   * jar, deduplicated and order-preserving. Separated from [resolve]'s IO so the ordering/dedup can
   * be unit-tested without a real bundle.
   */
  internal fun assemble(
    system: String,
    classesDir: File,
    libJars: List<File>,
    resolvedJars: List<File>,
  ): PlaygroundCompileService.Classpath {
    val entries =
      (listOf(classesDir) + libJars + resolvedJars)
        .map { it.absolutePath }
        .distinct()
        .map { it.toPath() }
    return PlaygroundCompileService.Classpath(moduleName = "playground-$system", entries = entries)
  }
}
