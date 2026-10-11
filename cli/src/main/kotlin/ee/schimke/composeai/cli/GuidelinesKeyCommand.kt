package ee.schimke.composeai.cli

import ee.schimke.composeai.guidelines.GuidelineCredentials
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `compose-preview guidelines key set|status|forget`: keeps the OpenRouter key in the user's
 * credentials file ([GuidelineCredentials]) so `guidelines` runs without
 * `COMPOSE_PREVIEW_OPENROUTER_KEY` exported in every shell. The environment still wins when set.
 *
 * `set` reads the key from a no-echo prompt, or from stdin when piped (`pbpaste | … key set`),
 * never from an argument, so it stays out of shell history and process listings. Nothing prints the
 * key.
 */
internal class GuidelinesKeyCommand(
  private val args: List<String>,
  private val env: (String) -> String? = System::getenv,
  private val file: File? = GuidelineCredentials.defaultFileOrNull(env = env),
  private val readKey: () -> String? = ::promptOrStdin,
) {
  private val json = "--json" in args

  /** `--for <base-url>`: the key for that OpenAI-compatible endpoint rather than OpenRouter's. */
  private val endpoint: String? = args.flagValue("--for")

  /** The arguments that are not flags or flag values: the subcommand, then its directory. */
  private val positional: List<String> = args.filterIndexed { i, arg ->
    !arg.startsWith("-") && args.getOrNull(i - 1) != "--for"
  }

  private val provider: String
    get() = endpoint?.let(GuidelineCredentials::endpointProvider) ?: GuidelineCredentials.OPENROUTER

  private val envVar: String
    get() = if (endpoint != null) GuidelineCredentials.MODEL_ENV else GuidelineCredentials.ENV

  private val label: String
    get() = endpoint?.let { "key for $it" } ?: "OpenRouter key"

  fun run() {
    if (endpoint != null && runCatching { provider }.isFailure) {
      System.err.println("guidelines key: --for $endpoint is not an http(s) URL")
      exitProcess(1)
    }
    exitProcess(
      when (positional.firstOrNull()) {
        "set" -> set()
        "status" -> status()
        "forget" -> forget()
        "trust" -> trust()
        "untrust" -> untrust()
        else -> {
          System.err.println(USAGE)
          if (args.isEmpty()) 0 else 1
        }
      }
    )
  }

  internal fun set(): Int {
    val target = file ?: return noHome()
    val key = readKey()?.trim()
    if (key.isNullOrEmpty() || !GuidelineCredentials.validKey(key)) {
      System.err.println("guidelines key: no key read (expected one line with no spaces)")
      return 1
    }
    try {
      GuidelineCredentials.store(target, key, provider)
    } catch (e: Exception) {
      System.err.println("guidelines key: could not write ${target.path} (${e.message})")
      return 1
    }
    println("Stored the $label in ${target.path}.")
    if (env(envVar)?.isNotBlank() == true) {
      println("$envVar is set in this environment and takes precedence over the file.")
    }
    return 0
  }

  internal fun status(): Int {
    val resolved =
      GuidelineCredentials.resolve(provider, envVar, env, file) {
        System.err.println("guidelines key: $it")
      }
    if (json) {
      println(
        buildJsonObject {
          put("configured", resolved != null)
          resolved?.let {
            put("source", it.source)
            put("masked", it.masked)
          }
          file?.let { put("file", it.path) }
          put(
            "trustedProjects",
            JsonArray(
              file?.let(GuidelineCredentials::trustedProjects).orEmpty().map(::JsonPrimitive)
            ),
          )
        }
          .toString()
      )
    } else if (resolved == null) {
      println("No $label: $envVar is unset and ${describeFile()}.")
      println("Run `compose-preview guidelines key set` to store one.")
    } else {
      println(
        "${label.replaceFirstChar { it.uppercase() }} ${resolved.masked} from ${resolved.source}."
      )
      if (resolved.stored) {
        val trusted = file?.let(GuidelineCredentials::trustedProjects).orEmpty()
        println(
          if (trusted.isEmpty()) "No project is trusted with it yet."
          else "Trusted projects:\n" + trusted.joinToString("\n") { "  $it" }
        )
      }
    }
    return if (resolved == null) 1 else 0
  }

  internal fun forget(): Int {
    val target = file ?: return noHome()
    val removed =
      try {
        GuidelineCredentials.forget(target, provider)
      } catch (e: Exception) {
        System.err.println("guidelines key: could not write ${target.path} (${e.message})")
        return 1
      }
    println(
      if (removed) "Removed the $label from ${target.path}."
      else "No $label stored in ${target.path}."
    )
    if (env(envVar)?.isNotBlank() == true) {
      println("$envVar is still set in this environment.")
    }
    return 0
  }

  /** The directory after `trust` / `untrust`, else the Gradle root above the working directory. */
  private fun project(): File =
    positional.getOrNull(1)?.let { File(it).absoluteFile }
      ?: findGradleProjectRoot()
      ?: File(".").absoluteFile

  internal fun trust(): Int {
    val target = file ?: return noHome()
    val project = project()
    if (!project.isDirectory) {
      System.err.println("guidelines key: ${project.path} is not a directory")
      return 1
    }
    try {
      GuidelineCredentials.trust(target, project)
    } catch (e: Exception) {
      System.err.println("guidelines key: could not write ${target.path} (${e.message})")
      return 1
    }
    println("Trusted ${project.canonicalPath} with the stored keys.")
    return 0
  }

  internal fun untrust(): Int {
    val target = file ?: return noHome()
    val project = project()
    val removed =
      try {
        GuidelineCredentials.untrust(target, project)
      } catch (e: Exception) {
        System.err.println("guidelines key: could not write ${target.path} (${e.message})")
        return 1
      }
    println(
      if (removed) "${project.canonicalPath} no longer uses the stored keys."
      else "${project.canonicalPath} was not trusted."
    )
    return 0
  }

  private fun describeFile(): String =
    file?.let { "${it.path} holds none" } ?: "no credentials file location could be determined"

  private fun noHome(): Int {
    System.err.println(
      "guidelines key: no place to keep the key: ${GuidelineCredentials.FILE_ENV} must be an " +
        "absolute path when set, and otherwise XDG_CONFIG_HOME, HOME or user.home must be one. " +
        "Set ${GuidelineCredentials.FILE_ENV} to an absolute path you control, or export " +
        "${GuidelineCredentials.ENV}."
    )
    return 1
  }

  companion object {
    val USAGE: String =
      """
      Usage: compose-preview guidelines key <set|status|forget|trust|untrust> [--for <base-url>] [--json]

        set      Store an OpenRouter key in the user's credentials file (0600), or with
                 --for <base-url> the key for that OpenAI-compatible endpoint. Prompts without
                 echo, or reads one line from stdin when piped. Never pass the key as an argument.
        status   Say where the key comes from (${GuidelineCredentials.ENV}, or with --for
                 ${GuidelineCredentials.MODEL_ENV}, wins over the file), showing only its last
                 four characters. Exits 1 when there is none.
        forget   Remove the stored key.
        trust [dir]    Let the project at dir (default: the Gradle root here) use the stored keys.
                       `guidelines` asks the first time it runs in a project, and refuses without a
                       terminal to ask in. A key from the environment needs no trust.
        untrust [dir]  Stop letting it.

      The file is ${'$'}${GuidelineCredentials.FILE_ENV}, else
      ${'$'}XDG_CONFIG_HOME/compose-preview/credentials.json (default ~/.config/…).
      """
        .trimIndent()

    /**
     * Whether [project] may spend the key stored in [file]: already trusted, or the user says so
     * now at [ask] (remembered). [ask] is null without a terminal, and then the answer is no, with
     * the command that trusts it: an agent or a script must not grant itself the user's key.
     */
    fun confirmTrust(
      file: File,
      project: File,
      /** The variable that would supply this key instead, named as the non-interactive way out. */
      envVar: String = GuidelineCredentials.ENV,
      ask: ((String) -> String?)? =
        System.console()?.let { console -> { console.readLine("%s", it) } },
    ): Boolean {
      if (GuidelineCredentials.isTrusted(file, project)) return true
      val path = project.absoluteFile.canonicalPath
      val trustCommand = "compose-preview guidelines key trust $path"
      if (ask == null) {
        System.err.println(
          "guidelines: $path is not trusted with the key stored in ${file.path}. " +
            "Run `$trustCommand` in a terminal, or set $envVar for this run."
        )
        return false
      }
      val answer =
        ask(
          "Use your stored model key for $path? Its previews and source are sent to " +
            "the model's provider and billed to that key. Only say yes for a project you trust. [y/N] "
        )
      if (answer?.trim()?.lowercase() !in setOf("y", "yes")) {
        System.err.println("guidelines: not trusted; nothing was sent. Later: `$trustCommand`.")
        return false
      }
      try {
        GuidelineCredentials.trust(file, project)
      } catch (e: Exception) {
        System.err.println(
          "guidelines: could not remember trusting $path (${e.message}); using the key this once"
        )
      }
      return true
    }

    private fun promptOrStdin(): String? {
      val console = System.console()
      if (console != null) {
        return console.readPassword("Key (input hidden): ")?.let { String(it) }
      }
      return System.`in`.bufferedReader().readLine()
    }
  }
}
