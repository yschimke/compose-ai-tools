package ee.schimke.composeai.cli

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Checks the JVM a launched distribution will actually run on, before launching it.
 *
 * `serve`, `browse`, `ui-builder` and `mcp serve` are launchers: [ServerBinaryDiscovery] finds a
 * binary and [ServeCommand] execs it as a separate process. That binary is a Gradle start script,
 * and it resolves `java` from `JAVA_HOME`/`PATH` — it does **not** inherit this CLI's JVM. So the
 * JVM that loads the server's classes is one nobody has checked on either side, and when it is too
 * old the user gets an `UnsupportedClassVersionError` on the stderr of a process they did not know
 * existed: no Java version named, no binary named, no hint.
 * [ServerBinaryDiscovery.installationHint] does not fire, because the binary is not missing — it is
 * present and unrunnable ([#344](https://github.com/yschimke/compose-preview-server/issues/344)).
 *
 * ## The number is theirs
 *
 * The floor belongs to `compose-preview-server` and moves on its schedule, so a copy here is a copy
 * that drifts — which is why nothing in this file names a version. The distribution states it, in
 * `java-min.properties` at its root, beside `bin/` and `lib/`. A launcher that resolved a binary
 * resolved `<root>/bin/<name>`, so the file is one `../..` away, and reading it costs no process: a
 * `--java-min` the start script answered would need the very JVM in question to answer it.
 *
 * ## Silence beats a guess
 *
 * Every step here fails open. No file (a distribution older than the one that started shipping it),
 * no resolvable `java`, an unparseable `-version` — each returns null and the launch proceeds
 * exactly as it did before. This turns a bad error message into a good one; it must never turn a
 * working launch into a refusal, and a CLI that outlives several server releases will meet all
 * three cases.
 */
internal object ServerJavaPreflight {

  /** The file a distribution states its floor in, at the distribution root. */
  const val MANIFEST: String = "java-min.properties"

  /**
   * The message to print and abort on, or null to launch.
   *
   * [javaFeatureVersion] and [javaExecutable] are seams so the decision can be tested without a
   * second JDK on the machine, which is the whole difficulty of testing this.
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
   * The floor [binary]'s distribution declares, or null when it declares none.
   *
   * `canonicalFile` first: `PATH` and `--server-binary` both routinely name a symlink, and the
   * distribution root is two levels above the *real* script, not above the link farm pointing at
   * it.
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
   * The `java` the start script will pick: `JAVA_HOME` first, then `PATH` — its own ordering.
   *
   * Deliberately not `java.home`. This process's JVM is the one thing that is certainly *not* what
   * the start script runs, and reading it here would produce a preflight that passes on exactly the
   * machines the failure happens on.
   */
  fun resolveJava(env: (String) -> String? = System::getenv): File? {
    val executable = if (isWindows()) "java.exe" else "java"
    env("JAVA_HOME")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let { home ->
        val candidate = File(File(home, "bin"), executable)
        // A JAVA_HOME that names no executable is the start script's error to report, in its own
        // words. Falling through to PATH here would check a JVM the script will never run.
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
   * The feature version out of `java -version` output.
   *
   * Every JVM prints `… version "<v>"` on its first line, and the quoted value is the only part of
   * that output whose shape is stable across vendors. `1.8.0_452` is the pre-9 spelling, where the
   * feature version is the *second* component; from 9 on it is the first.
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
    // Where the JVM came from, because that is what the reader has to change, and the two sources
    // are changed in different places.
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
