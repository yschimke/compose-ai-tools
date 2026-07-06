package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.BundleReader
import ee.schimke.composeai.cli.CoordinateResolver
import ee.schimke.composeai.cli.PreviewManifest
import ee.schimke.composeai.cli.bundleSidecarSearchDescription
import ee.schimke.composeai.cli.extractBundleClassesAndManifest
import ee.schimke.composeai.cli.locateBundleSidecarJars
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.mcp.DaemonLaunchDescriptor
import java.io.File
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Materialises a daemon-backed [ServeSessionState] straight from a **packed preview bundle** — no
 * Gradle build, no worktree, no repo clone. This is the engine behind serving a `--catalogs`
 * system's `liveBundle` ([ServeCatalogStore]): extract the bundle's `classes/app.jar` +
 * `previews.json` (+ any embedded `libs/`), resolve its `maven` classpath entries via
 * [CoordinateResolver], locate the CLI install's `lib-daemon-desktop`/`lib-renderer` sidecar jars
 * (same lookup `bundle daemon` uses), and write a `daemon-launch.json` in the exact shape
 * `SubprocessRenderSessions.open` reads. Writing it as a **file** (rather than constructing the
 * descriptor purely in-memory, the way
 * [ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions.openBundleDaemon] does)
 * is what lets this session ride the existing `ServeSessionState → openHost → ServeRenderHost.open
 * → registry.register` path unmodified — [ServeSessionRegistry] resumes a suspended session by
 * re-opening the same descriptor path, so suspend/resume works for free.
 *
 * Desktop-only for now — mirrors `bundle daemon`'s desktop launch (`DaemonMain` +
 * `lib-daemon-desktop` + `lib-renderer`). An `android`-backend bundle would need the Robolectric
 * sidecar + `ANDROID_HOME` wiring `bundle daemon` also does for that backend; out of scope here —
 * [materialize] returns `null` (logging why) so the caller falls back to the catalog's baked PNGs
 * or its Gradle `source` build.
 */
internal object ServeBundleDaemon {

  /**
   * Extract [bundleFile] into [destDir] and synthesise a working [ServeSessionState] for it, or
   * `null` (logging a clear reason via [onLog]) on any failure — a bad/foreign bundle, a
   * non-desktop backend, missing sidecar jars, or an empty preview manifest. [offline] forces
   * classpath resolution to skip the network (mirrors `-Dcomposeai.bundle.offline`); default
   * `false` still honours that sysprop / `COMPOSE_PREVIEW_OFFLINE` via [CoordinateResolver]'s own
   * default.
   */
  fun materialize(
    bundleFile: File,
    destDir: File,
    system: String,
    offline: Boolean = false,
    /**
     * Extra classpath directories prepended after the bundle's own `classes/` — the rehydrated
     * [BundleReader.Manifest.externalResources] pool (fonts lifted out of `classes/app.jar` by
     * `bundle externalize`, materialized at their original resource paths so
     * `getResourceAsStream("/fonts/…")` resolves). Empty for a self-contained bundle.
     */
    extraClasspathDirs: List<File> = emptyList(),
    fileSystem: FileSystem = SystemFileSystem,
    onLog: (String) -> Unit = { System.err.println("[serve bundle] $it") },
  ): ServeSessionState? {
    destDir.mkdirs()

    val manifest =
      try {
        BundleReader.readMetadata(bundleFile).manifest
      } catch (e: Exception) {
        onLog("catalog $system: could not read bundle metadata (${e.message})")
        return null
      }
    if (manifest.backend != "desktop") {
      onLog(
        "catalog $system: bundle backend '${manifest.backend}' is not 'desktop' — live daemon " +
          "from bundle is desktop-only for now"
      )
      return null
    }

    val zipBytes =
      try {
        BundleReader.extractZipBytes(bundleFile, fileSystem)
      } catch (e: Exception) {
        onLog("catalog $system: could not read bundle zip (${e.message})")
        return null
      }

    val classesDir = File(destDir, "classes").apply { mkdirs() }
    val libsDir = File(destDir, "libs").apply { mkdirs() }
    val previewsJson = File(destDir, "previews.json")
    // A fully IR-backed bundle (schema v5+) legitimately carries no classes/app.jar — mirrors
    // BundleDaemonCommand's gate. Serve's live path doesn't replay IR (that's the Android/tile
    // story), but a mixed bundle with at least one class-backed preview must still carry its jar.
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
      onLog("catalog $system: bundle extraction failed (${e.message})")
      return null
    }

