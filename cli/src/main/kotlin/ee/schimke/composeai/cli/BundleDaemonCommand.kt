package ee.schimke.composeai.cli

import ee.schimke.composeai.io.composeAiCacheDir
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess
import okio.Path.Companion.toPath
import okio.source

/**
 * `compose-preview bundle daemon <bundle.png>` — spawn the preview daemon JVM bound to a packed
 * preview bundle's classpath. The backend follows the bundle's `backend`: a desktop bundle launches
 * the CMP/Skiko daemon (`lib-daemon-desktop` + `lib-renderer`); an android bundle launches the
 * Robolectric daemon (`lib-daemon-android` + `android.jar`, with the JDK-17 `--add-opens` and
 * `robolectric.*` mode sysprops from [AndroidBundleLaunch] — the daemon manages its own SDK/
 * application config, so unlike `bundle render` it carries no `robolectric.properties`). Both speak
 * the same JSON-RPC over stdio via the same `DaemonMain` entry point. Inherits stdio so the parent
 * process (the VS Code extension's bundle viewer panel) can speak the daemon's protocol directly,
 * the same way the Gradle plugin's `composePreviewDaemonStart` works for in-workspace modules.
 *
 * # Layout
 *
 * The packed bundle is a PNG+ZIP polyglot whose zip portion contains `classes/app.jar` (consumer
 * module + inlined project deps), optionally embedded third-party jars under `libs/`, plus
 * `previews.json` (discovery output). We extract them to a temp working directory, then launch a
 * Java subprocess whose classpath joins `$APP_HOME/lib-daemon-desktop/` (daemon + data-extension
 * connectors) with `$APP_HOME/lib-renderer/` (Compose Multiplatform + Skiko). The consumer's
 * bytecode — the extracted app classes plus any embedded `libs/` jars — is exposed to the daemon
 * via `-Dcomposeai.daemon.userClassDirs=<paths>`, and the discovery manifest via
 * `-Dcomposeai.daemon.previewsJsonPath=<extracted-previews-json>` — the same sysprops the Gradle
 * daemon launch path uses.
 *
 * The subprocess inherits stdin/stdout/stderr so the parent owns the JSON-RPC channel without
 * additional plumbing. We don't wait on it from this command — `daemon` is `exec`-style: we print
 * one ready line on stderr and replace this process via `ProcessBuilder.inheritIO`.
 *
 * # Dependency resolution
 *
 * The bundle's third-party deps are joined onto the daemon's `userClassDirs` from two sources, the
 * same way `bundle render` builds its classpath:
 * - Embedded-mode bundles (schema-v3 `resolution = "embedded"`) carry their reachable deps under
 *   `libs/`; those are extracted and added directly.
 * - Default coordinate bundles record `ClasspathEntry.Maven` entries; [CoordinateResolver] resolves
 *   each from the machine's local Maven / Gradle caches and, on a miss, downloads from Maven
 *   Central / Google Maven, hash-checking against the v4 `sha256`. A miss or mismatch warns but
 *   never fails — the renderer's bundled Compose still covers the common API surface, and an
 *   almost-compatible jar renders.
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
    // A fully IR-backed bundle (schema v5+) drops its consumer classes — its previews replay from
    // `ir/` (extracted below), so `classes/app.jar` is legitimately absent. A mixed bundle that
    // still has a class-backed preview must carry it, so gate on whether any preview id is NOT
    // covered by an intermediate representation rather than merely "has some IR".
    val irPreviewIds = manifest.intermediateRepresentations.mapTo(mutableSetOf()) { it.previewId }
    extractBundleClassesAndManifest(
      zipBytes,
      classesDir,
      previewsJson,
      file,
      requireAppJar = manifest.previewIds.any { it !in irPreviewIds },
      fileSystem = fileSystem,
    )
    // Consumer classpath for the daemon's `userClassDirs` holder (dirs-before-jars ordered, see
    // UserClassLoaderHolder) — the extracted app classes plus the bundle's third-party deps. NOT
    // the
    // daemon/renderer `-cp`. Deps come from two sources: embedded `libs/` jars (embedded-mode
    // bundles) and `maven` coordinates resolved from local repos (the default detached bundles). A
    // resolver miss or hash mismatch warns but never fails — same contract as `bundle render`.
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

    // v5 IR replay: when the bundle carries intermediate representations, extract the `ir/` bytes
    // and the bundle.json so the daemon (Piece B) can replay those previews through the protolayout
    // / Remote Compose runtime instead of reflecting their (dropped) consumer classes. Skipped
    // entirely for a classic all-classes bundle.
    val hasIr = manifest.intermediateRepresentations.isNotEmpty()
    val irDir = if (hasIr) workDir.resolve("ir").apply { mkdirs() } else null
    val bundleManifestFile = if (hasIr) workDir.resolve("bundle.json") else null
    if (hasIr) {
      extractIrArtifacts(zipBytes, irDir!!, bundleManifestFile!!, file)
    }

    // Android resource carriage (ungated by IR): any `backend == "android"` bundle carries the
    // app's
    // merged resource APK + manifest (+ generated R classes) under `android/`, because a classic
    // `@Preview` that calls `stringResource(R.string.…)` needs the `0x7f` app table just as much as
    // a
    // Wear tile does. A detached daemon has neither the merged table (no AGP build) nor those R
    // classes, so [AndroidBundleResources] extracts them and synthesizes the Robolectric
    // `com/android/tools/test_config.properties` the daemon's RobolectricTestRunner auto-reads from
    // the classpath — exactly how the in-Gradle render path gets resources — and the config dir + R
    // jar ride the `-cp` seam below. Empty for a bundle with no `android/` payload (packed before
    // this
    // carriage / no binary resources): renders framework-resources-only, as before.
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

    // Branch on the bundle's backend, exactly as `bundle render` does: a desktop bundle launches
    // the CMP/Skiko daemon (lib-daemon-desktop + lib-renderer); an android bundle launches the
    // Robolectric daemon (lib-daemon-android + android.jar), reusing the Phase 1
    // [AndroidBundleLaunch]
    // jvmArgs + robolectric.* sysprops. Both speak the same JSON-RPC over stdio via the same
    // `DaemonMain` entry point, so only the classpath / JVM args / sysprops differ.
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
      // PROTOCOL.md § 3a — the daemon advertises capabilities lazily; the client
      // (BundleViewerPanel's DaemonClient) does the `initialize` round-trip on stdin.
      add("-D${USER_CLASS_DIRS_PROP}=$userClassPath")
      add("-D${PREVIEWS_JSON_PATH_PROP}=${previewsJson.absolutePath}")
      // IR replay inputs (Piece B); present only for a bundle that carries IR.
      irDir?.let { add("-D${IR_DIR_PROP}=${it.absolutePath}") }
      bundleManifestFile?.let { add("-D${BUNDLE_MANIFEST_PATH_PROP}=${it.absolutePath}") }
      // Tag the temp dir on the daemon so logs / debug dumps make it discoverable.
      add("-Dcomposeai.daemon.bundleSource=${file.absolutePath}")
      // Detached-bundle viewer: degrade a missing app-resource lookup to an obvious placeholder
      // rather than throwing on a stale/incompletely-packed bundle. Off by default in the daemon;
      // the pack-time semantics daemon never sets it, so published stickers still fail loudly.
      add("-Dcomposeai.render.placeholderMissingResources=true")
      for (prop in launch.sysProps) add(prop)
      add("-cp")
      add(
        composeDaemonClasspath(
          // The Android resource carriage (test-config dir + r-classes jar) must reach the
          // daemon `-cp` for *every* android bundle, not just IR-carrying ones — a classic
          // `stringResource` preview needs the resource table regardless of IR. Fold it into
          // the base classpath so it survives the `hasIr` gate that only guards the carried
          // lib/coordinate jars.
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
    val daemonJars = locateBundleSidecarJars("lib-daemon-desktop")
    if (daemonJars.isEmpty()) {
      System.err.println(
        "bundle daemon: no daemon jars found. Looked in `${bundleSidecarSearchDescription("lib-daemon-desktop")}`; " +
          "either build the CLI via `./gradlew :cli:installDist` or set " +
          "`-Dcomposeai.cli.libDaemonDesktopDir=<install-root/lib-daemon-desktop>`."
      )
      exitProcess(1)
    }
    val rendererJars = locateBundleSidecarJars("lib-renderer")
    if (rendererJars.isEmpty()) {
      System.err.println(
        "bundle daemon: no renderer jars found. Looked in `${bundleSidecarSearchDescription("lib-renderer")}`; " +
          "either build the CLI via `./gradlew :cli:installDist` or set " +
          "`-Dcomposeai.cli.libRendererDir=<install-root/lib-renderer>`."
      )
      exitProcess(1)
    }
    return DaemonLaunch(
      classpath = (daemonJars + rendererJars).joinToString(File.pathSeparator) { it.absolutePath },
      // -Dapple.awt.UIElement=true runs the desktop daemon JVM as a macOS background agent
      // (no Dock icon / focus steal). Launch -D so it lands before AWT inits; macOS-only.
      jvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dapple.awt.UIElement=true"),
      sysProps = desktopFontSysProps(),
    )
  }

  /**
   * Font-related `-D` props the desktop daemon needs, mirroring what the Android launch forwards
   * via [AndroidBundleLaunch.robolectricSystemProperties]. The `compose/figma-svg` export embeds
   * fonts by default, so the daemon fetches generic faces (e.g. Roboto) from Google Fonts; without
   * `composeai.fonts.cacheDir` those downloads would be uncached (re-fetched every launch), and
   * without forwarding `composeai.svg.embedFonts` a `-Dcomposeai.svg.embedFonts=false` opt-out set
   * on this CLI process would never reach the child daemon. Point at the SAME shared cache the
   * Android path and the Gradle plugin use, and forward the parent's embed/offline choices when
   * set.
   */
  private fun desktopFontSysProps(): List<String> = buildList {
    add("-Dcomposeai.fonts.cacheDir=${composeAiCacheDir("fonts").absolutePath}")
    System.getProperty("composeai.fonts.offline")?.let { add("-Dcomposeai.fonts.offline=$it") }
    System.getProperty("composeai.svg.embedFonts")?.let { add("-Dcomposeai.svg.embedFonts=$it") }
  }

  /**
   * Assemble the Robolectric daemon launch for an `backend="android"` bundle. The Android daemon
   * ([ee.schimke.composeai.daemon.DaemonMain] in `:daemon:android`) bundles its own Robolectric
   * render engine and manages its own Robolectric config: its `RobolectricHost.SandboxRunner` pins
   * `@Config(sdk = 35)` and supplies the stub `Application` via `buildGlobalConfig`, and it does
   * NOT read a renderer-package `robolectric.properties` (that file is scoped to
   * `ee.schimke.composeai.renderer`; the daemon's runner is in `ee.schimke.composeai.daemon`). So
   * we pass exactly what the Gradle Android daemon launch passes (AndroidPreviewSupport): the
   * JDK-17 `--add-opens` args plus the `robolectric.*` mode sysprops — nothing that would falsely
   * imply the SDK is configurable here. The `-Dcomposeai.bundle.androidSdk` override and
   * synthesized `robolectric.properties` apply to the one-shot `bundle render` path only, not the
   * daemon.
   *
   * The `:daemon:android` runtime is ~150-200 MB (Robolectric + the full Compose-Android stack), so
   * it is NOT bundled in the main CLI tarball — that ballooned it to ~382 MB. It ships separately
   * as `compose-preview-android-daemon-<version>.zip` (built by `packageAndroidDaemon`), which
   * `compose-preview bundle daemon` fetches on demand and caches the first time it renders an
   * `backend="android"` bundle. Until that auto-download lands, point at an unpacked archive via
   * `-Dcomposeai.cli.libDaemonAndroidDir=<dir>/lib-daemon-android` (the CI e2e does this). E2E
   * coverage lives in the SDK-gated `AndroidBundleDaemonRenderFunctionalTest`.
   */
  private fun androidDaemonLaunch(): DaemonLaunch {
    val daemonJars = locateBundleSidecarJars("lib-daemon-android")
    if (daemonJars.isEmpty()) {
      System.err.println(
        "bundle daemon: backend=android needs the Android daemon sidecar (`lib-daemon-android/`), " +
          "which ships separately as `compose-preview-android-daemon-<version>.zip` (it's too large " +
          "to bundle in the CLI tarball). Download + unpack it and point at it via " +
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
   * Find the nearest `local.properties` (carrying `sdk.dir`) by walking up from the working
   * directory — `bundle daemon` runs outside Gradle but is commonly launched from inside an Android
   * project whose SDK is configured only there. Mirrors `BundleRenderer.findLocalProperties`.
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

  /**
   * Extract the v5 IR artefacts from [zipBytes]: every `ir/<leaf>` entry into [irDir] (flattened to
   * its basename, since `BundleIr.path` is `ir/<previewId>.<ext>` and the daemon resolves by leaf),
   * plus `bundle.json` into [manifestFile] so the daemon can read `intermediateRepresentations`.
   * Each `ir/` destination is verified to live inside [irDir] — defeats Zip Slip on a hostile
   * bundle, same guard as [extractEmbeddedLibs] / [expandBundleJarBytesSafely].
   */
  private fun extractIrArtifacts(
    zipBytes: ByteArray,
    irDir: File,
    manifestFile: File,
    bundleFile: File,
  ) {
    val canonicalIr = irDir.canonicalFile
    var sawManifest = false
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        val name = entry.name
        when {
          name == "bundle.json" -> {
            fileSystem.write(manifestFile.path.toPath()) { write(zin.readBytes()) }
            sawManifest = true
          }
          !entry.isDirectory && name.startsWith("ir/") -> {
            val dest = File(irDir, File(name).name).canonicalFile
            if (dest.path.startsWith(canonicalIr.path + File.separator)) {
              fileSystem.write(dest.path.toPath()) { writeAll(zin.source()) }
            }
          }
        }
        zin.closeEntry()
      }
    }
    require(sawManifest) {
      "bundle daemon: bundle.json missing in ${bundleFile.path} — cannot resolve IR descriptors"
    }
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
     * Build the daemon launch `-cp`.
     *
     * For an **IR-carrying** bundle, the parent-loaded replay host (`:renderer-android`'s
     * `TileIrReplayComposable` or the connector's `RemoteComposeIrReplay`, both shipped on the
     * sidecar `-cp`) links the carried renderer/player libs (`androidx.wear.tiles.renderer.*`,
     * `androidx.compose.remote.player.*`) directly. Those libs live only in
     * `composeai.daemon.userClassDirs` — the child ([UserClassLoaderHolder]) loader — which the
     * parent never consults, so the parent-loaded host would `NoClassDefFoundError` at replay. We
     * therefore also append the carried deps onto the parent `-cp` here.
     *
     * **Appended, not prepended:** the renderer's own bundled Compose on the sidecar `-cp` must
     * stay authoritative (a `URLClassLoader` resolves first-match), so only classes *absent* from
     * the parent — the player / tiles-renderer APIs — become resolvable; a stale Compose carried in
     * the bundle can't shadow the renderer's. Classic (non-IR) bundles, which never load a replay
     * host, are left untouched. See `IrReplayClassloaderTopologyTest` for the topology
     * characterisation.
     */
    internal fun composeDaemonClasspath(
      base: String,
      carriedDeps: List<File>,
      hasIr: Boolean,
    ): String =
      if (!hasIr || carriedDeps.isEmpty()) base
      else (listOf(base) + carriedDeps.map { it.absolutePath }).joinToString(File.pathSeparator)

    // Same names the gradle daemon launch path uses — kept inline so this command doesn't
    // need an extra :daemon:core dependency just for the constants.
    private const val USER_CLASS_DIRS_PROP = "composeai.daemon.userClassDirs"
    private const val PREVIEWS_JSON_PATH_PROP = "composeai.daemon.previewsJsonPath"
    // v5 IR replay (consumed by the Android daemon — Piece B). `irDir` holds the extracted
    // `ir/<id>.<ext>` bytes; `bundleManifestPath` points at the bundle.json whose
    // `intermediateRepresentations` tell the daemon which previews replay from IR and in what
    // format. Passed only when the bundle actually carries IR; harmless to an older daemon that
    // doesn't read them.
    private const val IR_DIR_PROP = "composeai.daemon.irDir"
    private const val BUNDLE_MANIFEST_PATH_PROP = "composeai.daemon.bundleManifestPath"
  }
}
