package ee.schimke.composeai.cli.simulator

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The app's own copy of Guava, where the simulator's would break it: `:simulator-core`'s
 * `LibraryRelocation`, run on a converted jar.
 *
 * The sandbox serves the app's own classes first, except Guava, which Robolectric's sandboxed code
 * links against and so comes from the simulator's runtime (`SimulatorSandboxClassLoader`). A
 * release (R8) APK's Guava has been rewritten, and its code calls what only that copy has.
 * `LibraryRelocation` finds the packages whose references fail against the runtime's copy and
 * renames the app's classes in them, writing a second jar beside the converted one:
 * ```
 * <converter stamp>/
 *   classes.jar                          the converted classes (and the incremental index's input)
 *   relocation-<stamp>.json              what the relocation decided, for these runtime jars
 *   classes-relocated-<stamp>.jar        the jar the simulator runs, when anything was relocated
 * ```
 *
 * Kept out of the converter stamp on purpose: the decision depends on the simulator's own jars,
 * which change with every release, and re-running it takes seconds where a conversion takes
 * minutes. Its stamp is the runtime jars' names and sizes.
 */
internal object LibraryRelocations {
  const val MAIN = "ee.schimke.composeai.simulator.LibraryRelocation"
  const val RUNTIME_PROPERTY = "composeai.simulator.relocation.runtime"
  const val BOUNDARY_PROPERTY = "composeai.simulator.relocation.boundary"
  const val CONTEXT_PROPERTY = "composeai.simulator.relocation.context"

  /** `0` turns relocation off: the app runs against the runtime's copies, as before. */
  const val ENV = "COMPOSE_PREVIEW_SIMULATOR_RELOCATION"

  const val HEAP = "2g"

  /** What a relocation decided: [jar] to run, if anything moved, and the [packages] that did. */
  data class Outcome(val jar: File?, val packages: List<String>, val summary: String)

  /**
   * Names and sizes of [runtimeJars], and the [context] (an app's relocated jar, for its test APK):
   * what a decision depends on.
   */
  fun stamp(runtimeJars: List<File>, context: File?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("relocation-1".toByteArray())
    for (jar in runtimeJars) {
      digest.update("${jar.name}:${jar.length()}\n".toByteArray())
    }
    context?.let { digest.update("context:${it.path}:${it.length()}".toByteArray()) }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
  }

  /** The relocation JVM's command line. Pure, for tests. */
  fun command(
    java: File,
    classpath: List<File>,
    classesJar: File,
    out: File,
    androidJar: File,
    runtime: List<File>,
    boundary: List<File>,
    context: File?,
  ): List<String> =
    listOfNotNull(
      java.path,
      "-Xmx$HEAP",
      "-XX:+UseSerialGC",
      "-D$RUNTIME_PROPERTY=" + runtime.joinToString(File.pathSeparator) { it.path },
      "-D$BOUNDARY_PROPERTY=" + boundary.joinToString(File.pathSeparator) { it.path },
      context?.let { "-D$CONTEXT_PROPERTY=" + it.path },
      "-cp",
      classpath.joinToString(File.pathSeparator) { it.path },
      MAIN,
      classesJar.path,
      out.path,
      androidJar.path,
    )

  /**
   * [classesJar]'s relocation for [stamp], reusing the decision recorded beside it; otherwise [run]
   * writes the relocated jar to the file it is given (only when something moves) and returns
   * `LibraryRelocation`'s summary line.
   */
  fun ensure(classesJar: File, stamp: String, run: (out: File) -> String): Outcome {
    val dir = classesJar.absoluteFile.parentFile
    val decision = File(dir, "relocation-$stamp.json")
    val relocated = File(dir, "classes-relocated-$stamp.jar")
    if (decision.isFile) {
      val known = parse(decision.readText())
      if (known.isEmpty()) return Outcome(null, known, decision.readText().trim())
      if (relocated.isFile) return Outcome(relocated, known, decision.readText().trim())
    }
    val partial = File(dir, "${relocated.name}.partial-${ProcessHandle.current().pid()}")
    partial.delete()
    val summary =
      try {
        run(partial).trim()
      } catch (e: Exception) {
        partial.delete()
        throw e
      }
    val packages = parse(summary)
    if (packages.isNotEmpty()) {
      check(partial.isFile) { "the relocation named packages but wrote no jar" }
      Files.move(partial.toPath(), relocated.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } else {
      partial.delete()
    }
    val partialDecision = File(dir, "${decision.name}.partial-${ProcessHandle.current().pid()}")
    partialDecision.writeText(summary + "\n")
    Files.move(
      partialDecision.toPath(),
      decision.toPath(),
      StandardCopyOption.REPLACE_EXISTING,
    )
    // Decisions for runtimes no longer in use.
    dir
      .listFiles { f ->
        (f.name.startsWith("relocation-") || f.name.startsWith("classes-relocated-")) &&
          !f.name.contains(stamp)
      }
      .orEmpty()
      .forEach { it.delete() }
    return Outcome(relocated.takeIf { packages.isNotEmpty() }, packages, summary)
  }

  /** The `relocated` packages of a summary line; empty when there are none or it is unreadable. */
  fun parse(summary: String): List<String> = runCatching {
    (Json.parseToJsonElement(summary.trim()) as JsonObject)["relocated"] as JsonArray
  }
    .getOrNull()
    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
    .orEmpty()
}
