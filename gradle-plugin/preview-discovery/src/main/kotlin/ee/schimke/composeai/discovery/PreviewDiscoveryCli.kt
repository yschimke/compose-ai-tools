package ee.schimke.composeai.discovery

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

/**
 * CLI entry point over [PreviewDiscovery.discover] for non-Gradle build systems — a Bazel `genrule`
 * or an Amper task can shell out here without buying into a Gradle Tooling-API client.
 *
 * The published `ee.schimke.composeai:preview-discovery` JAR is a **slim library JAR** (no shaded
 * uber-JAR, no `Class-Path:` manifest entry). The intended invocation is therefore:
 * ```
 * java -cp <resolved-classpath> ee.schimke.composeai.discovery.PreviewDiscoveryCli \
 *   --classes <dir>[:<dir>...] \
 *   --dependency-jars <jar>[:<jar>...] \
 *   --source-files <file>[:<file>...] \
 *   --module <name> \
 *   --variant <name> \
 *   --project-directory <dir> \
 *   [--fail-on-empty] \
 *   --out <path>
 * ```
 *
 * where `<resolved-classpath>` is the runtime closure of `ee.schimke.composeai:preview-discovery`
 * as resolved by the caller's dep system (Bazel `rules_jvm_external`, Amper m2 cache, `mvn
 * dependency:build-classpath`, etc.) and joined with the platform-appropriate `File.pathSeparator`.
 * `java -jar <artifact>.jar` against the bare published JAR will fail with `NoClassDefFoundError` —
 * see the "CLI invocation" section in `docs/NON_GRADLE_INTEGRATION.md`.
 *
 * `--classes`, `--dependency-jars`, `--source-files` accept a `File.pathSeparator`-separated list
 * (matching how `java -cp` already encodes classpaths on the consumer's platform) and can be
 * repeated to concatenate. Empty entries are skipped, so passing an empty value through is harmless
 * when a build rule's input list happens to be empty.
 *
 * Exit codes: `0` on success (manifest written), `1` on discovery failure (e.g. zero previews
 * + `--fail-on-empty`), `2` on argument parsing failure.
 */
public object PreviewDiscoveryCli {

  @JvmStatic
  public fun main(args: Array<String>) {
    val parsed =
      try {
        parse(args)
      } catch (e: ArgError) {
        System.err.println("preview-discovery: ${e.message}")
        printUsage(System.err)
        exitProcess(2)
      }

    val outcome = PreviewDiscovery.discover(parsed.input)
    when (outcome) {
      is PreviewDiscovery.Outcome.Success -> {
        outcome.warnings.forEach { System.err.println("WARN: $it") }
        parsed.outFile.parentFile?.mkdirs()
        parsed.outFile.writeText(JSON.encodeToString(outcome.manifest))
        outcome.infoMessages.forEach { System.err.println(it) }
        exitProcess(0)
      }
      is PreviewDiscovery.Outcome.Failure -> {
        // Mirror the success branch's WARN emission so the failure path doesn't drop per-method
        // skip reasons (e.g. unsupported parameters) — they're the most actionable
        // signal when failOnEmpty filtered the run to zero previews.
        outcome.warnings.forEach { System.err.println("WARN: $it") }
        outcome.diagnostics.forEach { System.err.println(it) }
        System.err.println("preview-discovery: ${outcome.reason}")
        exitProcess(1)
      }
    }
  }

  private val JSON = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  internal data class ParsedArgs(val input: PreviewDiscovery.Input, val outFile: File)

  internal class ArgError(message: String) : RuntimeException(message)

  internal fun parse(args: Array<String>): ParsedArgs {
    val classDirs = mutableListOf<File>()
    val dependencyJars = mutableListOf<File>()
    val sourceFiles = mutableListOf<File>()
    var moduleName: String? = null
    var variantName: String? = null
    var projectDirectory: File? = null
    var failOnEmpty = false
    var outPath: File? = null

    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when (arg) {
        "--classes" -> classDirs += splitPathList(requireValue(args, i)).map { File(it) }
        "--dependency-jars" ->
          dependencyJars += splitPathList(requireValue(args, i)).map { File(it) }
        "--source-files" -> sourceFiles += splitPathList(requireValue(args, i)).map { File(it) }
        "--module" -> moduleName = requireValue(args, i)
        "--variant" -> variantName = requireValue(args, i)
        "--project-directory" -> projectDirectory = File(requireValue(args, i))
        "--out" -> outPath = File(requireValue(args, i))
        "--fail-on-empty" -> {
          failOnEmpty = true
          i++
          continue
        }
        "-h",
        "--help" -> {
          printUsage(System.out)
          exitProcess(0)
        }
        else -> throw ArgError("unknown argument: $arg")
      }
      i += 2
    }

    val module = moduleName ?: throw ArgError("--module is required")
    val variant = variantName ?: throw ArgError("--variant is required")
    val projectDir = projectDirectory ?: throw ArgError("--project-directory is required")
    val out = outPath ?: throw ArgError("--out is required")

    return ParsedArgs(
      input =
        PreviewDiscovery.Input(
          classDirs = classDirs,
          dependencyJars = dependencyJars,
          sourceFiles = sourceFiles,
          moduleName = module,
          variantName = variant,
          projectDirectory = projectDir,
          failOnEmpty = failOnEmpty,
        ),
      outFile = out,
    )
  }

  private fun requireValue(args: Array<String>, i: Int): String {
    if (i + 1 >= args.size) throw ArgError("${args[i]} requires a value")
    return args[i + 1]
  }

  private fun splitPathList(raw: String): List<String> =
    raw.split(File.pathSeparatorChar).filter { it.isNotEmpty() }

  private fun printUsage(out: java.io.PrintStream) {
    out.println(
      """
      Usage: java -cp <resolved-classpath> ee.schimke.composeai.discovery.PreviewDiscoveryCli [options]

      Required:
        --module <name>             Logical module name; surfaces as PreviewManifest.module.
        --variant <name>            Build variant ("debug" / "release" / "desktop");
                                    surfaces as PreviewManifest.variant.
        --project-directory <dir>   Module root; PreviewInfo.sourceFile paths are rendered
                                    relative to this.
        --out <path>                Destination path for the emitted previews.json.

      Inputs (path-separator-delimited, repeatable):
        --classes <dir>[:<dir>...]            Compiled .class directories belonging to the module.
        --dependency-jars <jar>[:<jar>...]    Dependency JARs to merge onto the scan classpath.
        --source-files <file>[:<file>...]     Source files used to resolve module-relative paths.

      Flags:
        --fail-on-empty   Exit non-zero with diagnostics when zero previews are discovered.
        --help, -h        Print this message.

      Exit codes: 0 = success, 1 = discovery failure, 2 = argument parsing failure.
      """
        .trimIndent()
    )
  }
}
