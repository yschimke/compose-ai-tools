package ee.schimke.composeai.daemonlaunch

import java.io.File
import kotlin.system.exitProcess

/**
 * CLI entry point over [DaemonLaunchBuilder.build] for non-Gradle build systems. A Bazel `genrule`
 * or an Amper task can shell out here without buying into a Kotlin/JVM client.
 *
 * The published `ee.schimke.composeai:daemon-launch-builder` JAR is a **slim library JAR** (no
 * shaded uber-JAR, no `Class-Path:` manifest entry). The intended invocation is therefore:
 * ```
 * java -cp <resolved-classpath> ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilderCli \
 *   --module-path <path> \
 *   --variant <name> \
 *   --main-class <fqn> \
 *   [--enabled true|false] \
 *   [--java-launcher <path>] \
 *   --classpath <jar>[:<jar>...] \
 *   [--jvm-arg <arg>]... \
 *   [--system-property <key>=<value>]... \
 *   --working-directory <dir> \
 *   --manifest-path <previews.json> \
 *   --out <daemon-launch.json>
 * ```
 *
 * where `<resolved-classpath>` is the runtime closure of
 * `ee.schimke.composeai:daemon-launch-builder` as resolved by the caller's dep system (Bazel
 * `rules_jvm_external`, Amper m2 cache, `mvn dependency:build-classpath`, etc.) and joined with the
 * platform-appropriate `File.pathSeparator`. `java -jar <artifact>.jar` against the bare published
 * JAR will fail with `NoClassDefFoundError` — see the "CLI invocation" section in
 * `docs/NON_GRADLE_INTEGRATION.md`.
 *
 * `--classpath` accepts a `File.pathSeparator`-separated list and can be repeated; entries are
 * concatenated in order. `--jvm-arg` and `--system-property` are repeatable single-value flags
 * (one arg / key=value per occurrence).
 *
 * Exit codes: `0` on success, `2` on argument parsing failure.
 */
public object DaemonLaunchBuilderCli {

  @JvmStatic
  public fun main(args: Array<String>) {
    val parsed =
      try {
        parse(args)
      } catch (e: ArgError) {
        System.err.println("daemon-launch-builder: ${e.message}")
        printUsage(System.err)
        exitProcess(2)
      }

    val descriptor =
      DaemonLaunchBuilder.build(
        modulePath = parsed.modulePath,
        variant = parsed.variant,
        mainClass = parsed.mainClass,
        classpath = parsed.classpath,
        jvmArgs = parsed.jvmArgs,
        systemProperties = parsed.systemProperties,
        workingDirectory = parsed.workingDirectory,
        manifestPath = parsed.manifestPath,
        enabled = parsed.enabled,
        javaLauncher = parsed.javaLauncher,
      )

    parsed.outFile.parentFile?.mkdirs()
    parsed.outFile.writeText(DaemonLaunchBuilder.encode(descriptor))
    exitProcess(0)
  }

  internal data class ParsedArgs(
    val modulePath: String,
    val variant: String,
    val mainClass: String,
    val classpath: List<String>,
    val jvmArgs: List<String>,
    val systemProperties: Map<String, String>,
    val workingDirectory: String,
    val manifestPath: String,
    val enabled: Boolean,
    val javaLauncher: String?,
    val outFile: File,
  )

  internal class ArgError(message: String) : RuntimeException(message)

  internal fun parse(args: Array<String>): ParsedArgs {
    var modulePath: String? = null
    var variant: String? = null
    var mainClass: String? = null
    val classpath = mutableListOf<String>()
    val jvmArgs = mutableListOf<String>()
    val systemProperties = linkedMapOf<String, String>()
    var workingDirectory: String? = null
    var manifestPath: String? = null
    var enabled = true
    var javaLauncher: String? = null
    var outFile: File? = null

    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when (arg) {
        "--module-path" -> modulePath = requireValue(args, i)
        "--variant" -> variant = requireValue(args, i)
        "--main-class" -> mainClass = requireValue(args, i)
        "--classpath" -> classpath += splitPathList(requireValue(args, i))
        "--jvm-arg" -> jvmArgs += requireValue(args, i)
        "--system-property" -> {
          val raw = requireValue(args, i)
          val eq = raw.indexOf('=')
          if (eq < 0) throw ArgError("--system-property value must be key=value, got: $raw")
          systemProperties[raw.substring(0, eq)] = raw.substring(eq + 1)
        }
        "--working-directory" -> workingDirectory = requireValue(args, i)
        "--manifest-path" -> manifestPath = requireValue(args, i)
        "--enabled" -> {
          enabled =
            when (val v = requireValue(args, i)) {
              "true" -> true
              "false" -> false
              else -> throw ArgError("--enabled must be true|false, got: $v")
            }
        }
        "--java-launcher" -> javaLauncher = requireValue(args, i)
        "--out" -> outFile = File(requireValue(args, i))
        "-h",
        "--help" -> {
          printUsage(System.out)
          exitProcess(0)
        }
        else -> throw ArgError("unknown argument: $arg")
      }
      i += 2
    }

    return ParsedArgs(
      modulePath = modulePath ?: throw ArgError("--module-path is required"),
      variant = variant ?: throw ArgError("--variant is required"),
      mainClass = mainClass ?: throw ArgError("--main-class is required"),
      classpath = classpath,
      jvmArgs = jvmArgs,
      systemProperties = systemProperties,
      workingDirectory =
        workingDirectory ?: throw ArgError("--working-directory is required"),
      manifestPath = manifestPath ?: throw ArgError("--manifest-path is required"),
      enabled = enabled,
      javaLauncher = javaLauncher,
      outFile = outFile ?: throw ArgError("--out is required"),
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
      Usage: java -cp <resolved-classpath> ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilderCli [options]

      Required:
        --module-path <path>           Module path (e.g. ":app", "//app", "app").
        --variant <name>               Build variant ("debug" / "release" / "desktop").
        --main-class <fqn>             Daemon entry point (typically
                                       ee.schimke.composeai.daemon.DaemonMain).
        --working-directory <dir>      Daemon JVM cwd.
        --manifest-path <path>         Absolute path to previews.json.
        --out <path>                   Destination path for daemon-launch.json.

      Optional:
        --enabled true|false           Default true. When false, consumers refuse to spawn the JVM.
        --java-launcher <path>         Absolute path to a java binary; defaults to caller's JDK.

      Repeatable inputs:
        --classpath <jar>[:<jar>...]   Daemon classpath in load order; repeat to concatenate.
        --jvm-arg <arg>                JVM flag (-Xmx, --add-opens, ...); repeat per arg.
        --system-property <key>=<val>  -D system property; repeat per key.

      Flags:
        --help, -h                     Print this message.

      Exit codes: 0 = success, 2 = argument parsing failure.
      """
        .trimIndent()
    )
  }
}
