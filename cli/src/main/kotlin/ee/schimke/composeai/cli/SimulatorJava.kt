package ee.schimke.composeai.cli

import java.io.File

/**
 * The JDK `compose-preview launch` runs the simulator on. Robolectric's simulator needs 21 or newer
 * (its `ShadowUiAutomation` calls `List.getFirst()`), while the CLI itself runs on 17, so the
 * launch cannot simply reuse its own JVM.
 *
 * In order: `--java` (a `java` binary or a JDK home), `COMPOSE_PREVIEW_SIMULATOR_JAVA`, the CLI's
 * own JVM, then `JAVA_HOME` — the first that is new enough.
 */
internal object SimulatorJava {

  const val MIN_FEATURE = 21
  const val ENV = "COMPOSE_PREVIEW_SIMULATOR_JAVA"

  /** A `java` binary and its feature version. */
  data class Java(val binary: File, val feature: Int)

  /** The chosen JDK, or the reason none qualifies (every candidate looked at, and why). */
  sealed interface Result {
    data class Found(val java: Java) : Result

    data class NotFound(val tried: List<String>) : Result
  }

  fun resolve(
    explicit: String?,
    env: (String) -> String? = System::getenv,
    ownHome: String? = System.getProperty("java.home"),
  ): Result {
    val candidates =
      listOfNotNull(
        explicit?.let { "--java" to it },
        env(ENV)?.takeIf { it.isNotBlank() }?.let { ENV to it },
        ownHome?.let { "this JVM" to it },
        env("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { "JAVA_HOME" to it },
      )
    val tried = mutableListOf<String>()
    for ((source, path) in candidates) {
      val home = jdkHome(File(path))
      val binary = home?.let { javaBinary(it) }
      val feature = home?.let { featureVersion(it) }
      when {
        home == null || binary == null -> tried += "$source ($path): not a JDK"
        feature == null -> tried += "$source ($path): no readable `release` file"
        feature < MIN_FEATURE -> tried += "$source ($path): Java $feature"
        else -> return Result.Found(Java(binary, feature))
      }
      // An explicit choice that does not qualify is an error, not a cue to look elsewhere.
      if (source == "--java") return Result.NotFound(tried)
    }
    return Result.NotFound(tried)
  }

  /** [path] as a JDK home: itself, or the home above a `bin/java` binary. */
  internal fun jdkHome(path: File): File? {
    if (File(path, "release").isFile || File(path, "bin").isDirectory) return path
    val parent = path.absoluteFile.parentFile ?: return null
    if (path.isFile && parent.name == "bin") return parent.parentFile
    return null
  }

  private fun javaBinary(home: File): File? =
    listOf(File(home, "bin/java"), File(home, "bin/java.exe")).firstOrNull { it.isFile }

  /** `JAVA_VERSION` from a JDK's `release` file: `"21.0.2"` is 21, `"1.8.0_402"` is 8. */
  internal fun featureVersion(home: File): Int? {
    val release = File(home, "release").takeIf { it.isFile } ?: return null
    val version =
      release
        .readLines()
        .firstOrNull { it.startsWith("JAVA_VERSION=") }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"') ?: return null
    return parseFeature(version)
  }

  internal fun parseFeature(version: String): Int? {
    val parts = version.split('.', '_', '-', '+')
    val first = parts.firstOrNull()?.toIntOrNull() ?: return null
    return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
  }
}
