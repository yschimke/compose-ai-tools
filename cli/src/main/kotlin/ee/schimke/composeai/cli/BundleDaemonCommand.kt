package ee.schimke.composeai.cli

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

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
    expandAppJarAndManifest(zipBytes, classesDir, previewsJson, file)
    // Consumer classpath for the daemon's `userClassDirs` holder (dirs-before-jars ordered, see
    // UserClassLoaderHolder) — the extracted app classes plus the bundle's third-party deps. NOT
    // the
    // daemon/renderer `-cp`. Deps come from two sources: embedded `libs/` jars (embedded-mode
    // bundles) and `maven` coordinates resolved from local repos (the default detached bundles). A
    // resolver miss or hash mismatch warns but never fails — same contract as `bundle render`.
    val libJars = BundleReader.extractEmbeddedLibs(zipBytes, libsDir)
    val manifest = BundleReader.readMetadata(file).manifest
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
      for (prop in launch.sysProps) add(prop)
      add("-cp")
      add(launch.classpath)
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
    val daemonJars = locateSidecarJars("lib-daemon-desktop")
    if (daemonJars.isEmpty()) {
      System.err.println(
        "bundle daemon: no daemon jars found. Looked in `${sidecarSearchDescription("lib-daemon-desktop")}`; " +
          "either build the CLI via `./gradlew :cli:installDist` or set " +
          "`-Dcomposeai.cli.libDaemonDesktopDir=<install-root/lib-daemon-desktop>`."
      )
      exitProcess(1)
    }
    val rendererJars = locateSidecarJars("lib-renderer")
    if (rendererJars.isEmpty()) {
      System.err.println(
        "bundle daemon: no renderer jars found. Looked in `${sidecarSearchDescription("lib-renderer")}`; " +
          "either build the CLI via `./gradlew :cli:installDist` or set " +
          "`-Dcomposeai.cli.libRendererDir=<install-root/lib-renderer>`."
      )
      exitProcess(1)
    }
    return DaemonLaunch(
      classpath = (daemonJars + rendererJars).joinToString(File.pathSeparator) { it.absolutePath },
      jvmArgs = listOf("--enable-native-access=ALL-UNNAMED"),
      sysProps = emptyList(),
    )
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
   * Still Phase 2 (only validatable in the SDK-gated Android CI chain): packaging `:daemon:android`
   * into the CLI distribution as `lib-daemon-android/` — until then this surfaces an actionable
   * diagnostic, and stays exercisable via `-Dcomposeai.cli.libDaemonAndroidDir=<dir>`.
   */
  private fun androidDaemonLaunch(): DaemonLaunch {
    val daemonJars = locateSidecarJars("lib-daemon-android")
    if (daemonJars.isEmpty()) {
      System.err.println(
        "bundle daemon: backend=android needs the Android daemon sidecar, which is not packaged in " +
          "this CLI build yet (Phase 2). Point at a built one via " +
          "`-Dcomposeai.cli.libDaemonAndroidDir=<dir>`. Looked in " +
          "`${sidecarSearchDescription("lib-daemon-android")}`."
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
   * Extract `classes/app.jar` into [classesDir] and `previews.json` into [previewsJson]. Throws if
   * either is missing — both are required for the daemon to come up against a usable preview index.
   * Embedded `libs/` jars are extracted separately via [BundleReader.extractEmbeddedLibs].
   */
  /**
   * Extract the v5 IR artefacts from [zipBytes]: every `ir/<leaf>` entry into [irDir] (flattened to
   * its basename, since `BundleIr.path` is `ir/<previewId>.<ext>` and the daemon resolves by leaf),
   * plus `bundle.json` into [manifestFile] so the daemon can read `intermediateRepresentations`.
   * Each `ir/` destination is verified to live inside [irDir] — defeats Zip Slip on a hostile
   * bundle, same guard as [extractEmbeddedLibs] / [expandJarBytesSafely].
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
            manifestFile.writeBytes(zin.readBytes())
            sawManifest = true
          }
          !entry.isDirectory && name.startsWith("ir/") -> {
            val dest = File(irDir, File(name).name).canonicalFile
            if (dest.path.startsWith(canonicalIr.path + File.separator)) {
              dest.outputStream().use { sink -> zin.copyTo(sink) }
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

  private fun expandAppJarAndManifest(
    zipBytes: ByteArray,
    classesDir: File,
    previewsJson: File,
    bundleFile: File,
  ) {
    var sawAppJar = false
    var sawPreviewsJson = false
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        when (entry.name) {
          "previews.json" -> {
            previewsJson.writeBytes(zin.readBytes())
            sawPreviewsJson = true
          }
          "classes/app.jar" -> {
            val appJarBytes = zin.readBytes()
            expandJarBytesSafely(appJarBytes, classesDir)
            sawAppJar = true
          }
        }
        zin.closeEntry()
      }
    }
    require(sawAppJar) {
      "bundle daemon: classes/app.jar missing in ${bundleFile.path} — not a packed bundle"
    }
    require(sawPreviewsJson) {
      "bundle daemon: previews.json missing in ${bundleFile.path} — not a packed bundle"
    }
  }

  /**
   * Unpack the bundled app jar, rejecting Zip Slip. Shared shape with `BundleRenderer`'s
   * `expandJarBytes` — duplicated here to avoid widening that class's surface for a single caller;
   * the helper is small enough that the duplication is cheaper than a refactor.
   */
  private fun expandJarBytesSafely(appJarBytes: ByteArray, targetDir: File) {
    val targetPath = targetDir.canonicalFile.toPath()
    ZipInputStream(ByteArrayInputStream(appJarBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        // Resolve + normalize the entry against the target and verify containment via
        // Path.startsWith — the form CodeQL's java/zipslip recognizes as sanitization (the prior
        // canonicalFile + String.startsWith guard was equally safe but flagged as a false
        // positive).
        val resolved = targetPath.resolve(entry.name).normalize()
        if (!resolved.startsWith(targetPath)) {
          throw SecurityException(
            "bundle daemon: app jar entry escapes target dir: ${entry.name} → $resolved"
          )
        }
        val candidate = resolved.toFile()
        if (entry.isDirectory) {
          candidate.mkdirs()
        } else {
          candidate.parentFile?.mkdirs()
          candidate.outputStream().use { sink -> zin.copyTo(sink) }
        }
        zin.closeEntry()
      }
    }
  }

  private fun createTempWorkDir(): File {
    val base = System.getProperty("java.io.tmpdir") ?: "/tmp"
    return File(base, "compose-preview-bundle-daemon-${System.nanoTime()}").also { it.mkdirs() }
  }

  /**
   * Locate a sidecar jar dir inside the CLI install. In order: explicit sysprop override
   * (`composeai.cli.libDaemonDesktopDir` / `composeai.cli.libRendererDir`), `$APP_HOME/<name>`,
   * `<jar-parent>/../<name>` (IDE / `JavaExec` runs).
   */
  private fun locateSidecarJars(sidecarName: String): List<File> {
    val sysprop =
      when (sidecarName) {
        "lib-daemon-desktop" -> "composeai.cli.libDaemonDesktopDir"
        "lib-daemon-android" -> "composeai.cli.libDaemonAndroidDir"
        "lib-renderer" -> "composeai.cli.libRendererDir"
        else -> "composeai.cli.${sidecarName.replace('-', '.')}Dir"
      }
    val override = System.getProperty(sysprop)
    val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
    val candidates =
      listOfNotNull(
          override?.let { File(it) },
          appHome?.let { File(it, sidecarName) },
          inferSidecarFromClasspath(sidecarName),
        )
        .distinct()
    val firstExistingDir = candidates.firstOrNull { it.isDirectory } ?: return emptyList()
    return firstExistingDir
      .listFiles { f -> f.isFile && f.name.endsWith(".jar") }
      ?.sortedBy { it.name }
      .orEmpty()
  }

  private fun sidecarSearchDescription(sidecarName: String): String {
    val sysprop =
      when (sidecarName) {
        "lib-daemon-desktop" -> "composeai.cli.libDaemonDesktopDir"
        "lib-daemon-android" -> "composeai.cli.libDaemonAndroidDir"
        "lib-renderer" -> "composeai.cli.libRendererDir"
        else -> "composeai.cli.${sidecarName.replace('-', '.')}Dir"
      }
    val override = System.getProperty(sysprop)
    val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
    return listOfNotNull(
        override?.let { "-D$sysprop=$it" },
        appHome?.let { "$it/$sidecarName" },
        "<classpath-parent>/../$sidecarName",
      )
      .joinToString(" or ")
  }

  private fun inferSidecarFromClasspath(sidecarName: String): File? {
    val cp = System.getProperty("java.class.path") ?: return null
    val firstEntry = cp.split(File.pathSeparator).firstOrNull { it.endsWith(".jar") } ?: return null
    val libDir = File(firstEntry).parentFile ?: return null
    val installRoot = libDir.parentFile ?: return null
    val candidate = File(installRoot, sidecarName)
    return candidate.takeIf { it.isDirectory }
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
