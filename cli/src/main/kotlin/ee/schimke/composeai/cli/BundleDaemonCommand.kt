package ee.schimke.composeai.cli

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

/**
 * `compose-preview bundle daemon <bundle.png>` — spawn the desktop daemon JVM bound to a packed
 * preview bundle's classpath. Inherits stdio so the parent process (the VS Code extension's bundle
 * viewer panel) can speak the daemon's JSON-RPC protocol directly, the same way the Gradle plugin's
 * `composePreviewDaemonStart` works for in-workspace modules.
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
 * Embedded-mode bundles (schema-v3 `resolution = "embedded"`) carry their reachable third-party
 * deps under `libs/`; those are extracted and joined onto the daemon's `userClassDirs` so a
 * preview's own deps resolve with no network. For coordinate-mode bundles, `bundle.json`'s
 * `ClasspathEntry.Maven` entries are still *not* resolved here — the renderer's bundled Compose
 * stack supplies the common API surface, and a full coordinate-resolver pass (download Maven,
 * hash-verify, layer-on) is its own milestone (#1632, Tier 3). Same trade-off `bundle render`
 * makes.
 */
class BundleDaemonCommand(args: List<String>) : Command(args) {

  override fun run() {
    val sub = args.firstOrNull { !it.startsWith("-") }
    if (sub == null || sub in setOf("help", "--help", "-h")) {
      printHelp()
      if (sub == null) exitProcess(64)
      return
    }
    val file = File(sub)
    if (!file.isFile) {
      System.err.println("bundle daemon: not a file: ${file.path}")
      exitProcess(1)
    }
    val verbose = "--verbose" in args || "-v" in args
    val workDir = createTempWorkDir()
    val classesDir = workDir.resolve("classes").apply { mkdirs() }
    val libsDir = workDir.resolve("libs").apply { mkdirs() }
    val previewsJson = workDir.resolve("previews.json")
    val zipBytes = BundleReader.extractZipBytes(file)
    expandAppJarAndManifest(zipBytes, classesDir, previewsJson, file)
    // Embedded `libs/` jars are consumer classes, so they ride the daemon's `userClassDirs` holder
    // (dirs-before-jars ordered, see UserClassLoaderHolder) alongside the extracted app classes —
    // NOT the daemon/renderer `-cp`. Empty for coordinate bundles.
    val libJars = BundleReader.extractEmbeddedLibs(zipBytes, libsDir)
    val userClassPath =
      (listOf(classesDir) + libJars).joinToString(File.pathSeparator) { it.absolutePath }

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

    val classpath = (daemonJars + rendererJars).joinToString(File.pathSeparator) { it.absolutePath }
    val javaBin = locateJava()
    val command = buildList {
      add(javaBin)
      add("--enable-native-access=ALL-UNNAMED")
      // PROTOCOL.md § 3a — the daemon advertises capabilities lazily; the client
      // (BundleViewerPanel's DaemonClient) does the `initialize` round-trip on stdin.
      add("-D${USER_CLASS_DIRS_PROP}=$userClassPath")
      add("-D${PREVIEWS_JSON_PATH_PROP}=${previewsJson.absolutePath}")
      // Tag the temp dir on the daemon so logs / debug dumps make it discoverable.
      add("-Dcomposeai.daemon.bundleSource=${file.absolutePath}")
      add("-cp")
      add(classpath)
      add("ee.schimke.composeai.daemon.DaemonMain")
    }

    if (verbose) {
      System.err.println("[bundle-daemon] working dir: ${workDir.absolutePath}")
      System.err.println("[bundle-daemon] classes dir: ${classesDir.absolutePath}")
      System.err.println("[bundle-daemon] embedded lib jars: ${libJars.size}")
      System.err.println("[bundle-daemon] previews.json: ${previewsJson.absolutePath}")
      System.err.println("[bundle-daemon] daemon jars: ${daemonJars.size}")
      System.err.println("[bundle-daemon] renderer jars: ${rendererJars.size}")
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

  private fun printHelp() {
    println(
      """
      compose-preview bundle daemon — start the desktop daemon against a packed bundle

      Usage:
        compose-preview bundle daemon <bundle.png> [-v]

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
    val canonicalTarget = targetDir.canonicalFile
    ZipInputStream(ByteArrayInputStream(appJarBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        val candidate = File(targetDir, entry.name).canonicalFile
        if (
          candidate != canonicalTarget &&
            !candidate.path.startsWith(canonicalTarget.path + File.separator)
        ) {
          throw SecurityException(
            "bundle daemon: app jar entry escapes target dir: ${entry.name} → ${candidate.path}"
          )
        }
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
  }
}
