package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.locateBundleSidecarJars
import java.io.File
import kotlin.system.exitProcess

/**
 * Thin launcher for `compose-preview android-simulator` (and `launch` / `dwf-runtime`): resolves
 * the standalone `android-simulator` distribution from [ANDROID_SIMULATOR_REPO] via
 * [ServerBinaryDiscovery] / [ServerDistributionProvision] (honoring `--simulator-binary`,
 * `COMPOSE_PREVIEW_SIMULATOR`, `PATH`, or the provisioned cache) and execs it over a process
 * boundary, matching `serve` and `mcp serve`.
 */
internal class SimulatorLauncherCommand(
  private val args: List<String>,
  private val invokedAs: String = "android-simulator",
) {

  fun run() {
    if (args == listOf("--help") || args == listOf("-h")) {
      println(usage(invokedAs))
      return
    }
    val choice = resolveBinary(args)
    if (choice == null) {
      System.err.println(ServerBinaryDiscovery.installationHint(ReleasedDistribution.SIMULATOR))
      exitProcess(1)
    }
    val forwarded = stripLauncherFlag(args)
    val pb = ProcessBuilder(listOf(choice.binary) + forwarded).inheritIO()
    forwardSidecarEnv(pb)
    val exit =
      try {
        pb.runTiedToLauncher()
      } catch (t: Throwable) {
        System.err.println(
          "could not start ${choice.binary} (from ${choice.source}): " +
            "${t.message ?: t.javaClass.name}",
        )
        System.err.println()
        System.err.println(ServerBinaryDiscovery.installationHint(ReleasedDistribution.SIMULATOR))
        exitProcess(1)
      }
    exitProcess(exit)
  }

  companion object {
    internal fun usage(invokedAs: String = "android-simulator"): String =
      """
      |Usage: compose-preview $invokedAs [options] [<app.apk>]
      |
      |Launches the standalone Android simulator (`yschimke/android-simulator`).
      |Resolves `--simulator-binary`, `COMPOSE_PREVIEW_SIMULATOR`, `android-simulator` on `PATH`,
      |or auto-provisions the latest release from `yschimke/android-simulator`.
      """
        .trimMargin()

    internal fun resolveBinary(args: List<String> = emptyList()): ServerBinaryDiscovery.Choice? {
      val found = ServerBinaryDiscovery.choose(args, ReleasedDistribution.SIMULATOR)
      return found ?: provisionSimulator()
    }

    private fun provisionSimulator(): ServerBinaryDiscovery.Choice? =
      ServerDistributionProvision.ensure(ReleasedDistribution.SIMULATOR)?.let {
        ServerBinaryDiscovery.Choice(it.path, ServerBinaryDiscovery.CACHE)
      }

    internal fun stripLauncherFlag(args: List<String>): List<String> = buildList {
      val flag = ReleasedDistribution.SIMULATOR.flag
      val prefix = "$flag="
      var i = 0
      while (i < args.size) {
        val arg = args[i]
        when {
          arg == flag -> i += 2
          arg.startsWith(prefix) -> i++
          else -> {
            add(arg)
            i++
          }
        }
      }
    }

    private fun forwardSidecarEnv(pb: ProcessBuilder) {
      val opts = mutableListOf<String>()
      DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.ANDROID)
      val androidDaemonDir = locateBundleSidecarJars("lib-daemon-android").firstOrNull()?.parentFile
      if (androidDaemonDir != null) {
        opts += "-Dcomposeai.cli.libDaemonAndroidDir=${androidDaemonDir.absolutePath}"
      }
      val rcJvmDir = locateBundleSidecarJars("lib-rcjvm").firstOrNull()?.parentFile
      if (rcJvmDir != null) {
        opts += "-Dcomposeai.cli.libRcjvmDir=${rcJvmDir.absolutePath}"
      }
      System.getProperty("composeai.cli.skikoDir")
        ?.takeIf { it.isNotBlank() }
        ?.let { opts += "-Dcomposeai.cli.skikoDir=$it" }
      if (opts.isNotEmpty()) {
        val existing = pb.environment()["ANDROID_SIMULATOR_OPTS"].orEmpty().trim()
        val combined = listOf(existing, opts.joinToString(" ")).filter { it.isNotEmpty() }
        pb.environment()["ANDROID_SIMULATOR_OPTS"] = combined.joinToString(" ")
      }
    }

    /** Delegates `compose-preview provision --host-natives` to the standalone simulator binary. */
    internal fun provisionHostNatives(apks: List<File>): File? {
      val choice = resolveBinary() ?: return null
      val cmd = buildList {
        add(choice.binary)
        add("host-natives")
        for (apk in apks) {
          add("--apk")
          add(apk.absolutePath)
        }
      }
      val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
      val out = proc.inputStream.bufferedReader().readText().trim()
      val exit = proc.waitFor()
      if (exit != 0 || out.isEmpty()) return null
      return File(out)
    }
  }
}
