package ee.schimke.composeai.tui

import java.io.File

/**
 * Locates the daemon + renderer sidecar jar directories shipped in the `compose-preview-tui`
 * distribution (`$APP_HOME/lib-daemon-desktop/` and `$APP_HOME/lib-renderer/`). Project-less bundle
 * mode joins both onto the daemon subprocess classpath.
 *
 * Resolution order mirrors `cli`'s `BundleDaemonCommand.locateSidecarJars`:
 * 1. an explicit `-Dcomposeai.tui.libDaemonDesktopDir` / `-Dcomposeai.tui.libRendererDir` override,
 * 2. `$APP_HOME/<name>` (the env var the generated launcher script exports),
 * 3. `<first-classpath-jar>/../../<name>` (IDE / `JavaExec` runs against the install layout).
 *
 * [locate] returns null when either directory is absent — e.g. running from source without
 * `installDist` — so the caller can fall back to the static seed image instead of failing.
 */
object BundleSidecars {

  /** Absolute paths of every jar across both sidecar dirs, ready for the daemon classpath. */
  data class Sidecars(val daemonDesktopDir: File, val rendererDir: File) {
    fun classpath(): List<String> =
      (jarsIn(daemonDesktopDir) + jarsIn(rendererDir)).map { it.absolutePath }

    private fun jarsIn(dir: File): List<File> =
      dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.sortedBy { it.name }.orEmpty()
  }

  fun locate(): Sidecars? {
    val daemon = locateDir("lib-daemon-desktop", "composeai.tui.libDaemonDesktopDir") ?: return null
    val renderer = locateDir("lib-renderer", "composeai.tui.libRendererDir") ?: return null
    val sidecars = Sidecars(daemonDesktopDir = daemon, rendererDir = renderer)
    // Both dirs must actually carry jars; an empty sidecar dir is as useless as a missing one.
    return sidecars.takeIf { it.classpath().isNotEmpty() }
  }

  private fun locateDir(name: String, sysprop: String): File? {
    val candidates =
      listOfNotNull(
        System.getProperty(sysprop)?.let(::File),
        (System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME"))?.let {
          File(it, name)
        },
        inferFromClasspath(name),
      )
    return candidates.firstOrNull { it.isDirectory }
  }

  /** `<install-root>/<name>` inferred from the first jar on the launcher's own classpath (`lib/`). */
  private fun inferFromClasspath(name: String): File? {
    val cp = System.getProperty("java.class.path") ?: return null
    val firstJar = cp.split(File.pathSeparator).firstOrNull { it.endsWith(".jar") } ?: return null
    val libDir = File(firstJar).parentFile ?: return null
    val installRoot = libDir.parentFile ?: return null
    return File(installRoot, name).takeIf { it.isDirectory }
  }
}