    val libJars = BundleReader.extractEmbeddedLibs(zipBytes, libsDir, fileSystem)
    val mavenCoords = manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
    val resolvedJars =
      CoordinateResolver(
          warn = { onLog("catalog $system: $it") },
          networkEnabled = if (offline) false else CoordinateResolver.defaultNetworkEnabled(),
        )
        .resolveAll(mavenCoords)
        .mapNotNull { it.file }
    // The rehydrated external-resource dirs go right after the bundle's own classes so a lifted
    // font resolves at the same `/fonts/…` classpath path it did when carried inline.
    val userClassPath =
      (listOf(classesDir) + extraClasspathDirs.filter { it.isDirectory } + libJars + resolvedJars)
        .joinToString(File.pathSeparator) { it.absolutePath }

    val daemonJars = locateBundleSidecarJars("lib-daemon-desktop")
    if (daemonJars.isEmpty()) {
      onLog(
        "catalog $system: no daemon jars found (looked in " +
          "${bundleSidecarSearchDescription("lib-daemon-desktop")}) — is this a " +
          "`:cli:installDist` build?"
      )
      return null
    }
    val rendererJars = locateBundleSidecarJars("lib-renderer")
    if (rendererJars.isEmpty()) {
      onLog(
        "catalog $system: no renderer jars found (looked in " +
          "${bundleSidecarSearchDescription("lib-renderer")})"
      )
      return null
    }
    val daemonClasspath = (daemonJars + rendererJars).map { it.absolutePath }

    val descriptor =
      DaemonLaunchDescriptor(
        schemaVersion = DAEMON_LAUNCH_SCHEMA_VERSION,
        modulePath = ":catalog",
        variant = "desktop",
        enabled = true,
        mainClass = DESKTOP_DAEMON_MAIN_CLASS,
        javaLauncher = null,
        classpath = daemonClasspath,
        jvmArgs = listOf("--enable-native-access=ALL-UNNAMED"),
        systemProperties =
          mapOf(
            "composeai.daemon.userClassDirs" to userClassPath,
            "composeai.daemon.previewsJsonPath" to previewsJson.absolutePath,
          ),
        workingDirectory = destDir.absolutePath,
        manifestPath = previewsJson.absolutePath,
      )
    val descriptorFile = File(destDir, "daemon-launch.json")
    try {
      fileSystem.write(descriptorFile.path.toPath()) {
        writeUtf8(json.encodeToString(DaemonLaunchDescriptor.serializer(), descriptor))
      }
    } catch (e: Exception) {
      onLog("catalog $system: could not write daemon-launch.json (${e.message})")
      return null
    }

    val previews = readPreviews(previewsJson, fileSystem)
    if (previews.isEmpty()) {
      onLog("catalog $system: bundle previews.json carried no previews")
      return null
    }

    return ServeSessionState(
      descriptor = descriptorFile,
      workspaceRoot = destDir,
      workspaceName = destDir.name.ifBlank { system },
      previews = previews,
      label = system,
    )
  }

  /** Read the bundle's extracted `previews.json` into the [ServePreview] shape serve expects. */
  private fun readPreviews(previewsJson: File, fileSystem: FileSystem): List<ServePreview> {
    val text =
      try {
        fileSystem.read(previewsJson.path.toPath()) { readUtf8() }
      } catch (_: Exception) {
        return emptyList()
      }
    val manifest =
      runCatching { previewsManifestJson.decodeFromString(PreviewManifest.serializer(), text) }
        .getOrNull() ?: return emptyList()
    return manifest.previews.map {
      ServePreview(id = it.id, label = it.functionName.ifBlank { it.id })
    }
  }

  private val json = Json { encodeDefaults = true }
  private val previewsManifestJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /** `ee.schimke.composeai.daemon.DaemonMain` — the desktop daemon entrypoint a bundle spawns. */
  private const val DESKTOP_DAEMON_MAIN_CLASS = "ee.schimke.composeai.daemon.DaemonMain"

  /** Descriptor schema version — mirrors `SubprocessRenderSessions.openBundleDaemon`. */
  private const val DAEMON_LAUNCH_SCHEMA_VERSION = 2
}
