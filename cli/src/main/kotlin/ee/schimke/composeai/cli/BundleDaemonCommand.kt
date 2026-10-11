package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.AndroidBundleResources
import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.bundleSidecarSearchDescription
import ee.schimke.composeai.bundle.coordinates.CoordinateResolver
import ee.schimke.composeai.bundle.extractBundleClassesAndManifest
import ee.schimke.composeai.bundle.extractBundleIrArtifacts
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import kotlin.system.exitProcess

/**
 * `compose-preview bundle daemon <bundle.png>` — spawn the preview daemon JVM bound to a packed
 * bundle's classpath, with inherited stdio so the parent (the VS Code bundle viewer) speaks its
 * JSON-RPC directly, like `composePreviewDaemonStart` for in-workspace modules.
 *
 * The bundle's `backend` picks the daemon: desktop → CMP/Skiko (`lib-daemon-desktop` +
 * `lib-renderer`); android → Robolectric (`lib-daemon-android` + `android.jar` with
 * [AndroidBundleLaunch]'s `--add-opens` and `robolectric.*` sysprops). Both use the same
 * `DaemonMain`.
 *
 * The bundle's app classes, embedded `libs/` and [CoordinateResolver]-resolved Maven coordinates
 * (misses only warn) are exposed via `-Dcomposeai.daemon.userClassDirs`, and the extracted manifest
 * via `-Dcomposeai.daemon.previewsJsonPath`, the same sysprops as the Gradle launch.
 */
class BundleDaemonCommand(args: List<String>) : Command(args) {

