package ee.schimke.composeai.discovery

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

/**
 * `java -jar preview-discovery-<version>.jar` entry point. Thin CLI wrapper over
 * [PreviewDiscovery.discover] for non-Gradle build systems — a Bazel `genrule` or an Amper
 * task can shell out here without buying into a Gradle Tooling-API client.
 *
 * Usage:
 * ```
 * java -jar preview-discovery.jar \
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
 * `--classes`, `--dependency-jars`, `--source-files` accept a `File.pathSeparator`-separated
 * list (matching how `java -cp` already encodes classpaths on the consumer's platform) and can
 * be repeated to concatenate. Empty entries are skipped, so passing an empty value through is
 * harmless when a build rule's input list happens to be empty.
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
        "--source-files" ->
          sourceFiles += splitPathList(requireValue(args, i)).map { File(it) }
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
      Usage: java -jar preview-discovery.jar [options]

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
