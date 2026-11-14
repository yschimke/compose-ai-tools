package ee.schimke.composeai.cli

import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import kotlin.system.exitProcess

/**
 * Helper entrypoint invoked out-of-process by `:simulator-host`'s `WatchFacePushStore` when an app
 * dynamically pushes a Watch Face Format APK via `WatchFacePushManager.addWatchFace(...)` or
 * `updateWatchFace(...)` and no external `DeclarativeWatchFaceRuntime.apk` is active.
 *
 * Prints the compiled watch-face directory path on stdout and exits 0, or exits 2 when the APK
 * carries no compilable declarative watch faces.
 */
internal object ApkDwfCompilerMain {
  @JvmStatic
  fun main(args: Array<String>) {
    val apkPath = argOf(args, "--apk")
    if (apkPath.isNullOrBlank()) {
      System.err.println("ApkDwfCompilerMain: missing --apk <file>")
      exitProcess(1)
    }
    val apk = File(apkPath).absoluteFile
    if (!apk.isFile) {
      System.err.println("ApkDwfCompilerMain: no such APK: $apk")
      exitProcess(1)
    }
    val javaBin =
      argOf(args, "--java")?.let(::File) ?: File(System.getProperty("java.home"), "bin/java")
    val robolectricJars =
      argOf(args, "--robolectric-cp")
        .orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map(::File)
    val cacheRoot = argOf(args, "--cache-root")?.let(::File) ?: composeAiCacheDir("simulator")
    val verbose = args.any { it == "--verbose" || it == "-v" }

    val compiledDir =
      ApkDwfExtractor.extractAndCompile(
        apk = apk,
        javaBinary = javaBin,
        robolectricJars = robolectricJars,
        cacheRoot = cacheRoot,
        verbose = verbose,
      )
    if (compiledDir == null) {
      exitProcess(2)
    }
    println(compiledDir.absolutePath)
  }

  private fun argOf(args: Array<String>, option: String): String? {
    for (i in args.indices) {
      val token = args[i]
      if (token == option && i + 1 < args.size) return args[i + 1]
      if (token.startsWith("$option=")) return token.substring(option.length + 1)
    }
    return null
  }
}