  override fun run() {
    val sub = args.firstOrNull { !it.startsWith("-") }
    if (sub == null || sub in setOf("help", "--help", "-h")) {
      printHelp()
      if (sub == null) exitProcess(64)
      return
    }
    val file =
      try {
        BundleSource.resolveToFile(sub)
      } catch (e: IllegalArgumentException) {
        System.err.println("bundle daemon: ${e.message}")
        exitProcess(1)
      }
    val verbose = "--verbose" in args || "-v" in args
    val workDir = createTempWorkDir()
    val classesDir = workDir.resolve("classes").apply { mkdirs() }
    val libsDir = workDir.resolve("libs").apply { mkdirs() }
    val previewsJson = workDir.resolve("previews.json")
    val zipBytes = BundleReader.extractZipBytes(file)
    val manifest = BundleReader.readMetadata(file).manifest
    // Fully IR-backed bundles (schema v5+) may omit `classes/app.jar`; require it when any preview
    // is class-backed.
    val irPreviewIds = manifest.intermediateRepresentations.mapTo(mutableSetOf()) { it.previewId }
    extractBundleClassesAndManifest(
      zipBytes,
      classesDir,
      previewsJson,
      file,
      requireAppJar = manifest.previewIds.any { it !in irPreviewIds },
      fileSystem = fileSystem,
    )
    // The daemon's `userClassDirs` (child loader), not its `-cp`: app classes plus embedded and
    // resolved deps. Resolver misses only warn, as in `bundle render`.
    val libJars = BundleReader.extractEmbeddedLibs(zipBytes, libsDir)
    val mavenCoords = manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
    val resolvedJars =
      CoordinateResolver(warn = { System.err.println("[bundle-daemon] $it") })
        .resolveAll(mavenCoords)
        .mapNotNull { it.file }
    val userClassPath =
      (listOf(classesDir) + libJars + resolvedJars).joinToString(File.pathSeparator) {
        it.absolutePath
      }

    // v5 IR replay: extract `ir/` and bundle.json so the Android daemon can replay those previews.
    val hasIr = manifest.intermediateRepresentations.isNotEmpty()
    val irDir = if (hasIr) workDir.resolve("ir").apply { mkdirs() } else null
    val bundleManifestFile = if (hasIr) workDir.resolve("bundle.json") else null
    if (hasIr) {
      extractBundleIrArtifacts(zipBytes, irDir!!, bundleManifestFile!!, file, fileSystem)
    }

    // Android bundles carry the app's resource APK, manifest and R classes under `android/`;
    // extract them and synthesize Robolectric's `test_config.properties` so
    // `stringResource(R.string.…)` resolves (as in the Gradle render path). Empty for bundles
    // without that payload.
    val androidReplayClasspath = mutableListOf<File>()
    if (manifest.backend == "android") {
      androidReplayClasspath +=
        AndroidBundleResources.daemonClasspath(
          zipBytes,
          workDir,
          manifest.androidResources?.applicationPackage,
        )
      System.err.println(
        "[bundle-daemon] android carriage: cpEntries=${androidReplayClasspath.size}"
      )
    }

    // Branch on backend as `bundle render` does; only classpath, JVM args and sysprops differ.
    val launch =
      when (manifest.backend) {
        "desktop" -> desktopDaemonLaunch()
        "android" -> androidDaemonLaunch()
        else -> {
          System.err.println(
            "bundle daemon: backend '${manifest.backend}' not supported (expected 'desktop' or 'android')."
          )
          exitProcess(1)
        }
      }

    val javaBin = locateJava()
    val command = buildList {
      add(javaBin)
      addAll(launch.jvmArgs)
      // PROTOCOL.md § 3a: the client does the `initialize` round-trip on stdin.
      add("-D${USER_CLASS_DIRS_PROP}=$userClassPath")
      add("-D${PREVIEWS_JSON_PATH_PROP}=${previewsJson.absolutePath}")
      // IR replay inputs (Piece B); present only for a bundle that carries IR.
      irDir?.let { add("-D${IR_DIR_PROP}=${it.absolutePath}") }
      bundleManifestFile?.let { add("-D${BUNDLE_MANIFEST_PATH_PROP}=${it.absolutePath}") }
      // Tag the temp dir on the daemon so logs / debug dumps make it discoverable.
      add("-Dcomposeai.daemon.bundleSource=${file.absolutePath}")
      // Viewer-only: a missing app resource renders a placeholder instead of throwing. The
      // pack-time daemon leaves this off so published stickers fail loudly.
      add("-Dcomposeai.render.placeholderMissingResources=true")
      for (prop in launch.sysProps) add(prop)
      add("-cp")
      add(
        composeDaemonClasspath(
          // The resource carriage must reach every android bundle's `-cp`, not only IR ones, so it
          // goes into the base classpath outside the `hasIr` gate.
          base =
            (listOf(launch.classpath) + androidReplayClasspath.map { it.absolutePath })
              .joinToString(File.pathSeparator),
          carriedDeps = libJars + resolvedJars,
          hasIr = hasIr,
        )
      )
      add("ee.schimke.composeai.daemon.DaemonMain")
    }

    if (verbose) {
      System.err.println("[bundle-daemon] working dir: ${workDir.absolutePath}")
      System.err.println("[bundle-daemon] backend: ${manifest.backend}")
      System.err.println("[bundle-daemon] classes dir: ${classesDir.absolutePath}")
      System.err.println("[bundle-daemon] embedded lib jars: ${libJars.size}")
      System.err.println(
        "[bundle-daemon] resolved coordinate jars: ${resolvedJars.size} / ${mavenCoords.size}"
      )
      System.err.println("[bundle-daemon] previews.json: ${previewsJson.absolutePath}")
      if (hasIr) {
        System.err.println(
          "[bundle-daemon] IR previews: ${manifest.intermediateRepresentations.size} → ${irDir?.absolutePath}"
        )
      }
      if (androidReplayClasspath.isNotEmpty()) {
        System.err.println(
          "[bundle-daemon] android resource carriage: ${androidReplayClasspath.joinToString(", ") { it.name }}"
        )
      }
      System.err.println("[bundle-daemon] launching: ${command.joinToString(" ")}")
    }

    val pb = ProcessBuilder(command).inheritIO()
    val proc = pb.start()
    // Best-effort cleanup: if the parent goes away, kill the daemon and drop the temp dir.
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          try {
            proc.destroy()
          } catch (_: Throwable) {
            /* ignore */
          }
          try {
            workDir.deleteRecursively()
          } catch (_: Throwable) {
            /* ignore */
          }
        }
      )
    val exitCode = proc.waitFor()
    try {
      workDir.deleteRecursively()
    } catch (_: Throwable) {
      /* ignore */
    }
    exitProcess(exitCode)
  }

  /** The backend-specific half of the daemon launch: classpath, JVM args, and extra `-D` props. */
  private data class DaemonLaunch(
    val classpath: String,
    val jvmArgs: List<String>,
    val sysProps: List<String>,
  )

  private fun desktopDaemonLaunch(): DaemonLaunch {
    // Fetched from the compose-preview-daemon release on first use; an explicit `-D…Dir` or an
    // installed directory wins.
    DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.DESKTOP)
    val daemonJars = locateBundleSidecarJars("lib-daemon-desktop")
    if (daemonJars.isEmpty()) {
      System.err.println(
        "bundle daemon: no daemon jars found. Looked in `${bundleSidecarSearchDescription("lib-daemon-desktop")}`; " +
          "it is fetched from the compose-preview-daemon release on first use, or set " +
          "`-Dcomposeai.cli.libDaemonDesktopDir=<dir>/lib-daemon-desktop`."
      )
      exitProcess(1)
    }
    val rendererJars = locateBundleSidecarJars("lib-renderer")
    if (rendererJars.isEmpty()) {
      System.err.println(
        "bundle daemon: no renderer jars found. Looked in `${bundleSidecarSearchDescription("lib-renderer")}`; " +
          "it is fetched from the compose-preview-daemon release on first use, or set " +
          "`-Dcomposeai.cli.libRendererDir=<dir>/lib-renderer`."
      )
      exitProcess(1)
    }
    val skikoNative =
      try {
        SkikoNativeProvision.prepare(daemonJars + rendererJars)
      } catch (e: IllegalStateException) {
        System.err.println("bundle daemon: ${e.message}")
        exitProcess(1)
      }
    return DaemonLaunch(
      classpath =
        (listOf(skikoNative) + daemonJars + rendererJars).distinct().joinToString(
          File.pathSeparator
        ) {
          it.absolutePath
        },
      // `apple.awt.UIElement`: no Dock icon or focus steal on macOS; must be set before AWT inits.
      jvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dapple.awt.UIElement=true"),
      sysProps = desktopFontSysProps(),
    )
  }

  /**
   * Font `-D`s for the desktop daemon: share the Google Fonts cache with the Android path and
   * Gradle plugin, and forward `composeai.svg.embedFonts` / offline choices so opt-outs reach the
   * child.
   */
  private fun desktopFontSysProps(): List<String> = buildList {
    add("-Dcomposeai.fonts.cacheDir=${composeAiCacheDir("fonts").absolutePath}")
    System.getProperty("composeai.fonts.offline")?.let { add("-Dcomposeai.fonts.offline=$it") }
    System.getProperty("composeai.svg.embedFonts")?.let { add("-Dcomposeai.svg.embedFonts=$it") }
    // Read in the child daemon, so it must be forwarded.
    System.getProperty("composeai.svg.background")?.let { add("-Dcomposeai.svg.background=$it") }
  }

  /**
   * The Robolectric daemon launch for a `backend="android"` bundle. The daemon manages its own
   * Robolectric config (`@Config(sdk = 35)`, stub `Application`), so pass exactly what the Gradle
   * launch does: JDK-17 `--add-opens` plus `robolectric.*` mode sysprops. The SDK override and
   * `robolectric.properties` only apply to `bundle render`.
   *
   * The ~150-200 MB runtime isn't in the CLI tarball; [DaemonSidecarProvision] fetches it on first
   * use, or `-Dcomposeai.cli.libDaemonAndroidDir=<dir>/lib-daemon-android` points at a copy.
   */
  private fun androidDaemonLaunch(): DaemonLaunch {
    DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.ANDROID)
    val daemonJars = locateBundleSidecarJars("lib-daemon-android")
    if (daemonJars.isEmpty()) {
      System.err.println(
        "bundle daemon: backend=android needs the Android daemon sidecar (`lib-daemon-android/`), " +
          "fetched from the compose-preview-daemon release on first use (it's too large to bundle " +
          "in the CLI tarball). Fetch `compose-preview-android-daemon-<version>.zip` while online, " +
          "or point at an unpacked copy via " +
          "`-Dcomposeai.cli.libDaemonAndroidDir=<dir>/lib-daemon-android`. Looked in " +
          "`${bundleSidecarSearchDescription("lib-daemon-android")}`."
      )
      exitProcess(1)
    }
    val androidJar =
      AndroidBundleLaunch.resolveAndroidJar(localPropertiesFile = findLocalProperties())
        ?: run {
          System.err.println(
            "bundle daemon: backend=android needs android.jar — set ANDROID_HOME / " +
              "ANDROID_SDK_ROOT, or run from a project whose local.properties has sdk.dir."
          )
          exitProcess(1)
        }
    val launch = AndroidBundleLaunch()
    return DaemonLaunch(
      classpath =
        (daemonJars + listOf(androidJar)).joinToString(File.pathSeparator) { it.absolutePath },
      jvmArgs = launch.jvmArgs(),
      sysProps = launch.robolectricSystemProperties().map { (k, v) -> "-D$k=$v" },
    )
  }

  /**
   * The nearest `local.properties` (with `sdk.dir`) walking up from the working directory. Mirrors
   * `BundleRenderer.findLocalProperties`.
   */
  private fun findLocalProperties(): File? {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    repeat(8) {
      val d = dir ?: return null
      File(d, "local.properties")
        .takeIf { it.isFile }
        ?.let {
          return it
        }
      dir = d.parentFile
    }
    return null
  }

  private fun printHelp() {
    println(
      """
      compose-preview bundle daemon — start the preview daemon against a packed bundle

      Usage:
        compose-preview bundle daemon <bundle.png | URL> [-v]

      <bundle> is a local path or an http(s)/file URL (downloaded first).

      The daemon backend follows the bundle's `backend`: a desktop bundle launches the CMP/Skiko
      daemon; an android bundle launches the Robolectric daemon (needs a local Android SDK for
      android.jar — via ANDROID_HOME/ANDROID_SDK_ROOT or local.properties `sdk.dir`).

      Inherits stdio: the spawned daemon JVM speaks JSON-RPC over stdin/stdout and writes log
      lines to stderr, the same protocol `composePreviewDaemonStart` uses in a Gradle module.
      Intended for tools that drive the daemon directly (the VS Code extension's bundle
      viewer panel is the v1 consumer).

      Flags:
        -v, --verbose   Print the resolved working dir + classpath sizes before launch.
      """
        .trimIndent()
    )
  }

  private fun createTempWorkDir(): File {
    val base = System.getProperty("java.io.tmpdir") ?: "/tmp"
    return File(base, "compose-preview-bundle-daemon-${System.nanoTime()}").also { it.mkdirs() }
  }

  private fun locateJava(): String {
    System.getProperty("composeai.cli.javaBinary")?.let {
      return it
    }
    val javaHome = System.getProperty("java.home") ?: error("java.home not set")
    val bin = File(javaHome, "bin/java")
    if (bin.isFile) return bin.absolutePath
    val winBin = File(javaHome, "bin/java.exe")
    if (winBin.isFile) return winBin.absolutePath
    return bin.absolutePath // best-effort; the spawn will surface the failure
  }

  companion object {
    /**
     * Build the daemon `-cp`.
     *
     * For an IR bundle, the parent-loaded replay host links the carried player/tiles-renderer libs,
     * which otherwise exist only in the child loader (`NoClassDefFoundError`), so they are appended
     * to the parent `-cp`. Appended, not prepended, so the sidecar's Compose stays authoritative.
     * Non-IR bundles are untouched. See `IrReplayClassloaderTopologyTest`.
     */
    internal fun composeDaemonClasspath(
      base: String,
      carriedDeps: List<File>,
      hasIr: Boolean,
    ): String =
      if (!hasIr || carriedDeps.isEmpty()) base
      else (listOf(base) + carriedDeps.map { it.absolutePath }).joinToString(File.pathSeparator)

    // Same names as the Gradle daemon launch, inlined to avoid a `:daemon:core` dependency.
    private const val USER_CLASS_DIRS_PROP = "composeai.daemon.userClassDirs"
    private const val PREVIEWS_JSON_PATH_PROP = "composeai.daemon.previewsJsonPath"
    // v5 IR replay (Android daemon): `irDir` holds extracted `ir/<id>.<ext>`, `bundleManifestPath`
    // the bundle.json naming which previews replay. Only passed when the bundle carries IR.
    private const val IR_DIR_PROP = "composeai.daemon.irDir"
    private const val BUNDLE_MANIFEST_PATH_PROP = "composeai.daemon.bundleManifestPath"
  }
}
