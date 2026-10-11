package ee.schimke.composeai.cli

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Checks the JVM a launched distribution will actually run on, before launching it. The server's
 * Gradle start script resolves `java` from `JAVA_HOME`/`PATH`, not this CLI's JVM, so a too-old JVM
 * otherwise surfaces as a bare `UnsupportedClassVersionError` from a process the user didn't know
 * existed.
 *
 * The floor is the server's, read from the distribution's `java-min.properties` (beside `bin/` and
 * `lib/`), never hard-coded here. Every step fails open — no file, no resolvable `java`, an
 * unparseable `-version` all return null and launch as before.
 */
internal object ServerJavaPreflight {

  /** The file a distribution states its floor in, at the distribution root. */
  const val MANIFEST: String = "java-min.properties"

  /**
   * The message to print and abort on, or null to launch. [javaFeatureVersion] and [javaExecutable]
   * are test seams.
   */
  fun failure(
    choice: ServerBinaryDiscovery.Choice,
    distribution: ReleasedDistribution,
    env: (String) -> String? = System::getenv,
    javaExecutable: ((String) -> String?) -> File? = ::resolveJava,
    javaFeatureVersion: (File) -> Int? = ::featureVersionOf,
  ): String? {
    val required = declaredMinimum(File(choice.binary)) ?: return null
    val java = javaExecutable(env) ?: return null
    val running = javaFeatureVersion(java) ?: return null
    if (running >= required) return null
    return message(choice, distribution, java, running, required, env)
  }

  /**
   * The floor [binary]'s distribution declares, or null. Uses `canonicalFile` since `PATH` and
   * `--server-binary` often name a symlink.
   */
  fun declaredMinimum(binary: File): Int? {
    val root = binary.canonicalFile.parentFile?.parentFile ?: return null
    val manifest = File(root, MANIFEST).takeIf { it.isFile } ?: return null
    val declared = runCatching {
      manifest.inputStream().use { Properties().apply { load(it) } }
    }
      .getOrNull()
      ?.getProperty("javaMin")
    return declared?.trim()?.toIntOrNull()
  }

  /**
   * The `java` the start script will pick: `JAVA_HOME`, then `PATH`. Not `java.home`, which is
   * certainly not what the script runs.
   */
  fun resolveJava(env: (String) -> String? = System::getenv): File? {
    val executable = if (isWindows()) "java.exe" else "java"
    env("JAVA_HOME")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let { home ->
        val candidate = File(File(home, "bin"), executable)
        // A bad `JAVA_HOME` is the start script's error to report; don't check a `PATH` JVM it
        // won't use.
        return candidate.takeIf { it.isFile }
      }
    return env("PATH")
      ?.split(File.pathSeparator)
      ?.asSequence()
      ?.filter { it.isNotBlank() }
      ?.map { File(it, executable) }
      ?.firstOrNull { it.isFile && it.canExecute() }
  }

  /** The feature version [java] reports, or null if it could not be asked or understood. */
  private fun featureVersionOf(java: File): Int? {
    val output =
      runCatching {
        val process =
          ProcessBuilder(java.path, "-version").redirectErrorStream(true).start().also {
            it.outputStream.close()
          }
        val text = process.inputStream.bufferedReader().use { it.readText() }
        // A JVM that will not answer in ten seconds is not one to block a launch on.
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
          process.destroyForcibly()
          return null
        }
        text
      }
        .getOrNull() ?: return null
    return parseFeatureVersion(output)
  }

  /**
   * The feature version from `java -version`'s quoted value: the second component for pre-9
   * `1.8.0_452`, the first from 9 on.
   */
  fun parseFeatureVersion(output: String): Int? {
    val quoted = Regex("""version "([^"]+)"""").find(output)?.groupValues?.get(1) ?: return null
    val parts = quoted.split('.', '_', '-', '+')
    val first = parts.firstOrNull()?.toIntOrNull() ?: return null
    return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
  }

  private fun message(
    choice: ServerBinaryDiscovery.Choice,
    distribution: ReleasedDistribution,
    java: File,
    running: Int,
    required: Int,
    env: (String) -> String? = System::getenv,
  ): String {
    val javaHome = env("JAVA_HOME")?.trim()?.takeIf { it.isNotEmpty() }
    // Name where the JVM came from, since that is what the reader must change.
    val found =
      if (javaHome != null) "${java.path} (JAVA_HOME=$javaHome)"
      else "${java.path} (first `java` on PATH)"
    return """
      ${distribution.label} needs Java $required or newer, and would have run on Java $running.

      Binary:  ${choice.binary} (from ${choice.source})
      Java:    $found

      ${distribution.binary} is a start script: it resolves `java` itself and does not inherit
      this CLI's JVM, so this would have failed inside it with `UnsupportedClassVersionError`
      rather than here.

      Point it at a newer JVM by setting JAVA_HOME=/path/to/jdk$required, or run a distribution
      that matches the JVM you have by passing ${distribution.flag} /path/to/${distribution.binary}.
      `compose-preview doctor` reports which binary is found.
      """
      .trimIndent()
  }

  private fun isWindows(): Boolean =
    (System.getProperty("os.name") ?: "").lowercase().contains("windows")
}
