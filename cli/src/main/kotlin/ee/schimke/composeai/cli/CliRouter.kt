package ee.schimke.composeai.cli

/**
 * Command routing: the top-level verbs, the command *groups* (`compose-preview <group> <command>`),
 * and the pure [route] that turns argv into a [Route] decision.
 *
 * 19 flat commands had grown a wide, flat namespace. Groups give the top level a short, honest
 * shape (a few core verbs + four groups) while **every command stays callable by its original flat
 * name** — the grouped form is additive, the flat name is a permanent back-compat alias, so
 * existing scripts and the published skill keep working unchanged.
 *
 * [route] is deliberately side-effect-free (no printing, no `exitProcess`, no command construction)
 * so `CliRouterTest` can exercise every dispatch path directly. `Main` maps the [Route] onto the
 * actual command factories; `CliRouterTest` also pins that the factory table and this router agree
 * on the command set, so the two can't drift.
 */
internal object CliRouter {
  /** Top-level commands shown first and not nested under a group. */
  val CORE: List<String> = listOf("render", "show", "list", "show-resources", "doctor", "mcp")

  /**
   * Command groups, in display order. `compose-preview <group> <command>` dispatches to the
   * command; each command is also reachable by its bare flat name. A group named with no (or an
   * unknown) subcommand prints the group's listing.
   */
  val GROUPS: Map<String, List<String>> =
    linkedMapOf(
      "inspect" to listOf("a11y", "diff-semantics", "devices", "extensions", "history", "profile"),
      "capture" to listOf("render-matrix", "record", "bundle"),
      "share" to listOf("serve", "share-preview"),
      "setup" to listOf("update", "init-script"),
    )

  /** Pseudo-commands that aren't backed by a `Command` class but are valid flat verbs. */
  val META: List<String> = listOf("version", "help")

  val GROUP_NAMES: Set<String> = GROUPS.keys

  /** Every command reachable by flat name (core + grouped + meta). */
  val KNOWN_FLAT: Set<String> = (CORE + GROUPS.values.flatten() + META).toSet()

  fun subcommandsOf(group: String): List<String> = GROUPS[group].orEmpty()

  /** The group a flat command belongs to, or `null` for core/meta commands. */
  fun groupOf(command: String): String? = GROUPS.entries.firstOrNull { command in it.value }?.key

  /**
   * A routing decision for argv that has already passed the `--version` / empty-args short-circuit.
   */
  sealed interface Route {
    /** Run [command] with [args] (the command token, and any consumed group token, removed). */
    data class Run(val command: String, val args: List<String>) : Route

    /** Print a group's command listing. [isError] when an unknown subcommand was given (exit 1). */
    data class GroupUsage(val group: String, val isError: Boolean) : Route

    /** Top-level `--help` with no command. */
    data class TopUsage(val full: Boolean) : Route

    /** No command token and not a help request (exit 1). */
    data object NoCommand : Route

    /** A leading token that isn't a known command or group (exit 1). */
    data class Unknown(val command: String) : Route
  }

  fun route(args: Array<String>): Route {
    val commandIndex = CliFlags.findCommandIndex(args)
    if (commandIndex < 0) {
      return if ("--help" in args || "-h" in args) Route.TopUsage(full = "--all" in args)
      else Route.NoCommand
    }

    val command = args[commandIndex]
    val rest = args.toMutableList().apply { removeAt(commandIndex) }

    if (command in GROUP_NAMES) {
      val subIndex = CliFlags.findCommandIndex(rest.toTypedArray())
      val sub = if (subIndex >= 0) rest[subIndex] else null
      if (sub != null && sub in subcommandsOf(command)) {
        val subArgs = rest.toMutableList().apply { removeAt(subIndex) }
        return Route.Run(sub, subArgs)
      }
      // Group named alone → its listing (exit 0); group + unknown subcommand → listing + exit 1.
      return Route.GroupUsage(command, isError = sub != null)
    }

    return if (command in KNOWN_FLAT) Route.Run(command, rest) else Route.Unknown(command)
  }
}
